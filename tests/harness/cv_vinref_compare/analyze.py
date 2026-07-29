#!/usr/bin/env python3
"""cv_vinref_compare/analyze.py — M-S10 Phase 2.2 cv-engine ↔ vin-ref 端到端判定。"""

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
        "S0a.create_vehicle_model",
        "S0b.create_batch",
        "S0c.create_sample_A",
        "S0d.publish_batch",
        "S1.compare_with_ref_identical",
        "S1b.identical_iou_high",
        "S2.compare_with_ref_shift",
        "S2b.shift_iou_mid",
        "S3.compare_with_ref_diff",
        "S3b.diff_iou_low",
        "S4.sim_sort_identical_gt_shift_gt_diff",
        "S5.compare_with_ref_chamfer",
        "S5b.chamfer_identical_low",
        "S6.compare_with_ref_threshold_pass",
        "S6b.threshold_pass_above",
        "S7.compare_with_ref_threshold_fail",
        "S7b.threshold_below",
        "S8.unknown_vmid_40701",
        "S9.no_sample_for_char_40701",
        "S10.invalid_char_I_10001",
        "S11.missing_image_10001",
        "S12.vinref_returns_alpha_url",
        "S12b.alpha_url_present",
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
        lat = r.get("latency_ms", 0)
        note = r.get("note") or ""
        line = f"{sym} {n:<46} http={r['http_code']:>3}  code={r['code']!s:<6}  {lat:>4}ms"
        if note:
            line += f"  {note}"
        print(line)
        if not ok:
            fails.append(n)

    lats = [r["latency_ms"] for r in rows if r.get("latency_ms")]
    if lats:
        sl = sorted(lats)
        print(f"\nlatency p50={sl[len(sl)//2]}ms  p95={sl[int(len(sl)*0.95)]}ms  max={max(lats)}ms  n={len(lats)}")
    print()
    if fails:
        print(f"✗ 异常：{len(fails)} 项失败")
        for n in fails:
            r = by[n]
            print(f"  - {n}: http={r['http_code']} code={r['code']} (期望 http={r['expected_http']} code={r['expected_code']})  {r.get('note','')}")
        return 1
    print(f"✓ 正常：{len(must)} 项全通过")
    return 0


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/cv_vinref_compare"
    sys.exit(main(out))
