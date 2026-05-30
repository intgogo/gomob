#!/usr/bin/env python3
# 飞点剔除合成 ground-truth 场景生成器。
# grounding 表明真实 vendor-dense 数据太杂乱、无飞点 GT，不能干净验证飞点剔除。
# 这里造已知几何场景 + 注入"同时具备空间夹心 + 时域双稳跳变 + 深度自适应噪声"的真飞点，
# 并导出逐像素 GT label（0=真几何 1=真飞点），供 analyze.py 算 TP/FP/FN/recall/geom_keep。
#
# 输出每场景目录：frame-000.raw..(N-1).raw (uint16 13I3D, mm*8, 0=无效) + gt_label.raw (uint8) + meta.json
# 用法：gen_scene.py <out_root> [N]
import json
import sys
from pathlib import Path

import numpy as np

FRAC = 8.0
W, H = 640, 400


def noise_std_mm(depth_mm):
    # 深度自适应：近距 ~12mm、远距随深度放大（对齐 grounding：近面 ~15mm、远 ~38mm 的逐帧噪声）。
    return np.maximum(12.0, 0.025 * depth_mm)


def to_raw(depth_mm):
    r = np.clip(depth_mm * FRAC, 0, 65535)
    return r.astype(np.uint16)


def add_noise(depth_mm, rng):
    valid = depth_mm > 0
    out = depth_mm.copy()
    out[valid] += rng.normal(0, 1, size=valid.sum()) * noise_std_mm(depth_mm[valid])
    out[~valid] = 0
    return out


def render(name, base_depth_mm, flyer_mask, fg_for_flyer, bg_for_flyer, n, rng):
    """base_depth_mm: 真几何(H,W) mm，0=无效。flyer_mask: 飞点像素位置。
    飞点逐帧在 fg/bg/中点三态间随机跳（双稳）+ 噪声。返回 frames(N,H,W) raw, gt_label(H,W)."""
    frames = []
    for _ in range(n):
        d = add_noise(base_depth_mm, rng)
        if flyer_mask.any():
            idx = np.where(flyer_mask)
            k = len(idx[0])
            fg = fg_for_flyer[idx]
            bg = bg_for_flyer[idx]
            # 真实飞点 = 边缘处插值出的"中间深度幽灵点"（物理上无实体）：逐帧落在 fg/bg 之间
            # 中段 20%-80% 的随机深度 + 噪声 → 空间上被前/背景夹住、时域上不稳、无共面支撑。
            t = rng.uniform(0.2, 0.8, size=k)
            val = fg + t * (bg - fg)
            val = val + rng.normal(0, 1, size=k) * noise_std_mm(val)
            d[idx] = val
        frames.append(to_raw(d))
    gt = flyer_mask.astype(np.uint8)  # 1=真飞点 0=真几何(含无效)
    return np.stack(frames), gt


def scene_s1(rng, near=500.0, far=1500.0):
    # 左半前景平面 + 右半远背景，竖直断崖；断崖处 3px 带为飞点
    d = np.zeros((H, W))
    cliff = W // 2
    d[:, :cliff] = near
    d[:, cliff:] = far
    fly = np.zeros((H, W), bool)
    fly[:, cliff:cliff + 1] = True  # 断崖处 1px 飞点晕（真实飞点多为 1-2px 薄晕，非纯块）
    fg = np.full((H, W), near)
    bg = np.full((H, W), far)
    return d, fly, fg, bg


def scene_slopes(rng):
    # 三段连续斜面（30/45/60°），无飞点。零误删红线。
    d = np.zeros((H, W))
    seg = W // 3
    base = 600.0
    for s, ang in enumerate((30, 45, 60)):
        x0 = s * seg
        x1 = (s + 1) * seg if s < 2 else W
        xs = np.arange(x1 - x0)
        # 坡度：每像素深度增量 = tan(ang) * pixel_pitch；用一个温和比例避免越界
        slope = np.tan(np.radians(ang)) * 1.2
        d[:, x0:x1] = base + xs[None, :] * slope
        base = d[0, x1 - 1]
    fly = np.zeros((H, W), bool)
    return d, fly, np.zeros((H, W)), np.zeros((H, W))


def scene_sphere(rng, center=800.0, radius_px=150.0, depth_amp=200.0):
    # 球面（曲率），无飞点。曲率护盾。
    yy, xx = np.mgrid[0:H, 0:W]
    cy, cx = H / 2, W / 2
    r = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2)
    inside = r < radius_px
    d = np.full((H, W), center + depth_amp)  # 周围平面稍远
    bump = np.zeros((H, W))
    bump[inside] = depth_amp * np.sqrt(np.clip(1 - (r[inside] / radius_px) ** 2, 0, 1))
    d = (center + depth_amp) - bump  # 球冠朝相机凸出
    fly = np.zeros((H, W), bool)
    return d, fly, np.zeros((H, W)), np.zeros((H, W))


def scene_steps(rng):
    # 阶梯：单调台阶真边缘（非夹心），无飞点。验真边缘不被误删。
    d = np.zeros((H, W))
    levels = [500, 700, 950, 1250, 1600]
    seg = W // len(levels)
    for i, lv in enumerate(levels):
        x0 = i * seg
        x1 = (i + 1) * seg if i < len(levels) - 1 else W
        d[:, x0:x1] = lv
    fly = np.zeros((H, W), bool)
    return d, fly, np.zeros((H, W)), np.zeros((H, W))


def scene_far(rng):
    return scene_s1(rng, near=1800.0, far=3000.0)


def scene_thin(rng, bar_depth=450.0, bg_depth=1100.0):
    # 薄结构：几条 1-2px 竖杆(真前景细物) + 远背景。无飞点。验薄结构不被误删。
    d = np.full((H, W), bg_depth)
    for cx in range(80, W, 120):
        d[:, cx:cx + 2] = bar_depth  # 2px 宽竖杆
    fly = np.zeros((H, W), bool)
    return d, fly, np.zeros((H, W)), np.zeros((H, W))


SCENES = {
    "S1_plane_bg": scene_s1,
    "S2_slopes": scene_slopes,
    "S3_sphere": scene_sphere,
    "S4_steps": scene_steps,
    "S5_far": scene_far,
    "S6_thin": scene_thin,
}


def selfcheck(name, frames, gt):
    # 打印注入飞点统计与 grounding 数字对比（治合成失真风险）
    mm = frames.astype(np.float32) / FRAC
    span = (mm.max(0) - mm.min(0))
    fly = gt == 1
    geom = (gt == 0) & (mm[0] > 0)
    info = {
        "scene": name, "frames": int(frames.shape[0]),
        "flyer_ratio": round(float(fly.mean()), 4),
        "flyer_tspan_median_mm": round(float(np.median(span[fly])) if fly.any() else 0.0, 1),
        "geom_tspan_median_mm": round(float(np.median(span[geom])) if geom.any() else 0.0, 1),
        "density": round(float((mm[0] > 0).mean()), 4),
    }
    return info


def main():
    if len(sys.argv) < 2:
        print("用法: gen_scene.py <out_root> [N]", file=sys.stderr)
        return 2
    out_root = Path(sys.argv[1])
    n = int(sys.argv[2]) if len(sys.argv) > 2 else 20
    out_root.mkdir(parents=True, exist_ok=True)
    summary = []
    for name, fn in SCENES.items():
        rng = np.random.default_rng(abs(hash(name)) % (2**32))  # 每场景固定种子，可复现
        base, fly, fg, bg = fn(rng)
        frames, gt = render(name, base, fly, fg, bg, n, rng)
        d = out_root / name
        d.mkdir(exist_ok=True)
        for i in range(n):
            frames[i].tofile(d / f"frame-{i:03d}.raw")
        gt.tofile(d / "gt_label.raw")
        info = selfcheck(name, frames, gt)
        (d / "meta.json").write_text(json.dumps(info, ensure_ascii=False, indent=2))
        summary.append(info)
        print(f"{name}: flyer={info['flyer_ratio']*100:.1f}% "
              f"flyer_span={info['flyer_tspan_median_mm']}mm geom_span={info['geom_tspan_median_mm']}mm")
    (out_root / "scenes.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
    # 一致性自检：飞点时域 span 应远大于真几何 span（双稳特征），否则合成失真
    bad = [s for s in summary if s["flyer_ratio"] > 0 and s["flyer_tspan_median_mm"] < 3 * max(s["geom_tspan_median_mm"], 1)]
    if bad:
        print("!! 合成失真告警：飞点时域 span 未显著大于真几何", [b["scene"] for b in bad])
    print(f"生成 {len(summary)} 组场景 → {out_root}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
