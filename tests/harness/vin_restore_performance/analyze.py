#!/usr/bin/env python3
"""判定 VIN 还原是否保持逐字节等价，并满足交互性能门。"""

from __future__ import annotations

import json
import math
import shlex
import statistics
import sys
from pathlib import Path


NORMAL_HTTP_P50_MS = 4_000.0
NORMAL_HTTP_P95_MS = 6_000.0
NORMAL_ANCHOR_P50_MS = 3_000.0
WARNING_HTTP_P50_MS = 6_000.0
WARNING_HTTP_P95_MS = 8_000.0
WARNING_ANCHOR_P50_MS = 5_000.0
REJECT_MAX_MS = 2_000.0
LEGACY_SINGLE_THREAD_P50_MS = 10_730.0


def percentile(values: list[float], quantile: float) -> float:
    ordered = sorted(values)
    index = max(0, math.ceil(len(ordered) * quantile) - 1)
    return ordered[index]


def parse_timings(log_path: Path) -> dict[str, dict[str, float]]:
    timings: dict[str, dict[str, float]] = {}
    for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines():
        if 'msg="VIN 还原完成"' not in line:
            continue
        fields: dict[str, str] = {}
        for token in shlex.split(line):
            if "=" in token:
                key, value = token.split("=", 1)
                fields[key] = value
        log_id = fields.get("log_id", "")
        if not log_id:
            continue
        timings[log_id] = {
            key: float(fields[key])
            for key in (
                "total_ms",
                "decode_ms",
                "obb_ms",
                "frame_ms",
                "probe_render_ms",
                "anchor_ms",
                "final_render_ms",
                "png_encode_ms",
            )
            if key in fields
        }
    return timings


def load_json(path: Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def main(out_dir: str) -> int:
    out = Path(out_dir)
    required = [out / "success.json", out / "reject.json", out / "cvengine.log"]
    missing = [str(path) for path in required if not path.exists()]
    if missing:
        print(f"异常：缺采样产物 {missing}")
        return 2

    success = load_json(out / "success.json")
    reject = load_json(out / "reject.json")
    timing_by_log_id = parse_timings(out / "cvengine.log")
    success_rows = success.get("results") or []
    reject_rows = reject.get("results") or []
    reference_hash = success.get("reference_png_sha256") or ""
    reference_bytes = int(success.get("reference_png_bytes") or 0)

    errors: list[str] = []
    warnings: list[str] = []
    if not success_rows:
        errors.append("成功路径无样本")
    if not reference_hash or reference_bytes <= 0:
        errors.append("缺旧版本黄金还原图")

    for row in success_rows:
        prefix = f"成功样本#{row.get('index')}"
        if row.get("status") != 200 or row.get("code") != 0 or not row.get("ok"):
            errors.append(f"{prefix} 未成功: {row}")
            continue
        if row.get("width") != 4425 or row.get("height") != 600:
            errors.append(f"{prefix} 输出尺寸不是 4425×600")
        if row.get("png_sha256") != reference_hash:
            errors.append(f"{prefix} PNG 与旧版本不逐字节等价")
        if int(row.get("png_bytes") or 0) != reference_bytes:
            errors.append(f"{prefix} PNG 字节数与旧版本不一致")
        log_id = row.get("log_id") or ""
        if log_id not in timing_by_log_id:
            errors.append(f"{prefix} 缺服务端阶段计时")

    if len(reject_rows) != 1:
        errors.append("判废路径必须恰好采样一次")
    elif (
        reject_rows[0].get("status") != 200
        or reject_rows[0].get("code") != 0
        or reject_rows[0].get("ok")
        or reject_rows[0].get("reject_reason") != "vin_not_detected"
        or int(reject_rows[0].get("png_bytes") or 0) != 0
    ):
        errors.append(f"VIN 未检出判废语义改变: {reject_rows[0]}")

    http_ms = [float(row["http_ms"]) for row in success_rows if row.get("ok")]
    total_ms = [
        timing_by_log_id[row["log_id"]]["total_ms"]
        for row in success_rows
        if row.get("log_id") in timing_by_log_id
        and "total_ms" in timing_by_log_id[row["log_id"]]
    ]
    anchor_ms = [
        timing_by_log_id[row["log_id"]]["anchor_ms"]
        for row in success_rows
        if row.get("log_id") in timing_by_log_id
        and "anchor_ms" in timing_by_log_id[row["log_id"]]
    ]
    if not http_ms or not total_ms or not anchor_ms:
        errors.append("性能样本不完整")

    metrics: dict[str, float | int | str | list[float] | None] = {
        "status": "异常",
        "sample_count": len(success_rows),
        "reference_png_sha256": reference_hash,
        "http_p50_ms": statistics.median(http_ms) if http_ms else None,
        "http_p95_ms": percentile(http_ms, 0.95) if http_ms else None,
        "http_max_ms": max(http_ms) if http_ms else None,
        "restore_total_p50_ms": statistics.median(total_ms) if total_ms else None,
        "anchor_p50_ms": statistics.median(anchor_ms) if anchor_ms else None,
        "reject_http_ms": float(reject_rows[0]["http_ms"]) if len(reject_rows) == 1 else None,
        "legacy_single_thread_p50_ms": LEGACY_SINGLE_THREAD_P50_MS,
        "speedup_vs_legacy": (
            LEGACY_SINGLE_THREAD_P50_MS / statistics.median(http_ms) if http_ms else None
        ),
        "load_average": success.get("load_average"),
        "errors": errors,
        "warnings": warnings,
    }

    if not errors:
        http_p50 = float(metrics["http_p50_ms"])
        http_p95 = float(metrics["http_p95_ms"])
        anchor_p50 = float(metrics["anchor_p50_ms"])
        reject_ms = float(metrics["reject_http_ms"])
        if (
            http_p50 > WARNING_HTTP_P50_MS
            or http_p95 > WARNING_HTTP_P95_MS
            or anchor_p50 > WARNING_ANCHOR_P50_MS
        ):
            errors.append(
                f"成功还原过慢: HTTP p50={http_p50:.1f}ms p95={http_p95:.1f}ms，"
                f"VINCHAR p50={anchor_p50:.1f}ms"
            )
        else:
            if (
                http_p50 > NORMAL_HTTP_P50_MS
                or http_p95 > NORMAL_HTTP_P95_MS
                or anchor_p50 > NORMAL_ANCHOR_P50_MS
            ):
                warnings.append(
                    f"成功还原接近上限: HTTP p50={http_p50:.1f}ms p95={http_p95:.1f}ms，"
                    f"VINCHAR p50={anchor_p50:.1f}ms"
                )
            if reject_ms > REJECT_MAX_MS:
                warnings.append(f"VIN 未检出判废耗时偏高: {reject_ms:.1f}ms")

    if errors:
        metrics["status"] = "异常"
    elif warnings:
        metrics["status"] = "警告"
    else:
        metrics["status"] = "正常"
    metrics["errors"] = errors
    metrics["warnings"] = warnings
    (out / "report.json").write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    print(
        "结果等价："
        f"{len(success_rows)} 次均为 4425×600，SHA-256={reference_hash or '缺失'}"
    )
    if http_ms:
        print(
            f"HTTP：p50={metrics['http_p50_ms']:.1f}ms "
            f"p95={metrics['http_p95_ms']:.1f}ms max={metrics['http_max_ms']:.1f}ms"
        )
        print(
            f"服务端：Restore p50={metrics['restore_total_p50_ms']:.1f}ms，"
            f"VINCHAR p50={metrics['anchor_p50_ms']:.1f}ms，"
            f"相对旧单线程约 {metrics['speedup_vs_legacy']:.2f}×"
        )
    print(f"判废路径：{metrics['reject_http_ms']}ms")
    print(f"结论：{metrics['status']}")
    for item in errors:
        print(f"  - 异常：{item}")
    for item in warnings:
        print(f"  - 警告：{item}")
    return 1 if errors else 0


if __name__ == "__main__":
    default = Path(__file__).resolve().parents[3] / ".dev" / "vin_restore_performance"
    sys.exit(main(sys.argv[1] if len(sys.argv) > 1 else str(default)))
