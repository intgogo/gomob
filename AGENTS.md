# AGENTS.md

本文件为所有编码 Agent (Claude / Codex / 其他) 在此仓库工作时提供统一接入指导。先读本文件, 再读 [`CLAUDE.md`](CLAUDE.md) 取项目细节。

**语言规范: 所有对话、代码注释、文档一律使用中文; 注释简洁, 避免无效注释**
**构建产物、运行日志、截图、采样数据统一输出到 `.dev/` 目录 (gitignored)**
**遇到需要用户决策的设计分叉 / 多选项 / 参数取值时, 必须启用 `AskUserQuestion` 工具结构化提问**

## ★ 宗旨 — 开场一问, 必问

**"有没有历史上下文?"** — 每次任务动手前, 先查 `docs/context/INDEX.md`, 命中哪个模块就先读哪篇历史上下文文档 (可多篇), 站在全部已有决策、证伪结论和禁区之上再动手, 不做失忆重启式的全新探索。没命中任何模块的全新领域 → 记下收尾时新建对应 context 文档。上下文在手后, 目标与验收仍以 `TODO.md` 为真理源, 要求不清楚先问用户。详见 `docs/agent-memory/principle_context_first.md`。

## ★ 顶级原则 — 第一性, 最优解, 不妥协

**遇到选择时, 按第一性原理推导最优解, 开发和设计上不做妥协。**

- 遇关键问题, 先调研参考行业最佳 / 最成功的解决方案, 再思考突破超越, 切莫闭门造车。
- 不退到"最小改动 / 风险最小"。先回到目标和因果链, 推导什么是对的, 然后做对的。
- 不用"v1 桩 / MVP 后续优化"蒙混次优方案; 临时方案必须写明 TODO 并指向终态设计。
- 模块 B 不就绪时补 B, 不在模块 A 里加假 fallback / 退化路径。
- stub (固定值返回 / 单分支硬编码 / demo 数据伪装真实链路) 直接接真实数据源, 不留。
- 详见 `docs/agent-memory/principle_first_principles_no_compromise.md`。

## 文档结构与读法

本文件之后按序接力, 按需深入, 不一次加载全部长文档:

1. `docs/context/INDEX.md` — 模块级历史上下文索引 (开场一问的入口), 按任务命中模块读对应文档。
2. `docs/agent-memory/AGENTS_MEMORY.md` — 跨 Agent 共享记忆索引 (硬规则 / 项目认知 / 复用经验), 先看硬规则和最近方向变更。
3. `docs/architecture.md` — 架构总入口, 先看总览 (7 层分层 + 模块映射), 再按专题深入; `docs/architecture/registry/` 是机器可校验的治理真理源。
4. 对应专题的 `*-summary.md` (长文档一页摘要, 优先读), 再决定是否深入正文。
5. `TODO.md` — 当前任务唯一真理源, 找任务、验收标准、相关设计文档。

不要把旧方案和当前主线混在一起加载。按任务查专题。

## 项目概述

**gomob** 是 Android 端 3D 扫描应用, 目标是把外接 Berxel iHawk 深度相机与手机主摄像头**深度绑定**, 做成一台移动高精度 3D 扫描设备。

- **硬件路径**: USB-C OTG 接 Berxel iHawk P100R3 深度相机 + 手机内置主摄 (及 HLSD8 第二颗 RGB) 同步采集 → JNI / C++ 层做标定、配准、点云融合与重建 → Compose UI 实时预览 → Filament / OpenGL 3D 回看。
- **软件栈**: Kotlin + Jetpack Compose + NDK (C++17) + Hilt + Room + CameraX + Filament / OpenGL, 多模块 (参照 Now in Android)。
- **重建主线 (2026-05-07 起)**: 实时 SLAM 优先级下调, 主线改为**多视角 RGBD 配准 + 端云融合** (云端 Open3D multiway_registration + PGO)。新工作默认围绕 `docs/architecture/04b-multiview-rgbd-reconstruction.md` 展开, 不继续扩老的实时 SLAM / 端侧 TSDF 路线, 除非用户明确改方向。
- **硬件规格真理源**: native 阈值、有效距离、精度假设必须能追溯到 `docs/agent-memory/reference_iHawkP100R3_spec.md` (工作距离 0.2-8m / 理想 0.25-2m / 精度 ≤1%@1-2m) 或实测 harness, 不拍脑袋。

**扫描真实化标准 (项目魂)**: 真实不只是"能显示点云", 而是**扫描结果经得起近距离量测和反复复现**。同一物体多角度采集后, 尺度、外参、纹理投影、遮挡边界、噪声分布、缺洞位置都必须能解释清楚。不能用漂亮预览、硬编码姿态、单帧 demo、离线资产伪装真实 RGBD 重建链路。

## 自分析与自优化 (Harness)

**核心原则**: 重要且存在不确定性的模块**必须具备自分析能力**。单元测试验证"代码对不对", harness 验证"行为好不好" — 前者是门槛, 后者是优化闭环。

**何时必须建 harness** (任一条件):

1. 涌现行为: 多帧融合后的点云密度、重建网格质量、纹理一致性。
2. 参数敏感: 时间同步阈值、ICP / 多视角配准阈值、TSDF voxel size、滤波窗口。
3. 长时序依赖: 完整扫描过程的漂移、回环、增量重建稳定性。
4. LLM 介入: 后期扫描提示、质量评估、自动标注等不确定输出。
5. 多 Agent 协作: 多人协作扫描、远程审核、端云任务调度。

**harness 设计规范** (放在 `tests/harness/<名称>/`):

- `run.sh` — 编译、启动、采样、停止, 产出写 `.dev/<名称>/`。
- `analyze.py` — 读采样数据, 输出**可判定结论** (正常 / 警告 / 异常 + 原因), 不是打印一堆数据让人看。

**开发闭环**:

```
设计 / 改动 → 编码 → 单测 → harness 采样 → 分析报告 → (异常时) 定位根因 → 修复 → 重采样 → 闭环完成
```

- 命中 harness 覆盖的模块, 提交前必须跑对应 harness 确认无退化。
- 新建模块命中上述五条判定标准时, 先设计 harness, 再写业务代码。
- 与扫描质量相关的现有入口优先查 `tests/harness/` 下 `scan_quality` / `device_sync` / `depth_temporal_quality` 等；VIN 独立链路的 bundle/服务 harness 位于 `vendor/vin-rubbing/tests/harness/`；host native 测试在 `tests/native_host/`。详见 `docs/agent-memory/feedback_harness_mandatory.md`。

## UI / 业务双重验证规范

涉及 Compose 界面、HUD、点击区域、显示 / 隐藏逻辑、3D 预览画面的改动, 不能只靠编译或单元测试结束。

- 默认 `./dev.sh install` 或 `./dev.sh run` 推到真机 / 模拟器后再下结论。
- 优先用 harness、logcat、服务端日志、API 返回、`uiautomator dump`、Compose / instrumentation 测试判断崩溃、空数据、权限、可点击性、状态切换、渲染首帧等问题。
- **默认不截图**: 只有用户主动要求看图时才 `./dev.sh shot <screen-name>` 到 `.dev/screenshots/<name>.png` 复核 (截图后先压缩再 Read)。详见 `docs/agent-memory/feedback_ui_visual_verification.md`。
- **不止 UI**: 功能"画面对了"不等于链路通。RGBD 同步、native 配准、端云融合、持久化、网络回传等业务路径要用真数据走通验证, 不要拿假数据 / 单帧蒙混。详见 `docs/agent-memory/feedback_business_verification_not_ui_only.md`。
- 发现 UI / 交互问题继续修, 不把"测试通过"当成完成。

## CodeGraph 代码图谱

- 代码结构定位、调用链、被调链、影响面分析优先使用 CodeGraph MCP 或 `./scripts/codegraph.sh`; 精确文本 / 日志 / 配置片段匹配仍优先用 `rg`。
- CodeGraph 索引属于运行产物, 固定在 `.dev/codegraph/`; 根目录 `.codegraph` 只是软链, 不提交 git。
- 不要求每次变更都手动同步。MCP 模式由 watcher 自动跟踪; CLI 查询前、切分支 / `git pull` / 大量重命名后, 先跑 `./scripts/codegraph.sh sync`。
- 结果明显过旧或结构异常时, 跑 `./scripts/codegraph.sh index --force` 干净重建。常用命令: `status` / `query` / `callers` / `callees` / `impact` / `files`。
- 覆盖以 Kotlin / 自有 native C++ / Go / Python / 配置为主, `third_party/` 厂商 SDK 二进制不纳入; 边界详见 `docs/agent-memory/reference_codegraph_coverage_boundaries.md`, 覆盖不到处退回 `rg`。

## 设计文档维护规范

- 新增大系统默认两份文档起步: 设计文档 (为什么) + 实施文档 (怎么做)。
- 长文档优先补 `*-summary.md`, 不堆长前言。
- 每个开发阶段结束后同步更新文档, 保持文档与代码一致。
- 架构改动同步更新 `docs/architecture/registry/`: 变更模块边界 / 依赖 / 能力成熟度时, 同步改 `modules.yaml` / `dependencies.yaml` / `capabilities.yaml`。

## 历史上下文维护 (docs/context/)

- `docs/context/` 按模块沉淀**连续叙事**的历史上下文 (使命与现状 / 决策时间线 / 禁区与已证伪路线 / 关键资产指针 / 未竟事项), `INDEX.md` 是唯一入口。与 `docs/agent-memory/` 分工: context 是模块级连线叙事, agent-memory 是点状经验, 互相引用不复制。
- **任务收尾硬规**: 产生新决策 / 新证伪 / 方向变更 / 里程碑进展的任务, 收尾时必须增量更新对应模块 context 文档并刷新头部"最后更新 / 截至 commit"戳, 与设计文档维护同级义务; 可用 `/context-archive` skill 一键归档。纯执行、无新信息的会话不硬凑。
- 全新领域 → 按模板新建 `docs/context/<module>.md` 并在 `INDEX.md` 加一行。
- 写作硬规同记忆: 简洁、引用不复制、无占位符; 已证伪路线写进"禁区"节而不是删掉 — 禁区是防重走弯路的护栏。

## 记忆写作硬规 (防臃肿)

- 需要保存记忆时, 直接写文件到 `docs/agent-memory/`, 更新 `AGENTS_MEMORY.md` 索引并提交 git; 不要只写到本地 Agent memory。
- 单文件聚焦一个主题, 结构 = 标题 + Why + How to apply 三段; 简洁, 无占位符。
- `AGENTS_MEMORY.md` 索引每条描述 **≤ 60 字** (索引是导航不是摘要, 详情查文件本身)。
- 改完索引必须跑 `./scripts/check_doc_index.sh` 守门 (索引行剥离链接后超长 = 把细节塞进了导航, 退码非 0)。
- 过时记忆**直接删除**不留历史噪音; 新增条目前先判断能否并入老主题。
- 写 plan / TODO 节 / spec 时遵守 `docs/agent-memory/feedback_plan_writing_quality.md` (无占位符 / 批判性复审 / 任务按 harness 可验收单元切而非时间切)。

## 构建产物落点

- 编译产物、运行日志、截图、采样数据、CodeGraph 索引一律落 `.dev/` (gitignored)。`dev.sh` 各动词已全部落 `.dev/`, 手动采样 / 临时输出也写进 `.dev/<名称>/`。
- 根目录、模块目录里出现裸产物 = 误操作残留, 应清理; 唯一约定落点是 `.dev/`。

## 工作区隔离 (worktree)

**只在并行任务编程时才开 worktree**。单 Agent 串行推进 (即使 multi-step) → 直接在 master 干, 不开 worktree。

并行场景约定 (多任务同时跑):

- 目录固定 `.worktrees/<branch-name-without-prefix>/` (`feature/scan-fusion` → `.worktrees/scan-fusion/`)。
- 每个 worktree 有独立 `.dev/`; 跨 worktree 比对用 `OUTPUT_DIR=.dev/<name>-<branch>/` 显式指定。
- **新开 worktree 前先问用户**。详见 `docs/agent-memory/feedback_worktree_only_for_parallel.md`。

## BUGS.md 约定

- `BUGS.md` 是用户的 bug / 漏洞清单 (git tracked, 不要误删 / 误覆盖)。用户随时往里追加发现的现象, Agent 负责定位 + 修复。
- **解决一条就从 `BUGS.md` 里删一条** (而非打勾标记 / 留历史)。修复 commit 顺手带上 `BUGS.md` 的删除。
- 没修完前条目原样保留, 不要改写用户的描述。

## 开发环境与常用命令 (CentOS 9)

Java 17 + Android SDK / NDK / build-tools / adb 在 `/opt/android-sdk`; 一键自检 `./dev.sh doctor`, 一键补装 `./scripts/ensure-android-sdk.sh`。

- `./dev.sh build` — 编译 debug APK。
- `./dev.sh install` / `run` — 安装 / 安装并启动 MainActivity。
- `./dev.sh test` / `ci` — 单元测试 / lint + test + assemble。
- `./dev.sh log` — 过滤 gomob 日志; `./dev.sh shot <name>` — 截图到 `.dev/screenshots/`。
- `./dev.sh harness <name>` — 跑 `tests/harness/<name>/run.sh`。
- `./dev.sh emu-start` / `emu-stop` — 启停 `gomob_test` AVD。
- 真机调试: `./scripts/adb-wifi.sh pair / connect`; 用户全程 VNC (DISPLAY=:1, TigerVNC 5901), emulator / GUI 必须显示在该桌面, 不要起 Xvfb headless。详见 `docs/agent-memory/feedback_vnc_remote_dev.md`。

## Git Push 策略

- 完成代码 / 文档改动时, 可按任务需要本地 `git commit` (在 master 上做 multi-step 大改前先确认是否需要分支)。
- **不要自动 `git push`**。只有用户明确要求"push / 推上去 / 推远端"时才执行。详见 `docs/agent-memory/feedback_git_push_policy.md`。

## Workflow

- 任务唯一真理源是 `TODO.md`, 不另起临时 plan 文档, 不使用本地临时 TODO 替代。
- TODO 条目须附相关设计 / 架构文档路径 (如 `| docs: docs/architecture/04b-multiview-rgbd-reconstruction.md`), 确保先读文档再编码。
- Understand context before coding; requirements 不清楚时先问。
- 完成任务后总结做了什么, 以及如何验证。

## Context Compaction

When compacting, always preserve the generated plan and all task steps.
