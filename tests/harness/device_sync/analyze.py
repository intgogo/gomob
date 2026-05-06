#!/usr/bin/env python3
"""device_sync/analyze.py — M-S3 device 服务 harness 判定器。"""

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
        "S2.bind_missing_serial",
        "S3.bind_first",
        "S3b.is_new_true",
        "S4.bind_idempotent",
        "S4b.is_new_false",
        "S5.bind_cross_user_40203",
        "S6.list_mine",
        "S6b.list_count",
        "S7.get_mine",
        "S8.get_others_40301",
        "S9.patch",
        "S9b.patch_applied",
        "S10.touch",
        "S10b.last_seen_set",
        "S11.upload_v1",
        "S11b.v1_state",
        "S12.upload_same_sha",
        "S12b.idempotent_no_bump",
        "S13.upload_v2",
        "S13b.v2_bumped",
        "S14.latest",
        "S14b.latest_is_v2",
        "S15.list_cals",
        "S15b.list_state",
        "S16.fetch_v1",
        "S16b.v1_has_params",
        "S17.fetch_v99_40301",
        "S18.retire",
        "S19.patch_retired_40401",
        "S20.upload_retired_40401",
        "S21.bind_handover",
        "S21b.handover_new_id",
        "S22.inspector_admin_40103",
        "S23.admin_list",
        "S23b.admin_list_count",
        "S24.gateway_list",
        "S24b.gateway_count",
        "S25.audit_count",
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
        line = f"{sym} {n:<32} http={r['http_code']:>3}  code={r['code']!s:<6}  {lat:>4}ms"
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
            print(f"  - {n}: http={r['http_code']} code={r['code']} (期望 http={r['expected_http']} code={r['expected_code']})  {r.get('note','')}")
        return 1
    print(f"✓ 正常：{len(must)} 项全通过")
    return 0


if __name__ == "__main__":
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/device_sync"
    sys.exit(main(out))
