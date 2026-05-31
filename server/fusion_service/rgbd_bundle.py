"""RgbdShot bundle — 端→云多视角 RGBD 上传/融合的 zip 契约(pack/unpack 单一真理源)。

一个扫描会话的 N 张已对齐 RGBD 打成一个 zip(端侧上传 MinIO,fusionworker 原样转发,
fusion_service /fuse 解包)。格式:

  manifest.json  —— {
      "session_key": str,
      "frame_count": int,
      "depth_unit_mm": 1.0,            # depth 原始值 × 此系数 = 毫米(默认深度即以 mm 存 uint16)
      "intrinsics": {"width","height","fx","fy","cx","cy"},   # RGB 与 depth 已对齐,共用此内参
      "shots": [{"index": i, "rgb": "rgb_0.png", "depth": "depth_0.u16", "conf": "conf_0.u8"|null}, ...]
  }
  rgb_{i}.png    —— 彩色 PNG,H×W×3 uint8
  depth_{i}.u16  —— 深度裸字节,uint16 小端,H*W,单位由 depth_unit_mm 定(默认 mm)
  conf_{i}.u8    —— 置信裸字节,uint8,H*W,0..255(可选;无则该帧不带 conf)

约定:RGB 与 depth **已在端侧对齐**到同一分辨率/内参(对齐属 M3.12 采集端职责),
本契约只承载对齐后的 RGBD。真 P100R3(RGB 1920×1080 / depth 1280×800)需上游对齐后再打包。
"""
from __future__ import annotations

import io
import json
import zipfile
from typing import List, Optional

import numpy as np
from PIL import Image

from fusion_core import Intrinsic, RgbdFrame


def pack(frames: List[RgbdFrame], session_key: str) -> bytes:
    """N 帧已对齐 RgbdFrame → bundle zip 字节。所有帧须共用同一内参/分辨率。"""
    if not frames:
        raise ValueError("空帧列表")
    intr = frames[0].intr
    shots = []
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for i, f in enumerate(frames):
            if f.intr.width != intr.width or f.intr.height != intr.height:
                raise ValueError(f"帧 {i} 分辨率与首帧不一致(契约要求同分辨率对齐 RGBD)")
            rgb_name, depth_name = f"rgb_{i}.png", f"depth_{i}.u16"
            png = io.BytesIO()
            Image.fromarray(np.ascontiguousarray(f.color.astype(np.uint8))).save(png, "PNG")
            z.writestr(rgb_name, png.getvalue())
            z.writestr(depth_name, np.ascontiguousarray(
                np.rint(f.depth_mm).astype("<u2")).tobytes())
            conf_name = None
            if f.conf is not None:
                conf_name = f"conf_{i}.u8"
                z.writestr(conf_name, np.ascontiguousarray(f.conf.astype(np.uint8)).tobytes())
            shots.append({"index": i, "rgb": rgb_name, "depth": depth_name, "conf": conf_name})
        manifest = {
            "session_key": session_key,
            "frame_count": len(frames),
            "depth_unit_mm": 1.0,
            "intrinsics": {"width": intr.width, "height": intr.height,
                           "fx": intr.fx, "fy": intr.fy, "cx": intr.cx, "cy": intr.cy},
            "shots": shots,
        }
        z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    return buf.getvalue()


def unpack(data: bytes) -> List[RgbdFrame]:
    """bundle zip 字节 → N 帧 RgbdFrame(depth 转 mm float32)。"""
    with zipfile.ZipFile(io.BytesIO(data), "r") as z:
        manifest = json.loads(z.read("manifest.json"))
        m = manifest["intrinsics"]
        intr = Intrinsic(width=int(m["width"]), height=int(m["height"]),
                         fx=float(m["fx"]), fy=float(m["fy"]),
                         cx=float(m["cx"]), cy=float(m["cy"]))
        unit = float(manifest.get("depth_unit_mm", 1.0))
        h, w = intr.height, intr.width
        frames: List[RgbdFrame] = []
        for shot in sorted(manifest["shots"], key=lambda s: s["index"]):
            color = np.asarray(Image.open(io.BytesIO(z.read(shot["rgb"]))).convert("RGB"), np.uint8)
            depth = np.frombuffer(z.read(shot["depth"]), dtype="<u2").reshape(h, w).astype(np.float32) * unit
            conf: Optional[np.ndarray] = None
            if shot.get("conf"):
                conf = np.frombuffer(z.read(shot["conf"]), dtype=np.uint8).reshape(h, w).copy()
            frames.append(RgbdFrame(color=color, depth_mm=depth, intr=intr, conf=conf))
        return frames
