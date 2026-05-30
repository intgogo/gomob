#!/usr/bin/env python3
# 深度时域降噪 grounding 仿真：在已采集的静态场景多帧序列上，量化
# 各融合策略能把 per-pixel 时域噪声压到多少、是否引入偏移、是否损失密度。
# 目的：先用真实数据证明"时域降噪是头号杠杆"并给 C++ 实现定参，再写业务代码。
#
# 输入 .raw = 640x400 uint16 fixed-point (13I_3D)，mm = raw/8，0 表示无效。
# 用法：simulate_fusion.py <序列目录> <glob> [out_json]
import sys
import json
from pathlib import Path

import numpy as np

FRAC = 8.0  # 13I_3D：raw/8 = mm


def load_sequence(seq_dir: Path, pattern: str, w: int, h: int) -> np.ndarray:
    files = sorted(seq_dir.glob(pattern))
    frames = []
    for f in files:
        buf = np.fromfile(f, dtype="<u2")
        if buf.size != w * h:
            continue
        frames.append(buf.reshape(h, w))
    if not frames:
        raise SystemExit(f"无可用帧: {seq_dir}/{pattern}")
    return np.stack(frames).astype(np.float32)  # (N,H,W) raw fixedpoint


def consec_median_abs_diff_mm(seq_mm: np.ndarray) -> float:
    # 相邻帧逐像素绝对差中位数（只在两帧都有效处统计），对齐 parity harness 的噪声底口径。
    diffs = []
    for i in range(1, seq_mm.shape[0]):
        a, b = seq_mm[i - 1], seq_mm[i]
        m = (a > 0) & (b > 0)
        if m.any():
            diffs.append(np.abs(a[m] - b[m]))
    if not diffs:
        return float("nan")
    return float(np.median(np.concatenate(diffs)))


def per_pixel_temporal_std_mm(seq_mm: np.ndarray) -> float:
    # 对每个"全程有效"的像素求时间维 std，再取中位数 → 单像素时域噪声强度。
    valid_all = np.all(seq_mm > 0, axis=0)
    if not valid_all.any():
        return float("nan")
    stds = np.std(seq_mm[:, valid_all], axis=0)
    return float(np.median(stds))


def fuse_cumulative_mean(seq: np.ndarray) -> np.ndarray:
    # 策略 A：静态假设下的累积均值（只对有效像素累计）。返回每帧的"当前估计"。
    n, h, w = seq.shape
    out = np.zeros_like(seq)
    acc = np.zeros((h, w), np.float64)
    cnt = np.zeros((h, w), np.float64)
    for i in range(n):
        v = seq[i] > 0
        acc[v] += seq[i][v]
        cnt[v] += 1
        est = np.where(cnt > 0, acc / np.maximum(cnt, 1), 0.0)
        out[i] = est
    return out.astype(np.float32)


def fuse_motion_aware_ema(seq: np.ndarray, thresh_mm: float, alpha: float) -> np.ndarray:
    # 策略 C：运动/边缘感知 EMA。raw 单位下阈值 = thresh_mm*FRAC。
    # 新值与估计差 < 阈值 → est = (1-a)*est + a*new；否则重置为 new（运动/边缘不拖影）。
    n, h, w = seq.shape
    thresh = thresh_mm * FRAC
    out = np.zeros_like(seq)
    est = np.zeros((h, w), np.float32)
    has = np.zeros((h, w), bool)
    for i in range(n):
        cur = seq[i]
        v = cur > 0
        new_pix = v & ~has
        est[new_pix] = cur[new_pix]
        has[new_pix] = True
        upd = v & has & ~new_pix
        d = np.abs(cur - est)
        blend = upd & (d < thresh)
        reset = upd & (d >= thresh)
        est[blend] = (1.0 - alpha) * est[blend] + alpha * cur[blend]
        est[reset] = cur[reset]
        out[i] = np.where(has, est, 0.0)
    return out.astype(np.float32)


def fuse_windowed(seq: np.ndarray, n_win: int, motion_thresh_mm: float, use_median: bool) -> np.ndarray:
    # 生产策略：滑动窗口 + 噪声底缩放的运动门限。
    # 对每像素维护最近 n_win 个有效样本；新值偏离窗口估计 > 阈值 → 判定运动/场景变，清窗重启。
    # 估计 = 窗口中位数(use_median) 或均值。阈值按噪声底缩放(raw 单位)。
    n, h, w = seq.shape
    thresh = motion_thresh_mm * FRAC
    out = np.zeros_like(seq)
    # 逐像素环形缓冲用 list-of-deque 太慢，改用 (n_win,H,W) 样本栈 + 计数。
    samples = np.zeros((n_win, h, w), np.float32)
    cnt = np.zeros((h, w), np.int32)
    est = np.zeros((h, w), np.float32)
    for i in range(n):
        cur = seq[i]
        v = cur > 0
        # 运动判定：已有估计且新值偏离过大 → 清该像素窗口。
        moved = v & (cnt > 0) & (np.abs(cur - est) >= thresh)
        cnt[moved] = 0
        # 入窗（写到 cnt%n_win 槽）
        slot = np.mod(cnt, n_win)
        for s in range(n_win):
            sel = v & (slot == s)
            samples[s][sel] = cur[sel]
        cnt[v] = np.minimum(cnt[v] + 1, n_win)
        # 估计：对有效槽求均值/中位数
        fill = np.minimum(cnt, n_win)
        valid_mask = np.arange(n_win)[:, None, None] < fill[None, :, :]
        masked = np.where(valid_mask, samples, np.nan)
        with np.errstate(invalid="ignore"):
            if use_median:
                e = np.nanmedian(masked, axis=0)
            else:
                e = np.nanmean(masked, axis=0)
        est = np.where(fill > 0, np.nan_to_num(e), 0.0).astype(np.float32)
        out[i] = np.where(fill > 0, est, 0.0)
    return out.astype(np.float32)


def stability_mm(fused_mm: np.ndarray, warmup: int) -> float:
    # 融合输出自身的相邻帧抖动（warmup 之后），衡量"读数稳不稳"。
    return consec_median_abs_diff_mm(fused_mm[warmup:])


def bias_mm(seq_mm: np.ndarray, fused_mm: np.ndarray, warmup: int) -> float:
    # 融合是否引入系统偏移：后段融合估计与全程均值的逐像素差中位数。
    valid_all = np.all(seq_mm > 0, axis=0)
    if not valid_all.any():
        return float("nan")
    gt_mean = np.mean(seq_mm[:, valid_all], axis=0)
    tail = fused_mm[warmup:, valid_all]
    tail_mean = np.mean(tail, axis=0)
    return float(np.median(np.abs(tail_mean - gt_mean)))


def density(seq: np.ndarray) -> float:
    return float(np.mean(seq > 0))


def analyze(seq_raw: np.ndarray, label: str) -> dict:
    seq_mm = seq_raw / FRAC
    n = seq_raw.shape[0]
    warmup = min(8, n // 2)
    base_consec = consec_median_abs_diff_mm(seq_mm)
    base_pstd = per_pixel_temporal_std_mm(seq_mm)
    base_density = density(seq_raw)

    res = {
        "label": label,
        "frames": n,
        "shape": [int(seq_raw.shape[1]), int(seq_raw.shape[2])],
        "baseline": {
            "consec_median_abs_diff_mm": round(base_consec, 3),
            "per_pixel_temporal_std_mm": round(base_pstd, 3),
            "density": round(base_density, 4),
        },
        "strategies": {},
    }

    # A：累积均值
    a = fuse_cumulative_mean(seq_raw) / FRAC
    res["strategies"]["cumulative_mean"] = {
        "stability_mm": round(stability_mm(a, warmup), 3),
        "bias_mm": round(bias_mm(seq_mm, a, warmup), 3),
        "density": round(density(a), 4),
    }

    # B：有界滑动窗口 + 噪声底缩放运动门限（生产候选）。阈值取 1.5×噪声底。
    motion_thresh = 1.5 * base_consec
    for n_win in (4, 8, 16):
        for use_median in (False, True):
            b = fuse_windowed(seq_raw, n_win, motion_thresh, use_median) / FRAC
            key = f"win{n_win}_{'median' if use_median else 'mean'}"
            res["strategies"][key] = {
                "stability_mm": round(stability_mm(b, warmup), 3),
                "bias_mm": round(bias_mm(seq_mm, b, warmup), 3),
                "density": round(density(b), 4),
                "motion_thresh_mm": round(motion_thresh, 1),
            }

    # C：运动感知 EMA（对照：暴露小阈值失效陷阱）
    for thresh_mm in (15.0, 40.0):
        c = fuse_motion_aware_ema(seq_raw, thresh_mm, 0.2) / FRAC
        res["strategies"][f"ema_t{int(thresh_mm)}_a0.2"] = {
            "stability_mm": round(stability_mm(c, warmup), 3),
            "bias_mm": round(bias_mm(seq_mm, c, warmup), 3),
            "density": round(density(c), 4),
        }
    return res


def main() -> int:
    if len(sys.argv) < 3:
        print("用法: simulate_fusion.py <序列目录> <glob> [out_json]", file=sys.stderr)
        return 2
    seq_dir = Path(sys.argv[1])
    pattern = sys.argv[2]
    out_json = Path(sys.argv[3]) if len(sys.argv) > 3 else None
    seq = load_sequence(seq_dir, pattern, 640, 400)
    res = analyze(seq, f"{seq_dir.name}/{pattern}")
    text = json.dumps(res, ensure_ascii=False, indent=2)
    print(text)
    if out_json:
        out_json.parent.mkdir(parents=True, exist_ok=True)
        out_json.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
