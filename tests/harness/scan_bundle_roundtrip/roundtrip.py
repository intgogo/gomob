#!/usr/bin/env python3
"""schema_version=2 原始 VIN RGBD bundle 跨语言 round-trip。"""
from __future__ import annotations

import hashlib
import io
import json
import os
import sys
import zipfile

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
sys.path.insert(0, os.path.join(ROOT, "server", "fusion_service"))

import fusion_core  # noqa: E402
import rgbd_bundle  # noqa: E402

DW, DH, CW, CH = 640, 128, 4160, 832
RAW = 1300
EXPECTED_MM = 378.13750906277164
CALIBRATION_PATH = os.path.join(ROOT, "tests", "vincreator-apk", "VIN_BF301208.bin")


def bundle(session_key: str, n: int) -> bytes:
    calibration = open(CALIBRATION_PATH, "rb").read()
    calibration_sha = hashlib.sha256(calibration).hexdigest()
    rgb = np.zeros((CH, CW, 3), dtype=np.uint8)
    rgb[..., 0] = np.linspace(0, 255, CW, dtype=np.uint8)[None, :]
    rgb[..., 1] = np.linspace(0, 255, CH, dtype=np.uint8)[:, None]
    png = io.BytesIO(); Image.fromarray(rgb).save(png, "PNG")
    raw = np.full((DH, DW), RAW, dtype="<u2").tobytes()
    conf = np.full((DH, DW), 255, dtype=np.uint8).tobytes()
    shots = [{
        "index": i, "rgb": f"rgb_{i}.png", "depth": f"depth_{i}.u16", "conf": f"conf_{i}.u8",
        "color_timestamp_us": 1_000_000 + i * 100_000,
        "depth_timestamp_us": 1_005_000 + i * 100_000,
    } for i in range(n)]
    manifest = {
        "schema_version": 2, "session_key": session_key,
        "calibration": {
            "format": "vin_creator_v3",
            "depth_device_id": "BF301208", "color_device_id": "202303111518",
            "depth_profile": "640x128_mode25", "color_profile": "4160x832", "sha256": calibration_sha,
        },
        "source": {
            "depth_width": DW, "depth_height": DH,
            "depth_encoding": "vin_creator_disparity_u16", "color_width": CW, "color_height": CH,
        },
        "shots": shots,
    }
    out = io.BytesIO()
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
        z.writestr("calibration.bin", calibration)
        for shot in shots:
            z.writestr(shot["rgb"], png.getvalue())
            z.writestr(shot["depth"], raw)
            z.writestr(shot["conf"], conf)
    return out.getvalue()


def main() -> int:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, ".dev", "scan_bundle_roundtrip")
    os.makedirs(out_dir, exist_ok=True)
    n = 3
    res: dict = {"expected_frames": n, "width": DW, "height": DH, "expected_mm": EXPECTED_MM}
    blob = bundle("scan-roundtrip", n)
    with zipfile.ZipFile(io.BytesIO(blob), "r") as z:
        res["has_calibration_bin"] = z.namelist().count("calibration.bin") == 1
        res["calibration_sha_match"] = hashlib.sha256(z.read("calibration.bin")).hexdigest() == json.loads(z.read("manifest.json"))["calibration"]["sha256"]
    try:
        frames = rgbd_bundle.unpack(blob)
        res["frames"] = len(frames)
        f0 = frames[0]
        res["got_width"], res["got_height"] = f0.intr.width, f0.intr.height
        res["got_fx"], res["got_cx"] = f0.intr.fx, f0.intr.cx
        res["got_mm"] = float(f0.depth_mm[DH // 2, DW // 2])
        res["conf_got"] = int(f0.conf[DH // 2, DW // 2]) if f0.conf is not None else -1
    except Exception as exc:  # noqa: BLE001
        res["unpack_error"] = repr(exc)
    if "unpack_error" not in res:
        try:
            res["mesh_vertices"] = len(fusion_core.fuse(frames).vertices)
        except Exception as exc:  # noqa: BLE001
            res["fuse_error"] = repr(exc); res["mesh_vertices"] = 0
    with open(os.path.join(out_dir, "result.json"), "w", encoding="utf-8") as fh:
        json.dump(res, fh, ensure_ascii=False, indent=2)
    print(f"采样写入 {os.path.join(out_dir, 'result.json')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
