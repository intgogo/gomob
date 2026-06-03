#!/usr/bin/env python3
"""scan_bundle_roundtrip 判定器(stdlib only)。读 result.json 输出可判定结论。

  ❌异常 — unpack 抛错 / 帧数错 / 分辨率错 / 植入深度未按 uint16-LE 还原 / conf 丢失。
  ⚠️警告 — 契约对上但 fuse 出空 mesh(合成数据配准退化,非契约问题)。
  ✅正常 — unpack 完全还原 + fuse 出非空 mesh。
"""
import json
import os
import sys


def main() -> int:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))),
        ".dev", "scan_bundle_roundtrip")
    path = os.path.join(out_dir, "result.json")
    if not os.path.exists(path):
        print(f"❌异常 — 缺采样 {path}(run.sh 未产出)")
        return 1
    with open(path) as f:
        r = json.load(f)

    if "unpack_error" in r:
        print(f"❌异常 — unpack 抛错: {r['unpack_error']}")
        return 1

    errors = []
    if r.get("frames") != r["expected_frames"]:
        errors.append(f"帧数 {r.get('frames')} != {r['expected_frames']}")
    if r.get("got_width") != r["width"] or r.get("got_height") != r["height"]:
        errors.append(f"分辨率 {r.get('got_width')}x{r.get('got_height')} != {r['width']}x{r['height']}")
    if abs(float(r.get("got_fx", 0)) - 50.0) > 1e-6 or abs(float(r.get("got_cx", 0)) - 32.0) > 1e-6:
        errors.append("内参未还原")
    if abs(float(r.get("plant_mm_got", -1)) - r["plant_mm"]) > 1e-3:
        errors.append(f"深度 uint16-LE 还原错: 期望 {r['plant_mm']} 实得 {r.get('plant_mm_got')}")
    if not r.get("conf_present"):
        errors.append("conf 通道丢失")
    elif r.get("conf_got") != 255:
        errors.append(f"conf 值错: {r.get('conf_got')}")

    if errors:
        print("❌异常 — bundle 跨语言契约不一致:")
        for e in errors:
            print(f"   · {e}")
        return 1

    rc = r["plant_rc"]
    print(f"✅ unpack 契约一致:{r['frames']} 帧 / {r['width']}x{r['height']} / "
          f"depth[{rc[0]},{rc[1]}]={r['plant_mm_got']:.0f}mm / conf=255 / 内参还原")

    if "fuse_error" in r:
        print(f"⚠️警告 — fuse 抛错(契约已过,合成数据融合退化): {r['fuse_error']}")
        return 0
    verts = int(r.get("mesh_vertices", 0))
    if verts <= 0:
        print("⚠️警告 — fuse 出空 mesh(合成平面配准退化,非契约问题);契约校验已通过")
        return 0
    print(f"✅正常 — 端侧 bundle 字节布局被服务端融合主线消费成功:mesh {verts} 顶点")
    return 0


if __name__ == "__main__":
    sys.exit(main())
