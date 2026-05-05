#!/usr/bin/env python3
"""admin_lifecycle/analyze.py — 读 results.jsonl 给最终判定。"""

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
        "S1.no_auth_40102",
        "S2.inspector_403",
        "S4.list_pending",
        "S4b.pending_count",
        "S5.approve_alice",
        "S6.reject_bob",
        "S7.repeat_approve_40401",
        "S8.patch_role_reviewer",
        "S9.invalid_role_10002",
        "S10.disable_alice",
        "S11.proxy_catalog",
        "S12a.proxy_model_create",
        "S12b.proxy_model_activate",
        "S13a.proxy_llm_create",
        "S13b.proxy_llm_activate",
        "S14.audit_all",
        "S14b.audit_count",
        "S15.audit_action_exact",
        "S15b.action_exact_count",
        "S16.audit_ilike",
        "S16b.ilike_count",
        "S17.audit_by_user",
        "S17b.by_user_count",
        "S18.audit_by_from",
        "S18b.from_count",
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
        line = f"{sym} {n:<28} http={r['http_code']:>3}  code={r['code']!s:<6}  {lat:>4}ms"
        if note:
            line += f"  {note}"
        print(line)
        if not ok:
            fails.append(n)

    lats = [r["latency_ms"] for r in rows if r.get("latency_ms")]
    if lats:
        sl = sorted(lats)
        print(
            f"\nlatency p50={sl[len(sl)//2]}ms  p95={sl[int(len(sl)*0.95)]}ms  max={max(lats)}ms  n={len(lats)}"
        )

    print()
    if fails:
        print(f"✗ 异常：{len(fails)} 项失败")
        for n in fails:
            r = by[n]
            print(
                f"  - {n}: http={r['http_code']} code={r['code']} "
                f"(期望 http={r['expected_http']} code={r['expected_code']})  {r.get('note','')}"
            )
        return 1
    print(f"✓ 正常：{len(must)} 项全通过")
    return 0


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/admin_lifecycle"
    sys.exit(main(out))
