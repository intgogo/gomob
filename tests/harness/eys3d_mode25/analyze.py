#!/usr/bin/env python3
# eys3d_mode25 analyze — 对 mode25 流出的 metric depthMm 帧出【可判定结论】:正常/警告/异常 + 原因。
#
# 判据(对标 finding:错模式吐"列恒定垃圾",正确模式深度有空间变化、值物理合理):
#   1. 有效率 = depthMm>0 占比(DepthFinalizer 对越界/0 视差出 0)。
#   2. 列恒定度 = median(列内std) / median(行内std)。垃圾=列恒定 → 列内std≈0 → 比值≈0。
#   3. 物理合理 = 有效像素 mm 中位数落 [150,6000](几何 fx·B/视差 在有效 11bit 视差区给 ~240mm-4m)。
import sys
import glob
import os
import struct
import statistics


def load_frame(path, w, h):
    data = open(path, "rb").read()
    n = w * h
    if len(data) < n * 2:
        return None
    vals = struct.unpack("<%dH" % n, data[: n * 2])
    return [vals[r * w:(r + 1) * w] for r in range(h)]  # rows


def col_row_constancy(rows, w, h):
    """返回 (median 列内std, median 行内std)。列内=沿列(变行);行内=沿行(变列)。"""
    # 仅在有效(>0)像素上算,避免大量 0 拉低 std。
    col_stds = []
    for c in range(w):
        col = [rows[r][c] for r in range(h) if rows[r][c] > 0]
        if len(col) >= 4:
            col_stds.append(statistics.pstdev(col))
    row_stds = []
    for r in range(h):
        row = [v for v in rows[r] if v > 0]
        if len(row) >= 4:
            row_stds.append(statistics.pstdev(row))
    cm = statistics.median(col_stds) if col_stds else 0.0
    rm = statistics.median(row_stds) if row_stds else 0.0
    return cm, rm


def analyze_one(rows, w, h):
    flat = [v for row in rows for v in row]
    valid = [v for v in flat if v > 0]
    valid_ratio = len(valid) / max(1, len(flat))
    med = statistics.median(valid) if valid else 0
    cm, rm = col_row_constancy(rows, w, h)
    constancy = cm / rm if rm > 1e-6 else 0.0  # 列恒定→趋 0
    return valid_ratio, med, cm, rm, constancy


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else ".dev/eys3d_mode25"
    w = int(sys.argv[2]) if len(sys.argv) > 2 else 640
    h = int(sys.argv[3]) if len(sys.argv) > 3 else 128
    files = sorted(glob.glob(os.path.join(out, "depthmm_*.bin")))
    if not files:
        print("异常: 无 depthMm 帧(mode25 未出 depth)。可能 mode25 PROBE/帧索引不符、或 AE arming 缺失。")
        return 1

    vrs, meds, consts = [], [], []
    for f in files:
        rows = load_frame(f, w, h)
        if rows is None:
            print(f"  {os.path.basename(f)}: 尺寸不足 {w}x{h},跳过")
            continue
        vr, med, cm, rm, const = analyze_one(rows, w, h)
        vrs.append(vr); meds.append(med); consts.append(const)
        print(f"  {os.path.basename(f)}: 有效率={vr:.1%} mm中位={med} 列内std={cm:.0f} 行内std={rm:.0f} 列恒定度={const:.2f}")

    if not vrs:
        print("异常: 帧尺寸全不符。检查 width/height(状态行?)。")
        return 1
    vr = statistics.median(vrs); med = statistics.median(meds); const = statistics.median(consts)
    print(f"\n汇总: 有效率={vr:.1%} mm中位={med} 列恒定度={const:.2f}")

    # 判定。三态:
    #   FAIL(return 1): 列恒定(ASIC 没真算视差) / 有效率过低 / mm 中位不在物理合理区(坏 scale 深度)。
    #     —— 物理合理性是硬判据:值落不到 [150,6000] 说明 scale/ZD 标定根本不对,不能当"质量待查"软放过。
    #   WARN(return 0): 三态保留位,当前所有可量化异常都属硬判据,无纯软警告档。
    #   正常(return 0): 全部判据通过。
    reasons = []
    phys_bad = not (150 <= med <= 6000)
    if vr < 0.30:
        reasons.append(f"有效率过低({vr:.1%}<30%)")
    if const < 0.15:
        reasons.append(f"列恒定(度{const:.2f}<0.15)= ASIC 没真算视差(错模式特征)")
    if phys_bad:
        reasons.append(f"mm 中位 {med} 不在物理合理区[150,6000]= 坏 scale 深度")

    if not reasons:
        print(f"\n✅ 正常: mode25(videoMode=36)硬件深度有效,深度有空间变化、值物理合理。")
        return 0
    # 任一硬判据触发即 FAIL。坏 scale 深度不再被软警告吞成 exit 0。
    print(f"\n❌ 异常: {'; '.join(reasons)}")
    if const < 0.15 or vr < 0.30:
        print("   → 若列恒定仍在:确认 videoMode=36 已写入、帧索引对、再查 AE arming(ASIC 进 live-depth 最后一环)。")
    if phys_bad:
        print("   → mm 中位越界:查 scale/ZD 表是否注入、视差→深度的 fx·B 几何参数是否对。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
