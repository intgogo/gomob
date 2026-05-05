#!/usr/bin/env python3
"""llm_streaming/analyze.py — 读 results.jsonl 给最终判定。"""

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
        "S1.create_v1_draft",
        "S2.chat_inactive_40601",
        "S3.activate_v1",
        "S4.chat_nonstream",
        "S4b.content_nonempty",
        "S5.sse_events",
        "S6.cancelled_recorded",
        "S7.missing_var_40601",
        "S7b.error_msg_has_var",
        "S8.inspector_write_403",
        "S9.duplicate_40201",
        "S10a.create_v2",
        "S10b.activate_v2",
        "S10c.v1_auto_archived",
        "S11a.archive_v2",
        "S11b.list_active_empty",
        "S11c.list_count_zero",
        "S12.call_logs_count",
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
        line = f"{sym} {n:<26} http={r['http_code']:>3}  code={r['code']!s:<6}  {lat:>4}ms"
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
                f"  - {n}: http={r['http_code']} code={r['code']} (期望 http={r['expected_http']} code={r['expected_code']})  {r.get('note','')}"
            )
        return 1
    print(f"✓ 正常：{len(must)} 项全通过")
    return 0


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/llm_streaming"
    sys.exit(main(out))
