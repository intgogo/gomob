#!/usr/bin/env python3
"""scan_bundle_roundtrip 采样器(需 open3d/PIL/numpy 的 venv)。

独立复刻 Kotlin `Scan3dBundleUploader.packBundle` 的确切字节布局产出 bundle(**不调用
rgbd_bundle.pack**),喂 `rgbd_bundle.unpack` + `fusion_core.fuse`,把结果写 result.json,
由 analyze.py(stdlib)判定。验端侧 Kotlin 产物 ↔ 服务端 Python 的字节级跨语言契约。
"""
from __future__ import annotations

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

W, H = 64, 48
FX, FY, CX, CY = 50.0, 50.0, 32.0, 24.0
PLANT_RC = (10, 20)
PLANT_MM = 1234


def kotlin_style_bundle(session_key: str, n: int) -> bytes:
    """复刻 Kotlin packBundle 字节布局:manifest + rgb_i.png + depth_i.u16(LE) + conf_i.u8。"""
    buf = io.BytesIO()
    shots = []
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for i in range(n):
            depth = np.full((H, W), 500 + i, dtype=np.uint16)
            depth[PLANT_RC] = PLANT_MM
            z.writestr(f"depth_{i}.u16", depth.astype("<u2").tobytes())
            rgb = np.zeros((H, W, 3), dtype=np.uint8)
            rgb[..., 0] = np.linspace(0, 255, W)[None, :].astype(np.uint8)
            rgb[..., 1] = np.linspace(0, 255, H)[:, None].astype(np.uint8)
            png = io.BytesIO()
            Image.fromarray(rgb).save(png, "PNG")
            z.writestr(f"rgb_{i}.png", png.getvalue())
            z.writestr(f"conf_{i}.u8", np.full((H, W), 255, dtype=np.uint8).tobytes())
            shots.append({"index": i, "rgb": f"rgb_{i}.png", "depth": f"depth_{i}.u16",
                          "conf": f"conf_{i}.u8", "mask": None})
        manifest = {
            "session_key": session_key, "frame_count": n, "depth_unit_mm": 1.0,
            "intrinsics": {"width": W, "height": H, "fx": FX, "fy": FY, "cx": CX, "cy": CY},
            "shots": shots,
        }
        z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    return buf.getvalue()


def main() -> int:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, ".dev", "scan_bundle_roundtrip")
    os.makedirs(out_dir, exist_ok=True)
    n = 3
    res: dict = {"expected_frames": n, "width": W, "height": H, "plant_mm": PLANT_MM, "plant_rc": list(PLANT_RC)}

    try:
        frames = rgbd_bundle.unpack(kotlin_style_bundle("scan-roundtrip", n))
        res["frames"] = len(frames)
        f0 = frames[0]
        res["got_width"] = f0.intr.width
        res["got_height"] = f0.intr.height
        res["got_fx"] = f0.intr.fx
        res["got_cx"] = f0.intr.cx
        res["plant_mm_got"] = float(f0.depth_mm[PLANT_RC[0], PLANT_RC[1]])
        res["conf_present"] = f0.conf is not None
        res["conf_got"] = int(f0.conf[PLANT_RC[0], PLANT_RC[1]]) if f0.conf is not None else -1
    except Exception as e:  # noqa: BLE001
        res["unpack_error"] = repr(e)
        with open(os.path.join(out_dir, "result.json"), "w") as f:
            json.dump(res, f, ensure_ascii=False, indent=2)
        return 0

    try:
        mesh = fusion_core.fuse(frames)
        res["mesh_vertices"] = len(mesh.vertices)
    except Exception as e:  # noqa: BLE001
        res["fuse_error"] = repr(e)
        res["mesh_vertices"] = 0

    with open(os.path.join(out_dir, "result.json"), "w") as f:
        json.dump(res, f, ensure_ascii=False, indent=2)
    print(f"采样写入 {os.path.join(out_dir, 'result.json')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
