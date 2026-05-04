# AGENTS.md

所有编码 Agent 的统一接入入口（Claude / Codex / 其它协作 Agent）。

## 启动顺序

1. `AGENTS.md`（本文件）
2. `CLAUDE.md` — 规范、架构边界、语言要求、第一性原则
3. `docs/agent-memory/AGENTS_MEMORY.md` — 跨 Agent 共享记忆索引
4. `TODO.md` + 当前任务涉及的设计文档

## 项目本质（一句话）

把外接深度相机（Berxel iHawk）和 Android 主摄像头**深度绑定**，做成一台
"3D 扫描手机"：手机端实时拿 RGBD → 端侧做配准 / 点云融合 / 重建 → 出 Mesh / 纹理。

## 记忆写入规约

长期有效的知识写到 `docs/agent-memory/`（进 git，所有 Agent + 团队共享）；
**不要**只写到 Claude 本地 `.claude/` 缓存。

1. `docs/agent-memory/<type>_<topic>.md` — 写记忆文件（type: principle / feedback / project / reference / finding / design）
2. `docs/agent-memory/AGENTS_MEMORY.md` — 加索引行
3. `git commit`

## 工程定位与方法论

继承自 `/root/lilw/gogame` 的成熟方法论（六公理 / 第一性 / 无妥协 / harness 自分析 /
单一真理源 / 数据驱动 / TODO.md 跟踪 / `.dev/` 产物隔离 / worktree 隔离），
本仓换成 Android Kotlin/NDK 技术栈承接。

跨仓约定:
- 通用方法论（principle / 写作纪律 / 开发闭环）从 gogame 迁移到本仓 `docs/agent-memory/`
- 业务相关 finding（古代世界 NPC / 家庭 / 行政 / …）**不**迁移，留在 gogame 仓
- 当本项目沉淀出可反哺 gogame 的通用经验，用 PR / 双向同步，不做单点拷贝
