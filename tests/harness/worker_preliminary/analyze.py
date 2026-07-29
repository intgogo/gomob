#!/usr/bin/env python3
"""worker_preliminary/analyze.py — M-S5.3 worker 端到端判定。"""

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
        "S1.asset_upload",
        "S2.vinref_seeded",
        "S3.inspection_created",
        "S5.verdict_pass",
        "S6.status_preliminary",
        "S7.reasons_present",
        "S8.worker_log_completed",
        "S9.audit_log_recorded",
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
        line = f"{sym} {n:<35} {note}"
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
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/worker_preliminary"
    sys.exit(main(out))
