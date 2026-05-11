#!/usr/bin/env python3
import json
import sys
from pathlib import Path


def main() -> int:
    out_dir = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(".dev/asr_transcript_queue")
    summary_path = out_dir / "summary.json"
    if not summary_path.exists():
        print("异常: 缺少 summary.json")
        return 1
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    verdict = summary.get("verdict")
    reason = summary.get("reason", "")
    if verdict == "normal":
        print(f"正常: {reason}")
        print(f"message_id={summary.get('message_id')} transcript_status={summary.get('transcript_status')}")
        if summary.get("transcript_text"):
            print(f"text={summary.get('transcript_text')}")
        return 0
    if verdict == "warning":
        print(f"警告: {reason}")
        return 0
    print(f"异常: {reason}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
