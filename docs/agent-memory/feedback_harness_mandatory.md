---
name: 重要不确定模块必须建 harness
description: 五条触发标准命中时, 先建 harness 再写业务代码; 改 harness 覆盖模块前先跑 harness 验证无退化
type: feedback
---

# 重要不确定模块必须建 harness

重要且存在不确定性的模块，必须具备自分析能力（harness）。
单元测试验证"代码对不对"，harness 验证"行为好不好"。

**Why:** 这类模块无法用断言一锤定音，必须靠采样 + 观测 + 统计来持续逼近正确。
这是项目自我优化的核心能力。

**五条触发标准（任一命中即必须建 harness）：**

1. **涌现行为** — 模块输出由多系统交互涌现，无法从单模块推断
   （在 gomob：多帧融合后的点云密度、重建网格质量、长时序回环）
2. **参数敏感** — 结果高度依赖配置参数或权重，调参需要反馈
   （时间同步阈值、TSDF voxel size、滤波窗口、外参微调）
3. **长时序依赖** — 正确性要跑完整时间跨度才能判断
   （全场扫描 / 漂移积累 / 增量重建）
4. **LLM 介入** — 含 LLM 决策路径的模块（gomob 暂无；预留）
5. **多 Agent 协作** — 多个独立子系统协同产出（gomob 暂无；预留）

**How to apply:**
开发新模块时，如果命中五条之一，**先**在 `tests/harness/<名称>/` 建 harness
（`run.sh` + `analyze.py`）**再**写业务代码。改动已有 harness 覆盖的模块时，
提交前必须跑 harness 确认无退化。

## 三档约定（沿袭 gogame ADR-0005）

规模 / 时长 / 种子统一通过环境变量覆盖。

- 默认 `run.sh` = smoke（秒级本地）
- 规模放大有新信号价值的 harness 可选加 `soak.sh`（10×，CI 夜间）/ `stress.sh`（100×，周跑）
- 子档脚本只设置 env var + `exec run.sh`，不复制 sim 代码
- env var 命名 `<HARNESS_NAME>_<PARAM>`

详见 `tests/harness/README.md`（M0 阶段创建）。

## 分析器输出契约

**必须输出可判定结论**：不是"打印一堆数据让人看"，而是明确给出
"PASS / WARN / FAIL + 原因"。Agent 据此决定是否需要修复。

约定退出码：
- `0` — PASS
- `1` — WARN（指标可疑但未越界，记录但不阻塞）
- `2` — FAIL（指标越界，提交前必须修）
