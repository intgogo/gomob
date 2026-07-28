#!/usr/bin/env python3
"""判定 VIN 还原的交互性能与契约语义。

逐字节等价**不在这里判**：模型下沉外部算法服务后，gosmart 更新权重就会让像素变化，
那不是 gomob 的回归。等价性改由 vin_restore_consistency 的离线回放门负责
（TestRestoreByteEquivalence，观测固定 → 几何计算确定性 → 输出必然字节相同）。
本 harness 连真实服务，只回答“够不够快、契约有没有变”。
"""

from __future__ import annotations

import json
import math
import shlex
import statistics
import sys
from pathlib import Path


NORMAL_HTTP_P50_MS = 4_000.0
NORMAL_HTTP_P95_MS = 6_000.0
# 逐字符检测门：产品级交互要求，与实现无关，故沿用远程化前的数值。
NORMAL_CHAR_DETECT_P50_MS = 3_000.0
WARNING_HTTP_P50_MS = 6_000.0
WARNING_HTTP_P95_MS = 8_000.0
WARNING_CHAR_DETECT_P50_MS = 5_000.0
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
                "region_ms",
                "frame_ms",
                "probe_render_ms",
                "probe_encode_ms",
                "char_detect_ms",
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
    # 同一次运行内部仍要求多次采样彼此字节一致：那检测的是服务端自身的非确定性
    # （并发、随机种子、缓存），与模型版本无关，属于本 harness 该管的范围。
    run_hashes = {row.get("png_sha256") for row in success_rows if row.get("ok")}

    errors: list[str] = []
    warnings: list[str] = []
    if not success_rows:
        errors.append("成功路径无样本")
    if len(run_hashes) > 1:
        errors.append(f"同一输入多次调用输出不一致，服务端存在非确定性: {sorted(run_hashes)}")

    for row in success_rows:
        prefix = f"成功样本#{row.get('index')}"
        if row.get("status") != 200 or row.get("code") != 0 or not row.get("ok"):
            errors.append(f"{prefix} 未成功: {row}")
            continue
        if row.get("width") != 4425 or row.get("height") != 600:
            errors.append(f"{prefix} 输出尺寸不是 4425×600")
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
    # 逐字符检测耗时：远程化后这一步是"编码 probe + 网络往返 + 服务端推理"，
    # 是旧 anchor_ms 的耗时主体继承者；anchor_ms 现在只剩纯格架拟合，不足以当门。
    char_detect_ms = [
        timing_by_log_id[row["log_id"]]["char_detect_ms"]
        for row in success_rows
        if row.get("log_id") in timing_by_log_id
        and "char_detect_ms" in timing_by_log_id[row["log_id"]]
    ]
    region_ms = [
        timing_by_log_id[row["log_id"]]["region_ms"]
        for row in success_rows
        if row.get("log_id") in timing_by_log_id
        and "region_ms" in timing_by_log_id[row["log_id"]]
    ]
    if not http_ms or not total_ms or not char_detect_ms:
        errors.append("性能样本不完整")

    metrics: dict[str, float | int | str | list[float] | None] = {
        "status": "异常",
        "sample_count": len(success_rows),
        "run_png_sha256": sorted(run_hashes)[0] if len(run_hashes) == 1 else None,
        "http_p50_ms": statistics.median(http_ms) if http_ms else None,
        "http_p95_ms": percentile(http_ms, 0.95) if http_ms else None,
        "http_max_ms": max(http_ms) if http_ms else None,
        "restore_total_p50_ms": statistics.median(total_ms) if total_ms else None,
        "char_detect_p50_ms": statistics.median(char_detect_ms) if char_detect_ms else None,
        "region_p50_ms": statistics.median(region_ms) if region_ms else None,
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
        char_detect_p50 = float(metrics["char_detect_p50_ms"])
        reject_ms = float(metrics["reject_http_ms"])
        if (
            http_p50 > WARNING_HTTP_P50_MS
            or http_p95 > WARNING_HTTP_P95_MS
            or char_detect_p50 > WARNING_CHAR_DETECT_P50_MS
        ):
            errors.append(
                f"成功还原过慢: HTTP p50={http_p50:.1f}ms p95={http_p95:.1f}ms，"
                f"字符检测 p50={char_detect_p50:.1f}ms"
            )
        else:
            if (
                http_p50 > NORMAL_HTTP_P50_MS
                or http_p95 > NORMAL_HTTP_P95_MS
                or char_detect_p50 > NORMAL_CHAR_DETECT_P50_MS
            ):
                warnings.append(
                    f"成功还原接近上限: HTTP p50={http_p50:.1f}ms p95={http_p95:.1f}ms，"
                    f"字符检测 p50={char_detect_p50:.1f}ms"
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
        "运行内一致："
        f"{len(success_rows)} 次均为 4425×600，"
        f"SHA-256={sorted(run_hashes)[0] if len(run_hashes) == 1 else '不一致'}"
    )
    if http_ms:
        print(
            f"HTTP：p50={metrics['http_p50_ms']:.1f}ms "
            f"p95={metrics['http_p95_ms']:.1f}ms max={metrics['http_max_ms']:.1f}ms"
        )
        print(
            f"服务端：Restore p50={metrics['restore_total_p50_ms']:.1f}ms，"
            f"区域检测 p50={metrics['region_p50_ms']:.1f}ms，"
            f"字符检测 p50={metrics['char_detect_p50_ms']:.1f}ms，"
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
