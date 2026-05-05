#!/usr/bin/env python3
"""ws_message_order/analyze.py — 给最终判定 + 暴露关键不变量。"""

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
        "S1.register_login_A",
        "S1b.register_login_B",
        "S2.connect_A",
        "S3.connect_B",
        "S4.send_one",
        "S5.sequential_50",
        "S6.concurrent_burst",
        "S7.fetch_since_0",
        "S8.invalid_send",
        "S9.invite_offline",
        "S10.pending_delivered_on_reconnect",
        "S11.answer_relay",
        "S12.ice_bidi",
        "S13.bye_relay",
        "S14.invite_ttl_expire",
        "S15.online_endpoint",
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
        line = f"{sym} {n:<40} {lat:>5}ms"
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
            print(f"  - {n}: {r.get('note','')}")
        return 1
    print(f"✓ 正常：{len(must)} 项全通过")
    return 0


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/ws_message_order"
    sys.exit(main(out))
