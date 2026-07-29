#!/usr/bin/env python3
"""把配对探针输出收敛为明确 PASS/FAIL。"""

from __future__ import annotations

import json
import sys
from pathlib import Path


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else ".dev/vin_rgbd_pairing")
    if path.is_dir():
        path = path / "result.json"
    try:
        result = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"❌ 异常：无法读取配对结果：{exc}")
        return 2

    failures: list[str] = []
    if result.get("phase_delta_us") != 54_300:
        failures.append("54.3ms 固定相位未正确配对")
    if not result.get("boundary_accepted"):
        failures.append("100ms 边界未通过")
    if not result.get("over_boundary_rejected"):
        failures.append("100001us 未拒绝")
    if (result.get("fresh_color_index"), result.get("fresh_depth_index")) != (2, 2):
        failures.append("快门复用了点击前缓存")
    if not result.get("stale_diagnostic_rejected"):
        failures.append("陈旧帧仍进入诊断")
    if not 10 <= int(result.get("await_ms", -1)) <= 480:
        failures.append(f"新帧等待耗时异常：{result.get('await_ms')}ms")
    if not 60 <= int(result.get("reuse_timeout_ms", -1)) <= 500:
        failures.append(f"连续快门超时异常：{result.get('reuse_timeout_ms')}ms")
    if result.get("burst_color_count") != 3:
        failures.append("跳过3张彩色帧后没有收齐3张候选")
    if result.get("burst_depth_count") != 6:
        failures.append("burst 深度候选数量错误")
    if result.get("burst_best_delta_us") != 4_000:
        failures.append("burst 未选择整批全局最小回调差")
    if (result.get("burst_color_index"), result.get("burst_depth_index")) != (6, 6):
        failures.append("burst 仍提前返回首个合格帧对")
    if not 10 <= int(result.get("burst_ms", -1)) <= 900:
        failures.append(f"burst 等待耗时异常：{result.get('burst_ms')}ms")

    if failures:
        print("❌ 异常：" + "；".join(failures))
        return 2

    print(
        "✅ 正常：100ms/100001us 回调边界正确；快门只消费点击后新帧，"
        "跳过3张彩色帧并收齐3+3 burst 后选择整批全局最小差，连续快门不复用旧数据。"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
