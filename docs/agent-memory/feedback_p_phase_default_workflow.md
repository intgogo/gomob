---
name: 计划(P 阶段)默认工作流 + 计划里的测试要快
description: 动手前默认先出计划、按 harness 可验收单元切；计划里的测试必须秒级
type: feedback
---

# 计划(P 阶段)默认工作流 + 计划里的测试要快

## 规则 1：动手前默认先出计划

涉及多步骤、跨模块、影响 native/JNI 边界或重建链路的改动，**默认先出计划再编码**，不要边想边敲。

**Why:** gomob 是 7 层分层 + 多模块工程，一个改动常牵动 `core:model` 契约、`core:native-bridge` 签名、harness 验收和 Compose 表现层。没有计划就动手，往往写到一半才发现契约对不上、漏了 registry 更新或没考虑离线 harness 路径，返工成本远高于先想清楚。

**How to apply:**
- 计划落在 `TODO.md` 的 "## 进行中: <topic>" 节，不另起 `docs/plans/` 临时文档（单一真理源）。
- 出计划前先过 [行动前三问](principle_three_questions_before_acting.md)：要解决什么、最优解是什么、改动的因果边界在哪。
- 计划的写作质量（无占位符、批判性复审、零仓库上下文清晰度）遵循 [plan/spec/TODO 写作质量硬规](feedback_plan_writing_quality.md)。
- 超大计划（跨多 Phase / 多 metric / 长时序）升格成 `docs/architecture/<NN>-<topic>.md` 专题文档，TODO.md 只放索引行。

## 规则 2：计划按 harness 可验收单元切，不按时间切

任务颗粒度不是"2-5 分钟一步"，而是"一个能独立判定好坏的验收单元"。

**How to apply:**
- **涌现行为模块**（点云融合 / 重建网格 / 多视角配准 / 长时序漂移）：一个 task = "改动覆盖 + 跑 harness + 整体画像通过"才算完。单 step 单测过 ≠ 整体行为正确，task 末尾必含"跑 `tests/harness/<name>/` 验收"step。
- **确定性 utility 模块**（深度反投影几何 / 内外参矩阵运算 / PCD 解析 / proto 序列化）：可以 bite-sized + TDD（失败测试 → 实现 → 通过 → 提交）。
- 一个计划里两类模块都有就分节，各按各的纪律切。
- 详细判定标准见 [plan/spec/TODO 写作质量硬规](feedback_plan_writing_quality.md) 规则 2。

## 规则 3：计划里安排的测试/采样必须快(秒级反馈)

阶段推进节奏快，计划中作为开发回路一部分的测试与 harness 采样**必须秒级完成**；等几十分钟才能验证的长跑不放进迭代闭环。

**Why:** 用户明确要求开发阶段所有测试必须是快速的。阶段推进靠"改 → 测 → 看结果 → 再改"频繁迭代，每轮等 30 分钟 real-time harness 跑完不可接受。秒级反馈才支撑 TDD + CI gate + 高频迭代；慢测试会逼人跳过验证、积累退化。

**How to apply:**
- 单元测试：直接 mock 依赖（用 fixture 构造 `RgbdFrame` / 内外参、collector 收集输出），不真的打开 USB 设备、不启 Berxel native stack。参见 [mock-first 守恒原则](feedback_mock_first_io_conserved.md)。
- 集成/host native test：用小尺寸固定输入 + 立即断言，不跑全分辨率全帧扫描会话；想验配准就喂预切好的几帧点云，不走完整在线采集。
- harness 采样回路：开发阶段用降采样、少帧、小 voxel 的快速 profile 出"正常/警告/异常"结论；几分钟级的全量重采样是**后续验证**步骤，不是阶段开发回路的一部分，用户主动要求才跑。
- 路径相关：用环境变量/参数注入 fixture 路径，不依赖真机连接、不依赖当前工作目录。
- 单测 + 快速集成测试合计控制在分钟级以内，超了就拆 fixture 或下沉到独立慢测试集，别拖垮迭代闭环。
