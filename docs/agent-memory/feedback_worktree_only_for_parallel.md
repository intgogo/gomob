---
name: worktree 只在并行任务才开
description: 单 Agent 串行推进直接在 master 干; 只有多任务真正并行才按 .worktrees/<branch> 隔离
type: feedback
---

# worktree 只在并行任务才开

**规则**: 不存在并行编程任务就不开 worktree。即使是 multi-step、跨多模块、带测试和重构的大改动，只要是单 Agent 串行推进、不需要多条线同时跑，就直接在 master 上改。

## Why

- 用户明确拍板: 没有并行任务就别开 worktree，只有真正并行编程时才开。
- worktree 的唯一价值是隔离互不冲突的并发改动；单线工作开 worktree 只增加目录切换、`.dev/` 分裂和合并成本，没有收益。
- 这条修订了本仓 `CLAUDE.md` / `AGENTS.md` 旧政策里"multi-step / 探索性大改动**推荐用 worktree 隔离**"的默认立场——multi-step 本身不再是开 worktree 的理由。

## How to apply

- 单 sprint multi-step（哪怕涉及 native 几何 + Kotlin bridge + Compose UI + harness 一整条链）→ 直接在 master 干，不开 worktree。
- 单 Agent 串行推进多个 phase（例如多视角 RGBD 配准从端侧采集到云端融合一路往下做）→ 仍是单线，留在 master。
- 探索性大改：决心要做就直接 master；只有真"探索、大概率会弃"的方案才考虑 worktree。
- 多任务真正并行（例如一条线改激光设备集成、另一条线改深度矫正 native，互不冲突且同时推进）→ 才按目录约定开 worktree。

## worktree 目录约定（确需并行时）

- 开 worktree 前先问用户。
- 目录固定 `.worktrees/<branch-name-without-prefix>/`，例如分支 `feature/scan-fusion` → `.worktrees/scan-fusion/`。
- 每个 worktree 用独立的 `.dev/`；跨 worktree 比对采样/产物时用 `OUTPUT_DIR=.dev/<name>-<branch>/` 显式指定，避免互相覆盖。
- 没有用户明确同意，不在 master 上直接做并行的 multi-step 大改。

## 相关

- [P 阶段默认工作流](feedback_p_phase_default_workflow.md) — "开 worktree 前先问用户"仍生效，但前提是已经处于并发场景。
- [自驱执行不中途请示](feedback_autonomous_execution_no_check_in.md) — 单线串行推进是默认形态，正对应留在 master 直接干。
- [Git Push 策略](feedback_git_push_policy.md) — 本地可 commit，push 远端需用户明确要求。
