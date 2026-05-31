#!/usr/bin/env python3
# 验证策略1:IR 散斑置信掩码能否把 density-first 稠密单帧误差从 ~9% 拉回接近 0.5% 官方标称。
# 复刻 p100r3_ir_speckle_confidence(C++)的映射,在交织 dump 上:
#   真值=18 帧时域中值;单帧误差%=|depth-真值|/真值;IR 单帧置信=散斑局部对比度(帧内中值归一化)。
#   按置信阈值扫描:保留密度 vs 保留像素的单帧误差中位/达标率。
# 用法:mask_recovery.py <dump_dir>
import sys, glob
import cv2, numpy as np

W, H, TW, TH, FRAC = 640, 400, 640, 401, 8.0
LO, HI_REL, MINC, WIN = 0.4, 3.7, 40, 7  # 与 C++ p100r3_ir_speckle_confidence 默认一致


def load(d):
    fr = []
    for f in sorted(glob.glob(d + '/dual_raw_*.bin')):
        b = np.fromfile(f, '<u2')
        if b.size < TW * TH:
            continue
        a = b[:TW * TH].reshape(TH, TW)[:H, :W].copy()
        k = 'D' if b[0] == 0x600 else ('I' if b[0] == 0x500 else '?')
        fr.append((int(f.stem.split('_')[-1]) if hasattr(f, 'stem') else 0, k, a))
    return fr


def ir_conf(ir_active):
    hi = (ir_active >> 8).astype(np.float32)
    m = cv2.blur(hi, (WIN, WIN)); m2 = cv2.blur(hi * hi, (WIN, WIN))
    std = np.sqrt(np.clip(m2 - m * m, 0, None))
    scale = np.median(std[hi > 0]) if (hi > 0).any() else 1.0
    scale = max(scale, 1e-6)
    t = np.clip((std / scale - LO) / (HI_REL - LO), 0, 1)
    conf = (MINC + t * (255 - MINC)).astype(np.float32)
    conf[hi <= 0] = 0
    return conf


def main():
    import os
    d = sys.argv[1] if len(sys.argv) > 1 else '.dev/depth-4b-analysis'
    files = sorted(glob.glob(d + '/dual_raw_*.bin'))
    frames = []
    for f in files:
        b = np.fromfile(f, '<u2')
        if b.size < TW * TH:
            continue
        a = b[:TW * TH].reshape(TH, TW)[:H, :W].copy()
        k = 'D' if b[0] == 0x600 else ('I' if b[0] == 0x500 else '?')
        frames.append((int(os.path.basename(f).split('_')[-1].split('.')[0]), k, a))
    frames.sort()
    ds = np.stack([a.astype(np.float32) / FRAC for _, k, a in frames if k == 'D'])
    ir_list = [(idx, a) for idx, k, a in frames if k == 'I']
    print(f'深度帧 {ds.shape[0]}  IR帧 {len(ir_list)}')

    dv = ds.copy(); dv[dv <= 0] = np.nan
    truth = np.nanmedian(dv, 0)
    tvalid = np.isfinite(truth) & (truth > 0)

    # 汇总所有 (深度帧, 像素):单帧误差% + 该帧配对 IR 的置信
    errs, confs = [], []
    di = 0
    for idx, k, a in frames:
        if k != 'D':
            continue
        d_mm = a.astype(np.float32) / FRAC
        j = min(ir_list, key=lambda t: abs(t[0] - idx))
        cf = ir_conf(j[1])
        m = (d_mm > 0) & tvalid
        errs.append(np.abs(d_mm[m] - truth[m]) / truth[m] * 100)
        confs.append(cf[m])
        di += 1
    err = np.concatenate(errs); conf = np.concatenate(confs)
    print(f'样本(帧×有效像素) {err.size}  全体单帧误差 中位={np.median(err):.2f}%  ≤0.5%占{100*(err<=0.5).mean():.0f}%')

    print('\n按 IR 置信阈值掩码(保留 conf≥阈值):')
    print(f'{"阈值":>5} {"保留密度":>8} {"误差中位%":>9} {"误差p90%":>9} {"≤0.5%占":>8} {"≤1%占":>7}')
    for thr in [0, 40, 80, 120, 160, 200, 230]:
        keep = conf >= thr
        if keep.sum() == 0:
            continue
        ek = err[keep]
        print(f'{thr:>5} {100*keep.mean():>7.0f}% {np.median(ek):>8.2f}% '
              f'{np.percentile(ek,90):>8.1f}% {100*(ek<=0.5).mean():>7.0f}% {100*(ek<=1).mean():>6.0f}%')
    print('\n结论判据:某阈值下 误差中位≤~0.5–1% 且 保留密度仍可观(≥30–50%) → 置信掩码有效恢复质量。')


if __name__ == '__main__':
    sys.exit(main())
