#!/usr/bin/env python3
"""cv_vin_pipeline/analyze.py — M-S10 Phase 2.x 一站式 VIN pipeline 判定。"""

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
        "S0.vmask_available",
        "S0b.cvengine_up",
        "S1.pipeline_synthetic_ok",
        "S2.image_rows_cols_real",
        "S3.detections_scored_nonneg",
        "S4.tag_echoed",
        "S5.default_thresholds",
        "S6.log_id_assigned",
        "S7.characters_array_present",
        "S8.zero_dets_verdict_fail_or_valid_verdict",
        "S9.custom_thresholds",
        "S9b.custom_thresholds_reflected",
        "S10.tag_not_registered_40701",
        "S11.missing_image_10001",
        "S12.missing_vmid_10001",
        "S13.invalid_vmid_10001",
        "S14.invalid_method_10001",
        "S15.dev_no_auth",
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
        line = f"{sym} {n:<50} {note}"
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
    # M12.4 路径参数化：优先 argv，其次 OUTPUT_DIR 环境变量，最后退仓库相对默认。
    _repo = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", ".."))
    _default = os.environ.get("OUTPUT_DIR") or os.path.join(_repo, ".dev", "cv_vin_pipeline")
    out = sys.argv[1] if len(sys.argv) > 1 else _default
    sys.exit(main(out))
