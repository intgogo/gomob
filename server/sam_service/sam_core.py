"""sam_core — HQ-SAM 高精度分割算法核。

形态对标 fusion_core:纯算法,无 HTTP/MinIO 依赖,供 sam_service(app.py)与 harness 共用。

HQ-SAM(sam-hq,Apache-2.0):在原版 SAM 上加一个 High-Quality 输出 token + 融合早/末期 ViT 特征,
边界明显更锐(细结构、薄边),保留 SAM 的 box/point 可提示接口。默认 vit_h(4×2080Ti 够)。
提示来源 = 人工框/点(M3.17 当前增量不做自动 grounding,框由人工给,见 04b §4)。

box 提示能定尺度 → 单 mask 无歧义(正好对应"人工框选目标");
纯 point 提示有粒度歧义(点轮胎=轮胎还是整车)→ multimask 取最高分缓解。
模型权重见 README(aria2c 离线下到 weights/)。
"""
from __future__ import annotations

import hashlib
import os
import threading
from dataclasses import dataclass
from typing import Optional

import numpy as np

_PREDICTOR = None              # 进程级单例,权重只加载一次
_LOAD_LOCK = threading.Lock()  # 保护懒加载(并发首请求只加载一次)
_INFER_LOCK = threading.Lock() # 保护 set_image+predict 临界区(SamPredictor 有请求级可变状态,并发会串图)

# 官方 HQ-SAM 权重(HF lkeab/hq-sam)的大小/校验,防下载不全或被篡改。
# size 每次加载即查(瞬时);完整 sha256 仅在 GOMOB_SAM_VERIFY_SHA=1 时校(2.5GB 哈希较慢)。
_EXPECTED = {
    "vit_h": {"size": 2570940653,
              "sha256": "a7ac14a085326d9fa6199c8c698c4f0e7280afdbb974d2c4660ec60877b45e35"},
}


def _device() -> str:
    import torch
    return "cuda" if torch.cuda.is_available() else "cpu"


def _default_ckpt(model_type: str) -> str:
    return os.path.join(os.path.dirname(__file__), "weights", f"sam_hq_{model_type}.pth")


def _verify_checkpoint(model_type: str, checkpoint: str) -> None:
    """加载前完整性检查:大小必查(瞬时,抓截断/下载不全);sha256 按需(防篡改,segment-anything-hq
    内部用 torch.load 反序列化 pickle,过期/被换的权重有 RCE 风险 → 来源须可信 + 校验)。"""
    exp = _EXPECTED.get(model_type)
    if not exp:
        return
    got = os.path.getsize(checkpoint)
    if got != exp["size"]:
        raise ValueError(f"HQ-SAM 权重大小异常({got} != {exp['size']}):疑下载不全/被篡改,"
                         f"按 README 用 aria2c --checksum 重下")
    if os.getenv("GOMOB_SAM_VERIFY_SHA") == "1":
        h = hashlib.sha256()
        with open(checkpoint, "rb") as fp:
            for chunk in iter(lambda: fp.read(1 << 20), b""):
                h.update(chunk)
        if h.hexdigest() != exp["sha256"]:
            raise ValueError("HQ-SAM 权重 SHA256 不符:疑被篡改,拒绝加载")


def load_model(model_type: Optional[str] = None, checkpoint: Optional[str] = None):
    """懒加载 HQ-SAM 到 GPU/CPU,返回 SamPredictor 单例(并发安全:双检 + 锁)。"""
    global _PREDICTOR
    if _PREDICTOR is not None:
        return _PREDICTOR
    with _LOAD_LOCK:
        if _PREDICTOR is not None:
            return _PREDICTOR
        import torch
        from segment_anything_hq import SamPredictor, sam_model_registry
        model_type = model_type or os.getenv("GOMOB_SAM_MODEL", "vit_h")
        checkpoint = checkpoint or os.getenv("GOMOB_SAM_CKPT", _default_ckpt(model_type))
        if not os.path.isfile(checkpoint):
            raise FileNotFoundError(f"HQ-SAM 权重缺失:{checkpoint}(见 README aria2c 下载)")
        _verify_checkpoint(model_type, checkpoint)
        sam = sam_model_registry[model_type](checkpoint=checkpoint)
        sam.to(_device())
        sam.eval()
        _PREDICTOR = SamPredictor(sam)
    return _PREDICTOR


@dataclass
class SegmentResult:
    mask: np.ndarray   # bool HxW
    score: float       # 模型预测 IoU 置信
    area: int          # mask 像素数


def segment(image_rgb: np.ndarray, box=None, points=None, point_labels=None,
            multimask: Optional[bool] = None) -> SegmentResult:
    """image_rgb: HxWx3 uint8 RGB;box=[x0,y0,x1,y1] 或 None;points=[[x,y],...];point_labels=[1/0,...](1=前景)。
    至少给 box 或 points。返回最佳单 mask。"""
    if box is None and not points:
        raise ValueError("至少需 box 或 points 提示")
    if image_rgb.ndim != 3 or image_rgb.shape[2] != 3:
        raise ValueError(f"image_rgb 须 HxWx3,实得 {image_rgb.shape}")
    import torch
    predictor = load_model()
    pc = np.array(points, dtype=np.float32) if points else None
    if pc is not None:
        pl = (np.array(point_labels, dtype=np.int32) if point_labels is not None
              else np.ones(len(points), dtype=np.int32))
    else:
        pl = None
    bx = np.array(box, dtype=np.float32) if box is not None else None
    # box 定尺度 → 单 mask;纯点有粒度歧义 → 多 mask 取最高分
    mm = multimask if multimask is not None else (box is None)
    # SamPredictor 把 set_image 的特征存在实例上,predict 依赖之 → 并发请求会串图/错配尺寸;
    # 串行化整段临界区(GPU 本就是瓶颈,单飞推理可接受;Go worker 也是逐视角顺序调)。
    with _INFER_LOCK:
        predictor.set_image(np.ascontiguousarray(image_rgb.astype(np.uint8)))
        with torch.no_grad():
            masks, scores, _ = predictor.predict(
                point_coords=pc, point_labels=pl, box=bx,
                multimask_output=mm, hq_token_only=False)
    best = int(np.argmax(scores))
    m = masks[best].astype(bool)
    return SegmentResult(mask=m, score=float(scores[best]), area=int(m.sum()))


def deps_status() -> dict:
    """healthz 用:报告关键依赖是否就位 + 设备 + 权重存在性。"""
    out = {}
    try:
        import torch
        out["torch"] = torch.__version__
        out["cuda"] = torch.cuda.is_available()
    except Exception:
        out["torch"] = ""
        out["cuda"] = False
    try:
        import segment_anything_hq  # noqa: F401
        out["segment_anything_hq"] = "ok"
    except Exception:
        out["segment_anything_hq"] = ""
    mt = os.getenv("GOMOB_SAM_MODEL", "vit_h")
    out["model_type"] = mt
    out["checkpoint_present"] = os.path.isfile(os.getenv("GOMOB_SAM_CKPT", _default_ckpt(mt)))
    return out
