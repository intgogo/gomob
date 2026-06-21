#!/usr/bin/env python3
"""logs_upload analyze.py — 读 .dev/logs_upload/results.jsonl 给最终判定。

只遍历已记录的行会把"负路径漏 record"误判为过(场景在 record 前崩 → 行缺失 → 静默放行)。
故维护 MUST 场景清单:每个必跑场景(含 S5-S8 负路径)都必须有一行 record;缺任一 → 异常(exit 1),
不静默过。S4_jsonl_fields 是条件必跑:仅当 S4_jsonl_5lines 通过(文件落盘成功)时才要求其存在。

退出码:0 = 全部 MUST 场景齐备且全 OK;1 = 有 FAIL 或缺 MUST 场景(异常);2 = results.jsonl 缺失/空(harness 未产出)。
"""
import json
import sys
from pathlib import Path

# 必跑场景清单(与 run.sh 场景一一对应)。缺任一 = 负路径漏 record / harness 半途崩,判异常。
MUST_SCENARIOS = [
    "S2_register",
    "S2_login",
    "S3_upload_5",
    "S4_jsonl_5lines",
    "S5_no_token_401",
    "S6_empty_40111",
    "S7_field_10001",
    "S8_oversize_40111",
]
# 条件必跑:键场景通过(=对应前置成立)时,值场景也必须存在。
CONDITIONAL_MUST = {"S4_jsonl_5lines": "S4_jsonl_fields"}


def main() -> int:
    root = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".dev/logs_upload")
    rfile = root / "results.jsonl"
    if not rfile.exists():
        print(f"ERR: {rfile} 不存在", file=sys.stderr)
        return 2
    rows = [json.loads(l) for l in rfile.read_text().splitlines() if l.strip()]
    if not rows:
        print("ERR: results.jsonl 为空", file=sys.stderr)
        return 2

    by_scenario = {r["scenario"]: r for r in rows}

    print(f"{'scenario':<24} {'ok':<4} {'note'}")
    print("-" * 80)
    fails = 0
    for r in rows:
        ok = r.get("ok", False)
        if not ok:
            fails += 1
        print(f"{r['scenario']:<24} {'OK' if ok else 'FAIL':<4} {r.get('note', '')}")

    # MUST 场景齐备性:缺失 = 负路径漏 record / harness 半途崩,不能静默放行。
    missing = [s for s in MUST_SCENARIOS if s not in by_scenario]
    for trigger, required in CONDITIONAL_MUST.items():
        if by_scenario.get(trigger, {}).get("ok") and required not in by_scenario:
            missing.append(required)

    print()
    print(f"total={len(rows)} pass={len(rows)-fails} fail={fails}")
    if missing:
        print(f"ERR: 缺 MUST 场景(漏 record / harness 半途崩): {', '.join(missing)}", file=sys.stderr)

    if missing or fails:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
