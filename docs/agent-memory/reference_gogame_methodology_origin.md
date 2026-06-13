---
name: gogame 方法论源仓
description: 本项目方法论源头是 /root/lilw/gogame, 通用规则迁过来, 业务相关 finding 留原仓
type: reference
---

# gogame 方法论源仓

本项目（gomob）的方法论 — 第一性原则、无妥协、harness 强制、TODO.md 单一真理源、
`.dev/` 产物隔离、worktree 隔离、AskUserQuestion 决策分叉、写作纪律 — 全部来自
`/root/lilw/gogame`。

## 路径

| 内容 | 路径 |
|------|------|
| gogame 仓根 | `/root/lilw/gogame/` |
| gogame CLAUDE.md | `/root/lilw/gogame/CLAUDE.md` |
| gogame AGENTS.md | `/root/lilw/gogame/AGENTS.md` |
| gogame agent-memory | `/root/lilw/gogame/docs/agent-memory/` |
| gogame architecture | `/root/lilw/gogame/docs/architecture/` |
| gogame 工程治理脚本 | `/root/lilw/gogame/scripts/` 与 `/root/lilw/gogame/dev.sh` |

## 迁移边界

| 迁过来 | 留原仓 |
|--------|--------|
| principle / feedback（通用纪律） | finding（业务发现，比如古代 NPC、家庭、行政相关的） |
| 写作 / 协作纪律 | game-world-design / gameplay-design 等业务文档 |
| harness 范式 | 古代世界 harness 实例（npc_day_audit / grain_cycle 等） |
| AskUserQuestion 用法约定 | client2d / Godot / Unreal 客户端约定 |

## 双向同步

当 gomob 沉淀出可反哺 gogame 的通用经验（比如新的 feedback 规则），
通过 PR / 双向同步，**不**做单点拷贝。

## 何时回查 gogame

- 写新 feedback 时先看 gogame 是否已有类似条目，避免重复发明
- 写 harness 时参考 gogame 现有 harness 的脚本骨架（采样器 → 分析器二段式）
- 写工程治理脚本（dev.sh / ensure-*）时参考 gogame 的脚本结构和命名约定

## 最近同步（2026-06-12）

从 gogame 同步通用工程方法论一轮，已落地：

- 新 principle：[[principle_three_questions_before_acting]]、[[principle_compute_equivalent_optimization]]；更新 [[principle_first_principles_no_compromise]]。
- 新 feedback：critical_thinking_not_yes_man / holistic_not_patching / mock_first_io_conserved / autonomous_execution_no_check_in / self_test_before_claim_done / business_verification_not_ui_only / p_phase_default_workflow / phase_0_is_skeleton_not_realism / worktree_only_for_parallel；更新 design_style / dev_loop / plan_writing_quality / harness_mandatory。
- 顶层文档：`AGENTS.md` 从一行重定向改成正式入口（三问宗旨 / 文档读法 / harness / UI+业务双验证 / CodeGraph / 记忆硬规 / 构建落点 / worktree / BUGS.md）；`CLAUDE.md` 补三问、CodeGraph 节、记忆硬规 check_doc_index 守门、BUGS.md，并把 worktree 政策从「multi-step 就推荐」改成「仅并行才开」。
- 治理工具：移植 `scripts/codegraph.sh`、`scripts/check_doc_index.sh`、`scripts/sync-claude-memory.sh`，新建 `BUGS.md`，并给 gomob 建了 CodeGraph 索引（覆盖见 [[reference_codegraph_coverage_boundaries]]）。

**本轮刻意跳过的 gogame 专属约定**（Go / 游戏特有，不迁）：archgov 架构治理 CLI、`go build -o .dev` 二进制纪律、Godot 渲染器规范、客户端世界放映机原则、NPC 自主 / 灵魂链接 / 六公理等业务公理。下次同步从这些里挑能通用化的再议。
