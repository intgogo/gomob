#!/usr/bin/env python3
# IR 作"深度置信/有效性"信号验证(区别于 IR 作边缘,见 prototype.py)。
#
# 假设:结构光下,IR 帧里散斑图案的"可见度/局部对比度"本就是深度可信度的物理指标——
#   散斑清晰(高局部对比)=图案被良好接收=深度强约束;散斑被冲淡/无回波/强光饱和=深度靠猜。
# 若单帧 IR 某特征能预测"该像素深度不可信",则 IR 给出**零延迟单帧置信**(比时域窗口稳定性更快)。
#
# 真值:18 帧近静态深度的逐像素时域 MAD(中值绝对偏差,mm)= 不可信度真值;MAD 大 = 不稳/不可信。
# 评估:各 IR 特征预测 (MAD>阈值) 的 AUC(秩法 Mann-Whitney)+ Spearman 相关。
# 用法:confidence_probe.py <dump_dir> <out_dir> [--mad-bad-mm F --contrast-win N]
import json
import sys
from pathlib import Path

import cv2
import numpy as np

W, H, TW, TH = 640, 400, 640, 401
DEPTH_MARK, IR_MARK = 0x0600, 0x0500
FRAC = 8.0
CFG = dict(mad_bad_mm=30.0, contrast_win=7, sat_hi=250, dark_lo=12)


def load(d):
    fr = []
    for f in sorted(Path(d).glob('dual_raw_*.bin')):
        b = np.fromfile(f, '<u2')
        if b.size < TW * TH:
            continue
        a = b[:TW * TH].reshape(TH, TW)[:H, :W].copy()
        k = 'D' if b[0] == DEPTH_MARK else ('I' if b[0] == IR_MARK else '?')
        fr.append((int(f.stem.split('_')[-1]), k, a))
    return sorted(fr)


def auc(score, label):
    """秩法 AUC:score 越大越预测 label=1。"""
    pos = score[label]; neg = score[~label]
    if len(pos) == 0 or len(neg) == 0:
        return float('nan')
    alls = np.concatenate([pos, neg])
    order = alls.argsort()
    ranks = np.empty(len(alls), float)
    ranks[order] = np.arange(1, len(alls) + 1)
    # 处理并列:平均秩
    _, inv, cnt = np.unique(alls, return_inverse=True, return_counts=True)
    csum = np.cumsum(cnt)
    avg = {i: (csum[i] - cnt[i] + 1 + csum[i]) / 2.0 for i in range(len(cnt))}
    ranks = np.array([avg[v] for v in inv])
    r_pos = ranks[:len(pos)].sum()
    u = r_pos - len(pos) * (len(pos) + 1) / 2.0
    return float(u / (len(pos) * len(neg)))


def spearman(a, b):
    ra = np.argsort(np.argsort(a)); rb = np.argsort(np.argsort(b))
    ra = ra - ra.mean(); rb = rb - rb.mean()
    d = np.sqrt((ra * ra).sum() * (rb * rb).sum())
    return float((ra * rb).sum() / d) if d > 0 else float('nan')


def main():
    if len(sys.argv) < 3:
        print('用法: confidence_probe.py <dump_dir> <out_dir> [--mad-bad-mm F ...]', file=sys.stderr)
        return 2
    dump, out = Path(sys.argv[1]), Path(sys.argv[2]); out.mkdir(parents=True, exist_ok=True)
    i = 3
    while i < len(sys.argv):
        k = sys.argv[i].lstrip('-').replace('-', '_'); v = sys.argv[i + 1]
        if k in CFG:
            CFG[k] = type(CFG[k])(v)
        i += 2

    fr = load(dump)
    ds = np.stack([o[2].astype(np.float32) / FRAC for o in fr if o[1] == 'D'])
    irs = np.stack([(o[2] >> 8).astype(np.float32) for o in fr if o[1] == 'I'])
    print(f'深度帧 {ds.shape[0]}  IR帧 {irs.shape[0]}')

    dv = ds.copy(); dv[dv <= 0] = np.nan
    median = np.nanmedian(dv, 0)
    mad = np.nanmedian(np.abs(dv - median), 0)          # 逐像素时域不可信度真值(mm)
    valid = np.isfinite(median) & np.isfinite(mad)
    bad = valid & (mad > CFG['mad_bad_mm'])             # 不可信像素

    # IR 特征(场景近静态 → 取 IR 帧中值代表)
    ir_med = np.median(irs, 0)
    win = CFG['contrast_win']
    mean = cv2.blur(ir_med, (win, win))
    mean_sq = cv2.blur(ir_med * ir_med, (win, win))
    contrast = np.sqrt(np.clip(mean_sq - mean * mean, 0, None))   # 局部标准差=散斑可见度
    gx = cv2.Sobel(ir_med, cv2.CV_32F, 1, 0); gy = cv2.Sobel(ir_med, cv2.CV_32F, 0, 1)
    grad = np.sqrt(gx * gx + gy * gy)

    feats = {
        'IR_intensity_low(暗→不可信)': -ir_med,            # 越暗越可能不可信 → 取负
        'IR_local_contrast_low(散斑弱→不可信)': -contrast,  # 对比低→图案弱→不可信
        'IR_saturation(饱和→不可信)': (ir_med >= CFG['sat_hi']).astype(np.float32),
        'IR_local_grad(高→边界/不可信)': grad,
    }

    m = valid
    n_bad = int(bad[m].sum()); n_tot = int(m.sum())
    print(f'有效像素 {n_tot}  不可信(MAD>{CFG["mad_bad_mm"]}mm) {n_bad} ({100*n_bad/max(1,n_tot):.1f}%)')
    print(f'\n{"IR 特征":40s} {"AUC":>7s} {"Spearman(vs MAD)":>18s}')
    results = {}
    for name, fmap in feats.items():
        a = auc(fmap[m], bad[m])
        s = spearman(fmap[m], mad[m])
        results[name] = dict(auc=a, spearman=s)
        print(f'{name:40s} {a:7.3f} {s:18.3f}')

    best = max(results.items(), key=lambda kv: (kv[1]['auc'] if kv[1]['auc'] == kv[1]['auc'] else 0))
    print(f'\n最佳单帧 IR 预测子: {best[0]}  AUC={best[1]["auc"]:.3f}')
    print('判据:AUC>0.70 = IR 单帧置信有实用价值;0.55~0.70 = 弱;<0.55 = 基本无预测力')

    # 可视化:MAD(不可信)图 vs IR 对比度图,肉眼看空间相关
    def norm_png(path, x, mask, vmax=None, cmap=cv2.COLORMAP_TURBO):
        v = x.copy(); v[~mask] = 0
        vmax = vmax or np.percentile(v[mask], 98) if mask.any() else 1
        col = cv2.applyColorMap(np.clip(v / max(vmax, 1e-6) * 255, 0, 255).astype(np.uint8), cmap)
        col[~mask] = (30, 30, 30); cv2.imwrite(str(path), col)
    norm_png(out / 'conf_mad_mm.png', mad, valid, vmax=80)
    norm_png(out / 'conf_ir_contrast.png', contrast, valid)
    cv2.imwrite(str(out / 'conf_bad_mask.png'), (bad * 255).astype(np.uint8))

    (out / 'confidence_results.json').write_text(json.dumps(
        dict(cfg=CFG, n_total=n_tot, n_bad=n_bad, bad_frac=n_bad / max(1, n_tot),
             features=results, best=dict(name=best[0], **best[1])), ensure_ascii=False, indent=2))
    print(f'\n结果 -> {out}/confidence_results.json,图见 conf_*.png')
    return 0


if __name__ == '__main__':
    sys.exit(main())
