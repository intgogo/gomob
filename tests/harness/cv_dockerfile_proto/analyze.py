#!/usr/bin/env python3
"""cv_dockerfile_proto/analyze.py — M-S10.6 Dockerfile + proto 静态校验。"""

import json
import os
import sys


def main(out_dir: str) -> int:
    rf = os.path.join(out_dir, "results.jsonl")
    if not os.path.exists(rf):
        print(f"✗ 缺 {rf}", file=sys.stderr)
        return 2
    rows = []
    with open(rf) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))
    if not rows:
        print("✗ results.jsonl 为空")
        return 2
    by = {r["scenario"]: r for r in rows}

    must = [
        "A1.dockerfile_exists",
        "A2.multi_stage_builder_runtime",
        "A3.opencv_install",
        "A4.onnxruntime_install",
        "A5.libccv_copy",
        "A6.ld_library_path",
        "A7.healthcheck",
        "A8.expose_18810",
        "B1.proto_exists",
        "B2.syntax_proto3",
        "B3.go_package_correct",
        "B4.service_cvengine",
        "B5.five_rpcs_defined",
        "B6.font_dist_enum",
        "C1.proto_gen_executable",
        "C2.proto_gen_reports_missing_protoc",
    ]
    missing = [m for m in must if m not in by]
    if missing:
        print(f"✗ 缺场景: {missing}")
        return 1

    fails = []
    for n in must:
        r = by[n]
        ok = r["ok"]
        sym = "✓" if ok else "✗"
        note = r.get("note") or ""
        line = f"{sym} {n:<45} {note}"
        print(line)
        if not ok:
            fails.append(n)

    print()
    if fails:
        print(f"✗ 异常：{len(fails)} 项失败")
        for n in fails:
            r = by[n]
            print(f"  - {n}: {r.get('note','')}")
        return 1
    print(f"✓ 正常：{len(must)} 项全通过")
    return 0


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/cv_dockerfile_proto"
    sys.exit(main(out))
