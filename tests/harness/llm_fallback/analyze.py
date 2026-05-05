#!/usr/bin/env python3
"""llm_fallback/analyze.py — M-S11.7 LLM provider failover 判定。"""

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
        "S0b.fallback_log_present",
        "S1.template_active",
        "S2.fallback_chat_ok",
        "S2b.fallback_content_present",
        "S2c.fallback_or_mock_used",
        "S3.fallback_triggered_in_log",
        "S4.stream_fallback_full_sse",
        "S5.explicit_provider_mock",
        "S5b.explicit_mock_no_fallback",
        "S6.explicit_deepseek_fails_no_fallback",
        "S7.audit_ok_records",
        "S8.no_chain_fails_correctly",
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
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/llm_fallback"
    sys.exit(main(out))
