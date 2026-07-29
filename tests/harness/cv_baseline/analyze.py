#!/usr/bin/env python3
"""cv_baseline/analyze.py — M-S10.8 cv-engine 精度基线判定。

每对字符的实测值与 expected.json 基线对比。
- 偏离 > tol  → 异常
- 不满足 expected_lt / expected_gt → 异常
- 全在 tol 内 → 正常
"""

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

    fails = []
    for r in rows:
        ok = r["ok"]
        sym = "✓" if ok else "✗"
        note = r.get("note") or ""
        line = f"{sym} {r['scenario']:<32} {note}"
        print(line)
        if not ok:
            fails.append(r["scenario"])

    print()
    if fails:
        print(f"✗ 异常：{len(fails)} 项基线偏离")
        for n in fails:
            print(f"  - {n}")
        return 1
    print(f"✓ 正常：{len(rows)} 项基线全在 tol 内")
    return 0


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/cv_baseline"
    sys.exit(main(out))
