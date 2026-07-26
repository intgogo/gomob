#!/usr/bin/env python3
"""验证空工位背景相减契约。

生产顺序必须与 runner 一致：

``A/B live`` 用 canonical site 应用当前 region；A/B background 必须已经处于同一裁剪域；
随后分设备相减，仅把 B 前景用本次最终 B→A 变到 A 系并合并。

迁移前已在真实工位验证的 ``legacy_fused`` 背景保留原有路径：live/background 都是区域裁剪后的
A 系融合云，直接相减。该入口必须显式声明 ``BACKGROUND_SCHEMA=legacy_fused``，不能冒充新 A/B 背景。
"""

from __future__ import annotations

import io
import json
import math
import os
import sys
from contextlib import redirect_stdout
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping

import numpy as np

try:
    from scipy.spatial import cKDTree
except ImportError:  # pragma: no cover - 无 scipy 时保留纯 Python 等价路径
    cKDTree = None


DEFAULT_TOL = 40.0
REAL_INPUT_KEYS = (
    "LIVE_A_PCD",
    "LIVE_B_PCD",
    "BG_A_PCD",
    "BG_B_PCD",
    "REGION_JSON",
    "REGION_B_TO_A_JSON",
    "FINAL_B_TO_A_JSON",
)
LEGACY_FUSED_KEYS = ("LIVE_PCD", "BG_PCD")


@dataclass
class PipelineResult:
    live_a_region: np.ndarray
    live_b_region: np.ndarray
    bg_a_region: np.ndarray
    bg_b_region: np.ndarray
    foreground_a: np.ndarray
    foreground_b: np.ndarray
    merged: np.ndarray


@dataclass(frozen=True)
class RealInputs:
    live_a: Path
    live_b: Path
    bg_a: Path
    bg_b: Path
    region_raw: str
    region_b_to_a_raw: str
    final_b_to_a_raw: str


@dataclass(frozen=True)
class LegacyFusedInputs:
    live: Path
    background: Path


def xyz_array(value: Any, name: str) -> np.ndarray:
    points = np.asarray(value, dtype=np.float32)
    if points.size == 0:
        return np.empty((0, 3), dtype=np.float32)
    if points.ndim != 2 or points.shape[1] != 3:
        raise ValueError(f"{name} 必须是 N×3 点阵")
    if not np.isfinite(points).all():
        raise ValueError(f"{name} 含 NaN/Inf")
    return np.ascontiguousarray(points)


def load_pcd(path: Path) -> np.ndarray:
    data = path.read_bytes()
    marker = b"DATA binary\n"
    index = data.find(marker)
    if index < 0:
        marker = b"DATA binary\r\n"
        index = data.find(marker)
    if index < 0:
        raise ValueError(f"{path}: 仅支持 DATA binary PCD")

    header = data[:index].decode("ascii", "strict")
    fields: list[str] = []
    sizes: list[int] = []
    types: list[str] = []
    counts: list[int] = []
    point_count = 0
    for line in header.splitlines():
        tokens = line.split()
        if not tokens:
            continue
        if tokens[0] == "FIELDS":
            fields = tokens[1:]
        elif tokens[0] == "SIZE":
            sizes = [int(item) for item in tokens[1:]]
        elif tokens[0] == "TYPE":
            types = tokens[1:]
        elif tokens[0] == "COUNT":
            counts = [int(item) for item in tokens[1:]]
        elif tokens[0] == "POINTS":
            point_count = int(tokens[1])
    if not counts:
        counts = [1] * len(fields)
    if not (len(fields) == len(sizes) == len(types) == len(counts)):
        raise ValueError(f"{path}: PCD 字段定义不完整")
    for axis in ("x", "y", "z"):
        if axis not in fields:
            raise ValueError(f"{path}: PCD 缺少 {axis} 字段")
        axis_index = fields.index(axis)
        if sizes[axis_index] != 4 or types[axis_index] != "F" or counts[axis_index] != 1:
            raise ValueError(f"{path}: {axis} 必须是 float32")

    offsets: dict[str, int] = {}
    stride = 0
    for field, size, count in zip(fields, sizes, counts):
        offsets[field] = stride
        stride += size * count
    body = data[index + len(marker) :]
    expected = point_count * stride
    if point_count <= 0 or len(body) < expected:
        raise ValueError(f"{path}: PCD 点数或数据长度无效")
    raw = np.frombuffer(body[:expected], dtype=np.uint8).reshape(point_count, stride)
    axes = [
        raw[:, offsets[axis] : offsets[axis] + 4].copy().view("<f4").reshape(-1)
        for axis in ("x", "y", "z")
    ]
    points = np.stack(axes, axis=1)
    valid = np.isfinite(points).all(axis=1) & (np.abs(points) < 1e6).all(axis=1)
    return np.ascontiguousarray(points[valid], dtype=np.float32)


def write_pcd(path: Path, points: np.ndarray) -> None:
    points = xyz_array(points, "输出点云")
    header = (
        "# .PCD v0.7\nVERSION 0.7\nFIELDS x y z\nSIZE 4 4 4\nTYPE F F F\n"
        f"COUNT 1 1 1\nWIDTH {len(points)}\nHEIGHT 1\nVIEWPOINT 0 0 0 1 0 0 0\n"
        f"POINTS {len(points)}\nDATA binary\n"
    ).encode("ascii")
    path.write_bytes(header + np.asarray(points, dtype="<f4").tobytes(order="C"))


def subtract_background(live: np.ndarray, background: np.ndarray, tolerance: float) -> np.ndarray:
    """镜像 Go 的 tol 体素哈希 + 27 邻域精确距离。"""
    live = xyz_array(live, "live")
    background = xyz_array(background, "background")
    if tolerance <= 0 or not math.isfinite(tolerance):
        raise ValueError("背景相减 tolerance 必须是正有限数")
    if len(live) == 0 or len(background) == 0:
        return live.copy()

    # 真机 A/B 云可达百万点；KD-tree 与 Go 的“最近背景点 <= tol”判据等价，避免 Python
    # 逐点循环成为 harness 本身的瓶颈。精简环境无 scipy 时继续走下方体素哈希。
    if cKDTree is not None and max(len(live), len(background)) >= 5000:
        distances, _ = cKDTree(background).query(
            live,
            k=1,
            distance_upper_bound=tolerance,
            workers=-1,
        )
        return live[~np.isfinite(distances)]

    inverse = 1.0 / tolerance
    background_keys = np.floor(background * inverse).astype(np.int64)
    grid: dict[tuple[int, int, int], list[int]] = {}
    for index, key in enumerate(map(tuple, background_keys)):
        grid.setdefault(key, []).append(index)
    tolerance_squared = tolerance * tolerance
    live_keys = np.floor(live * inverse).astype(np.int64)
    keep = np.ones(len(live), dtype=bool)
    for index, point in enumerate(live):
        cell_x, cell_y, cell_z = live_keys[index]
        matched = False
        for delta_x in (-1, 0, 1):
            for delta_y in (-1, 0, 1):
                for delta_z in (-1, 0, 1):
                    candidates = grid.get(
                        (cell_x + delta_x, cell_y + delta_y, cell_z + delta_z), ()
                    )
                    for background_index in candidates:
                        delta = background[background_index] - point
                        if float(delta @ delta) <= tolerance_squared:
                            matched = True
                            break
                    if matched:
                        break
                if matched:
                    break
            if matched:
                break
        keep[index] = not matched
    return live[keep]


def transform_points(points: np.ndarray, matrix: np.ndarray) -> np.ndarray:
    points = xyz_array(points, "待变换点云")
    if len(points) == 0:
        return points.copy()
    return np.ascontiguousarray(points @ matrix[:3, :3].T + matrix[:3, 3], dtype=np.float32)


def inverse_rigid(matrix: np.ndarray) -> np.ndarray:
    rotation = matrix[:3, :3]
    translation = matrix[:3, 3]
    result = np.eye(4, dtype=np.float32)
    result[:3, :3] = rotation.T
    result[:3, 3] = -(rotation.T @ translation)
    return result


def points_in_region(points_world: np.ndarray, polygon: np.ndarray) -> np.ndarray:
    points_world = xyz_array(points_world, "世界系点云")
    polygon = xyz_array(polygon, "区域墙")
    if len(polygon) < 3:
        raise ValueError("区域墙至少需要 3 个点")
    x = points_world[:, 0]
    y = points_world[:, 1]
    inside = np.zeros(len(points_world), dtype=bool)
    boundary = np.zeros(len(points_world), dtype=bool)
    previous = len(polygon) - 1
    for current in range(len(polygon)):
        x1, y1 = polygon[current, :2]
        x2, y2 = polygon[previous, :2]
        cross = (x - x1) * (y2 - y1) - (y - y1) * (x2 - x1)
        dot = (x - x1) * (x - x2) + (y - y1) * (y - y2)
        boundary |= (np.abs(cross) <= 1e-3) & (dot <= 1e-3)
        crossing = (y1 > y) != (y2 > y)
        if y2 != y1:
            cross_x = (x2 - x1) * (y - y1) / (y2 - y1) + x1
            inside ^= crossing & (x <= cross_x)
        previous = current
    return inside | boundary


def filter_region(
    points: np.ndarray,
    polygon: np.ndarray,
    unit: str,
    b_to_a: np.ndarray,
) -> np.ndarray:
    points = xyz_array(points, f"unit {unit} 点云")
    if unit not in ("a", "b"):
        raise ValueError("unit 必须是 a 或 b")
    world = points if unit == "a" else transform_points(points, b_to_a)
    return points[points_in_region(world, polygon)]


def run_pipeline(
    live_a: np.ndarray,
    live_b: np.ndarray,
    background_a: np.ndarray,
    background_b: np.ndarray,
    polygon: np.ndarray,
    region_b_to_a: np.ndarray,
    final_b_to_a: np.ndarray,
    tolerance: float = DEFAULT_TOL,
) -> PipelineResult:
    live_a_region = filter_region(live_a, polygon, "a", region_b_to_a)
    live_b_region = filter_region(live_b, polygon, "b", region_b_to_a)
    bg_a_region = xyz_array(background_a, "background A")
    bg_b_region = xyz_array(background_b, "background B")
    foreground_a = subtract_background(live_a_region, bg_a_region, tolerance)
    foreground_b = subtract_background(live_b_region, bg_b_region, tolerance)
    merged = np.concatenate((foreground_a, transform_points(foreground_b, final_b_to_a)), axis=0)
    return PipelineResult(
        live_a_region,
        live_b_region,
        bg_a_region,
        bg_b_region,
        foreground_a,
        foreground_b,
        merged,
    )


def span(points: np.ndarray) -> np.ndarray:
    return points.max(axis=0) - points.min(axis=0) if len(points) else np.zeros(3)


def json_value(raw: str, name: str) -> Any:
    candidate = Path(raw)
    try:
        is_file = candidate.is_file()
    except OSError:
        is_file = False
    text = candidate.read_text(encoding="utf-8") if is_file else raw
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"{name} 不是有效 JSON：{exc}") from exc


def parse_region(raw: str) -> np.ndarray:
    value = json_value(raw, "REGION_JSON")
    if isinstance(value, dict):
        if value.get("enabled") is False:
            raise ValueError("REGION_JSON 当前区域未启用")
        if value.get("b_to_a") is not None:
            raise ValueError("REGION_JSON 不得携带 b_to_a；裁剪矩阵必须单独提供")
        value = value.get("points")
    polygon = xyz_array(value, "REGION_JSON.points")
    if len(polygon) > 3 and np.allclose(polygon[0, :2], polygon[-1, :2], atol=1e-3):
        polygon = polygon[:-1]
    if len(polygon) < 3:
        raise ValueError("REGION_JSON 至少需要 3 个区域点")
    return polygon


def parse_b_to_a(raw: str, name: str) -> np.ndarray:
    value = json_value(raw, name)
    if isinstance(value, dict):
        value = value.get("b_to_a", value.get("matrix"))
    matrix = np.asarray(value, dtype=np.float64)
    if matrix.size != 16:
        raise ValueError(f"{name} 必须包含 16 个数")
    matrix = matrix.reshape(4, 4)
    if not np.isfinite(matrix).all():
        raise ValueError(f"{name} 含 NaN/Inf")
    if not np.allclose(matrix[3], (0, 0, 0, 1), atol=1e-5):
        raise ValueError(f"{name} 不是齐次刚体矩阵")
    rotation = matrix[:3, :3]
    if not np.allclose(rotation.T @ rotation, np.eye(3), atol=1e-3):
        raise ValueError(f"{name} 旋转含缩放/剪切")
    if not math.isclose(float(np.linalg.det(rotation)), 1.0, abs_tol=1e-3):
        raise ValueError(f"{name} 旋转 determinant 必须为 +1")
    return matrix.astype(np.float32)


def real_inputs_from_env(env: Mapping[str, str]) -> RealInputs | LegacyFusedInputs | None:
    legacy = [key for key in LEGACY_FUSED_KEYS if env.get(key)]
    if legacy:
        modern = [key for key in REAL_INPUT_KEYS if env.get(key)]
        if modern:
            raise ValueError("legacy fused 与 A/B 背景输入不能混用")
        if env.get("BACKGROUND_SCHEMA") != "legacy_fused":
            raise ValueError("融合云相减只允许显式 BACKGROUND_SCHEMA=legacy_fused")
        missing = [key for key in LEGACY_FUSED_KEYS if not env.get(key)]
        if missing:
            raise ValueError("legacy fused 输入不完整，缺少 " + "、".join(missing))
        live, background = (Path(env[key]) for key in LEGACY_FUSED_KEYS)
        absent = [str(path) for path in (live, background) if not path.is_file()]
        if absent:
            raise ValueError("legacy fused PCD 不存在：" + "、".join(absent))
        return LegacyFusedInputs(live, background)
    present = [key for key in REAL_INPUT_KEYS if env.get(key)]
    if not present:
        return None
    missing = [key for key in REAL_INPUT_KEYS if not env.get(key)]
    if missing:
        raise ValueError("真实闭环输入不完整，缺少 " + "、".join(missing))
    paths = [Path(env[key]) for key in REAL_INPUT_KEYS[:4]]
    absent = [str(path) for path in paths if not path.is_file()]
    if absent:
        raise ValueError("真实 PCD 不存在：" + "、".join(absent))
    return RealInputs(
        *paths,
        env["REGION_JSON"],
        env["REGION_B_TO_A_JSON"],
        env["FINAL_B_TO_A_JSON"],
    )


def plane_grid(axis: int, fixed: float, a_range: tuple[float, float], b_range: tuple[float, float], step: float) -> np.ndarray:
    a = np.arange(a_range[0], a_range[1], step, dtype=np.float32)
    b = np.arange(b_range[0], b_range[1], step, dtype=np.float32)
    grid_a, grid_b = np.meshgrid(a, b)
    grid_a = grid_a.ravel()
    grid_b = grid_b.ravel()
    fixed_values = np.full_like(grid_a, fixed)
    if axis == 0:
        return np.stack((fixed_values, grid_a, grid_b), axis=1)
    if axis == 1:
        return np.stack((grid_a, fixed_values, grid_b), axis=1)
    return np.stack((grid_a, grid_b, fixed_values), axis=1)


def make_background_world(step: float = 180.0) -> np.ndarray:
    floor = plane_grid(2, 0, (-3600, 3601), (-2700, 2701), step)
    walls = np.concatenate(
        (
            plane_grid(0, -3400, (-2500, 2501), (0, 2401), step),
            plane_grid(0, 3400, (-2500, 2501), (0, 2401), step),
            plane_grid(1, -2500, (-3400, 3401), (0, 2401), step),
            plane_grid(1, 2500, (-3400, 3401), (0, 2401), step),
        ),
        axis=0,
    )
    fixture = plane_grid(0, 2200, (-900, 901), (0, 1801), step)
    return np.concatenate((floor, walls, fixture), axis=0).astype(np.float32)


def make_vehicle_world(step: float = 110.0) -> np.ndarray:
    half_length, half_width, height = 1900.0, 780.0, 1450.0
    shell = np.concatenate(
        (
            plane_grid(2, height, (-half_length, half_length + 1), (-half_width, half_width + 1), step),
            plane_grid(0, -half_length, (-half_width, half_width + 1), (80, height + 1), step),
            plane_grid(0, half_length, (-half_width, half_width + 1), (80, height + 1), step),
            plane_grid(1, -half_width, (-half_length, half_length + 1), (80, height + 1), step),
            plane_grid(1, half_width, (-half_length, half_length + 1), (80, height + 1), step),
        ),
        axis=0,
    )
    angle = math.radians(13)
    rotation = np.array(
        [[math.cos(angle), -math.sin(angle), 0], [math.sin(angle), math.cos(angle), 0], [0, 0, 1]],
        dtype=np.float32,
    )
    return shell @ rotation.T + np.array((100, -80, 0), dtype=np.float32)


def synthetic_scene() -> tuple[dict[str, np.ndarray], np.ndarray, np.ndarray, np.ndarray]:
    angle = math.radians(27)
    b_to_a = np.array(
        [
            [math.cos(angle), -math.sin(angle), 0, 920],
            [math.sin(angle), math.cos(angle), 0, -680],
            [0, 0, 1, 120],
            [0, 0, 0, 1],
        ],
        dtype=np.float32,
    )
    a_to_b = inverse_rigid(b_to_a)
    region = np.array(
        [(-2450, -1550, 0), (2450, -1550, 0), (2450, 1550, 0), (-2450, 1550, 0)],
        dtype=np.float32,
    )
    background_world = make_background_world()
    vehicle_world = make_vehicle_world()
    background_a = background_world[background_world[:, 0] <= 1200]
    background_b_world = background_world[background_world[:, 0] >= -1200]
    background_b = transform_points(background_b_world, a_to_b)
    vehicle_a = vehicle_world[vehicle_world[:, 1] <= 250]
    vehicle_b_world = vehicle_world[vehicle_world[:, 1] >= -250]
    vehicle_b = transform_points(vehicle_b_world, a_to_b)

    rng = np.random.default_rng(20260712)
    live_background_a = background_a + rng.uniform(-10, 10, background_a.shape).astype(np.float32)
    live_background_b = background_b + rng.uniform(-10, 10, background_b.shape).astype(np.float32)
    outside_world = np.array(
        [[3100 + x, 2100 + y, 600 + z] for x in range(0, 500, 100) for y in range(0, 300, 100) for z in range(0, 500, 100)],
        dtype=np.float32,
    )
    outside_a = outside_world.copy()
    outside_b = transform_points(outside_world + np.array((300, 0, 0), dtype=np.float32), a_to_b)
    background_a_cropped = filter_region(background_a, region, "a", b_to_a)
    background_b_cropped = filter_region(background_b, region, "b", b_to_a)
    final_b_to_a = b_to_a.copy()
    final_b_to_a[0, 3] += 35
    return (
        {
            "background_a": background_a,
            "background_b": background_b,
            "background_a_cropped": background_a_cropped,
            "background_b_cropped": background_b_cropped,
            "live_background_a": live_background_a,
            "live_background_b": live_background_b,
            "vehicle_a": vehicle_a,
            "vehicle_b": vehicle_b,
            "outside_a": outside_a,
            "outside_b": outside_b,
            "live_a": np.concatenate((live_background_a, vehicle_a, outside_a), axis=0),
            "live_b": np.concatenate((live_background_b, vehicle_b, outside_b), axis=0),
        },
        region,
        b_to_a,
        final_b_to_a,
    )


def run_synthetic() -> tuple[list[str], list[str], dict[str, Any]]:
    scene, region, region_b_to_a, final_b_to_a = synthetic_scene()
    warnings: list[str] = []
    errors: list[str] = []
    print("=== 合成 A/B 区域背景同域相减 ===")
    print("顺序: live A/B 用 canonical region 裁剪 → 与预裁剪背景分别相减 → 最终 B→A 合并")
    default_result: PipelineResult | None = None
    default_residual = 0
    default_vehicle = 0
    for tolerance in (20.0, 30.0, 40.0, 60.0):
        result = run_pipeline(
            scene["live_a"],
            scene["live_b"],
            scene["background_a_cropped"],
            scene["background_b_cropped"],
            region,
            region_b_to_a,
            final_b_to_a,
            tolerance,
        )
        residual_a = subtract_background(
            filter_region(scene["live_background_a"], region, "a", region_b_to_a), result.bg_a_region, tolerance
        )
        residual_b = subtract_background(
            filter_region(scene["live_background_b"], region, "b", region_b_to_a), result.bg_b_region, tolerance
        )
        retained_vehicle_a = subtract_background(
            filter_region(scene["vehicle_a"], region, "a", region_b_to_a), result.bg_a_region, tolerance
        )
        retained_vehicle_b = subtract_background(
            filter_region(scene["vehicle_b"], region, "b", region_b_to_a), result.bg_b_region, tolerance
        )
        residual = len(residual_a) + len(residual_b)
        retained_vehicle = len(retained_vehicle_a) + len(retained_vehicle_b)
        vehicle_input = len(filter_region(scene["vehicle_a"], region, "a", region_b_to_a)) + len(
            filter_region(scene["vehicle_b"], region, "b", region_b_to_a)
        )
        retention = retained_vehicle / max(1, vehicle_input)
        print(
            f"  tol={tolerance:>4.0f}mm 区域后 A/B={len(result.live_a_region)}/{len(result.live_b_region)} "
            f"静态残留={residual} 车辆保留={retention * 100:.1f}% 合并前景={len(result.merged)}"
        )
        if tolerance == DEFAULT_TOL:
            default_result = result
            default_residual = residual
            default_vehicle = retained_vehicle

    assert default_result is not None
    outside_bg_a = len(default_result.bg_a_region) - len(filter_region(default_result.bg_a_region, region, "a", region_b_to_a))
    outside_bg_b = len(default_result.bg_b_region) - len(filter_region(default_result.bg_b_region, region, "b", region_b_to_a))
    if outside_bg_a != 0 or outside_bg_b != 0:
        errors.append(f"预裁剪背景仍含区域外点 A/B={outside_bg_a}/{outside_bg_b}")
    wrong_b_count = len(filter_region(scene["background_b"], region, "a", region_b_to_a))
    if wrong_b_count == len(default_result.bg_b_region):
        errors.append("合成场景未覆盖 B region 必须使用最终 B→A 的判别力")
    if default_residual > max(5, int(default_vehicle * 0.05)):
        errors.append(f"默认 tol 静态背景残留 {default_residual} 点，超过车辆点 5%")
    if default_vehicle <= 0:
        errors.append("默认 tol 未保留车辆前景")
    expected_count = default_vehicle + default_residual
    if len(default_result.merged) != expected_count:
        errors.append(
            f"A/B 分设备前景合并点数不守恒：merged={len(default_result.merged)} expected={expected_count}"
        )
    size = span(default_result.merged)
    print(f"  默认 tol 合并前景跨度 XYZ=({size[0]:.0f},{size[1]:.0f},{size[2]:.0f})mm")
    if not (3000 < size[0] < 5000 and 1300 < size[1] < 2600 and 1000 < size[2] < 1800):
        errors.append(f"合并车辆跨度异常 ({size[0]:.0f},{size[1]:.0f},{size[2]:.0f})")

    metrics = {
        "default_tolerance_mm": DEFAULT_TOL,
        "raw_points": {
            "live_a": len(scene["live_a"]),
            "live_b": len(scene["live_b"]),
            "background_a": len(scene["background_a"]),
            "background_b": len(scene["background_b"]),
        },
        "region_points": {
            "live_a": len(default_result.live_a_region),
            "live_b": len(default_result.live_b_region),
            "background_a": len(default_result.bg_a_region),
            "background_b": len(default_result.bg_b_region),
            "background_outside_a": outside_bg_a,
            "background_outside_b": outside_bg_b,
        },
        "foreground_points": {
            "a": len(default_result.foreground_a),
            "b": len(default_result.foreground_b),
            "merged": len(default_result.merged),
            "static_residual": default_residual,
            "vehicle_retained": default_vehicle,
        },
        "span_mm": size.tolist(),
    }
    return warnings, errors, metrics


def run_real(inputs: RealInputs, output_dir: Path) -> tuple[list[str], list[str], dict[str, Any]]:
    warnings: list[str] = []
    errors: list[str] = []
    region = parse_region(inputs.region_raw)
    region_b_to_a = parse_b_to_a(inputs.region_b_to_a_raw, "REGION_B_TO_A_JSON")
    final_b_to_a = parse_b_to_a(inputs.final_b_to_a_raw, "FINAL_B_TO_A_JSON")
    live_a = load_pcd(inputs.live_a)
    live_b = load_pcd(inputs.live_b)
    background_a = load_pcd(inputs.bg_a)
    background_b = load_pcd(inputs.bg_b)
    outside_bg_a = len(background_a) - len(filter_region(background_a, region, "a", region_b_to_a))
    outside_bg_b = len(background_b) - len(filter_region(background_b, region, "b", region_b_to_a))
    if outside_bg_a != 0 or outside_bg_b != 0:
        errors.append(f"背景对象仍含区域外点 A/B={outside_bg_a}/{outside_bg_b}")
    result = run_pipeline(
        live_a,
        live_b,
        background_a,
        background_b,
        region,
        region_b_to_a,
        final_b_to_a,
    )
    size = span(result.merged)
    foreground_fraction = len(result.merged) / max(1, len(result.live_a_region) + len(result.live_b_region))

    print("\n=== 真数据 A/B 区域背景闭环 ===")
    print(f"  A live raw/region={len(live_a)}/{len(result.live_a_region)} bg cropped={len(background_a)} outside={outside_bg_a}")
    print(f"  B live raw/region={len(live_b)}/{len(result.live_b_region)} bg cropped={len(background_b)} outside={outside_bg_b}")
    print(
        f"  A/B 前景={len(result.foreground_a)}/{len(result.foreground_b)} "
        f"合并={len(result.merged)} ({foreground_fraction * 100:.1f}% region live) "
        f"跨度XYZ=({size[0]:.0f},{size[1]:.0f},{size[2]:.0f})mm"
    )
    if min(len(result.live_a_region), len(result.live_b_region)) == 0:
        errors.append("当前 region 后至少一个 live 单元为空")
    if min(len(result.bg_a_region), len(result.bg_b_region)) == 0:
        errors.append("至少一个区域裁剪背景单元为空")
    if len(result.merged) == 0:
        errors.append("同域相减后前景为空")
    if foreground_fraction > 0.60:
        warnings.append(f"前景占 region live 的 {foreground_fraction * 100:.1f}%>60%，背景覆盖或安装位姿可能失配")
    if len(result.merged) and not (1000 < size[0] < 18000 and 1000 < size[1] < 18000 and 300 < size[2] < 4500):
        warnings.append(f"合并前景跨度 ({size[0]:.0f},{size[1]:.0f},{size[2]:.0f}) 不像单车")
    output_dir.mkdir(parents=True, exist_ok=True)
    write_pcd(output_dir / "foreground_same_domain.pcd", result.merged)
    metrics = {
        "default_tolerance_mm": DEFAULT_TOL,
        "raw_points": {
            "live_a": len(live_a),
            "live_b": len(live_b),
            "background_a": len(background_a),
            "background_b": len(background_b),
        },
        "region_points": {
            "live_a": len(result.live_a_region),
            "live_b": len(result.live_b_region),
            "background_a": len(result.bg_a_region),
            "background_b": len(result.bg_b_region),
            "background_outside_a": outside_bg_a,
            "background_outside_b": outside_bg_b,
        },
        "foreground_points": {
            "a": len(result.foreground_a),
            "b": len(result.foreground_b),
            "merged": len(result.merged),
        },
        "foreground_fraction": foreground_fraction,
        "span_mm": size.tolist(),
        "output_pcd": str(output_dir / "foreground_same_domain.pcd"),
    }
    return warnings, errors, metrics


def run_legacy_fused_real(
    inputs: LegacyFusedInputs,
    output_dir: Path,
) -> tuple[list[str], list[str], dict[str, Any]]:
    warnings: list[str] = []
    errors: list[str] = []
    live = load_pcd(inputs.live)
    background = load_pcd(inputs.background)
    foreground = subtract_background(live, background, DEFAULT_TOL)
    fraction = len(foreground) / max(1, len(live))
    size = span(foreground)
    print("\n=== 真数据 legacy 融合背景闭环 ===")
    print(
        f"  live/background={len(live)}/{len(background)} 前景={len(foreground)} "
        f"({fraction * 100:.1f}% live) 跨度XYZ=({size[0]:.0f},{size[1]:.0f},{size[2]:.0f})mm"
    )
    if len(live) == 0 or len(background) == 0:
        errors.append("legacy live/background 至少一个为空")
    if len(foreground) == 0:
        errors.append("legacy 融合背景相减后前景为空")
    if fraction > 0.60:
        warnings.append(f"legacy 前景占 live 的 {fraction * 100:.1f}%>60%，背景可能失配")
    output_dir.mkdir(parents=True, exist_ok=True)
    output = output_dir / "foreground_legacy_fused.pcd"
    write_pcd(output, foreground)
    return warnings, errors, {
        "mode": "legacy_fused",
        "default_tolerance_mm": DEFAULT_TOL,
        "live_points": len(live),
        "background_points": len(background),
        "foreground_points": len(foreground),
        "foreground_fraction": fraction,
        "span_mm": size.tolist(),
        "output_pcd": str(output),
    }


def analyze(output_dir: Path, env: Mapping[str, str]) -> tuple[int, dict[str, Any]]:
    require_real = str(env.get("REQUIRE_REAL", "1")).strip().lower() in ("1", "true", "yes", "on")
    synthetic_warnings, synthetic_errors, synthetic_metrics = run_synthetic()
    warnings = list(synthetic_warnings)
    errors = list(synthetic_errors)
    report: dict[str, Any] = {
        "synthetic": synthetic_metrics,
        "real": None,
        "require_real": require_real,
    }
    inputs = real_inputs_from_env(env)
    if inputs is None:
        print(
            "\n-- 真数据闭环跳过：设置 LIVE_A_PCD/LIVE_B_PCD、BG_A_PCD/BG_B_PCD、"
            "REGION_JSON、REGION_B_TO_A_JSON、FINAL_B_TO_A_JSON；或显式设置 "
            "BACKGROUND_SCHEMA=legacy_fused、LIVE_PCD、BG_PCD；不会触发设备扫描 --"
        )
        if require_real:
            errors.append("REQUIRE_REAL=1 但未提供完整现场 A/B live、区域背景、region 裁剪矩阵与最终 B→A")
    elif isinstance(inputs, LegacyFusedInputs):
        real_warnings, real_errors, real_metrics = run_legacy_fused_real(inputs, output_dir)
        warnings.extend(real_warnings)
        errors.extend(real_errors)
        report["real"] = real_metrics
    else:
        real_warnings, real_errors, real_metrics = run_real(inputs, output_dir)
        warnings.extend(real_warnings)
        errors.extend(real_errors)
        report["real"] = real_metrics

    print("\n=== 结论 ===")
    if errors:
        print("❌ 异常：")
        for reason in errors:
            print("  - " + reason)
        report["verdict"] = "异常"
        report["reasons"] = errors
        return 1, report
    if warnings:
        print("⚠ 警告：")
        for reason in warnings:
            print("  - " + reason)
        report["verdict"] = "警告"
        report["reasons"] = warnings
        return (1 if require_real else 0), report
    if isinstance(inputs, LegacyFusedInputs):
        print("✅ 正常：显式 legacy_fused 背景按修改前区域裁剪融合云路径完成相减。")
    else:
        print("✅ 正常：live A/B 用 canonical region 裁剪后与预裁剪背景分设备相减，再用最终 B→A 合并。")
    report["verdict"] = "正常"
    report["reasons"] = []
    return 0, report


def main(argv: list[str] | None = None) -> int:
    args = list(argv if argv is not None else sys.argv[1:])
    output_dir = Path(args[0]) if args else Path(".dev/laser_background")
    output_dir.mkdir(parents=True, exist_ok=True)
    output = io.StringIO()
    report: dict[str, Any]
    try:
        with redirect_stdout(output):
            code, report = analyze(output_dir, os.environ)
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        code = 1
        report = {"verdict": "异常", "reasons": [str(exc)]}
        print(f"异常：{exc}", file=output)
    text = output.getvalue()
    print(text, end="")
    (output_dir / "report.txt").write_text(text, encoding="utf-8")
    (output_dir / "report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    return code


if __name__ == "__main__":
    raise SystemExit(main())
