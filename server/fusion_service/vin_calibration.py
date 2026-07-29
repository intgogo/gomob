"""VINCreator v3 BIN 的严格解析与 RS-D550→HLSD8 投影。"""
from __future__ import annotations

import math
import re
import struct
from dataclasses import dataclass

import numpy as np


CALIBRATION_SIZE = 2420
CALIBRATION_VERSION = 3
DISPARITY_UNIT = 0.125
# RS-D550 的 mode25 有效工作范围按项目硬件规格约束；不能把 1m 以上的车身点误删。
DEPTH_MIN_MM = 200.0
DEPTH_MAX_MM = 8000.0
DEVICE_ID = re.compile(r"^[A-Z0-9_-]+$")


class CalibrationError(ValueError):
    pass


@dataclass(frozen=True)
class CalibrationCamera:
    principal_row: float
    principal_column: float
    focal_row: float
    focal_column: float
    distortion: tuple[float, float, float, float, float]
    euler: tuple[float, float, float]
    rotation: np.ndarray
    translation: np.ndarray


@dataclass(frozen=True)
class VinCalibration:
    depth_device_id: str
    version: int
    depth_principal_column: float
    depth_principal_row: float
    depth_focal: float
    baseline_mm: float
    color: CalibrationCamera
    sha256: str

    def depth_intrinsics(self, width: int, height: int) -> tuple[float, float, float, float]:
        if width <= 0 or height <= 0:
            raise CalibrationError("深度 profile 尺寸非法")
        sx, sy = width / 1280.0, height / 256.0
        if abs(sx - sy) > 1e-9:
            raise CalibrationError("深度 profile 不是 mode25 等比例缩放")
        return (
            self.depth_focal * sx,
            self.depth_focal * sy,
            self.depth_principal_column * sx,
            self.depth_principal_row * sy,
        )


def _f32(blob: bytes, offset: int) -> float:
    return float(struct.unpack_from("<f", blob, offset)[0])


def _f64(blob: bytes, offset: int) -> float:
    return float(struct.unpack_from("<d", blob, offset)[0])


def _camera(blob: bytes, base: int) -> CalibrationCamera:
    values = [_f64(blob, base + i * 8) for i in range(4)]
    distortion = tuple(_f64(blob, base + 44 + i * 8) for i in range(5))
    euler = tuple(_f64(blob, base + 88 + i * 8) for i in range(3))
    translation = np.asarray([_f64(blob, base + 112 + i * 8) for i in range(3)], dtype=np.float64)
    all_values = values + list(distortion) + list(euler) + list(translation)
    if not all(math.isfinite(x) for x in all_values):
        raise CalibrationError(f"相机模型 0x{base:x} 包含 NaN/Inf")
    if not (0 <= values[0] <= 5000 and 0 <= values[1] <= 5000 and 1000 <= values[2] <= 10000 and 1000 <= values[3] <= 10000):
        raise CalibrationError("彩色相机内参超出合理范围")
    if any(abs(x) > 2 * math.pi for x in euler) or any(abs(x) > 200 for x in translation):
        raise CalibrationError("彩色相机外参超出合理范围")
    e0, e1, e2 = euler
    ry = np.asarray([[math.cos(e0), 0, math.sin(e0)], [0, 1, 0], [-math.sin(e0), 0, math.cos(e0)]])
    rx = np.asarray([[1, 0, 0], [0, math.cos(e1), -math.sin(e1)], [0, math.sin(e1), math.cos(e1)]])
    rz = np.asarray([[math.cos(e2), -math.sin(e2), 0], [math.sin(e2), math.cos(e2), 0], [0, 0, 1]])
    return CalibrationCamera(values[0], values[1], values[2], values[3], distortion, euler, rz @ rx @ ry, translation)


def parse_calibration(blob: bytes, sha256: str) -> VinCalibration:
    if len(blob) != CALIBRATION_SIZE:
        raise CalibrationError(f"标定文件大小={len(blob)}，期望 {CALIBRATION_SIZE}")
    serial = blob[:8].decode("ascii", errors="strict").rstrip("\x00 ").upper()
    if not DEVICE_ID.fullmatch(serial) or ".." in serial:
        raise CalibrationError(f"Depth 序列号非法：{serial!r}")
    version = struct.unpack_from("<I", blob, 0x200)[0]
    if version != CALIBRATION_VERSION:
        raise CalibrationError(f"BIN 版本={version}，期望 {CALIBRATION_VERSION}")
    if struct.unpack_from("<II", blob, 0x204) != (0, 0):
        raise CalibrationError("BIN payload 保留字段非法")
    for offset in (0x234, 0x260, 0x2bc, 0x2e8, 0x31c):
        if struct.unpack_from("<I", blob, offset)[0] != 1:
            raise CalibrationError("BIN 相机/保留向量数量非法")
    color = _camera(blob, 0x20c)
    duplicate = _camera(blob, 0x294)
    if not np.allclose(
        np.asarray([
            color.principal_row, color.principal_column, color.focal_row, color.focal_column,
            *color.distortion, *color.euler, *color.translation,
        ]),
        np.asarray([
            duplicate.principal_row, duplicate.principal_column, duplicate.focal_row, duplicate.focal_column,
            *duplicate.distortion, *duplicate.euler, *duplicate.translation,
        ]),
        rtol=0,
        atol=1e-12,
    ):
        raise CalibrationError("BIN 前后彩色相机模型不一致")
    if abs(_f32(blob, 0x338) - 1) > 1e-6 or abs(_f32(blob, 0x33c) - 1) > 1e-6:
        raise CalibrationError("深度缩放字段非法")
    cx, cy, focal, baseline = _f32(blob, 0x340), _f32(blob, 0x344), _f32(blob, 0x348), _f32(blob, 0x35c)
    if not all(math.isfinite(x) for x in (cx, cy, focal, baseline)) or not (0 < cx < 1280 and 0 < cy < 256 and 500 <= focal <= 3000 and 40 <= baseline <= 60):
        raise CalibrationError("mode25 深度参数超出合理范围")
    if struct.unpack_from("<I", blob, 0x360)[0] != 1:
        raise CalibrationError("mode25 深度数据类型不是 disparity×8")
    if abs(_f32(blob, 0x34c) - cx) > 1e-4 or abs(_f32(blob, 0x354) - focal) > 1e-4 or abs(_f32(blob, 0x358) - focal) > 1e-4:
        raise CalibrationError("mode25 冗余焦距字段不一致")
    full_row = _f32(blob, 0x350)
    if not (cy < full_row < 960):
        raise CalibrationError("深度全幅主点非法")
    return VinCalibration(serial, version, cx, cy, focal, baseline, color, sha256)


def align_raw_rgbd(
    rgb: np.ndarray,
    raw_disparity: np.ndarray,
    calibration: VinCalibration,
    confidence: np.ndarray | None,
) -> tuple[np.ndarray, np.ndarray, np.ndarray | None]:
    """逐深度像素还原毫米深度，并以原始彩色图双线性采样到深度网格。"""
    if rgb.ndim != 3 or rgb.shape[2] != 3:
        raise CalibrationError("RGB 不是 HxWx3")
    if confidence is not None and confidence.shape != raw_disparity.shape:
        raise CalibrationError("confidence 尺寸与 disparity 不一致")
    z, rgb_col, rgb_row, projectable = project_disparity(raw_disparity, calibration)
    h, w = raw_disparity.shape
    rh, rw = rgb.shape[:2]
    raw = raw_disparity.astype(np.float64, copy=False)
    depth_valid = (raw > 0) & np.isfinite(z) & (z > DEPTH_MIN_MM) & (z < DEPTH_MAX_MM)
    in_bounds = projectable & (rgb_col >= 0) & (rgb_col <= rw - 1) & (rgb_row >= 0) & (rgb_row <= rh - 1)
    x0 = np.floor(np.clip(rgb_col, 0, rw - 1)).astype(np.int64); y0 = np.floor(np.clip(rgb_row, 0, rh - 1)).astype(np.int64)
    x1 = np.minimum(x0 + 1, rw - 1); y1 = np.minimum(y0 + 1, rh - 1)
    wx = np.clip(rgb_col - x0, 0, 1)[..., None]; wy = np.clip(rgb_row - y0, 0, 1)[..., None]
    c00, c10, c01, c11 = rgb[y0, x0], rgb[y0, x1], rgb[y1, x0], rgb[y1, x1]
    color = ((1 - wy) * ((1 - wx) * c00 + wx * c10) + wy * ((1 - wx) * c01 + wx * c11)).astype(np.uint8)
    color[~in_bounds] = 0
    # RGB 越界只代表该深度点没有颜色采样，不能丢弃其几何深度或原始 confidence。
    depth_mm = np.where(depth_valid, z, 0).astype(np.float32)
    conf = None if confidence is None else confidence.astype(np.uint8, copy=True)
    return color, depth_mm, conf


def project_disparity(
    raw_disparity: np.ndarray,
    calibration: VinCalibration,
) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """返回 (depth_mm, color_column, color_row, valid)，供跨语言 oracle 验证。"""
    if raw_disparity.ndim != 2:
        raise CalibrationError("disparity 不是二维数组")
    h, w = raw_disparity.shape
    fx, fy, cx, cy = calibration.depth_intrinsics(w, h)
    raw = raw_disparity.astype(np.float64, copy=False)
    z = np.zeros_like(raw)
    np.divide(
        calibration.depth_focal * calibration.baseline_mm,
        raw * DISPARITY_UNIT,
        out=z,
        where=raw > 0,
    )
    valid = (raw > 0) & np.isfinite(z) & (z > DEPTH_MIN_MM) & (z < DEPTH_MAX_MM)
    rows, cols = np.indices((h, w), dtype=np.float64)
    world = np.stack(((cy - rows) * z / fy, (cols - cx) * z / fx, z), axis=-1)
    camera = world @ calibration.color.rotation.T + calibration.color.translation
    positive = valid & (np.abs(camera[..., 2]) > 1e-12)
    row_delta = calibration.color.focal_row * camera[..., 0] / np.where(positive, camera[..., 2], 1)
    col_delta = calibration.color.focal_column * camera[..., 1] / np.where(positive, camera[..., 2], 1)
    radius = np.hypot(row_delta, col_delta)
    scale_row = np.ones_like(radius)
    scale_col = np.ones_like(radius)
    nz = radius > 1e-12
    scale_row[nz] = calibration.color.focal_row * np.arctan(radius[nz] / calibration.color.focal_row) / radius[nz]
    scale_col[nz] = calibration.color.focal_column * np.arctan(radius[nz] / calibration.color.focal_column) / radius[nz]
    row_delta *= scale_row; col_delta *= scale_col
    k, p1, p2, s1, s2 = calibration.color.distortion
    undistorted_row, undistorted_col = row_delta, col_delta
    r2 = undistorted_row * undistorted_row + undistorted_col * undistorted_col
    row_delta = undistorted_row + k * undistorted_row * r2 + p1 * (3 * undistorted_row * undistorted_row + undistorted_col * undistorted_col) + 2 * p2 * undistorted_row * undistorted_col + s1 * r2
    col_delta = undistorted_col + k * undistorted_col * r2 + p2 * (undistorted_row * undistorted_row + 3 * undistorted_col * undistorted_col) + 2 * p1 * undistorted_row * undistorted_col + s2 * r2
    rgb_row = calibration.color.principal_row + row_delta
    rgb_col = calibration.color.principal_column + col_delta
    return z, rgb_col, rgb_row, positive
