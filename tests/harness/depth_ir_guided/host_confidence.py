#!/usr/bin/env python3
# host_confidence — 在 host_capture 顺序采集的 depth+light-IR 上重验 IR 散斑置信(无需手机)。
# 输入:<dir>/depth_NN.raw(640x400 u16,raw/8=mm,density-first 稠密)
#       <dir>/lightir_NN.raw(640x400 u16,纯 IR 10bit 灰度,与 depth 同传感器逐像素对齐)
# 静态近物场景:depth 时域中值=低噪真值,时域 MAD=逐像素不可信度;
#   light-IR 散斑局部对比度=单帧置信预测量。两批顺序采,空间对齐即可。
# 复刻 C++ p100r3_ir_speckle_confidence 映射(LO=0.4 HI_REL=3.7 MINC=40 WIN=7)。
# 产出两个判定:① AUC(IR 低对比度预测高 MAD)② 置信掩码恢复(density-first 单帧误差→标称)。
# 用法:host_confidence.py <dir>
import sys, glob, os
import cv2, numpy as np

FRAC = 8.0                          # pix=2 DEP_16BIT_13I_3D -> raw/8 = mm
LO, HI_REL, MINC, WIN = 0.4, 3.7, 40, 7
MAD_K = 1.4826                      # MAD -> robust std
UNREL_MM = 30.0                     # 时域稳健 std > 此值 = 不可信(真值标签)


def load_stack(d, kind):
    fs = sorted(glob.glob(os.path.join(d, kind + '_*.raw')))
    out = []
    for f in fs:
        a = np.fromfile(f, '<u2')
        if a.size < 640 * 400:
            continue
        out.append(a[:640 * 400].reshape(400, 640).astype(np.float32))
    return out


def ir_conf(ir):
    # 与 C++ p100r3_ir_speckle_confidence 一致:box 局部 std / 帧内中值归一 -> [MINC..255]
    hi = ir.astype(np.float32)
    m = cv2.blur(hi, (WIN, WIN)); m2 = cv2.blur(hi * hi, (WIN, WIN))
    std = np.sqrt(np.clip(m2 - m * m, 0, None))
    scale = np.median(std[hi > 0]) if (hi > 0).any() else 1.0
    scale = max(float(scale), 1e-6)
    t = np.clip((std / scale - LO) / (HI_REL - LO), 0, 1)
    conf = (MINC + t * (255 - MINC)).astype(np.float32)
    conf[hi <= 0] = 0
    return conf


def auc(score, label):
    # rank 法 Mann-Whitney:score 越大越「正类(不可信)」
    pos = score[label]; neg = score[~label]
    if pos.size == 0 or neg.size == 0:
        return float('nan')
    allv = np.concatenate([pos, neg])
    order = allv.argsort(kind='mergesort')
    ranks = np.empty_like(order, dtype=np.float64)
    ranks[order] = np.arange(1, allv.size + 1)
    # 处理并列:平均秩
    _, inv, cnt = np.unique(allv, return_inverse=True, return_counts=True)
    csum = np.cumsum(cnt); start = csum - cnt
    avg = (start + csum + 1) / 2.0
    ranks = avg[inv]
    rpos = ranks[:pos.size].sum()
    return (rpos - pos.size * (pos.size + 1) / 2.0) / (pos.size * neg.size)


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else '.dev/depth_ir_guided/host_capture'
    depth = load_stack(d, 'depth')
    ir = load_stack(d, 'lightir')
    if len(depth) < 5 or len(ir) < 1:
        print(f'✗ 数据不足:depth={len(depth)} ir={len(ir)}(需 depth≥5, ir≥1)')
        return 1
    ds = np.stack([x / FRAC for x in depth])           # mm
    print(f'depth {ds.shape[0]} 帧  light-IR {len(ir)} 帧  尺寸 {ds.shape[1]}x{ds.shape[2]}')

    dv = ds.copy(); dv[dv <= 0] = np.nan
    truth = np.nanmedian(dv, 0)
    valid = np.isfinite(truth) & (truth > 0)
    # 逐像素时域稳健 std(只在该像素有效帧上)
    abs_dev = np.abs(dv - truth[None])
    mad = np.nanmedian(abs_dev, 0) * MAD_K
    print(f'稠密度(truth 有效){100*valid.mean():.1f}%  '
          f'时域稳健std 中位={np.nanmedian(mad[valid]):.1f}mm  '
          f'不可信(>{UNREL_MM:.0f}mm)占{100*(mad[valid] > UNREL_MM).mean():.0f}%')

    # IR 置信:静态场景取时域均值 IR 图算对比度,更稳
    ir_mean = np.mean(np.stack(ir), 0)
    conf = ir_conf(ir_mean)

    # ① AUC:IR 低置信(=255-conf,越大越不可信)预测高 MAD
    m = valid & np.isfinite(mad)
    label = mad[m] > UNREL_MM
    score_lowconf = (255.0 - conf[m])
    a_low = auc(score_lowconf, label)
    # 符号校验:不可信像素 vs 可信像素的 IR 局部对比度
    hi = ir_mean
    bm = cv2.blur(hi, (WIN, WIN)); bm2 = cv2.blur(hi * hi, (WIN, WIN))
    contrast = np.sqrt(np.clip(bm2 - bm * bm, 0, None)) / max(float(np.median(np.sqrt(np.clip(bm2-bm*bm,0,None))[hi>0])), 1e-6)
    c_bad = np.median(contrast[m][label]) if label.any() else float('nan')
    c_good = np.median(contrast[m][~label]) if (~label).any() else float('nan')
    print(f'\n① IR 置信预测不可信  AUC(低对比度)={a_low:.3f}')
    print(f'   符号校验:不可信像素 IR 对比度中位={c_bad:.2f}  可信像素={c_good:.2f}'
          f'  → {"成立(不可信处散斑更弱)" if c_bad < c_good else "反号(需复查)"}')

    # ② 掩码恢复:density-first 单帧误差 vs truth,按 conf 阈值扫描
    errs, confs = [], []
    for fi in range(ds.shape[0]):
        dm = ds[fi]
        mm = (dm > 0) & valid
        errs.append(np.abs(dm[mm] - truth[mm]) / truth[mm] * 100)
        confs.append(conf[mm])
    err = np.concatenate(errs); cf = np.concatenate(confs)
    print(f'\n② 掩码恢复(单帧误差 vs 18帧时域中值真值)  样本={err.size}')
    print(f'   {"阈值":>5} {"保留密度":>8} {"误差中位%":>9} {"误差p90%":>9} {"≤0.5%":>7} {"≤1%":>6}')
    rows = {}
    for thr in [0, 40, 80, 120, 160, 200]:
        keep = cf >= thr
        if keep.sum() == 0:
            continue
        ek = err[keep]
        rows[thr] = (100 * keep.mean(), np.median(ek), np.percentile(ek, 90),
                     100 * (ek <= 0.5).mean(), 100 * (ek <= 1).mean())
        print(f'   {thr:>5} {rows[thr][0]:>7.0f}% {rows[thr][1]:>8.2f}% '
              f'{rows[thr][2]:>8.1f}% {rows[thr][3]:>6.0f}% {rows[thr][4]:>5.0f}%')

    # 判定
    print('\n=== 判定 ===')
    ok_auc = (not np.isnan(a_low)) and a_low >= 0.70 and (c_bad < c_good)
    best = max((r for r in rows.items() if r[1][0] >= 30), key=lambda r: -r[1][1], default=None)
    ok_mask = best is not None and best[1][1] <= 1.0 and rows[0][1] > best[1][1] * 1.5
    print(f'① IR 置信成立: {"✓" if ok_auc else "✗"}  (AUC≥0.70 且符号正确)')
    if best:
        print(f'② 掩码恢复有效: {"✓" if ok_mask else "✗"}  '
              f'(conf≥{best[0]} 保留{best[1][0]:.0f}% 密度,误差中位 {rows[0][1]:.2f}%→{best[1][1]:.2f}%)')
    verdict = '正常' if (ok_auc and ok_mask) else ('警告' if (ok_auc or ok_mask) else '异常')
    print(f'\n>>> {verdict}:host 顺序采集 {"复现了 Android 交织流的 IR 置信结论" if ok_auc and ok_mask else "结果与预期部分/不符,需复查"}')
    return 0 if verdict == '正常' else (0 if verdict == '警告' else 1)


if __name__ == '__main__':
    sys.exit(main())
