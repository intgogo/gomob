#!/usr/bin/env python3
"""分析 Berxel host SDK frames.csv 的双流 host timestamp 同步情况。"""

from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, round((len(ordered) - 1) * p)))
    return ordered[index]


def midpoint_ns(row: dict[str, str]) -> int:
    return (int(row["host_start_ns"]) + int(row["host_end_ns"])) // 2


def nearest_depth(color_mid: int, depth_mids: list[int], start_index: int) -> tuple[int, int]:
    if not depth_mids:
        return -1, 0
    i = min(max(start_index, 0), len(depth_mids) - 1)
    while i + 1 < len(depth_mids) and abs(depth_mids[i + 1] - color_mid) <= abs(depth_mids[i] - color_mid):
        i += 1
    return i, depth_mids[i] - color_mid


def sequence_summary(values: list[int]) -> dict[str, int | bool]:
    missing = 0
    non_increasing = 0
    for prev, cur in zip(values, values[1:]):
        if cur <= prev:
            non_increasing += 1
        elif cur > prev + 1:
            missing += cur - prev - 1
    return {
        "first": values[0] if values else 0,
        "last": values[-1] if values else 0,
        "missing": missing,
        "non_increasing": non_increasing,
        "strictly_increasing": non_increasing == 0,
    }


def stream_summary(rows: list[dict[str, str]]) -> dict[str, object]:
    def count_one(field: str) -> int:
        return sum(1 for r in rows if r.get(field) == "1")

    return {
        "frames": len(rows),
        "frame_numbers": sequence_summary([int(r["frame"]) for r in rows]),
        "completed_by": {
            "eof": count_one("completed_by_eof"),
            "size": count_one("completed_by_size"),
            "fid": count_one("completed_by_fid"),
            "jpeg_eoi": count_one("completed_by_jpeg_eoi"),
        },
        "with_uvc_pts": count_one("has_uvc_pts"),
        "with_uvc_scr": count_one("has_uvc_scr"),
    }


def pair_sequence_summary(pair_rows: list[dict[str, str]]) -> dict[str, object]:
    pair_numbers = [int(r["pair"]) for r in pair_rows]
    color_frames = [int(r["color_frame"]) for r in pair_rows]
    depth_frames = [int(r["depth_frame"]) for r in pair_rows]
    color_skipped = sum(max(0, cur - prev - 1) for prev, cur in zip(color_frames, color_frames[1:]))
    depth_skipped = sum(max(0, cur - prev - 1) for prev, cur in zip(depth_frames, depth_frames[1:]))
    return {
        "pair_numbers": sequence_summary(pair_numbers),
        "color_frames": sequence_summary(color_frames),
        "depth_frames": sequence_summary(depth_frames),
        "color_frames_skipped_between_pairs": color_skipped,
        "depth_frames_skipped_between_pairs": depth_skipped,
        "within_tolerance": sum(1 for r in pair_rows if r.get("within_tolerance", "1") == "1"),
    }


def analyze(path: Path) -> dict[str, object]:
    rows: list[dict[str, str]] = []
    with path.open(newline="") as f:
        rows = list(csv.DictReader(f))

    color = [r for r in rows if r["stream"] == "color"]
    depth = [r for r in rows if r["stream"] == "depth"]
    color_mids = [midpoint_ns(r) for r in color]
    depth_mids = [midpoint_ns(r) for r in depth]

    nearest_deltas_ms: list[float] = []
    abs_deltas_ms: list[float] = []
    depth_index = 0
    for mid in color_mids:
        depth_index, delta_ns = nearest_depth(mid, depth_mids, depth_index)
        if depth_index < 0:
            continue
        delta_ms = delta_ns / 1_000_000.0
        nearest_deltas_ms.append(delta_ms)
        abs_deltas_ms.append(abs(delta_ms))

    def count_with(field: str, stream_rows: list[dict[str, str]]) -> int:
        return sum(1 for r in stream_rows if r.get(field) == "1")

    result: dict[str, object] = {
        "frames_csv": str(path),
        "color_frames": len(color),
        "depth_frames": len(depth),
        "matched_color_frames": len(abs_deltas_ms),
        "color_with_uvc_pts": count_with("has_uvc_pts", color),
        "depth_with_uvc_pts": count_with("has_uvc_pts", depth),
        "color_with_uvc_scr": count_with("has_uvc_scr", color),
        "depth_with_uvc_scr": count_with("has_uvc_scr", depth),
        "streams": {
            "color": stream_summary(color),
            "depth": stream_summary(depth),
        },
        "nearest_depth_delta_ms": {
            "mean_signed": sum(nearest_deltas_ms) / len(nearest_deltas_ms) if nearest_deltas_ms else 0.0,
            "p50_abs": percentile(abs_deltas_ms, 0.50),
            "p95_abs": percentile(abs_deltas_ms, 0.95),
            "max_abs": max(abs_deltas_ms) if abs_deltas_ms else 0.0,
        },
    }
    pairs_path = path.with_name("pairs.csv")
    if pairs_path.exists():
        with pairs_path.open(newline="") as f:
            pair_rows = list(csv.DictReader(f))
        pair_deltas = [float(r["delta_ms"]) for r in pair_rows]
        pair_abs = [abs(v) for v in pair_deltas]
        result["pairs_csv"] = str(pairs_path)
        result["rgbd_pairs"] = len(pair_rows)
        result["pair_sequence"] = pair_sequence_summary(pair_rows)
        result["pair_delta_ms"] = {
            "mean_signed": sum(pair_deltas) / len(pair_deltas) if pair_deltas else 0.0,
            "p50_abs": percentile(pair_abs, 0.50),
            "p95_abs": percentile(pair_abs, 0.95),
            "max_abs": max(pair_abs) if pair_abs else 0.0,
        }
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("frames_csv", type=Path)
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()

    result = analyze(args.frames_csv)
    text = json.dumps(result, ensure_ascii=False, indent=2)
    print(text)
    if args.out:
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(text + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
