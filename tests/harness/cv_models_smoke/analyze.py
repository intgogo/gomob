#!/usr/bin/env python3
"""cv_models_smoke/analyze.py — M-S10 Phase 2.1 ONNX 模型注册判定。"""

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
        "A1.list_empty",
        "A1b.empty_list",
        "B0.real_onnx_available",
        "B1.list_two",
        "B1b.total_2_loaded_1",
        "B2.vmet_loaded",
        "B3.bogus_failed_with_error",
        "B4.readyz_still_ok",
        "B4b.opencv_still_loaded",
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
        line = f"{sym} {n:<35} http={r['http_code']:>3}  code={r['code']!s:<6}  {lat:>4}ms"
        if note:
            line += f"  {note}"
        print(line)
        if not ok:
            fails.append(n)

    lats = [r["latency_ms"] for r in rows if r.get("latency_ms")]
    if lats:
        sl = sorted(lats)
        print(f"\nlatency p50={sl[len(sl)//2]}ms  p95={sl[int(len(sl)*0.95)]}ms  max={max(lats)}ms  n={len(lats)}")
    print()
    if fails:
        print(f"✗ 异常：{len(fails)} 项失败")
        for n in fails:
            r = by[n]
            print(f"  - {n}: http={r['http_code']} code={r['code']}  {r.get('note','')}")
        return 1
    print(f"✓ 正常：{len(must)} 项全通过")
    return 0


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/cv_models_smoke"
    sys.exit(main(out))
