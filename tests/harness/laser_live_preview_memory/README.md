# 激光实时预览内存 harness

从 Android UI 真实触发双激光扫描，联合采集 App Java/RSS、服务端完整点数、预览驻留点数和终态清理结果。

默认运行 55 秒，覆盖历史约 44 秒 OOM 窗口并由 UI 取消；`soak.sh` 最多等待 360 秒，要求自然完成、融合 PCD 非空，并在完成页继续留观 45 秒。

```bash
./dev.sh harness laser_live_preview_memory
OUTPUT_DIR=.dev/laser_live_preview_memory-soak tests/harness/laser_live_preview_memory/soak.sh
python3 tests/harness/laser_live_preview_memory/analyze.py .dev/laser_live_preview_memory-soak
```

所有产物写入 `.dev/laser_live_preview_memory/`。harness 只接管由本次 UI 点击新建且 ID 大于运行前基线的任务；异常退出会尝试停止该任务并验证数据库终态。

分析器按 `scanning`、`fusing`、`completed` 分段判断 Dalvik Heap 与 VmRSS，避免把最终三朵有界云的一次性载入台阶误判为泄漏。完成态必须同时满足至少 4 个样本和配置的留观时长，信号中断、非零采样退出、空最终云或驻留点数不符合预算均判 FAIL。
