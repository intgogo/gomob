#!/usr/bin/env python3
"""分析 P100R3 depth raw16，并输出统计和伪彩预览。"""

from __future__ import annotations

import argparse
import json
import math
import struct
from pathlib import Path


KNOWN_DIMS = [
    (1280, 801),
    (1280, 800),
    (640, 401),
    (640, 400),
    (320, 201),
    (320, 200),
]


def infer_dims(pixel_count: int) -> tuple[int, int]:
    for width, height in KNOWN_DIMS:
        if width * height == pixel_count:
            return width, height
    raise SystemExit(f"无法从 {pixel_count} 像素推断尺寸，请显式传 --width/--height")


def active_height(height: int) -> int:
    if height in (801, 401, 201):
        return height - 1
    return height


def percentile(sorted_values: list[float], q: float) -> float:
    if not sorted_values:
        return 0.0
    index = min(len(sorted_values) - 1, max(0, int(q * (len(sorted_values) - 1))))
    return sorted_values[index]


def colorize(mm: float, min_mm: float, max_mm: float) -> tuple[int, int, int]:
    if mm <= 0.0 or mm < min_mm or mm > max_mm:
        return 0, 0, 0
    t = min(1.0, max(0.0, (mm - min_mm) / max(1.0, max_mm - min_mm)))
    r = int(255.0 * t)
    g = int(220.0 * (1.0 - abs(t - 0.45) * 1.8))
    b = int(255.0 * (1.0 - t))
    return max(0, min(255, r)), max(0, min(255, g)), max(0, min(255, b))


def write_ppm(path: Path, mm_values: list[float], width: int, height: int, min_mm: float, max_mm: float) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as out:
        out.write(f"P6\n{width} {height}\n255\n".encode("ascii"))
        for v in mm_values:
            out.write(bytes(colorize(v, min_mm, max_mm)))


def output_stem(path: Path) -> str:
    parent = path.parent.name
    stem = path.stem
    if parent:
        return f"{parent}-{stem}"
    return stem


def analyze(path: Path, width: int, height: int, frac_bits: int, min_mm: float, max_mm: float, out_dir: Path | None) -> dict:
    data = path.read_bytes()
    if len(data) % 2 != 0:
        raise SystemExit(f"{path} 不是偶数字节 raw16")
    pixel_count = len(data) // 2
    if width <= 0 or height <= 0:
        width, height = infer_dims(pixel_count)
    if width * height != pixel_count:
        raise SystemExit(f"{path} 尺寸不匹配: {width}x{height} != {pixel_count} 像素")

    values = list(struct.unpack("<" + "H" * pixel_count, data))
    act_h = active_height(height)
    active_values = []
    for y in range(act_h):
        active_values.extend(values[y * width : (y + 1) * width])

    scale = float(1 << frac_bits)
    mm_values = [v / scale if v else 0.0 for v in active_values]
    nonzero_raw = [v for v in active_values if v]
    nonzero_mm = sorted(v / scale for v in nonzero_raw)
    row_nonzero = [
        sum(1 for v in active_values[y * width : (y + 1) * width] if v)
        for y in range(act_h)
    ]

    result = {
        "path": str(path),
        "bytes": len(data),
        "transport_width": width,
        "transport_height": height,
        "active_width": width,
        "active_height": act_h,
        "fraction_bits": frac_bits,
        "active_pixels": len(active_values),
        "nonzero_pixels": len(nonzero_raw),
        "nonzero_ratio": len(nonzero_raw) / max(1, len(active_values)),
        "raw_quantiles_nonzero": {
            "p00": percentile(sorted(nonzero_raw), 0.0),
            "p01": percentile(sorted(nonzero_raw), 0.01),
            "p05": percentile(sorted(nonzero_raw), 0.05),
            "p25": percentile(sorted(nonzero_raw), 0.25),
            "p50": percentile(sorted(nonzero_raw), 0.50),
            "p75": percentile(sorted(nonzero_raw), 0.75),
            "p95": percentile(sorted(nonzero_raw), 0.95),
            "p99": percentile(sorted(nonzero_raw), 0.99),
            "p100": percentile(sorted(nonzero_raw), 1.0),
        },
        "mm_quantiles_nonzero": {
            "p00": percentile(nonzero_mm, 0.0),
            "p01": percentile(nonzero_mm, 0.01),
            "p05": percentile(nonzero_mm, 0.05),
            "p25": percentile(nonzero_mm, 0.25),
            "p50": percentile(nonzero_mm, 0.50),
            "p75": percentile(nonzero_mm, 0.75),
            "p95": percentile(nonzero_mm, 0.95),
            "p99": percentile(nonzero_mm, 0.99),
            "p100": percentile(nonzero_mm, 1.0),
        },
        "row_nonzero": {
            "first5": row_nonzero[:5],
            "last5": row_nonzero[-5:],
            "max": max(row_nonzero) if row_nonzero else 0,
            "nonempty_rows": sum(1 for v in row_nonzero if v),
        },
    }

    if out_dir:
        stem = output_stem(path)
        preview = out_dir / f"{stem}-{width}x{act_h}-depth.ppm"
        write_ppm(preview, mm_values, width, act_h, min_mm, max_mm)
        result["preview_ppm"] = str(preview)
        json_path = out_dir / f"{stem}-{width}x{act_h}-depth-stats.json"
        json_path.parent.mkdir(parents=True, exist_ok=True)
        json_path.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        result["stats_json"] = str(json_path)

    return result


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("paths", nargs="+", type=Path)
    parser.add_argument("--width", type=int, default=0)
    parser.add_argument("--height", type=int, default=0)
    parser.add_argument("--frac-bits", type=int, default=3)
    parser.add_argument("--min-mm", type=float, default=200.0)
    parser.add_argument("--max-mm", type=float, default=2000.0)
    parser.add_argument("--out-dir", type=Path)
    args = parser.parse_args()

    results = [
        analyze(path, args.width, args.height, args.frac_bits, args.min_mm, args.max_mm, args.out_dir)
        for path in args.paths
    ]
    print(json.dumps(results[0] if len(results) == 1 else results, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
