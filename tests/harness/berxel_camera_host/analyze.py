#!/usr/bin/env python3
# berxel_camera_host analyze — 对 open_host 出的 metric depthMm 帧出【可判定结论】:正常/异常 + 原因。
# 判据:① 有效率=depthMm>0 占比;② mm 中位落 P100R3 有效区[150,8000]mm(0.2-8m 规格)。
import sys
import glob
import os
import struct
import statistics


def load(path, w, h):
    d = open(path, "rb").read()
    n = w * h
    if len(d) < n * 2:
        return None
    return struct.unpack("<%dH" % n, d[: n * 2])


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else ".dev/berxel_camera_host"
    w = int(sys.argv[2]) if len(sys.argv) > 2 else 1280
    h = int(sys.argv[3]) if len(sys.argv) > 3 else 800
    files = sorted(glob.glob(os.path.join(out, "depthmm_*.bin")))
    if not files:
        print("异常: 无 depthMm 帧 → open_host 没出 depth(看 LOGE: 枚举/权限/setup_dual/keepalive)。")
        return 1

    vrs, meds = [], []
    for f in files:
        v = load(f, w, h)
        if v is None:
            print(f"  {os.path.basename(f)}: 尺寸不足 {w}x{h},跳过")
            continue
        valid = [x for x in v if x > 0]
        vr = len(valid) / len(v)
        med = statistics.median(valid) if valid else 0
        vrs.append(vr)
        meds.append(med)
        print(f"  {os.path.basename(f)}: 有效率={vr:.1%} mm中位={med}")

    if not vrs:
        print("异常: 帧尺寸全不符。")
        return 1
    vr = statistics.median(vrs)
    med = statistics.median(meds)
    print(f"\n汇总: 有效率={vr:.1%} mm中位={med}")

    reasons = []
    if vr < 0.20:
        reasons.append(f"有效率过低({vr:.1%}<20%)")
    if not (150 <= med <= 8000):
        reasons.append(f"mm中位 {med} 不在 P100R3 有效区[150,8000]")

    if not reasons:
        print("\n✅ 正常: host open_host 统一路径出 metric depth,有效且物理合理(与 Android open_fd 同 setup 序列)。")
        return 0
    print(f"\n❌ 异常: {'; '.join(reasons)}")
    print("   → depth 出了但质量待查:确认 dense controls/keepalive/帧索引;或服务器供电/USB2 链路。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
