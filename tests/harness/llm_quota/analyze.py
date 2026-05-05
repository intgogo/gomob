#!/usr/bin/env python3
"""llm_quota/analyze.py — M-S11.6 LLM 配额判定。"""

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
        "S0.services_up",
        "S0b.quota_log_present",
        "S1.template_active",
        "S2.userA_call_1_ok",
        "S2.userA_call_2_ok",
        "S2.userA_call_3_ok",
        "S3.userA_call_4_quota_exceeded",
        "S4.userB_isolated_from_A",
        "S5.redis_counts_correct",
        "S6.tpl_call_1_ok",
        "S6b.tpl_call_2_ok",
        "S7.tpl_call_3_quota_exceeded",
        "S8.disabled_quota_all_pass",
        "S9.bad_redis_still_starts",
        "S9b.bad_redis_calls_pass",
        "S9c.bad_redis_logged",
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
        line = f"{sym} {n:<40} {note}"
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
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/llm_quota"
    sys.exit(main(out))
