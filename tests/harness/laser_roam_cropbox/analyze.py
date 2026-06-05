#!/usr/bin/env python3
"""laser_roam_cropbox/analyze.py — 读 RoamBoxFitTest JUnit XML，判定「走一圈→车位框 OBB」几何健康度。

对应 RoamBoxFitTest 四例：
  axisAlignedFootprint_recoversDims        轴对齐 footprint 尺寸恢复
  rotatedFootprint_recoversDimsAndContains 旋转尺寸恢复 + 框紧包合成车点云
  farPointsExcluded                        远处背景点被框排除
  degeneratePath_returnsNull               点不足/共线零面积回 null
全过=正常；任一失败=异常（非 advisory：几何错会让保存的车位框偏，必须红）。
"""
import os
import sys
import glob
import xml.etree.ElementTree as ET


def _cases(out_dir):
    for x in sorted(glob.glob(os.path.join(out_dir, "TEST-*.xml"))):
        root = ET.parse(x).getroot()
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for s in suites:
            for tc in s.findall("testcase"):
                bad = tc.findall("failure") or tc.findall("error")
                yield tc.get("name"), (bad[0].get("message") if bad else None)


def main():
    out = sys.argv[1] if len(sys.argv) > 1 else ".dev/laser-roam-cropbox"
    cases = list(_cases(out))
    if not cases:
        print(f"[异常] 未找到 JUnit 结果 XML：{out}（gradle 测试是否跑成功？看 gradle.log）")
        return 1

    print(f"{'test':<46} verdict")
    print("-" * 64)
    failed = []
    for name, msg in cases:
        ok = msg is None
        if not ok:
            failed.append((name, msg))
        print(f"{name:<46} {'正常' if ok else '异常'}")

    print()
    verdict = "正常" if not failed else "异常"
    print(f"summary: total={len(cases)} pass={len(cases) - len(failed)} fail={len(failed)} → {verdict}")
    for name, msg in failed:
        print(f"  ✗ {name}: {msg}")
    return 0 if not failed else 1


if __name__ == "__main__":
    sys.exit(main())
