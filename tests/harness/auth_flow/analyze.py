#!/usr/bin/env python3
"""auth_flow/analyze.py — 读 results.jsonl 给最终判定（正常 / 警告 / 异常）。

用法：python3 analyze.py [.dev/auth_flow]
"""

import json
import os
import sys
from collections import Counter


def main(out_dir: str) -> int:
    rf = os.path.join(out_dir, "results.jsonl")
    if not os.path.exists(rf):
        print(f"✗ 缺 {rf}（先跑 run.sh）", file=sys.stderr)
        return 2

    rows = []
    with open(rf) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            rows.append(json.loads(line))

    if not rows:
        print("✗ results.jsonl 为空", file=sys.stderr)
        return 2

    # 必跑场景清单（顺序与命名约定）
    must = [
        "S1.register",
        "S2.login",
        "S3.me_via_gateway",
        "S4.me_direct",
        "S5.refresh",
        "S6a.passwd_wrong",
        "S6b.passwd_ok",
        "S6c.old_login_fail",
        "S6d.new_login_ok",
        "S7.rate_limit",
    ]
    by_name = {r["scenario"]: r for r in rows}
    missing = [n for n in must if n not in by_name]
    if missing:
        print(f"✗ 缺场景: {missing}")
        return 1

    # 逐项判定
    fails = []
    warns = []
    for n in must:
        r = by_name[n]
        ok = r["ok"]
        sym = "✓" if ok else "✗"
        lat = r.get("latency_ms", 0)
        note = r.get("note") or ""
        line = f"{sym} {n:<22} http={r['http_code']:>3}  code={r['code']!s:<6}  {lat:>4}ms"
        if note:
            line += f"  {note}"
        print(line)
        if not ok:
            fails.append(n)
        elif lat and lat > 1000:
            warns.append(f"{n} 慢: {lat}ms")

    # latency 分布
    lats = [r["latency_ms"] for r in rows if r.get("latency_ms")]
    if lats:
        lats_sorted = sorted(lats)
        p50 = lats_sorted[len(lats_sorted) // 2]
        p95 = lats_sorted[int(len(lats_sorted) * 0.95)]
        print(f"\nlatency p50={p50}ms  p95={p95}ms  max={max(lats)}ms  n={len(lats)}")

    # 总判定
    print()
    if fails:
        print(f"✗ 异常：{len(fails)} 项失败")
        for n in fails:
            r = by_name[n]
            print(
                f"  - {n}: 期望 http={r['expected_http']} code={r['expected_code']}, "
                f"实际 http={r['http_code']} code={r['code']}"
            )
        return 1
    if warns:
        print(f"⚠ 警告：{len(warns)} 项")
        for w in warns:
            print(f"  - {w}")
        return 0
    print(f"✓ 正常：{len(must)} 项全通过")
    return 0


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser(
        "~/lilw/gomob/.dev/auth_flow"
    )
    sys.exit(main(out))
