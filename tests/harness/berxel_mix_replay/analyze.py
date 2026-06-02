#!/usr/bin/env python3
# 读 berxel_mix_replay 采样,出可判定结论:并发 color+depth(MIX)行为好不好。
#
# 判据(第一性,阈值取自原厂 MIX 实测基线 depth~147/color~1003/pairs~147/metric 298mm @5s,留足余量):
#   异常 ❌  任一:color 帧=0 / depth 帧=0 / color errors>0 / depth errors>0 / depth metric 不可信
#   警告 ⚠   流都在但:RGBD 对<20 / depth 中心有效率<0.8 / 帧率明显偏低(depth<50 或 color<50 /5s)
#   正常 ✅  两条流都稳定出帧 + 0 错 + depth 是 metric(中心中位数∈[100,8000]mm,有效率≥0.8)+ RGBD 对≥20
import sys, os, re, struct, statistics

OUT = sys.argv[1] if len(sys.argv) > 1 else ".dev/berxel_mix_replay"


def parse_stats(text, name):
    """从 RESULT 段抽某条流的 frames/bytes/errors。"""
    m = re.search(r'^%s:\n((?:  .*\n)+)' % re.escape(name), text, re.M)
    if not m:
        return None
    body = m.group(1)
    def g(k):
        mm = re.search(r'%s\s*=\s*(-?\d+)' % k, body)
        return int(mm.group(1)) if mm else None
    return {"frames": g("frames"), "bytes": g("bytes"), "errors": g("errors")}


def parse_pairs(text):
    m = re.search(r'rgbd_pairs:\n((?:  .*\n)+)', text, re.M)
    if not m:
        return None
    body = m.group(1)
    p = re.search(r'pairs\s*=\s*(\d+)', body)
    d = re.search(r'mean_abs_ms\s*=\s*([\d.]+)', body)
    return {"pairs": int(p.group(1)) if p else 0,
            "mean_abs_ms": float(d.group(1)) if d else -1.0}


def depth_metric(path):
    """640x401 transport RAW16,剥状态行首行,raw/8=mm;算中心 40x40 中位数 + 有效率。"""
    if not os.path.exists(path):
        return None
    data = open(path, 'rb').read()
    n = len(data) // 2
    if n < 640:
        return None
    u16 = struct.unpack('<%dH' % n, data[:n * 2])
    W = 640
    H = n // W
    rows = u16[W:] if H == 401 else u16        # 剥状态行
    act_h = len(rows) // W
    cx, cy = 320, act_h // 2
    cen = []
    for y in range(max(0, cy - 20), min(act_h, cy + 20)):
        for x in range(cx - 20, cx + 20):
            v = rows[y * W + x] / 8.0
            if 0 < v < 8000:
                cen.append(v)
    cen_total = 40 * 40
    allv = [v / 8.0 for v in rows if 0 < v / 8.0 < 8000]
    return {
        "active_h": act_h,
        "center_valid_ratio": len(cen) / cen_total,
        "center_median_mm": statistics.median(cen) if cen else -1.0,
        "full_valid_ratio": len(allv) / len(rows) if rows else 0.0,
        "full_median_mm": statistics.median(allv) if allv else -1.0,
    }


def color_jpeg_ok(path):
    if not os.path.exists(path):
        return False
    b = open(path, 'rb').read(4)
    return len(b) >= 2 and b[0] == 0xFF and b[1] == 0xD8   # JPEG SOI


def main():
    result_txt = os.path.join(OUT, "result.txt")
    text = open(result_txt).read() if os.path.exists(result_txt) else ""
    depth = parse_stats(text, "depth")
    color = parse_stats(text, "color")
    pairs = parse_pairs(text)
    dm = depth_metric(os.path.join(OUT, "depth-first.raw"))
    cj = color_jpeg_ok(os.path.join(OUT, "color-first.jpg"))

    print("=" * 60)
    print("berxel_mix_replay —— 并发 color+depth(MIX)行为分析")
    print("=" * 60)
    print(f"depth : {depth}")
    print(f"color : {color}  jpeg_ok={cj}")
    print(f"pairs : {pairs}")
    print(f"metric: {dm}")
    print("-" * 60)

    problems, warns = [], []
    # 硬异常
    if not depth or not depth["frames"]:
        problems.append("depth 0 帧(并发下深度流死)")
    if not color or not color["frames"]:
        problems.append("color 0 帧(并发下彩色流死)")
    if depth and depth["errors"]:
        problems.append(f"depth errors={depth['errors']}")
    if color and color["errors"]:
        problems.append(f"color errors={color['errors']}")
    if not cj:
        problems.append("color 首帧不是合法 JPEG")
    if dm:
        med = dm["center_median_mm"]
        if not (100 <= med <= 8000):
            problems.append(f"depth 中心中位数 {med:.0f}mm 不在 [100,8000](非 metric/坏帧)")
    else:
        problems.append("无 depth-first.raw,无法验 metric")
    # 警告
    if depth and depth["frames"] and depth["frames"] < 50:
        warns.append(f"depth 帧率偏低 {depth['frames']}/5s")
    if color and color["frames"] and color["frames"] < 50:
        warns.append(f"color 帧率偏低 {color['frames']}/5s")
    if pairs and pairs["pairs"] < 20:
        warns.append(f"RGBD 对偏少 {pairs['pairs']}")
    if dm and dm["center_valid_ratio"] < 0.8:
        warns.append(f"depth 中心有效率偏低 {dm['center_valid_ratio']:.2f}")

    if problems:
        print("❌ 异常:")
        for p in problems:
            print("   -", p)
        if warns:
            print("⚠  另有警告:", "; ".join(warns))
        return 1
    if warns:
        print("⚠  警告(流通但质量待观察):")
        for w in warns:
            print("   -", w)
        return 0
    print("✅ 正常:并发 color+depth 稳定出帧,0 错,深度 metric,RGBD 时间对齐。")
    print(f"   color {color['frames']} 帧 / depth {depth['frames']} 帧 / RGBD {pairs['pairs']} 对 / "
          f"中心 {dm['center_median_mm']:.0f}mm(有效率 {dm['center_valid_ratio']:.2f})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
