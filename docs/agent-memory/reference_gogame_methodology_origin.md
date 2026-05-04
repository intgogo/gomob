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
