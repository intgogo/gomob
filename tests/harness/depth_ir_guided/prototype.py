#!/usr/bin/env python3
# IR 引导深度精修离线原型 —— 复刻厂商死 API inner_process_with_IR 的思路:
#   对 IR 强度图跑 Canny 提边缘 → 边缘把图分区 → 区内最小二乘平面拟合 → 填洞/插值。
# 对照组(厂商无 IR 版 inner_process):边缘改从单帧 depth 自身提。
#
# 数据:companion 交织 dump dual_raw_NN.bin(640x401 u16,pixel[0]=0x0600 深度 / 0x0500 IR)。
# 这批是稠密帧(无真实洞),故用两条可判定指标:
#   (1) 边缘质量:18 帧时域中值=低噪真值→真边界;比 IR 边缘 vs 单帧深度边缘对真边界的 precision/recall。
#   (2) 留一法补洞:挖掉已知有效像素当合成洞,两法填同一批洞(只换边缘来源),
#       以时域中值为真值比 RMS(整体 / 近真边界 / 远边界)。
# IR 边缘独立于深度噪声与空洞 → 近边界处分区更准 → 填值不跨真边界 → 误差更低。
#
# 用法:prototype.py <dump_dir> <out_dir> [--canny-lo N --canny-hi N --min-support N
#                                          --holeloo-frac F --near-dist N --edge-tol N]
import json
import sys
from pathlib import Path

import cv2
import numpy as np

W, H = 640, 400
TW, TH = 640, 401
DEPTH_MARK, IR_MARK = 0x0600, 0x0500
FRAC = 8.0

P = dict(canny_lo=40, canny_hi=120, min_support=12, max_resid_mm=60.0,
         dilate=1, holeloo_frac=0.18, near_dist=4, edge_tol=2, seed=20260530)


def parse_args(argv):
    d = Path(argv[1]); out = Path(argv[2]); out.mkdir(parents=True, exist_ok=True)
    i = 3
    while i < len(argv):
        k = argv[i].lstrip('-').replace('-', '_'); v = argv[i + 1]
        if k in P:
            P[k] = type(P[k])(v)
        i += 2
    return d, out


def load_frames(dump_dir: Path):
    frames = []
    for f in sorted(dump_dir.glob('dual_raw_*.bin')):
        buf = np.fromfile(f, dtype='<u2')
        if buf.size < TW * TH:
            continue
        mark = int(buf[0])
        active = buf[:TW * TH].reshape(TH, TW)[:H, :W].copy()
        kind = 'D' if mark == DEPTH_MARK else ('I' if mark == IR_MARK else '?')
        frames.append((int(f.stem.split('_')[-1]), kind, active))
    return sorted(frames, key=lambda x: x[0])


def pair_depth_ir(frames):
    ir_list = [(o[0], o[2]) for o in frames if o[1] == 'I']
    pairs = []
    for (idx, kd, a) in frames:
        if kd == 'D' and ir_list:
            j = min(ir_list, key=lambda t: abs(t[0] - idx))
            pairs.append((idx, a, j[0], j[1]))
    return pairs


def clean_reference(frames):
    """18 帧近静态深度的时域中值 = 低噪真值(mm)。"""
    ds = [o[2].astype(np.float32) / FRAC for o in frames if o[1] == 'D']
    stk = np.stack(ds)
    stk[stk <= 0] = np.nan
    med = np.nanmedian(stk, axis=0)
    valid = ~np.isnan(med)
    med = np.nan_to_num(med, nan=0.0)
    return med, valid


def stretch_u8(x, mask):
    if not mask.any():
        return np.zeros((H, W), np.uint8)
    lo, hi = np.percentile(x[mask], [2, 98])
    if hi <= lo:
        hi = lo + 1
    s = np.clip((x - lo) * 255.0 / (hi - lo), 0, 255)
    s[~mask] = 0
    return s.astype(np.uint8)


def ir_intensity_u8(ir_active):
    hi = (ir_active >> 8).astype(np.float32)
    return stretch_u8(hi, hi > 0)


def ir_edge_variants(ir8):
    """原始 + 几种去散斑后的 IR 边缘(验证散斑能否被压掉以恢复真边界)。"""
    k5 = np.ones((5, 5), np.uint8)
    return {
        'raw': cv2.Canny(ir8, P['canny_lo'], P['canny_hi']),
        'median9': cv2.Canny(cv2.medianBlur(ir8, 9), P['canny_lo'], P['canny_hi']),
        'open_close': cv2.Canny(
            cv2.morphologyEx(cv2.morphologyEx(ir8, cv2.MORPH_CLOSE, k5), cv2.MORPH_OPEN, k5),
            P['canny_lo'], P['canny_hi']),
    }


def region_fill(depth_mm, valid, edge_u8):
    """边缘约束区域填洞:平面拟合(退化用区域均值),不设平面性门限→两法填同一批洞公平比。"""
    if P['dilate'] > 0:
        k = np.ones((2 * P['dilate'] + 1,) * 2, np.uint8)
        barrier = cv2.dilate(edge_u8, k)
    else:
        barrier = edge_u8
    free = (barrier == 0).astype(np.uint8)
    n_lab, labels = cv2.connectedComponents(free, connectivity=4)
    out = depth_mm.copy()
    filled = np.zeros((H, W), bool)
    ys_all, xs_all = np.mgrid[0:H, 0:W]
    for lab in range(1, n_lab):
        region = labels == lab
        vmask = region & valid
        cnt = int(vmask.sum())
        if cnt < P['min_support']:
            continue
        holes = region & (~valid)
        if not holes.any():
            continue
        xs = xs_all[vmask].astype(np.float32); ys = ys_all[vmask].astype(np.float32)
        zs = depth_mm[vmask].astype(np.float32)
        hx = xs_all[holes].astype(np.float32); hy = ys_all[holes].astype(np.float32)
        A = np.stack([xs, ys, np.ones_like(xs)], 1)
        ok = False
        if cnt >= 3:
            coef, res, rank, _ = np.linalg.lstsq(A, zs, rcond=None)
            if rank == 3:
                zf = coef[0] * hx + coef[1] * hy + coef[2]; ok = True
        if not ok:
            zf = np.full(hx.shape, float(zs.mean()))
        out[holes] = np.clip(zf, 0, 65535 / FRAC)
        filled[holes] = True
    return out, filled


def edge_pr(test_edge, true_edge, tol):
    """test 边缘对 true 边缘的 precision/recall(tol 像素膨胀容差)。"""
    k = np.ones((2 * tol + 1,) * 2, np.uint8)
    true_d = cv2.dilate(true_edge, k) > 0
    test_d = cv2.dilate(true_edge * 0 + test_edge, k) > 0  # noop, keep test as-is below
    t = test_edge > 0; g = true_edge > 0
    g_d = true_d; t_d = cv2.dilate(test_edge, k) > 0
    prec = float((t & g_d).sum()) / max(1, int(t.sum()))
    rec = float((g & t_d).sum()) / max(1, int(g.sum()))
    f1 = 2 * prec * rec / max(1e-9, prec + rec)
    return prec, rec, f1


def turbo_png(path, mm, mask, vmin=250, vmax=700):
    a = np.clip((mm - vmin) / (vmax - vmin), 0, 1)
    col = cv2.applyColorMap((a * 255).astype(np.uint8), cv2.COLORMAP_TURBO)
    col[~mask] = (30, 30, 30)
    cv2.imwrite(str(path), col)


def main():
    if len(sys.argv) < 3:
        print('用法: prototype.py <dump_dir> <out_dir> [--canny-lo N ...]', file=sys.stderr)
        return 2
    dump_dir, out = parse_args(sys.argv)
    frames = load_frames(dump_dir)
    kinds = ''.join(k for _, k, _ in frames)
    pairs = pair_depth_ir(frames)
    clean_mm, clean_valid = clean_reference(frames)
    true_edge = cv2.Canny(stretch_u8(clean_mm, clean_valid), P['canny_lo'], P['canny_hi'])
    true_dist = cv2.distanceTransform((true_edge == 0).astype(np.uint8), cv2.DIST_L2, 3)
    print(f'帧序列({len(frames)}): {kinds}  D={kinds.count("D")} I={kinds.count("I")}  配对={len(pairs)}')
    print(f'参数: {P}')
    print(f'真边界(时域中值 Canny)像素: {int((true_edge>0).sum())}')

    rng = np.random.default_rng(P['seed'])
    rows = []
    sample = False
    for (didx, dactive, iidx, iactive) in pairs:
        depth_mm = dactive.astype(np.float32) / FRAC
        valid = dactive > 0
        ir8 = ir_intensity_u8(iactive)
        ir_edge = cv2.Canny(ir8, P['canny_lo'], P['canny_hi'])
        depth8 = stretch_u8(depth_mm, valid)
        depth_edge = cv2.Canny(depth8, P['canny_lo'], P['canny_hi'])

        # 指标1:边缘对真边界 P/R(IR 原始 + 去散斑变体 vs 单帧深度边缘)
        ir_pr = edge_pr(ir_edge, true_edge, P['edge_tol'])
        de_pr = edge_pr(depth_edge, true_edge, P['edge_tol'])
        ir_variants_f1 = {name: edge_pr(e, true_edge, P['edge_tol'])[2]
                          for name, e in ir_edge_variants(ir8).items()}

        # 指标2:留一法(以 clean_mm 为真值)
        vy, vx = np.where(valid & clean_valid)
        n = len(vy)
        loo = None
        if n >= 2000:
            sel = rng.choice(n, int(n * P['holeloo_frac']), replace=False)
            hy, hx = vy[sel], vx[sel]
            gt = clean_mm[hy, hx]
            pvalid = valid.copy(); pvalid[hy, hx] = False
            punched = depth_mm.copy(); punched[hy, hx] = 0
            near = true_dist[hy, hx] <= P['near_dist']
            de_p = cv2.Canny(stretch_u8(punched, pvalid), P['canny_lo'], P['canny_hi'])
            fb, fbm = region_fill(punched, pvalid, de_p)        # 无 IR
            fc, fcm = region_fill(punched, pvalid, ir_edge)     # IR 引导

            def stat(img, fm):
                cov = fm[hy, hx]
                if cov.sum() == 0:
                    return dict(rms=None, cov=0.0, near_rms=None, far_rms=None)
                e = img[hy, hx][cov] - gt[cov]
                nn = cov & near; ff = cov & (~near)
                return dict(
                    rms=float(np.sqrt(np.mean(e ** 2))), cov=float(cov.mean()),
                    near_rms=float(np.sqrt(np.mean((img[hy, hx][nn] - gt[nn]) ** 2))) if nn.sum() else None,
                    far_rms=float(np.sqrt(np.mean((img[hy, hx][ff] - gt[ff]) ** 2))) if ff.sum() else None)
            loo = dict(n_hole=len(hy), near_n=int(near.sum()),
                       depth_only=stat(fb, fbm), ir_guided=stat(fc, fcm))

        rows.append(dict(depth_idx=didx, ir_idx=iidx, density=float(valid.mean()),
                         ir_edge_pr=ir_pr, depth_edge_pr=de_pr,
                         ir_variants_f1=ir_variants_f1, loo=loo))

        if not sample:
            tag = f'{didx:02d}'
            cv2.imwrite(str(out / f's{tag}_ir8.png'), ir8)
            cv2.imwrite(str(out / f's{tag}_edge_ir.png'), ir_edge)
            cv2.imwrite(str(out / f's{tag}_edge_depth.png'), depth_edge)
            cv2.imwrite(str(out / f's{tag}_edge_true.png'), true_edge)
            turbo_png(out / f's{tag}_depth_raw.png', depth_mm, valid)
            turbo_png(out / 's_depth_clean.png', clean_mm, clean_valid)
            sample = True

    def m(getter):
        xs = [getter(r) for r in rows if getter(r) is not None]
        return float(np.mean(xs)) if xs else None
    loos = [r['loo'] for r in rows if r['loo']]

    def lm(method, key):
        xs = [l[method][key] for l in loos if l[method][key] is not None]
        return float(np.mean(xs)) if xs else None
    summary = dict(
        params=P, n_pairs=len(pairs), kinds=kinds,
        true_edge_px=int((true_edge > 0).sum()),
        ir_edge_f1=m(lambda r: r['ir_edge_pr'][2]), depth_edge_f1=m(lambda r: r['depth_edge_pr'][2]),
        ir_despeckle_f1={name: m(lambda r: r['ir_variants_f1'][name])
                         for name in ('raw', 'median9', 'open_close')},
        ir_edge_prec=m(lambda r: r['ir_edge_pr'][0]), depth_edge_prec=m(lambda r: r['depth_edge_pr'][0]),
        ir_edge_rec=m(lambda r: r['ir_edge_pr'][1]), depth_edge_rec=m(lambda r: r['depth_edge_pr'][1]),
        loo_depth_only_rms=lm('depth_only', 'rms'), loo_ir_guided_rms=lm('ir_guided', 'rms'),
        loo_depth_only_near=lm('depth_only', 'near_rms'), loo_ir_guided_near=lm('ir_guided', 'near_rms'),
        loo_depth_only_far=lm('depth_only', 'far_rms'), loo_ir_guided_far=lm('ir_guided', 'far_rms'),
        loo_depth_only_cov=lm('depth_only', 'cov'), loo_ir_guided_cov=lm('ir_guided', 'cov'),
    )
    (out / 'results.json').write_text(json.dumps(dict(summary=summary, rows=rows), ensure_ascii=False, indent=2))
    print('\n=== 汇总 ===')
    for k, v in summary.items():
        if k != 'params':
            print(f'  {k}: {v}')
    print(f'\n结果 -> {out}/results.json,样张 PNG 见同目录')
    return 0


if __name__ == '__main__':
    sys.exit(main())
