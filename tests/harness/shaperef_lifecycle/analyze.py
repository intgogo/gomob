#!/usr/bin/env python3
"""shaperef_lifecycle/analyze.py — M-S9 shape-ref harness 判定器。"""

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
        "S3.create_vehicle_model",
        "S4.upload_mesh1",
        "S5.upload_complete_mesh1",
        "S6.create_shape_v1",
        "S7.duplicate_version_40201",
        "S8.invalid_format_10002",
        "S9.list_shapes",
        "S9b.shapes_count",
        "S10.publish_v1",
        "S10b.published_state",
        "S11.patch_after_publish_40401",
        "S12.active_with_url",
        "S12b.active_url_present",
        "S13.download_sha_match",
        "S14.create_shape_v2",
        "S15.publish_v2",
        "S15b.v1_auto_archived",
        "S16.active_is_v2",
        "S17.delete_archived_40401",
        "S18.delete_draft",
        "S19.app_active_via_gateway",
        "S19b.app_url_present",
        "S20.audit_count",
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
    out = sys.argv[1] if len(sys.argv) > 1 else "/root/lilw/gomob/.dev/shaperef_lifecycle"
    sys.exit(main(out))
