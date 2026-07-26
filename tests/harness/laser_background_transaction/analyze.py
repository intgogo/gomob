#!/usr/bin/env python3
"""判定背景 revision 激活与任务终态是否满足原子、线性化契约。"""

from __future__ import annotations

import re
import sys
from pathlib import Path


EXPECTED = {
    "reset_scope_preserved",
    "cancel_wins",
    "complete_wins",
    "insert_failure_rollback",
    "commit_failure_rollback",
    "concurrent_linearizable",
}


def main() -> int:
    out = Path(sys.argv[1] if len(sys.argv) > 1 else ".dev/laser_background_transaction")
    log = out / "go-test.log" if out.is_dir() else out
    if not log.is_file():
        print(f"异常：未找到事务测试日志 {log}")
        return 1

    text = log.read_text(encoding="utf-8", errors="replace")
    passed = set(re.findall(r"TX_CASE:([a-z_]+):PASS", text))
    iterations = re.search(r"TX_CONCURRENT_ITERATIONS:(\d+)", text)
    missing = sorted(EXPECTED - passed)
    if "FAIL" in text or missing or iterations is None or int(iterations.group(1)) < 20:
        reasons = []
        if "FAIL" in text:
            reasons.append("Go 测试含 FAIL")
        if missing:
            reasons.append("缺少场景：" + "、".join(missing))
        if iterations is None:
            reasons.append("缺少并发轮次证据")
        elif int(iterations.group(1)) < 20:
            reasons.append(f"并发轮次不足：{iterations.group(1)}")
        print("结论：异常 — " + "；".join(reasons))
        return 1

    print(
        "结论：正常 — 清理范围未触碰无关工位；cancel/complete 只允许一个终态，插入与提交故障完整回滚，"
        f"stats revision 与 active 一致，并发线性化验证 {iterations.group(1)} 轮通过"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
