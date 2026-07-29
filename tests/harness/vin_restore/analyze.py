#!/usr/bin/env python3
# 还原重合度可判定分析（几何残余为主指标）。
# 「重合」= 同一 VIN 多角度还原图在签名图里几何对齐误差小。用 EUCLIDEAN ECC（平移+旋转+缩放）
# 测把 B 对到 A 需要的残余位移/旋转——残余小 = 已重合（不靠后处理硬掰）。
import os, sys, glob, itertools
import numpy as np
import cv2

W, H = 1000, 180


def _norm(g):
    return (g - g.mean()) / (g.std() + 1e-6)


def load_gray(p, sigma=2.5):
    g = cv2.imread(p, cv2.IMREAD_GRAYSCALE)
    g = cv2.resize(g, (W, H)).astype(np.float32)
    g = cv2.GaussianBlur(g, (0, 0), sigma)   # 二值签名：模糊容 2-3px，NCC 反映"几何是否落在同处"而非逐像素硬比
    return _norm(g)


def ncc(a, b):
    return float((a * b).mean())


def ecc_residual(ref, mov):
    """EUCLIDEAN ECC 把 mov 对到 ref，返回 (残余位移px, 残余旋转deg, 对齐后NCC)。失败回退。"""
    warp = np.eye(2, 3, dtype=np.float32)
    try:
        crit = (cv2.TERM_CRITERIA_EPS | cv2.TERM_CRITERIA_COUNT, 300, 1e-6)
        _, warp = cv2.findTransformECC(ref, mov, warp, cv2.MOTION_EUCLIDEAN, crit, None, 5)
        aligned = cv2.warpAffine(mov, warp, (W, H), flags=cv2.INTER_LINEAR + cv2.WARP_INVERSE_MAP)
        dx, dy = warp[0, 2], warp[1, 2]
        rot = np.degrees(np.arctan2(warp[1, 0], warp[0, 0]))
        return (dx * dx + dy * dy) ** 0.5, abs(rot), ncc(ref, aligned)
    except cv2.error:
        return 999.0, 99.0, ncc(ref, mov)


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else os.environ.get("OUTPUT_DIR", ".dev/vin_restore")
    pat = sys.argv[2] if len(sys.argv) > 2 else "cap_*_sig.png"
    files = sorted(glob.glob(os.path.join(d, pat)))
    if len(files) < 2:
        print("需要至少 2 张", pat); return 1
    imgs = {os.path.basename(f): load_gray(f) for f in files}          # 度量用 σ=2.5
    grp = {os.path.basename(f): load_gray(f, sigma=6.0) for f in files}  # 分组用 σ=6 稳健
    names = list(imgs)
    n = len(names)

    # 分组：重模糊图抗二值高频，细节图防跨批次误并。
    # 动态高度后不同批次同 VIN 可能粗看相似，但细节 ECC 位移很大；这类应拆组再评估各自重合度。
    aln = np.zeros((n, n))
    merge = np.zeros((n, n), dtype=bool)
    for i, j in itertools.combinations(range(n), 2):
        sh, _, cd = ecc_residual(imgs[names[i]], imgs[names[j]])
        _, _, c = ecc_residual(grp[names[i]], grp[names[j]])
        aln[i, j] = aln[j, i] = c
        merge[i, j] = merge[j, i] = c > 0.55 and cd > 0.50 and sh <= W * 0.02
    seen, groups = set(), []
    for i in range(n):
        if i in seen: continue
        g = [i]; seen.add(i)
        for j in range(n):
            if j not in seen and merge[i, j]:
                g.append(j); seen.add(j)
        groups.append(g)

    print("分组（粗匹配 NCC>0.55 且细节位移≤2%宽）：")
    SHIFT_PCT = 100.0 / W           # 残余位移 → 占宽百分比
    worst_align = 1.0; worst_shift = 0.0
    for gi, g in enumerate(groups):
        gn = [names[k] for k in g]
        ids = ",".join(s.split('_')[1] for s in gn)
        if len(g) < 2:
            print(f"  组{gi+1}(单张,跳过): {ids}"); continue
        ref = imgs[gn[0]]
        shifts, rots, alns = [], [], []
        for s in gn[1:]:
            sh, ro, c = ecc_residual(ref, imgs[s])
            shifts.append(sh); rots.append(ro); alns.append(c)
            print(f"  组{gi+1} {s.split('_')[1]}: 残余位移={sh:.1f}px({sh*SHIFT_PCT:.2f}%) 旋转={ro:.2f}° 对齐后NCC={c:.3f}")
        mshift = float(np.median(shifts)); maln = float(np.mean(alns)); mxsh = float(np.max(shifts))
        print(f"    组{gi+1}({len(g)}张 {ids})均：中位残余位移={mshift:.1f}px({mshift*SHIFT_PCT:.2f}%) 最大={mxsh:.1f}px 对齐后NCC={maln:.3f}")
        worst_align = min(worst_align, maln); worst_shift = max(worst_shift, mshift)

    # 重合度主指标 = 几何残余位移（同 VIN 还原图差多远）；二值签名 NCC 偏低属固有(笔画边缘)，作内容匹配辅证。
    print("\n结论：", end="")
    if worst_shift <= W * 0.008 and worst_align >= 0.55:   # 中位残余≤0.8%宽 + 内容匹配
        print(f"正常 — 同 VIN 各张几何重合（最差组中位残余={worst_shift:.0f}px/{worst_shift*SHIFT_PCT:.2f}%，对齐后NCC={worst_align:.2f}）")
        return 0
    # 警告档：残余偏大但仍在 1.5% 内，且内容仍能匹配（NCC≥0.55）→ WARN（退码 0，但醒目打印）。
    # 若内容都对不上（NCC<0.55），即便位移小也是配准假重合 → 落 FAIL 而非被警告档吞掉。
    if worst_shift <= W * 0.015 and worst_align >= 0.55:
        print(f"⚠ 警告 — 大致重合，残余偏大（最差组中位残余={worst_shift:.0f}px/{worst_shift*SHIFT_PCT:.2f}%，对齐后NCC={worst_align:.2f}）")
        return 0
    print(f"❌ 异常 — 未重合（最差组中位残余={worst_shift:.0f}px/{worst_shift*SHIFT_PCT:.2f}%，对齐后NCC={worst_align:.2f}）；查平面/OBB/配准")
    return 1


if __name__ == "__main__":
    sys.exit(main())
