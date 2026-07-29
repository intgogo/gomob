"""生成可经真实 VINCreator v3 投影往返的多视角 schema v2 数据集。"""
from __future__ import annotations

import hashlib
import io
import json
import pathlib
import sys
import zipfile

import numpy as np
from PIL import Image

ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "server" / "fusion_service"))
sys.path.insert(0, str(ROOT / "tests" / "harness" / "scan_fusion"))

import synth_dataset as synth  # noqa: E402
from fusion_core import Intrinsic, RgbdFrame  # noqa: E402
from vin_calibration import align_raw_rgbd, parse_calibration, project_disparity  # noqa: E402

DW, DH, CW, CH = 640, 128, 4160, 832
DEPTH_ID = "BF301208"
COLOR_ID = "202303111518"
CALIBRATION_PATH = ROOT / "tests" / "vincreator-apk" / "VIN_BF301208.bin"
CALIBRATION_BLOB = CALIBRATION_PATH.read_bytes()
CALIBRATION_SHA = hashlib.sha256(CALIBRATION_BLOB).hexdigest()
CALIBRATION = parse_calibration(CALIBRATION_BLOB, CALIBRATION_SHA)
FX, FY, CX, CY = CALIBRATION.depth_intrinsics(DW, DH)
INTRINSIC = Intrinsic(DW, DH, FX, FY, CX, CY)


def _raw_disparity(depth_mm: np.ndarray) -> np.ndarray:
    raw = np.zeros(depth_mm.shape, dtype=np.uint16)
    valid = (depth_mm > 50.0) & (depth_mm < 1000.0)
    values = np.rint(
        CALIBRATION.depth_focal * CALIBRATION.baseline_mm / (depth_mm[valid] * 0.125),
    )
    raw[valid] = np.clip(values, 1, np.iinfo(np.uint16).max).astype(np.uint16)
    return raw


def _source_rgb(aligned_color: np.ndarray, raw: np.ndarray) -> np.ndarray:
    """把深度网格目标色写回 HLSD8 投影邻域，服务端双线性采样后可精确取回。"""
    _, color_columns, color_rows, valid = project_disparity(raw, CALIBRATION)
    inside = valid & (color_columns >= 0) & (color_columns <= CW - 1) & (color_rows >= 0) & (color_rows <= CH - 1)
    depth_rows, depth_columns = np.where(inside)
    source = np.zeros((CH, CW, 3), dtype=np.uint8)
    x0 = np.floor(color_columns[inside]).astype(np.int64)
    y0 = np.floor(color_rows[inside]).astype(np.int64)
    colors = aligned_color[depth_rows, depth_columns]
    for dy in (0, 1):
        for dx in (0, 1):
            source[np.minimum(y0 + dy, CH - 1), np.minimum(x0 + dx, CW - 1)] = colors
    return source


def build_dataset(n_views: int = 10) -> tuple[list[RgbdFrame], list[tuple[np.ndarray, np.ndarray, np.ndarray]], object, list[np.ndarray]]:
    gt = synth.make_gt_mesh()
    views = synth.render_views(gt, INTRINSIC, n_views=n_views, radius=0.75, elev_deg=0.0)
    frames: list[RgbdFrame] = []
    sources: list[tuple[np.ndarray, np.ndarray, np.ndarray]] = []
    extrinsics: list[np.ndarray] = []
    for depth_mm, aligned_color, extrinsic in views:
        raw = _raw_disparity(depth_mm)
        source_rgb = _source_rgb(aligned_color, raw)
        confidence = np.where(raw > 0, 255, 0).astype(np.uint8)
        color, decoded_depth, decoded_conf = align_raw_rgbd(source_rgb, raw, CALIBRATION, confidence)
        frames.append(RgbdFrame(color=color, depth_mm=decoded_depth, intr=INTRINSIC, conf=decoded_conf))
        sources.append((source_rgb, raw, confidence))
        extrinsics.append(extrinsic)
    return frames, sources, gt, extrinsics


def bundle(session_key: str, n_views: int = 10) -> bytes:
    _, sources, _, _ = build_dataset(n_views)
    shots = [
        {
            "index": index,
            "rgb": f"rgb_{index}.png",
            "depth": f"depth_{index}.u16",
            "conf": f"conf_{index}.u8",
            "color_timestamp_us": 1_000_000 + index * 100_000,
            "depth_timestamp_us": 1_005_000 + index * 100_000,
        }
        for index in range(n_views)
    ]
    manifest = {
        "schema_version": 2,
        "session_key": session_key,
        "calibration": {
            "format": "vin_creator_v3",
            "depth_device_id": DEPTH_ID,
            "color_device_id": COLOR_ID,
            "depth_profile": "640x128_mode25",
            "color_profile": "4160x832",
            "sha256": CALIBRATION_SHA,
        },
        "source": {
            "depth_width": DW,
            "depth_height": DH,
            "depth_encoding": "vin_creator_disparity_u16",
            "color_width": CW,
            "color_height": CH,
        },
        "shots": shots,
    }
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        archive.writestr("calibration.bin", CALIBRATION_BLOB)
        for shot, (source_rgb, raw, confidence) in zip(shots, sources, strict=True):
            png = io.BytesIO()
            Image.fromarray(source_rgb).save(png, "PNG")
            archive.writestr(shot["rgb"], png.getvalue())
            archive.writestr(shot["depth"], raw.astype("<u2", copy=False).tobytes())
            archive.writestr(shot["conf"], confidence.tobytes())
    return output.getvalue()
