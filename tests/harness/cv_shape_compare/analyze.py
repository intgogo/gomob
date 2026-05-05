#!/usr/bin/env python3
"""cv_shape_compare/analyze.py — M-S9.x cv-engine shape_compare 判定。"""

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
        "S0.all_services_up",
        "S1.shape_active_seeded",
        "S2.perfect_match_200",
        "S3.perfect_verdict_pass",
        "S4.perfect_iou_one",
        "S5.shifted_bbox_200",
        "S6.shifted_verdict_warning",
        "S7.bad_match_200",
        "S8.bad_verdict_fail",
        "S9.no_bbox_200",
        "S10.no_bbox_reason_present",
        "S11.no_bbox_metric_flag",
        "S12.custom_threshold",
        "S12b.custom_threshold_reflected",
        "S13.no_active_40701",
        "S14.missing_vmid_10001",
        "S15.invalid_vmid_10001",
        "S16.bad_json_10001",
        "S17.ref_meta_echoed",
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
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/cv_shape_compare"
    sys.exit(main(out))
