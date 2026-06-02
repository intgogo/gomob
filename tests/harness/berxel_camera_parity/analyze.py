#!/usr/bin/env python3
# berxel_camera_parity analyze — 分辨率无关的【统一 open_host vs 原厂 SDK】深度【分布】parity。
#
# 为何分布级而非逐像素:原厂 SDK 实证档位 640×400、我们 open_host 生产档 1280×800,逐像素网格不对齐;
# 但同一【静态场景】下深度的 median/p10/p90 与分辨率无关。两侧分布一致 = 统一抽象产出 = 原厂产出。
#
# 量纲:vendor raw16 ÷8 = mm(13I3D);open_host depthmm 已是 mm(÷1)。
import sys
import glob
import os
import struct


def load_u16(path):
    d = open(path, "rb").read()
    cnt = len(d) // 2
    return struct.unpack("<%dH" % cnt, d[: cnt * 2])


def dist_mm(files, scale):
    vals = []
    tot = 0
    for f in files:
        v = load_u16(f)
        tot += len(v)
        vals += [x / scale for x in v if x > 0]
    if not vals:
        return None
    vals.sort()
    q = lambda p: vals[min(len(vals) - 1, int(p * len(vals)))]
    return {
        "valid_ratio": len(vals) / tot if tot else 0.0,
        "p10": q(0.10), "p50": q(0.50), "p90": q(0.90),
        "mean": sum(vals) / len(vals),
    }


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else ".dev/berxel_camera_parity"
    vfiles = sorted(glob.glob(os.path.join(out, "vendor-dense", "vendor-depth-*.raw")))
    hfiles = sorted(glob.glob(os.path.join(out, "host-openhost", "depthmm_*.bin")))
    if not vfiles:
        print("异常: 无 vendor 帧（原厂 SDK 没采到，看 vendor.log）。")
        return 1
    if not hfiles:
        print("异常: 无 open_host 帧（看 host.log）。")
        return 1

    v = dist_mm(vfiles, 8.0)
    h = dist_mm(hfiles, 1.0)
    if not v or not h:
        print("异常: 某侧全无有效深度。")
        return 1

    print(f"原厂SDK  : p10={v['p10']:.0f} p50={v['p50']:.0f} p90={v['p90']:.0f} mean={v['mean']:.0f}mm "
          f"有效率={v['valid_ratio']:.1%}  ({len(vfiles)}帧)")
    print(f"open_host: p10={h['p10']:.0f} p50={h['p50']:.0f} p90={h['p90']:.0f} mean={h['mean']:.0f}mm "
          f"有效率={h['valid_ratio']:.1%}  ({len(hfiles)}帧)")

    rel = abs(v["p50"] - h["p50"]) / v["p50"] if v["p50"] else 1.0
    rel90 = abs(v["p90"] - h["p90"]) / max(v["p90"], 1.0)
    print(f"\nmedian 相对偏差={rel:.1%}  p90 相对偏差={rel90:.1%}")

    reasons = []
    if rel > 0.05:
        reasons.append(f"median 偏差 {rel:.1%}>5% (原厂{v['p50']:.0f} vs open_host{h['p50']:.0f}mm)")
    if rel90 > 0.12:
        reasons.append(f"p90 偏差 {rel90:.1%}>12% (分布尾不一致)")
    if min(v["valid_ratio"], h["valid_ratio"]) < 0.20:
        reasons.append(f"有效率过低(原厂{v['valid_ratio']:.1%} open_host{h['valid_ratio']:.1%})")

    if not reasons:
        print("\n✅ 正常: 统一 open_host 深度分布与原厂 SDK 一致(median/p90 对齐)。"
              "→ 不退化:厂商无关抽象层产出 = 原厂 SDK 产出。")
        return 0
    print(f"\n❌ 异常: {'; '.join(reasons)}")
    print("   → 同一静态场景? 若整体偏移查 scale/像素格式(13I3D=÷8);若一侧空查采集日志。")
    return 1


if __name__ == "__main__":
    sys.exit(main())
