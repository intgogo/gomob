#!/usr/bin/env python3
"""berxel_depth_parity/analyze.py — 判定 P100R3 host depth raw parity。"""

from __future__ import annotations

import csv
import json
import sys
from array import array
from pathlib import Path
from statistics import mean, median, pstdev
from typing import Any


ACTIVE_PIXELS = 640 * 400
WIDTH = 640
HEIGHT = 400
DEPTH_SCALE = 8.0


def read_json(path: Path) -> dict[str, Any]:
    if not path.exists():
        raise FileNotFoundError(path)
    return json.loads(path.read_text(encoding="utf-8"))


def avg_vendor_valid_ratio(path: Path) -> float | None:
    if not path.exists():
        return None
    ratios: list[float] = []
    with path.open(newline="") as f:
        for row in csv.DictReader(f):
            value = row.get("valid_ratio")
            if value:
                ratios.append(float(value))
    return mean(ratios) if ratios else None


def avg_host_valid_ratio(path: Path) -> float | None:
    if not path.exists():
        return None
    ratios: list[float] = []
    with path.open(newline="") as f:
        for row in csv.DictReader(f):
            value = row.get("raw_active_valid")
            if value:
                ratios.append(float(value) / ACTIVE_PIXELS)
    return mean(ratios) if ratios else None


def read_u16_le(path: Path, width: int = WIDTH, height: int = HEIGHT) -> array:
    data = path.read_bytes()[: width * height * 2]
    if len(data) < width * height * 2:
        raise ValueError(f"{path} raw16 字节不足")
    values = array("H")
    values.frombytes(data)
    if sys.byteorder != "little":
        values.byteswap()
    return values


def center_roi_metrics(directory: Path, pattern: str) -> dict[str, Any] | None:
    files = sorted(directory.glob(pattern))
    if not files:
        return None
    x0 = WIDTH // 4
    x1 = WIDTH - x0
    y0 = HEIGHT // 4
    y1 = HEIGHT - y0
    roi_pixels = (x1 - x0) * (y1 - y0)
    frame_medians: list[float] = []
    valid_ratios: list[float] = []
    for path in files:
        values = read_u16_le(path)
        roi_values: list[float] = []
        valid = 0
        for y in range(y0, y1):
            base = y * WIDTH
            for x in range(x0, x1):
                raw = values[base + x]
                if raw == 0:
                    continue
                valid += 1
                roi_values.append(raw / DEPTH_SCALE)
        valid_ratios.append(valid / roi_pixels)
        if roi_values:
            frame_medians.append(float(median(roi_values)))
    if not frame_medians:
        return {
            "frames": len(files),
            "roi": {"x0": x0, "y0": y0, "x1": x1, "y1": y1},
            "valid_ratio_mean": mean(valid_ratios) if valid_ratios else 0.0,
            "median_mm_median": None,
            "median_mm_range": None,
            "median_mm_std": None,
        }
    return {
        "frames": len(files),
        "roi": {"x0": x0, "y0": y0, "x1": x1, "y1": y1},
        "valid_ratio_mean": mean(valid_ratios),
        "median_mm_median": float(median(frame_medians)),
        "median_mm_min": min(frame_medians),
        "median_mm_max": max(frame_medians),
        "median_mm_range": max(frame_medians) - min(frame_medians),
        "median_mm_std": pstdev(frame_medians) if len(frame_medians) > 1 else 0.0,
    }


def fmt(value: float | int | None, digits: int = 4) -> str:
    if value is None:
        return "n/a"
    return f"{float(value):.{digits}f}"


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".dev/berxel_depth_parity/latest.txt")
    if root.is_file():
        root = Path(root.read_text(encoding="utf-8").strip())
    if not root.is_dir():
        raise SystemExit(f"输出目录不存在: {root}")

    compare = read_json(root / "analysis/active-vs-vendor/summary.json")["summary"]
    scene_path = root / "scene.json"
    scene = read_json(scene_path) if scene_path.exists() else {"scene_name": "unknown"}
    reset_host = avg_vendor_valid_ratio(root / "reset-before-host/frames.csv")
    reset_vendor = avg_vendor_valid_ratio(root / "reset-before-vendor/frames.csv")
    reset_no_controls = avg_vendor_valid_ratio(root / "reset-before-no-controls/frames.csv")
    host_default = avg_host_valid_ratio(root / "host-default/saved_depth_frames.csv")
    host_no_controls = avg_host_valid_ratio(root / "host-no-controls/saved_depth_frames.csv")
    vendor_dense = avg_vendor_valid_ratio(root / "vendor-dense/frames.csv")
    host_roi = center_roi_metrics(root / "host-default", "depth-frame-*-active.raw")
    vendor_roi = center_roi_metrics(root / "vendor-dense", "vendor-depth-*.raw")

    pair_median = compare.get("pair_median_abs_diff_mm")
    vendor_self = compare.get("vendor_self_median_abs_diff_mm")
    host_self = compare.get("host_self_median_abs_diff_mm")
    noise_floor = max(
        float(v)
        for v in (vendor_self, host_self)
        if v is not None
    )
    median_over_noise = None if pair_median is None else float(pair_median) - noise_floor
    p50_delta = abs(float(compare["vendor_mm_p50_median"]) - float(compare["host_mm_p50_median"]))

    fail: list[str] = []
    warn: list[str] = []

    if int(compare["pairs"]) < 10:
        fail.append(f"配对帧过少: {compare['pairs']}")
    if reset_host is None or reset_host > 0.35:
        fail.append(f"host 前 sparse 复位无效: {fmt(reset_host)}")
    if reset_vendor is None or reset_vendor > 0.35:
        fail.append(f"vendor 前 sparse 复位无效: {fmt(reset_vendor)}")
    if host_default is None or host_default < 0.98:
        fail.append(f"host 默认 dense 有效率不足: {fmt(host_default)}")
    if vendor_dense is None or vendor_dense < 0.98:
        fail.append(f"vendor dense 有效率不足: {fmt(vendor_dense)}")
    if float(compare["pair_valid_jaccard_mean"]) < 0.97:
        fail.append(f"有效区 Jaccard 过低: {fmt(compare['pair_valid_jaccard_mean'])}")
    valid_delta = abs(float(compare["vendor_valid_ratio_mean"]) - float(compare["host_valid_ratio_mean"]))
    if valid_delta > 0.03:
        fail.append(f"host/vendor 有效率差过大: {fmt(valid_delta)}")
    if pair_median is None:
        fail.append("host/vendor 没有有效重叠像素")
    elif median_over_noise is not None and median_over_noise > 15.0:
        fail.append(
            f"median abs diff 高于噪声底过多: diff={fmt(pair_median, 2)} "
            f"noise={fmt(noise_floor, 2)} over={fmt(median_over_noise, 2)}"
        )
    if p50_delta > 25.0:
        fail.append(f"median depth 差过大: {fmt(p50_delta, 2)}mm")

    if host_no_controls is not None:
        if reset_no_controls is None or reset_no_controls > 0.35:
            warn.append(f"no-controls 前 sparse 复位偏高: {fmt(reset_no_controls)}")
        if host_no_controls > 0.40:
            warn.append(f"--no-depth-controls 未复现 sparse: {fmt(host_no_controls)}")

    status = "FAIL" if fail else ("WARN" if warn else "OK")
    verdict = "；".join(fail or warn or ["host depth raw parity 正常"])
    result = {
        "status": status,
        "verdict": verdict,
        "root": str(root),
        "scene": scene,
        "reset_before_host_valid_ratio": reset_host,
        "reset_before_vendor_valid_ratio": reset_vendor,
        "reset_before_no_controls_valid_ratio": reset_no_controls,
        "host_default_valid_ratio": host_default,
        "host_no_controls_valid_ratio": host_no_controls,
        "vendor_dense_valid_ratio": vendor_dense,
        "host_center_roi": host_roi,
        "vendor_center_roi": vendor_roi,
        "valid_delta": valid_delta,
        "median_depth_delta_mm": p50_delta,
        "median_over_noise_mm": median_over_noise,
        "compare_summary": compare,
        "warnings": warn,
        "failures": fail,
    }

    (root / "analysis.json").write_text(
        json.dumps(result, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    lines = [
        "# Berxel Depth Parity Harness",
        "",
        f"- 状态: {status}",
        f"- 结论: {verdict}",
        f"- 场景: {scene.get('scene_name', 'unknown')} / {scene.get('material', 'unknown')} / {scene.get('angle_deg', 0)}deg",
        f"- sparse reset(host/vendor): {fmt(reset_host)} / {fmt(reset_vendor)}",
        f"- host default dense: {fmt(host_default)}",
        f"- vendor dense: {fmt(vendor_dense)}",
        f"- host --no-depth-controls: {fmt(host_no_controls)}",
        f"- vendor/host valid delta: {fmt(valid_delta)}",
        f"- median depth delta: {fmt(p50_delta, 2)} mm",
        f"- median abs diff: {fmt(pair_median, 2)} mm",
        f"- noise floor vendor/host: {fmt(vendor_self, 2)} / {fmt(host_self, 2)} mm",
        f"- median over noise: {fmt(median_over_noise, 2)} mm",
        f"- center ROI median(host/vendor): {fmt(host_roi.get('median_mm_median') if host_roi else None, 2)} / {fmt(vendor_roi.get('median_mm_median') if vendor_roi else None, 2)} mm",
        f"- center ROI jitter range(host/vendor): {fmt(host_roi.get('median_mm_range') if host_roi else None, 2)} / {fmt(vendor_roi.get('median_mm_range') if vendor_roi else None, 2)} mm",
        "",
        "详见 `analysis/active-vs-vendor/summary.md`、`analysis.json`。",
    ]
    (root / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if status == "OK" else (1 if status == "WARN" else 2)


if __name__ == "__main__":
    raise SystemExit(main())
