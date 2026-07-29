#!/usr/bin/env python3
"""按 VINCreator 原厂模型投影真机深度帧，输出可由分析器裁决的指标。"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import struct
from pathlib import Path

import numpy as np


EXPECTED_SHA256 = "1a87dc030c50d532503218fbb026a453b2c0fa9b17df5316da60782d8d7bf5d2"
DEPTH_WIDTH = 640
DEPTH_HEIGHT = 128
COLOR_WIDTH = 4160
COLOR_HEIGHT = 832
PREVIEW_WIDTH = 1040
PREVIEW_HEIGHT = 208
ROI_REFERENCE_WIDTHS_DP = (360.0, 411.0, 432.0)
ROI_MIN_COVERAGE_RATIO = 0.95
ROI_MIN_PROJECTED_POINT_RATIO = 0.15
ROI_GUIDANCE_DISTANCE_MM = 300.0
ROI_MAX_CAPTURE_DISTANCE_MM = 400.0


def f64(blob: bytes, offset: int) -> float:
    return struct.unpack_from("<d", blob, offset)[0]


def f32(blob: bytes, offset: int) -> float:
    return float(struct.unpack_from("<f", blob, offset)[0])


def rotation_from_euler(euler: np.ndarray) -> np.ndarray:
    e0, e1, e2 = euler
    ry = np.array(
        [[math.cos(e0), 0.0, math.sin(e0)], [0.0, 1.0, 0.0], [-math.sin(e0), 0.0, math.cos(e0)]],
        dtype=np.float64,
    )
    rx = np.array(
        [[1.0, 0.0, 0.0], [0.0, math.cos(e1), -math.sin(e1)], [0.0, math.sin(e1), math.cos(e1)]],
        dtype=np.float64,
    )
    rz = np.array(
        [[math.cos(e2), -math.sin(e2), 0.0], [math.sin(e2), math.cos(e2), 0.0], [0.0, 0.0, 1.0]],
        dtype=np.float64,
    )
    return rz @ rx @ ry


def parse_calibration(path: Path) -> dict[str, object]:
    blob = path.read_bytes()
    digest = hashlib.sha256(blob).hexdigest()
    if len(blob) != 2420:
        raise ValueError(f"标定文件大小 {len(blob)} != 2420")
    if digest != EXPECTED_SHA256:
        raise ValueError(f"标定 SHA-256 {digest} != {EXPECTED_SHA256}")
    if struct.unpack_from("<I", blob, 0x200)[0] != 3:
        raise ValueError("标定版本不是 3")

    base = 0x20C
    euler = np.array([f64(blob, base + 88 + i * 8) for i in range(3)], dtype=np.float64)
    return {
        "sha256": digest,
        "version": 3,
        "principal_row": f64(blob, base),
        "principal_column": f64(blob, base + 8),
        "focal_row": f64(blob, base + 16),
        "focal_column": f64(blob, base + 24),
        "distortion": np.array([f64(blob, base + 44 + i * 8) for i in range(5)], dtype=np.float64),
        "rotation": rotation_from_euler(euler),
        "translation": np.array([f64(blob, base + 112 + i * 8) for i in range(3)], dtype=np.float64),
        "depth_cx": f32(blob, 0x340) * 0.5,
        "depth_cy": f32(blob, 0x344) * 0.5,
        "depth_profile_focal": f32(blob, 0x348) * 0.5,
        "depth_disparity_focal": f32(blob, 0x348),
        "baseline_mm": f32(blob, 0x35C),
    }


def project(calib: dict[str, object], columns: np.ndarray, rows: np.ndarray, z: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    profile_focal = float(calib["depth_profile_focal"])
    vertical_up = (float(calib["depth_cy"]) - rows) * z / profile_focal
    horizontal_right = (columns - float(calib["depth_cx"])) * z / profile_focal
    world = np.column_stack((vertical_up, horizontal_right, z))
    camera = world @ np.asarray(calib["rotation"]).T + np.asarray(calib["translation"])
    camera_z = camera[:, 2]

    row_delta = float(calib["focal_row"]) * camera[:, 0] / camera_z
    column_delta = float(calib["focal_column"]) * camera[:, 1] / camera_z
    radius = np.hypot(row_delta, column_delta)
    nonzero = radius > 1e-12
    fov_row = row_delta.copy()
    fov_column = column_delta.copy()
    fov_row[nonzero] = (
        float(calib["focal_row"])
        * np.arctan(radius[nonzero] / float(calib["focal_row"]))
        * row_delta[nonzero]
        / radius[nonzero]
    )
    fov_column[nonzero] = (
        float(calib["focal_column"])
        * np.arctan(radius[nonzero] / float(calib["focal_column"]))
        * column_delta[nonzero]
        / radius[nonzero]
    )

    k, p1, p2, s1, s2 = np.asarray(calib["distortion"])
    radius2 = fov_row * fov_row + fov_column * fov_column
    distorted_row = (
        fov_row
        + k * fov_row * radius2
        + p1 * (3.0 * fov_row * fov_row + fov_column * fov_column)
        + 2.0 * p2 * fov_row * fov_column
        + s1 * radius2
    )
    distorted_column = (
        fov_column
        + k * fov_column * radius2
        + p2 * (fov_row * fov_row + 3.0 * fov_column * fov_column)
        + 2.0 * p1 * fov_row * fov_column
        + s2 * radius2
    )
    color_column = float(calib["principal_column"]) + distorted_column
    color_row = float(calib["principal_row"]) + distorted_row
    return color_column, color_row, np.abs(camera_z)


def load_roi_contract(path: Path) -> dict[float, tuple[float, float, float, float]]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError(f"无法读取生产 Kotlin ROI 契约: {exc}") from exc
    contract: dict[float, tuple[float, float, float, float]] = {}
    for item in payload.get("roi_contract", []):
        width_dp = float(item["viewport_width_dp"])
        normalized = item["normalized_roi"]
        roi = tuple(float(normalized[key]) for key in ("left", "top", "right", "bottom"))
        left, top, right, bottom = roi
        if not (0.0 <= left < right <= 1.0 and 0.0 <= top < bottom <= 1.0):
            raise ValueError(f"生产 Kotlin ROI 非法: {width_dp}dp {roi}")
        contract[width_dp] = roi
    expected = set(ROI_REFERENCE_WIDTHS_DP)
    if set(contract) != expected:
        raise ValueError(f"生产 Kotlin ROI 宽度集合 {sorted(contract)} != {sorted(expected)}")
    return contract


def projected_depth_buffer(
    calib: dict[str, object],
    raw: np.ndarray,
) -> tuple[np.ndarray, dict[str, object]]:
    grid_rows, grid_columns = np.indices(raw.shape, dtype=np.float64)
    valid = raw > 0
    z_all = np.zeros(raw.shape, dtype=np.float64)
    z_all[valid] = (
        float(calib["depth_disparity_focal"])
        * float(calib["baseline_mm"])
        / (raw[valid].astype(np.float64) * 0.125)
    )
    valid &= (z_all > 50.0) & (z_all < 1000.0)
    columns = grid_columns[valid]
    rows = grid_rows[valid]
    z = z_all[valid]

    color_column, color_row, camera_distance = project(calib, columns, rows, z)
    preview_x = color_column * PREVIEW_WIDTH / COLOR_WIDTH
    preview_y = color_row * PREVIEW_HEIGHT / COLOR_HEIGHT
    in_view = (
        np.isfinite(preview_x)
        & np.isfinite(preview_y)
        & np.isfinite(camera_distance)
        & (preview_x >= 0.0)
        & (preview_x < PREVIEW_WIDTH)
        & (preview_y >= 0.0)
        & (preview_y < PREVIEW_HEIGHT)
    )
    center_x = np.floor(preview_x[in_view] + 0.5).astype(np.int32)
    center_y = np.floor(preview_y[in_view] + 0.5).astype(np.int32)
    center_depth = z[in_view]
    center_distance = camera_distance[in_view]
    center_valid = (
        (center_x >= 0)
        & (center_x < PREVIEW_WIDTH)
        & (center_y >= 0)
        & (center_y < PREVIEW_HEIGHT)
    )
    z_buffer = np.full(PREVIEW_WIDTH * PREVIEW_HEIGHT, np.inf, dtype=np.float64)
    depth_buffer = np.full_like(z_buffer, np.nan)
    point_mask = np.zeros(PREVIEW_WIDTH * PREVIEW_HEIGHT, dtype=np.bool_)
    point_mask[center_y[center_valid] * PREVIEW_WIDTH + center_x[center_valid]] = True
    for delta_y in (-1, 0, 1):
        for delta_x in (-1, 0, 1):
            x = center_x + delta_x
            y = center_y + delta_y
            inside = (x >= 0) & (x < PREVIEW_WIDTH) & (y >= 0) & (y < PREVIEW_HEIGHT)
            indices = y[inside] * PREVIEW_WIDTH + x[inside]
            distances = center_distance[inside]
            depths = center_depth[inside]
            order = np.lexsort((distances, indices))
            ordered_indices = indices[order]
            first = np.empty(ordered_indices.size, dtype=np.bool_)
            if first.size:
                first[0] = True
                first[1:] = ordered_indices[1:] != ordered_indices[:-1]
                selected = order[first]
                selected_indices = indices[selected]
                closer = distances[selected] < z_buffer[selected_indices]
                selected = selected[closer]
                selected_indices = indices[selected]
                z_buffer[selected_indices] = distances[selected]
                depth_buffer[selected_indices] = depths[selected]

    return depth_buffer.reshape((PREVIEW_HEIGHT, PREVIEW_WIDTH)), {
        "valid_mask": valid,
        "columns": columns,
        "rows": rows,
        "z": z,
        "preview_x": preview_x,
        "preview_y": preview_y,
        "in_view": in_view,
        "point_mask": point_mask.reshape((PREVIEW_HEIGHT, PREVIEW_WIDTH)),
    }


def roi_metrics(
    depth_buffer: np.ndarray,
    projected: dict[str, object],
    width_dp: float,
    roi: tuple[float, float, float, float],
) -> dict[str, object]:
    left, top, right, bottom = roi
    x0 = max(0, min(PREVIEW_WIDTH, math.floor(left * PREVIEW_WIDTH)))
    x1 = max(x0, min(PREVIEW_WIDTH, math.ceil(right * PREVIEW_WIDTH)))
    y0 = max(0, min(PREVIEW_HEIGHT, math.floor(top * PREVIEW_HEIGHT)))
    y1 = max(y0, min(PREVIEW_HEIGHT, math.ceil(bottom * PREVIEW_HEIGHT)))
    region = depth_buffer[y0:y1, x0:x1]
    valid_depths = region[np.isfinite(region)]
    total_pixels = int(region.size)
    valid_pixels = int(valid_depths.size)
    coverage = valid_pixels / max(1, total_pixels)
    preview_x = np.asarray(projected["preview_x"])
    preview_y = np.asarray(projected["preview_y"])
    depths = np.asarray(projected["z"])
    roi_points = (
        np.isfinite(preview_x)
        & np.isfinite(preview_y)
        & (preview_x >= left * PREVIEW_WIDTH)
        & (preview_x < right * PREVIEW_WIDTH)
        & (preview_y >= top * PREVIEW_HEIGHT)
        & (preview_y < bottom * PREVIEW_HEIGHT)
    )
    roi_depths = depths[roi_points]
    projected_points = int(roi_depths.size)
    projected_point_ratio = projected_points / max(1, total_pixels)
    far_enough_ratio = float(np.mean(roi_depths >= ROI_GUIDANCE_DISTANCE_MM)) if projected_points else 0.0
    distance_p10 = float(np.percentile(roi_depths, 10)) if projected_points else None
    distance_median = float(np.percentile(roi_depths, 50)) if projected_points else None
    return {
        "viewport_width_dp": width_dp,
        "normalized_roi": {"left": left, "top": top, "right": right, "bottom": bottom},
        "total_pixels": total_pixels,
        "valid_pixels": valid_pixels,
        "coverage_ratio": coverage,
        "projected_points": projected_points,
        "projected_point_ratio": projected_point_ratio,
        "distance_p10_mm": distance_p10,
        "distance_median_mm": distance_median,
        "far_enough_ratio": far_enough_ratio,
        "ready": coverage >= ROI_MIN_COVERAGE_RATIO
        and projected_point_ratio >= ROI_MIN_PROJECTED_POINT_RATIO
        and distance_median is not None
        and distance_median <= ROI_MAX_CAPTURE_DISTANCE_MM,
    }


def historical_samples(
    calib: dict[str, object],
    root: Path | None,
    roi_contract: dict[float, tuple[float, float, float, float]],
) -> list[dict[str, object]]:
    if root is None or not root.is_dir():
        return []
    samples: list[dict[str, object]] = []
    for capture in sorted(root.glob("cap_*")):
        depth_path = capture / "depth.yuv"
        restore_path = capture / "restore.json"
        if not depth_path.is_file() or not restore_path.is_file():
            continue
        raw = np.fromfile(depth_path, dtype="<u2")
        if raw.size != DEPTH_WIDTH * DEPTH_HEIGHT:
            continue
        try:
            restore = json.loads(restore_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        depth_buffer, projected = projected_depth_buffer(calib, raw.reshape((DEPTH_HEIGHT, DEPTH_WIDTH)))
        samples.append(
            {
                "capture": str(capture),
                "restore_ok": bool(restore.get("ok", False)),
                "reject_reason": str(restore.get("reject_reason", "")),
                "roi_metrics": [
                    roi_metrics(depth_buffer, projected, width_dp, roi_contract[width_dp])
                    for width_dp in ROI_REFERENCE_WIDTHS_DP
                ],
            }
        )
    return samples


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--calibration", type=Path, required=True)
    parser.add_argument("--capture", type=Path, required=True)
    parser.add_argument("--history-root", type=Path)
    parser.add_argument("--roi-contract", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    calib = parse_calibration(args.calibration)
    roi_contract = load_roi_contract(args.roi_contract)
    raw = np.fromfile(args.capture / "depth.yuv", dtype="<u2")
    if raw.size != DEPTH_WIDTH * DEPTH_HEIGHT:
        raise ValueError(f"深度帧像素数 {raw.size} != {DEPTH_WIDTH * DEPTH_HEIGHT}")
    raw = raw.reshape((DEPTH_HEIGHT, DEPTH_WIDTH))
    depth_buffer, projected = projected_depth_buffer(calib, raw)
    valid = np.asarray(projected["valid_mask"])
    columns = np.asarray(projected["columns"])
    rows = np.asarray(projected["rows"])
    z = np.asarray(projected["z"])
    preview_x = np.asarray(projected["preview_x"])
    preview_y = np.asarray(projected["preview_y"])
    in_view = np.asarray(projected["in_view"])
    point_mask = np.asarray(projected["point_mask"])
    splat_mask = np.isfinite(depth_buffer)

    median_z = float(np.median(z))
    fixed_column, fixed_row, _ = project(calib, columns, rows, np.full_like(z, median_z))
    fixed_x = fixed_column * PREVIEW_WIDTH / COLOR_WIDTH
    fixed_y = fixed_row * PREVIEW_HEIGHT / COLOR_HEIGHT
    comparable = in_view & np.isfinite(fixed_x) & np.isfinite(fixed_y)
    fixed_error = np.hypot(preview_x[comparable] - fixed_x[comparable], preview_y[comparable] - fixed_y[comparable])

    oracle_inputs = [(324, 65), (344, 55), (304, 75)]
    oracle_expected = [(279.383921, 62.219432), (306.568288, 48.603812), (252.195054, 75.826743)]
    oracle = []
    for (column, row), (want_x, want_y) in zip(oracle_inputs, oracle_expected):
        oracle_z = np.array([
            float(calib["depth_disparity_focal"]) * float(calib["baseline_mm"]) / (1300.0 * 0.125)
        ])
        got_column, got_row, _ = project(
            calib,
            np.array([column], dtype=np.float64),
            np.array([row], dtype=np.float64),
            oracle_z,
        )
        got_x = float(got_column[0] * 640.0 / COLOR_WIDTH)
        got_y = float(got_row[0] * 128.0 / COLOR_HEIGHT)
        oracle.append(
            {
                "column": column,
                "row": row,
                "x": got_x,
                "y": got_y,
                "error_px": math.hypot(got_x - want_x, got_y - want_y),
            }
        )

    result = {
        "calibration_sha256": calib["sha256"],
        "calibration_version": calib["version"],
        "capture": str(args.capture),
        "valid_depth_points": int(valid.sum()),
        "points_in_color_view": int(in_view.sum()),
        "in_color_view_ratio": float(in_view.sum() / max(1, valid.sum())),
        "point_coverage_ratio": float(point_mask.mean()),
        "splat_3x3_coverage_ratio": float(splat_mask.mean()),
        "median_depth_mm": median_z,
        "fixed_depth_error_median_px": float(np.median(fixed_error)),
        "fixed_depth_error_p95_px": float(np.percentile(fixed_error, 95)),
        "roi_thresholds": {
            "min_coverage_ratio": ROI_MIN_COVERAGE_RATIO,
            "min_projected_point_ratio": ROI_MIN_PROJECTED_POINT_RATIO,
            "guidance_distance_mm": ROI_GUIDANCE_DISTANCE_MM,
            "max_capture_distance_mm": ROI_MAX_CAPTURE_DISTANCE_MM,
        },
        "roi_metrics": [
            roi_metrics(depth_buffer, projected, width_dp, roi_contract[width_dp])
            for width_dp in ROI_REFERENCE_WIDTHS_DP
        ],
        "historical_samples": historical_samples(calib, args.history_root, roi_contract),
        "oracle": oracle,
    }
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
