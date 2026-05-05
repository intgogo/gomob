#!/usr/bin/env python3
"""inspection_lifecycle/analyze.py — 读 results.jsonl 给最终判定。

CLAUDE.md "分析器必须输出可判定结论"：正常 / 警告 / 异常 + 原因。
"""

import json
import os
import sys


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
        print("✗ results.jsonl 为空")
        return 2

    by = {r["scenario"]: r for r in rows}

    # 必需场景
    must = [
        "S1a.register_inspector",
        "S1b.register_reviewer",
        "S2a.login_inspector",
        "S2b.login_reviewer",
        "S2c.create_inspection",
        "S3.start_scanning",
        "S4a.upload_init",
        "S4b.chunk_1",
        "S4b.chunk_2",
        "S4b.chunk_3",
        "S4c.upload_complete",
        "S5.list_assets",
        "S5b.list_count",
        "S6a.presign",
        "S6b.expires_300",
        "S6c.dl_sha_match",
        "S7.update_preliminary",
        "S8.submit_review",
        "S10a.list_pending",
        "S10b.decide_correct",
        "S11.repeat_decide_conflict",
        "S12.close_inspection",
        "S13.audit_count",
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
        line = f"{sym} {n:<28} http={r['http_code']:>3}  code={r['code']!s:<6}  {lat:>5}ms"
        if note:
            line += f"  {note}"
        print(line)
        if not ok:
            fails.append(n)

    # latency
    lats = [r["latency_ms"] for r in rows if r.get("latency_ms")]
    if lats:
        sorted_lats = sorted(lats)
        p50 = sorted_lats[len(sorted_lats) // 2]
        p95 = sorted_lats[int(len(sorted_lats) * 0.95)]
        print(f"\nlatency p50={p50}ms  p95={p95}ms  max={max(lats)}ms  n={len(lats)}")

    print()
    if fails:
        print(f"✗ 异常：{len(fails)} 项失败")
        for n in fails:
            r = by[n]
            print(
                f"  - {n}: http={r['http_code']} code={r['code']} (期望 http={r['expected_http']} code={r['expected_code']})"
            )
        return 1
    print(f"✓ 正常：{len(must)} 项全通过")
    return 0


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/inspection_lifecycle"
    sys.exit(main(out))
