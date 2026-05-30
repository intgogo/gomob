#!/usr/bin/env python3
# 时域降噪 harness 判定：对比 C++ P100R3TemporalFilter 融合前后的
# 相邻帧稳定性(stability)、系统偏移(bias)、密度(density)，输出 正常/警告/异常 + 原因。
#
# 单测验证融合数学；本 harness 验证在真实录制序列上的"行为好不好"。
# 用法：analyze.py <in_dir> <in_suffix> <fused_dir> <width> <height> [out_json]
import json
import sys
from pathlib import Path
from statistics import mean, median

import numpy as np

FRAC = 8.0  # 13I3D: mm = raw/8

# 判定阈值
MIN_STABILITY_GAIN = 2.0   # 融合后相邻帧抖动至少降到原来的 1/2
WARN_STABILITY_GAIN = 3.0  # 达到 3× 才算理想
MAX_BIAS_MM = 8.0          # 融合估计相对全序列均值的中位偏移上限
MAX_DENSITY_DROP = 0.02    # 密度下降不超过 2 个百分点


def load_seq(dir_path: Path, suffix: str, w: int, h: int) -> np.ndarray:
    files = sorted(p for p in dir_path.iterdir() if p.name.endswith(suffix))
    frames = []
    for f in files:
        buf = np.fromfile(f, dtype="<u2")
        if buf.size == w * h:
            frames.append(buf.reshape(h, w))
    if not frames:
        raise SystemExit(f"无可用帧: {dir_path}/*{suffix}")
    return np.stack(frames).astype(np.float32)


def consec_median_abs_diff_mm(seq_mm: np.ndarray) -> float:
    diffs = []
    for i in range(1, seq_mm.shape[0]):
        a, b = seq_mm[i - 1], seq_mm[i]
        m = (a > 0) & (b > 0)
        if m.any():
            diffs.append(np.abs(a[m] - b[m]))
    if not diffs:
        return float("nan")
    return float(np.median(np.concatenate(diffs)))


def density(seq: np.ndarray) -> float:
    return float(np.mean(seq > 0))


def bias_and_deviation_mm(raw_mm: np.ndarray, fused_mm: np.ndarray, warmup: int):
    # 均值滤波器不应移动每像素的时间均值。
    # bias = 有符号差均值的绝对值（零均值噪声相消，只剩系统偏移）。
    # deviation = |差| 的中位数（含估计残余噪声，仅作诊断不作门）。
    valid_all = np.all(raw_mm > 0, axis=0)
    if not valid_all.any():
        return float("nan"), float("nan")
    gt = np.mean(raw_mm[:, valid_all], axis=0)
    tail = np.mean(fused_mm[warmup:, valid_all], axis=0)
    diff = tail - gt
    return float(abs(np.mean(diff))), float(np.median(np.abs(diff)))


def fmt(v, n=2):
    return "n/a" if v is None or (isinstance(v, float) and np.isnan(v)) else f"{v:.{n}f}"


def main() -> int:
    if len(sys.argv) < 6:
        print("用法: analyze.py <in_dir> <in_suffix> <fused_dir> <width> <height> [out_json]",
              file=sys.stderr)
        return 2
    in_dir, in_suffix, fused_dir = Path(sys.argv[1]), sys.argv[2], Path(sys.argv[3])
    w, h = int(sys.argv[4]), int(sys.argv[5])
    out_json = Path(sys.argv[6]) if len(sys.argv) > 6 else None

    raw = load_seq(in_dir, in_suffix, w, h)
    fused = load_seq(fused_dir, ".raw", w, h)
    # fused 含 conf-*.raw，需排除：只取 fused-*.raw
    fused_files = sorted(p for p in fused_dir.iterdir() if p.name.startswith("fused-"))
    fused = np.stack([np.fromfile(p, dtype="<u2").reshape(h, w) for p in fused_files]).astype(np.float32)

    n = min(raw.shape[0], fused.shape[0])
    raw, fused = raw[:n], fused[:n]
    raw_mm, fused_mm = raw / FRAC, fused / FRAC
    warmup = min(8, n // 2)

    base_stab = consec_median_abs_diff_mm(raw_mm[warmup:])
    fused_stab = consec_median_abs_diff_mm(fused_mm[warmup:])
    gain = base_stab / fused_stab if fused_stab and fused_stab > 0 else float("inf")
    b, dev = bias_and_deviation_mm(raw_mm, fused_mm, warmup)
    raw_dens, fused_dens = density(raw), density(fused)
    dens_drop = raw_dens - fused_dens

    fail, warn = [], []
    if not (gain >= MIN_STABILITY_GAIN):
        fail.append(f"稳定性增益不足: {fmt(gain)}× (需≥{MIN_STABILITY_GAIN}×, "
                    f"base={fmt(base_stab)}mm fused={fmt(fused_stab)}mm)")
    elif gain < WARN_STABILITY_GAIN:
        warn.append(f"稳定性增益偏低: {fmt(gain)}× (<{WARN_STABILITY_GAIN}× 理想)")
    if b is not None and not np.isnan(b) and b > MAX_BIAS_MM:
        fail.append(f"系统偏移过大: {fmt(b)}mm (>{MAX_BIAS_MM}mm)")
    if dens_drop > MAX_DENSITY_DROP:
        fail.append(f"密度下降过多: {fmt(dens_drop*100,1)}pp (>{MAX_DENSITY_DROP*100:.0f}pp)")

    status = "FAIL" if fail else ("WARN" if warn else "OK")
    verdict = "；".join(fail or warn or ["时域降噪显著降低相邻帧抖动、无偏移、密度不退化"])

    result = {
        "status": status,
        "verdict": verdict,
        "frames": n,
        "warmup": warmup,
        "baseline_stability_mm": round(base_stab, 3),
        "fused_stability_mm": round(fused_stab, 3),
        "stability_gain_x": round(gain, 3) if gain != float("inf") else None,
        "bias_mm": round(b, 3) if b is not None and not np.isnan(b) else None,
        "estimate_deviation_mm": round(dev, 3) if dev is not None and not np.isnan(dev) else None,
        "raw_density": round(raw_dens, 4),
        "fused_density": round(fused_dens, 4),
        "density_drop_pp": round(dens_drop * 100, 3),
    }
    lines = [
        "# Depth Temporal Quality Harness",
        f"- 状态: {status}",
        f"- 结论: {verdict}",
        f"- 帧数/warmup: {n}/{warmup}",
        f"- 相邻帧抖动 base→fused: {fmt(base_stab)} → {fmt(fused_stab)} mm（增益 {fmt(gain)}×）",
        f"- 系统偏移 bias: {fmt(b)} mm；估计残余偏差: {fmt(dev)} mm",
        f"- 密度 raw/fused: {fmt(raw_dens,4)} / {fmt(fused_dens,4)}",
    ]
    print("\n".join(lines))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    if out_json:
        out_json.parent.mkdir(parents=True, exist_ok=True)
        out_json.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return 0 if status == "OK" else (1 if status == "WARN" else 2)


if __name__ == "__main__":
    raise SystemExit(main())
