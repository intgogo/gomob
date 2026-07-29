#!/usr/bin/env python3
"""判定 schema_version=2 bundle 是否被服务端真实消费。"""
import json
import os
import sys


def main() -> int:
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))),
        ".dev", "scan_bundle_roundtrip")
    path = os.path.join(out_dir, "result.json")
    if not os.path.exists(path):
        print(f"异常 — 缺少采样结果：{path}"); return 1
    with open(path, encoding="utf-8") as fh: result = json.load(fh)
    if "unpack_error" in result:
        print(f"异常 — 新 bundle 解包失败：{result['unpack_error']}"); return 1
    errors = []
    if not result.get("has_calibration_bin") or not result.get("calibration_sha_match"):
        errors.append("calibration.bin 缺失或 SHA 不匹配")
    if result.get("frames") != result["expected_frames"]:
        errors.append("帧数不一致")
    if (result.get("got_width"), result.get("got_height")) != (result["width"], result["height"]):
        errors.append("深度 profile 尺寸未还原")
    if abs(float(result.get("got_fx", 0)) - 614.60498046875) > 1e-6 or abs(float(result.get("got_cx", 0)) - 324) > 1e-6:
        errors.append("BIN 深度内参未还原")
    if abs(float(result.get("got_mm", -1)) - result["expected_mm"]) > 1e-3:
        errors.append("raw disparity 没有按 VINCreator 公式解码")
    if result.get("conf_got") != 255:
        errors.append("confidence 未保留")
    if errors:
        print("异常 — " + "；".join(errors)); return 1
    if result.get("fuse_error"):
        print(f"警告 — bundle 契约通过，但融合依赖不可用：{result['fuse_error']}"); return 0
    if result.get("mesh_vertices", 0) <= 0:
        print("异常 — 新 bundle 融合结果为空"); return 1
    print(f"正常 — calibration.bin + 原始 disparity 已完成跨语言配准并生成 {result['mesh_vertices']} 个顶点")
    return 0


if __name__ == "__main__":
    sys.exit(main())
