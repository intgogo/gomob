#!/usr/bin/env python3
"""realtime_message_sync/analyze.py — 判定 M5.2 断线重连消息补齐质量。"""

import json
import os
import sys


def main(out_dir: str) -> int:
    rf = os.path.join(out_dir, "results.jsonl")
    if not os.path.exists(rf):
        print(f"异常：缺少采样结果 {rf}")
        return 2

    rows = []
    with open(rf, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                rows.append(json.loads(line))
    if not rows:
        print("异常：results.jsonl 为空")
        return 2

    by = {r["scenario"]: r for r in rows}
    must = [
        "R1.register_login",
        "R2.initial_online_sync",
        "R3.offline_send_and_idempotent_retry",
        "R4.reconnect_fetch_gapless",
        "R5.no_duplicate_after_retry",
        "R6.fetch_since_latest_empty",
    ]
    missing = [name for name in must if name not in by]
    if missing:
        print(f"异常：缺场景 {missing}")
        return 1

    fails = []
    for name in must:
        row = by[name]
        ok = bool(row.get("ok"))
        note = row.get("note") or ""
        sym = "✓" if ok else "✗"
        print(f"{sym} {name:<42} {row.get('latency_ms', 0):>5}ms  {note}")
        if not ok:
            fails.append(name)

    lats = sorted(r.get("latency_ms", 0) for r in rows if r.get("latency_ms"))
    if lats:
        p95_index = min(len(lats) - 1, int(len(lats) * 0.95))
        print(f"\nlatency p50={lats[len(lats)//2]}ms p95={lats[p95_index]}ms max={max(lats)}ms n={len(lats)}")

    if fails:
        print(f"\n异常：{len(fails)} 项失败，原因见上方 note")
        return 1

    print("\n正常：重连补齐无重复、无空洞，重复 client_msg_id 未产生新 server_seq")
    return 0


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/realtime_message_sync"
    sys.exit(main(out))
