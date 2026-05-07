#!/usr/bin/env python3
"""logs_upload analyze.py — 读 .dev/logs_upload/results.jsonl 给最终判定。"""
import json
import sys
from pathlib import Path


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

    print(f"{'scenario':<24} {'ok':<4} {'note'}")
    print("-" * 80)
    fails = 0
    for r in rows:
        ok = r.get("ok", False)
        if not ok:
            fails += 1
        print(f"{r['scenario']:<24} {'OK' if ok else 'FAIL':<4} {r.get('note', '')}")

    print()
    print(f"total={len(rows)} pass={len(rows)-fails} fail={fails}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
