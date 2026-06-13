---
name: 动手前必答三问 — 我要什么 / 我有什么 / 我要怎么做 (项目级原则)
description: 每次任务动手前先答清三问；任一答不上来先调研或澄清，不动手
type: principle
---

# 项目级硬规则 — 动手前必答三问

每次对话, 动手之前必须先确认三个问题的答案:

1. **我要什么?** — 这次任务的真正目标和验收标准是什么。不是字面指令的复读, 是用户要达成的结果。扫描类任务尤其要先定标准: 重建结果要经得起近距离量测和反复复现, 还是只要预览能动? 验收口径不一样, 做法完全不同。
2. **我有什么?** — 现有代码 / 文档 / 工具 / harness / 记忆里已经有哪些可用的东西。先查现成机制, 再造新的。
3. **我要怎么做?** — 路径是什么, 分几步, 每步怎么验证。方案先于动手。

## 为什么

有时对话太随意、太简单, 三问都没搞明白就开始动手, 结果是各种方向性、根本性的巨大错误。返工的根都在动手前没答清这三问:

- **"我有什么"没查 → 重复造轮子**: JNI 调用唯一入口是 `core:native-bridge` 的 `NativeBridge`, 数据契约已下沉在 `core:model`。不先查现成通道就在 feature 里散写 `external fun` / 重新发明一份 RGBD 帧结构, 既违反边界约束又白写一遍。
- **"我要什么"没定 → 改了又退回**: 扫描验收标准没明确就动 native 多视角配准, 调了一版 ICP 阈值 / 外参变换, 后来才发现用户要的是端云融合主线(云端 Open3D multiway_registration + PGO)的口径, 端侧那套阈值全废, 整段返工。
- **"我要怎么做"没规划 → 没法验证好坏**: 涌现行为(多帧融合点云密度 / 重建网格质量)和参数敏感模块, 不先想清楚用哪个 harness 采样、看哪个可判定结论, 改完无从证明是变好还是变差。

## 如何应用

- 任务开始时先过一遍三问; 复杂 / 模糊任务在回复开头显式写出三问的答案再动手。
- 任何一问答不上来 → 先调研 / 先用 AskUserQuestion 澄清, 不动手。
- **"我要什么"** 与 [批判性思考, 不做应声虫](feedback_critical_thinking_not_yes_man.md) 配合: 包括质疑用户字面指令是否真是最优, 目标和验收标准要推到因果链上而不是停在表述。
- **"我有什么"** 落到本仓具象: 先翻 `docs/agent-memory/AGENTS_MEMORY.md` 找硬规则与既有 finding, 再查 `docs/architecture/registry/` 的 modules / dependencies / capabilities, 再看 `core:native-bridge` / `core:model` / `tests/harness/` 有没有现成机制(scan_quality / cv_vin_pipeline / device_sync 等), 最后才决定新写。
- **"我要怎么做"** 与 [第一性原则选最优, 不做妥协](principle_first_principles_no_compromise.md) 配合: 按第一性原理推导最优解, 不退最小妥协; 每步都要绑一个可验证的口径(单测 / harness 采样 / logcat / uiautomator), 改完能自证好坏。
