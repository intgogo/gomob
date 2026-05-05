#!/usr/bin/env python3
"""cv_hmac_auth/analyze.py — M-S10.2c HMAC 验签判定。"""

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
        "S0.healthz_bypass_hmac",
        "S0b.readyz_bypass_hmac",
        "S1.missing_headers_40110",
        "S2.partial_headers_40110",
        "S3.ts_expired_40111",
        "S4.bad_sig_40113",
        "S5.wrong_secret_40113",
        "S6.good_sig_200",
        "S6b.good_sig_returns_data",
        "S7a.first_use_200",
        "S7b.replay_40112",
        "S8.post_with_body_sig_passes_then_biz_returns",
        "S9.signing_transport_e2e",
        "S10.lax_missing_headers_passes",
        "S11.lax_partial_still_rejected_40110",
        "S12.disabled_no_check",
        "S13.disabled_with_wrong_sig_passes",
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
        line = f"{sym} {n:<55} {note}"
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
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/cv_hmac_auth"
    sys.exit(main(out))
