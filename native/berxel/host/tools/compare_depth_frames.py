#!/usr/bin/env python3
"""对比原厂 SDK 与自研 SDK 采集到的多帧 depth raw16。"""

from __future__ import annotations

import argparse
import csv
import json
import statistics
import sys
from array import array
from pathlib import Path
from typing import Iterable


ORIENTATIONS = ("none", "mirror-x", "mirror-y", "rotate-180")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="对比 vendor depth raw16 与 host depth raw16 的有效率、分布和逐像素差异。"
    )
    parser.add_argument("--vendor-dir", required=True)
    parser.add_argument("--host-dir", required=True)
    parser.add_argument("--out-dir", required=True)
    parser.add_argument("--vendor-pattern", default="vendor-depth-*.raw")
    parser.add_argument("--host-pattern", default="depth-frame-*-processed.raw")
    parser.add_argument("--vendor-width", type=int, default=640)
    parser.add_argument("--vendor-height", type=int, default=400)
    parser.add_argument("--host-width", type=int, default=640)
    parser.add_argument("--host-height", type=int, default=400)
    parser.add_argument("--vendor-scale", type=float, default=8.0)
    parser.add_argument("--host-scale", type=float, default=8.0)
    parser.add_argument("--orientation", choices=("auto",) + ORIENTATIONS, default="auto")
    parser.add_argument("--max-pairs", type=int, default=0)
    parser.add_argument("--preview-count", type=int, default=3)
    return parser.parse_args()


def sorted_files(directory: Path, pattern: str) -> list[Path]:
    files = sorted(directory.glob(pattern))
    if not files:
        raise FileNotFoundError(f"{directory} 中没有匹配 {pattern} 的文件")
    return files


def read_u16_le(path: Path, width: int, height: int) -> array:
    expected = width * height * 2
    data = path.read_bytes()
    if len(data) < expected:
        raise ValueError(f"{path} 太小: {len(data)} < {expected}")
    values = array("H")
    values.frombytes(data[:expected])
    if sys.byteorder != "little":
        values.byteswap()
    return values


def quantile(sorted_values: list[int] | list[float], q: float) -> int | float:
    if not sorted_values:
        return 0
    index = min(len(sorted_values) - 1, int(q * (len(sorted_values) - 1)))
    return sorted_values[index]


def frame_stats(values: array, scale: float) -> dict[str, float | int]:
    nonzero = [int(v) for v in values if v]
    pixels = len(values)
    if not nonzero:
        return {
            "pixels": pixels,
            "valid_pixels": 0,
            "valid_ratio": 0.0,
            "raw_min": 0,
            "raw_p01": 0,
            "raw_p50": 0,
            "raw_p95": 0,
            "raw_p99": 0,
            "raw_max": 0,
            "mean_raw": 0.0,
            "mean_mm": 0.0,
            "mm_p50": 0.0,
        }
    nonzero.sort()
    mean_raw = sum(nonzero) / len(nonzero)
    raw_p50 = quantile(nonzero, 0.50)
    return {
        "pixels": pixels,
        "valid_pixels": len(nonzero),
        "valid_ratio": len(nonzero) / pixels if pixels else 0.0,
        "raw_min": nonzero[0],
        "raw_p01": quantile(nonzero, 0.01),
        "raw_p50": raw_p50,
        "raw_p95": quantile(nonzero, 0.95),
        "raw_p99": quantile(nonzero, 0.99),
        "raw_max": nonzero[-1],
        "mean_raw": mean_raw,
        "mean_mm": mean_raw / scale,
        "mm_p50": raw_p50 / scale,
    }


def transform(values: array, width: int, height: int, orientation: str) -> array:
    if orientation == "none":
        return values
    out = array("H", [0]) * (width * height)
    for y in range(height):
        row = y * width
        for x in range(width):
            src = row + x
            if orientation == "mirror-x":
                dst = row + (width - 1 - x)
            elif orientation == "mirror-y":
                dst = (height - 1 - y) * width + x
            elif orientation == "rotate-180":
                dst = (height - 1 - y) * width + (width - 1 - x)
            else:
                raise ValueError(f"未知方向: {orientation}")
            out[dst] = values[src]
    return out


def compare_values(
    vendor: array,
    host: array,
    vendor_scale: float,
    host_scale: float,
) -> dict[str, float | int | None]:
    if len(vendor) != len(host):
        raise ValueError(f"尺寸不一致: vendor={len(vendor)} host={len(host)}")

    vendor_valid = 0
    host_valid = 0
    overlap = 0
    host_only = 0
    vendor_only = 0
    abs_diffs: list[float] = []
    signed_sum = 0.0

    for vv, hv in zip(vendor, host):
        v_ok = vv != 0
        h_ok = hv != 0
        if v_ok:
            vendor_valid += 1
        if h_ok:
            host_valid += 1
        if v_ok and h_ok:
            overlap += 1
            diff = (hv / host_scale) - (vv / vendor_scale)
            signed_sum += diff
            abs_diffs.append(abs(diff))
        elif v_ok:
            vendor_only += 1
        elif h_ok:
            host_only += 1

    union = overlap + host_only + vendor_only
    abs_diffs.sort()
    return {
        "vendor_valid": vendor_valid,
        "host_valid": host_valid,
        "overlap": overlap,
        "vendor_only": vendor_only,
        "host_only": host_only,
        "valid_jaccard": overlap / union if union else 0.0,
        "overlap_ratio_pixels": overlap / len(vendor) if vendor else 0.0,
        "mae_mm": sum(abs_diffs) / len(abs_diffs) if abs_diffs else None,
        "median_abs_diff_mm": quantile(abs_diffs, 0.50) if abs_diffs else None,
        "p95_abs_diff_mm": quantile(abs_diffs, 0.95) if abs_diffs else None,
        "mean_signed_diff_mm": signed_sum / overlap if overlap else None,
    }


def median_or_none(values: Iterable[float | int | None]) -> float | None:
    clean = [float(v) for v in values if v is not None]
    if not clean:
        return None
    return float(statistics.median(clean))


def mean(values: Iterable[float | int]) -> float:
    clean = [float(v) for v in values]
    return sum(clean) / len(clean) if clean else 0.0


def adjacent_baseline(frames: list[array], scale: float) -> dict[str, float | None]:
    metrics = [
        compare_values(frames[i], frames[i + 1], scale, scale)
        for i in range(len(frames) - 1)
    ]
    return {
        "median_abs_diff_mm": median_or_none(m["median_abs_diff_mm"] for m in metrics),
        "p95_abs_diff_mm": median_or_none(m["p95_abs_diff_mm"] for m in metrics),
        "mae_mm": median_or_none(m["mae_mm"] for m in metrics),
    }


def select_orientation(
    vendor_frames: list[array],
    host_frames: list[array],
    width: int,
    height: int,
    vendor_scale: float,
    host_scale: float,
    requested: str,
) -> str:
    if requested != "auto":
        return requested

    sample_count = min(3, len(vendor_frames), len(host_frames))
    best_name = "none"
    best_score = float("inf")
    for name in ORIENTATIONS:
        scores: list[float] = []
        for i in range(sample_count):
            host = transform(host_frames[i], width, height, name)
            metric = compare_values(vendor_frames[i], host, vendor_scale, host_scale)
            diff = metric["median_abs_diff_mm"]
            if diff is not None and metric["overlap"] > 0:
                scores.append(float(diff))
        score = statistics.median(scores) if scores else float("inf")
        if score < best_score:
            best_score = score
            best_name = name
    return best_name


def scale_limits(frames: list[array], scales: list[float]) -> tuple[float, float]:
    samples: list[float] = []
    for values, scale in zip(frames, scales):
        samples.extend(v / scale for v in values if v)
    if not samples:
        return 0.0, 1.0
    samples.sort()
    low = float(quantile(samples, 0.01))
    high = float(quantile(samples, 0.99))
    if high <= low:
        high = low + 1.0
    return low, high


def write_depth_pgm(
    path: Path,
    values: array,
    width: int,
    height: int,
    scale: float,
    low_mm: float,
    high_mm: float,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    span = max(1e-6, high_mm - low_mm)
    with path.open("wb") as out:
        out.write(f"P5\n{width} {height}\n255\n".encode("ascii"))
        for v in values:
            if v == 0:
                gray = 0
            else:
                mm = v / scale
                gray = int(max(0.0, min(255.0, (mm - low_mm) * 255.0 / span)))
            out.write(bytes((gray,)))


def write_absdiff_pgm(
    path: Path,
    vendor: array,
    host: array,
    width: int,
    height: int,
    vendor_scale: float,
    host_scale: float,
    max_mm: float,
) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("wb") as out:
        out.write(f"P5\n{width} {height}\n255\n".encode("ascii"))
        for vv, hv in zip(vendor, host):
            if vv == 0 or hv == 0:
                gray = 0
            else:
                diff = abs((hv / host_scale) - (vv / vendor_scale))
                gray = int(max(0.0, min(255.0, diff * 255.0 / max_mm)))
            out.write(bytes((gray,)))


def verdict(summary: dict[str, float | int | str | None]) -> str:
    median_abs = summary.get("pair_median_abs_diff_mm")
    valid_delta = abs(float(summary["vendor_valid_ratio_mean"]) - float(summary["host_valid_ratio_mean"]))
    jaccard = float(summary["pair_valid_jaccard_mean"])
    if median_abs is None:
        return "异常：两边有效像素没有稳定重叠，无法做逐像素深度差比较。"
    median_abs = float(median_abs)
    self_baselines = [
        float(v)
        for v in (
            summary.get("vendor_self_median_abs_diff_mm"),
            summary.get("host_self_median_abs_diff_mm"),
        )
        if v is not None
    ]
    if self_baselines:
        noise_floor = max(self_baselines)
        if median_abs <= noise_floor + 10.0 and valid_delta <= 0.02 and jaccard >= 0.98:
            return "正常：host-vendor 差异已接近相机自身相邻帧噪声。"
    if median_abs <= 30.0 and valid_delta <= 0.10 and jaccard >= 0.70:
        return "正常：深度值分布和逐像素差异接近原厂 SDK。"
    if median_abs <= 100.0 and valid_delta <= 0.25 and jaccard >= 0.45:
        return "警告：整体可对上，但有效区域或深度值仍有明显偏差。"
    return "异常：自研 SDK 与原厂 SDK 的有效区域或深度值差异过大，需要继续定位。"


def write_csv(path: Path, rows: list[dict[str, object]]) -> None:
    if not rows:
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", newline="") as out:
        writer = csv.DictWriter(out, fieldnames=list(rows[0].keys()))
        writer.writeheader()
        writer.writerows(rows)


def fmt_num(value: object, digits: int = 2) -> str:
    if value is None:
        return "n/a"
    return f"{float(value):.{digits}f}"


def main() -> int:
    args = parse_args()
    vendor_dir = Path(args.vendor_dir)
    host_dir = Path(args.host_dir)
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)

    if args.vendor_width != args.host_width or args.vendor_height != args.host_height:
        raise ValueError("当前对比器要求 vendor/host 分辨率一致")

    vendor_files = sorted_files(vendor_dir, args.vendor_pattern)
    host_files = sorted_files(host_dir, args.host_pattern)
    pair_count = min(len(vendor_files), len(host_files))
    if args.max_pairs > 0:
        pair_count = min(pair_count, args.max_pairs)
    if pair_count <= 0:
        raise ValueError("没有可配对的 depth 帧")

    vendor_files = vendor_files[:pair_count]
    host_files = host_files[:pair_count]
    width = args.vendor_width
    height = args.vendor_height

    vendor_frames = [read_u16_le(path, width, height) for path in vendor_files]
    host_frames = [read_u16_le(path, width, height) for path in host_files]
    vendor_self = adjacent_baseline(vendor_frames, args.vendor_scale)
    host_self = adjacent_baseline(host_frames, args.host_scale)
    orientation = select_orientation(
        vendor_frames,
        host_frames,
        width,
        height,
        args.vendor_scale,
        args.host_scale,
        args.orientation,
    )
    host_frames_oriented = [transform(frame, width, height, orientation) for frame in host_frames]

    frame_rows: list[dict[str, object]] = []
    vendor_stats = []
    host_stats = []
    for index, (vendor_path, host_path, vendor, host) in enumerate(
        zip(vendor_files, host_files, vendor_frames, host_frames_oriented)
    ):
        vs = frame_stats(vendor, args.vendor_scale)
        hs = frame_stats(host, args.host_scale)
        vendor_stats.append(vs)
        host_stats.append(hs)
        frame_rows.append({"side": "vendor", "index": index, "path": str(vendor_path), **vs})
        frame_rows.append({"side": "host", "index": index, "path": str(host_path), **hs})

    pair_rows: list[dict[str, object]] = []
    pair_metrics = []
    for index, (vendor, host) in enumerate(zip(vendor_frames, host_frames_oriented)):
        metric = compare_values(vendor, host, args.vendor_scale, args.host_scale)
        pair_metrics.append(metric)
        pair_rows.append(
            {
                "index": index,
                "vendor_path": str(vendor_files[index]),
                "host_path": str(host_files[index]),
                **metric,
            }
        )

    preview_count = min(args.preview_count, pair_count)
    for index in range(preview_count):
        low, high = scale_limits(
            [vendor_frames[index], host_frames_oriented[index]],
            [args.vendor_scale, args.host_scale],
        )
        write_depth_pgm(
            out_dir / f"pair-{index:03d}-vendor.pgm",
            vendor_frames[index],
            width,
            height,
            args.vendor_scale,
            low,
            high,
        )
        write_depth_pgm(
            out_dir / f"pair-{index:03d}-host.pgm",
            host_frames_oriented[index],
            width,
            height,
            args.host_scale,
            low,
            high,
        )
        write_absdiff_pgm(
            out_dir / f"pair-{index:03d}-absdiff.pgm",
            vendor_frames[index],
            host_frames_oriented[index],
            width,
            height,
            args.vendor_scale,
            args.host_scale,
            200.0,
        )

    summary: dict[str, float | int | str | None] = {
        "vendor_dir": str(vendor_dir),
        "host_dir": str(host_dir),
        "vendor_pattern": args.vendor_pattern,
        "host_pattern": args.host_pattern,
        "pairs": pair_count,
        "width": width,
        "height": height,
        "orientation": orientation,
        "vendor_valid_ratio_mean": mean(float(s["valid_ratio"]) for s in vendor_stats),
        "host_valid_ratio_mean": mean(float(s["valid_ratio"]) for s in host_stats),
        "vendor_mm_p50_median": median_or_none(s["mm_p50"] for s in vendor_stats),
        "host_mm_p50_median": median_or_none(s["mm_p50"] for s in host_stats),
        "pair_valid_jaccard_mean": mean(float(m["valid_jaccard"]) for m in pair_metrics),
        "pair_overlap_ratio_pixels_mean": mean(float(m["overlap_ratio_pixels"]) for m in pair_metrics),
        "pair_mae_mm_median": median_or_none(m["mae_mm"] for m in pair_metrics),
        "pair_median_abs_diff_mm": median_or_none(m["median_abs_diff_mm"] for m in pair_metrics),
        "pair_p95_abs_diff_mm_median": median_or_none(m["p95_abs_diff_mm"] for m in pair_metrics),
        "pair_mean_signed_diff_mm_median": median_or_none(m["mean_signed_diff_mm"] for m in pair_metrics),
        "vendor_self_median_abs_diff_mm": vendor_self["median_abs_diff_mm"],
        "host_self_median_abs_diff_mm": host_self["median_abs_diff_mm"],
        "vendor_self_p95_abs_diff_mm": vendor_self["p95_abs_diff_mm"],
        "host_self_p95_abs_diff_mm": host_self["p95_abs_diff_mm"],
    }
    summary["verdict"] = verdict(summary)

    write_csv(out_dir / "frame_stats.csv", frame_rows)
    write_csv(out_dir / "pair_stats.csv", pair_rows)
    (out_dir / "summary.json").write_text(
        json.dumps({"summary": summary, "pairs": pair_rows}, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    (out_dir / "summary.md").write_text(
        "\n".join(
            [
                "# Berxel Depth 多帧对比",
                "",
                f"- 原厂帧数: {len(vendor_files)}，自研帧数: {len(host_files)}，配对: {pair_count}",
                f"- 分辨率: {width}x{height}，方向匹配: {orientation}",
                f"- 原厂平均有效率: {fmt_num(summary['vendor_valid_ratio_mean'], 4)}",
                f"- 自研平均有效率: {fmt_num(summary['host_valid_ratio_mean'], 4)}",
                f"- 原厂 median depth: {fmt_num(summary['vendor_mm_p50_median'])} mm",
                f"- 自研 median depth: {fmt_num(summary['host_mm_p50_median'])} mm",
                f"- 有效区 Jaccard: {fmt_num(summary['pair_valid_jaccard_mean'], 4)}",
                f"- median abs diff: {fmt_num(summary['pair_median_abs_diff_mm'])} mm",
                f"- p95 abs diff median: {fmt_num(summary['pair_p95_abs_diff_mm_median'])} mm",
                f"- 原厂相邻帧 median abs: {fmt_num(summary['vendor_self_median_abs_diff_mm'])} mm",
                f"- 自研相邻帧 median abs: {fmt_num(summary['host_self_median_abs_diff_mm'])} mm",
                f"- 结论: {summary['verdict']}",
                "",
                "详表见 `frame_stats.csv`、`pair_stats.csv`；预览图见 `pair-*-vendor/host/absdiff.pgm`。",
            ]
        )
        + "\n",
        encoding="utf-8",
    )

    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
