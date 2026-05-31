"""seg_bench — M3.17 HQ-SAM 高精度分割「行为好不好」基准。

自包含合成场景(纯 numpy/PIL,不依赖 open3d → 留在 sam-venv 内):
  - 背景:一种正弦纹理 RGB;
  - 前景:星形/齿形不规则多边形(带细尖,考验边界保真),填另一种纹理 + 轻噪;
  - GT mask = 多边形(精确已知,代「人工标注」);box = 其外扩 bbox(模拟人工松框)。
给 sam_core.segment(image, box) → mask,与 GT 算 IoU。门:IoU ≥ 0.92(对齐 TODO M3.17)。
直接调 sam_core(对标 fusion_bench 调 fusion_core);HTTP 服务由 smoke test 另测。确定性(固定 seed)。
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = os.path.dirname(__file__)
sys.path.insert(0, os.path.join(HERE, "..", "..", "..", "server", "sam_service"))
from sam_core import segment  # noqa: E402

H, W = 480, 640
SEED = 17


def _texture(h, w, fx, fy, phase, base):
    """正弦 RGB 纹理 [0,1]→uint8,base 为整体色偏。"""
    yy, xx = np.mgrid[0:h, 0:w].astype(np.float64)
    r = 0.5 + 0.4 * np.sin(xx * fx + phase)
    g = 0.5 + 0.4 * np.sin(yy * fy + phase + 2.0)
    b = 0.5 + 0.4 * np.sin((xx + yy) * (fx + fy) * 0.5 + phase + 4.0)
    t = np.stack([r, g, b], axis=2)
    return np.clip((t * 0.6 + np.array(base) * 0.4) * 255, 0, 255).astype(np.uint8)


def _star_mask(h, w, cx, cy, r_out, r_in, n_points=9, rot=0.0):
    """n 尖星形多边形 mask(细尖考验边界保真)。"""
    pts = []
    for i in range(n_points * 2):
        ang = rot + np.pi * i / n_points
        rad = r_out if i % 2 == 0 else r_in
        pts.append((cx + rad * np.cos(ang), cy + rad * np.sin(ang)))
    img = Image.new("L", (w, h), 0)
    ImageDraw.Draw(img).polygon(pts, fill=255)
    return np.asarray(img) > 127


def build_scene():
    g = np.random.RandomState(SEED)
    bg = _texture(H, W, 0.025, 0.018, 0.0, base=(0.25, 0.45, 0.85))     # 偏蓝背景
    fg = _texture(H, W, 0.06, 0.05, 1.3, base=(0.9, 0.55, 0.2))         # 偏橙前景,纹理更细
    gt = _star_mask(H, W, cx=W * 0.52, cy=H * 0.5, r_out=150, r_in=66, n_points=9, rot=0.3)
    img = np.where(gt[..., None], fg, bg).astype(np.float64)
    img = np.clip(img + g.normal(0, 6.0, img.shape), 0, 255).astype(np.uint8)  # 轻传感器噪
    ys, xs = np.where(gt)
    pad = 8                                                              # 人工框通常略松
    box = [max(0, xs.min() - pad), max(0, ys.min() - pad),
           min(W - 1, xs.max() + pad), min(H - 1, ys.max() + pad)]
    return img, gt, [float(v) for v in box]


def iou(a: np.ndarray, b: np.ndarray) -> float:
    inter = np.logical_and(a, b).sum()
    union = np.logical_or(a, b).sum()
    return float(inter / union) if union else 0.0


def main() -> int:
    out_dir = os.environ.get("OUTPUT_DIR", os.path.join(HERE, "..", "..", "..", ".dev", "sam_segmentation"))
    os.makedirs(out_dir, exist_ok=True)
    print(f"[seg_bench] 合成场景 {W}x{H},星形(9 尖)前景,box 人工松框")
    img, gt, box = build_scene()
    res = segment(img, box=box)
    score = iou(res.mask, gt)
    # 记环境,便于指标回归时区分"模型/环境变了"还是"真退化"
    try:
        import torch
        env = {"torch": torch.__version__, "cuda": torch.cuda.is_available(),
               "device": torch.cuda.get_device_name(0) if torch.cuda.is_available() else "cpu"}
    except Exception:
        env = {}
    metrics = {
        "iou": round(score, 4),
        "model_score": round(res.score, 4),
        "pred_area": res.area,
        "gt_area": int(gt.sum()),
        "box": box,
        "model_type": os.getenv("GOMOB_SAM_MODEL", "vit_h"),
        "env": env,
    }
    # 落盘:mask 叠加图 + metrics,便于人工复核
    Image.fromarray(img).save(os.path.join(out_dir, "scene.png"))
    Image.fromarray((res.mask.astype(np.uint8) * 255), "L").save(os.path.join(out_dir, "pred_mask.png"))
    Image.fromarray((gt.astype(np.uint8) * 255), "L").save(os.path.join(out_dir, "gt_mask.png"))
    with open(os.path.join(out_dir, "metrics.json"), "w") as fh:
        json.dump(metrics, fh, ensure_ascii=False, indent=2)
    print(f"  IoU={metrics['iou']}  模型置信={metrics['model_score']}  "
          f"pred/gt 面积={res.area}/{int(gt.sum())}  model={metrics['model_type']}")
    print(f"[seg_bench] metrics → {os.path.join(out_dir, 'metrics.json')}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
