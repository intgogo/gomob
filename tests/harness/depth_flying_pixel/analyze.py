#!/usr/bin/env python3
# 飞点剔除 harness 判定：对照合成 GT label 算 TP/FP/FN/recall/geom_keep/floater_drop，
# 输出 正常/警告/异常 + 原因。单测验数学；本 harness 验真实行为(在合成 GT 上)。
#
# 用法：analyze.py <scenes_root> <apply_root> <W> <H> [out_json]
#   scenes_root/<scene>/gt_label.raw (uint8 1=真飞点)
#   apply_root/<scene>/{detect_count.raw(uint16), apply_meta.json}
import json
import math
import sys
from pathlib import Path

import numpy as np

# 验收门
RECALL_MIN = 0.80        # 真飞点召回
GEOM_KEEP_MIN = 0.99     # 真几何保留率（过杀红线）
FLOATER_DROP_MIN = 0.70  # 悬浮点下降
DETECT_FRAC = 0.5        # 像素被判飞点 = post-warmup 中标记比例 ≥ 此值


def load_u16(p, px):
    return np.fromfile(p, dtype="<u2") if Path(p).exists() else np.zeros(px, "<u2")


def load_u8(p, px):
    return np.fromfile(p, dtype=np.uint8) if Path(p).exists() else np.zeros(px, np.uint8)


def fmt(v, n=3):
    return "n/a" if v is None else (f"{v:.{n}f}" if isinstance(v, float) else str(v))


def analyze_scene(name, scene_dir, apply_dir, w, h):
    px = w * h
    gt = load_u8(scene_dir / "gt_label.raw", px).astype(bool)
    cnt = load_u16(apply_dir / "detect_count.raw", px).astype(np.int32)
    meta = json.loads((apply_dir / "apply_meta.json").read_text())
    post = max(1, meta.get("post_warmup", 1))
    detected = cnt >= math.ceil(DETECT_FRAC * post)

    # 有效几何像素：第 0 帧非零且非飞点（合成场景里真几何恒有效）
    f0 = load_u16(scene_dir / "frame-000.raw", px).reshape(h, w).astype(np.float32)
    valid0 = f0.reshape(-1) > 0
    geom = valid0 & (~gt)

    tp = int(np.sum(detected & gt))
    fn = int(np.sum(~detected & gt))
    fp = int(np.sum(detected & geom))  # 在真几何上误删
    n_fly = int(np.sum(gt))
    n_geom = int(np.sum(geom))
    recall = tp / n_fly if n_fly else None
    geom_keep = 1 - fp / n_geom if n_geom else 1.0

    res = {
        "scene": name, "post_warmup": post,
        "n_flyer": n_fly, "n_geom": n_geom,
        "tp": tp, "fp": fp, "fn": fn,
        "recall": round(recall, 4) if recall is not None else None,
        "geom_keep": round(geom_keep, 4),
        "fp_ratio_pct": round(100 * fp / max(1, n_geom), 4),
    }
    # 判定：有飞点场景看 recall+geom_keep；无飞点场景(斜面/球/台阶/薄结构)看 FP==0
    fail, warn = [], []
    if n_fly > 0:
        if recall is None or recall < RECALL_MIN:
            fail.append(f"召回不足 {fmt(recall)}(<{RECALL_MIN})")
        if geom_keep < GEOM_KEEP_MIN:
            fail.append(f"过杀: geom_keep {fmt(geom_keep)}(<{GEOM_KEEP_MIN})")
    else:
        # 纯真几何场景：任何标记都是误删
        if fp > 0:
            fail.append(f"真几何场景误删 {fp} 像素(应 0)")
    res["status"] = "FAIL" if fail else ("WARN" if warn else "OK")
    res["verdict"] = "；".join(fail or warn or ["飞点剔除正常"])
    return res


def main():
    if len(sys.argv) < 5:
        print("用法: analyze.py <scenes_root> <apply_root> <W> <H> [out_json]", file=sys.stderr)
        return 2
    scenes_root, apply_root = Path(sys.argv[1]), Path(sys.argv[2])
    w, h = int(sys.argv[3]), int(sys.argv[4])
    out_json = Path(sys.argv[5]) if len(sys.argv) > 5 else None

    scenes = sorted(d.name for d in scenes_root.iterdir()
                    if d.is_dir() and (d / "gt_label.raw").exists())
    results = []
    for s in scenes:
        ad = apply_root / s
        if not (ad / "detect_count.raw").exists():
            continue
        results.append(analyze_scene(s, scenes_root / s, ad, w, h))

    overall = "OK"
    for r in results:
        if r["status"] == "FAIL":
            overall = "FAIL"
        elif r["status"] == "WARN" and overall != "FAIL":
            overall = "WARN"

    lines = ["# Depth Flying-Pixel Harness", f"- 总判定: {overall}", ""]
    for r in results:
        extra = (f"recall={fmt(r['recall'])} geom_keep={fmt(r['geom_keep'])}"
                 if r["n_flyer"] > 0 else f"FP={r['fp']}(真几何场景应0)")
        lines.append(f"- [{r['status']}] {r['scene']}: {extra} | {r['verdict']}")
    text = "\n".join(lines)
    print(text)
    out = {"overall": overall, "recall_min": RECALL_MIN, "geom_keep_min": GEOM_KEEP_MIN,
           "scenes": results}
    print(json.dumps(out, ensure_ascii=False, indent=2))
    if out_json:
        out_json.parent.mkdir(parents=True, exist_ok=True)
        out_json.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if overall == "OK" else (1 if overall == "WARN" else 2)


if __name__ == "__main__":
    raise SystemExit(main())
