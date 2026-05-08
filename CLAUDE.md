# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指导。Codex 等其它 Agent 也读本文件。

**语言规范：所有对话、代码注释、文档一律使用中文，注释要简洁，避免无效注释**
**构建产物、运行日志、截图、采样数据统一输出到 `.dev/` 目录**
**遇到需要用户决策的设计分叉 / 多选项 / 参数取值时，必须启用 `AskUserQuestion` 工具结构化提问**

## ★ 顶级原则 — 第一性，最优解，不妥协

**遇到选择时，按第一性原理推导最优解，开发和设计上不做妥协。**

- 不退到"最小改动 / 风险最小"。先回到目标和因果链，推导什么是对的，然后做对的。
- 不用"v1 桩 / MVP 后续优化"蒙混次优方案。临时方案必须写明 TODO，并指向终态设计。
- 模块 B 不就绪时补 B，不在模块 A 里加假 fallback / 退化路径。
- stub（固定值返回 / 单分支硬编码 / demo 数据伪装真实链路）直接接真实数据源，不留。
- 详见 `docs/agent-memory/principle_first_principles_no_compromise.md`。

## 项目概述

**gomob** 是 Android 端 3D 扫描应用，目标是把外接 Berxel iHawk 深度相机与手机主摄像头**深度绑定**，做成一台移动高精度 3D 扫描设备。

**硬件路径**：USB-C OTG 接 Berxel 深度相机 + 手机内置主摄像头同步采集 → JNI / C++ 层做标定、配准、点云融合与重建 → Compose UI 实时预览 → Filament / OpenGL 3D 回看。

**软件栈**：Kotlin + Jetpack Compose + NDK（C++17） + Hilt + Room + CameraX + Filament / OpenGL。工程结构参照 Now in Android 多模块蓝本。

**扫描真实化标准（项目魂）**：真实不只是"能显示点云"，而是**扫描结果经得起近距离量测和反复复现**。同一物体多角度采集后，尺度、外参、纹理投影、遮挡边界、噪声分布、缺洞位置都必须能解释清楚。不能用漂亮预览、硬编码姿态、单帧 demo、离线资产伪装真实 RGBD 重建链路。

**当前重建主线（2026-05-07 起）**：实时 SLAM 优先级下调，主线改为**多视角 RGBD 配准 + 端云融合**。新工作默认围绕 `docs/architecture/04b-multiview-rgbd-reconstruction.md` 展开；`docs/architecture/04-reconstruction-pipeline.md` 主要保留 native TSDF / Marching Cubes / 历史沉淀和可复用模块背景，不继续扩成主路线，除非用户明确要求改方向。

实际硬件型号以 `docs/agent-memory/reference_iHawkP100R3_spec.md` 为准；native 阈值、有效距离、精度假设必须能追溯到该规格或实测 harness。

## 文档结构

- `AGENTS.md` — 跨 Agent 统一接入入口，先读它再读本文件。
- `docs/agent-memory/AGENTS_MEMORY.md` — 跨 Agent 共享记忆索引，硬规则 / 项目认知 / 复用经验都从这里导航。
- `docs/architecture.md` — 架构总入口，先看总览，再按需深入专题。
- `docs/architecture/00-overview.md` — 7 层分层 + 模块映射。
- `docs/architecture/01-depth-camera-integration.md` — 深度相机绑定，USB OTG / 双摄外参标定 / RGBD 同步 / Berxel SDK 接入。
- `docs/architecture/02-app-architecture.md` — App 多模块切分，app / core / feature / native / third_party 边界与依赖规则。
- `docs/architecture/03-jni-boundary.md` — JNI 边界契约，数据通道 / 错误模型 / 零拷贝。
- `docs/architecture/04b-multiview-rgbd-reconstruction.md` — **当前重建主线权威文档**。
- `docs/architecture/04-reconstruction-pipeline.md` — 旧重建管线与 native 沉淀，按需查阅，不作为新主线扩展入口。
- `docs/architecture/05-calibration-pipeline.md` — 双摄内参 / 外参一次性标定流程。
- `docs/architecture/08-vin-rectify-design.md` — VIN / iHawk 深度矫正与参考实现设计。
- `docs/architecture/registry/` — 机器可校验治理真理源，modules / dependencies / capabilities 等。
- `TODO.md` — 当前任务唯一真理源，不另起临时 plan 文档。

### 当前推荐读法

1. `docs/agent-memory/AGENTS_MEMORY.md` — 先看硬规则和最近方向变更。
2. `docs/architecture.md` — 建立整体分层和专题入口。
3. 对应专题文档；有 `*-summary.md` 时先读摘要，再决定是否深入正文。
4. `TODO.md` — 找当前任务、验收标准、相关设计文档。

不要一次加载全部长文档。按任务查专题，避免把旧方案和当前主线混在一起。

## 架构概要

### 七层分层

上层依赖下层，下层绝不依赖上层。跨层数据契约优先沉到 `core:model`，高吞吐数据优先走零拷贝 native 通道。

| 层 | 职责 | 在本仓的实体 |
|----|------|-------------|
| 1. 设备 / 硬件层 | USB-C OTG、CameraX、Berxel SDK、通用 UVC 能力 | `third_party/berxel-android/`、Android USB / CameraX 接入 |
| 2. 同步采集层 | RGBD 双流读取、时间戳同步、帧元数据绑定 | `feature:scan3d`、`core:native-bridge`、`native/depth/` |
| 3. 几何层 | 深度反投影、畸变矫正、噪声滤波、有效距离裁剪 | `native/depth/`、`native/vin/` |
| 4. 配准与融合层 | 外参变换、多视角配准、颜色回填、深度补洞 | `native/fusion/`、`native/reconstruction/scan_session.cpp` |
| 5. 重建层 | TSDF、Marching Cubes、网格/点云输出、纹理烘焙 | `native/reconstruction/` |
| 6. 应用层 | 用例编排、状态机、持久化、网络同步 | `core:domain`、`core:data`、`core:database`、`core:network` |
| 7. 表现层 | Compose UI、扫描预览、3D 回看、账户/协作壳 | `app`、`feature:*`、`core:ui`、`core:designsystem` |

### 模块划分

- `app/` — Android 应用入口，`MainActivity` / `Application` / 顶层导航。
- `build-logic/convention/` — Gradle 约定插件，统一 Android / Compose / Hilt / native 配置。
- `core/common` — 通用工具、结果类型、跨模块基础能力。
- `core/model` — 跨层数据契约，RGBD 帧、相机内参、外参、扫描会话等只在这里定义。
- `core/native-bridge` — Kotlin 到 C++ 的唯一桥，`NativeBridge` 是唯一入口。
- `core/data` / `core:domain` / `core:database` / `core:network` — 仓库层、用例层、Room、网络同步。
- `core:designsystem` / `core:ui` / `core:logging` — 主题、公共 Composable、日志基础设施。
- `feature:scan3d` — 3D 扫描主流程，采集、预览、录制、点云/网格回看。
- `feature:auth` / `feature:home` / `feature:message` / `feature:collaboration` / `feature:profile` — 当前 App 壳与业务入口。
- `native/` — C++17 原生代码，单一 `libgomob_native.so`，JNI 入口只允许在 `native/jni/`。
- `third_party/` — 厂商 SDK、外部二进制和头文件隔离区。
- `tests/` — host native test、集成测试、harness。
- `.dev/` — 构建产物、日志、截图、harness 采样输出，必须 gitignored。

模块归属与依赖规则以 `docs/architecture/registry/` 为机器真理源。

### 依赖硬约束

- **数据契约下沉到 `core:model`**：feature / core 其它模块只 import，不重新发明相似结构。
- **JNI 边界唯一**：feature 不允许直接 `System.loadLibrary` / `external fun`；必须经 `core:native-bridge`。
- **Berxel SDK 隔离**：业务模块不直接 import Berxel API；通过 native bridge 或封装后的设备抽象接入。
- **大数据零拷贝**：RGBD 帧、点云、网格等大数据优先走 `DirectByteBuffer` / `HardwareBuffer` / native 指针句柄；JNI 数组只用于小元数据。
- **同步性是公理**：RGB 和 depth 必须带可验证时间戳；偏差超阈值的帧丢弃或进入可解释的重同步流程，不"凑合用"。
- **离线可运行是正式能力**：厂商 SDK 缺失时通用 UVC / host harness 路径也要能跑通，不是假的 fallback。
- **Compose 与 Material3 收口**：共享视觉 token / 组件只从 `core:designsystem` 和 `core:ui` 暴露。

## 开发环境（CentOS 9）

- Java 17 OpenJDK。
- Android SDK / NDK / build-tools / platform-tools(adb) 位于 `/opt/android-sdk`，`dev.sh` 自动装载 `ANDROID_HOME=/opt/android-sdk`。
- 一键自检：`./dev.sh doctor`。
- 一键补装：`./scripts/ensure-android-sdk.sh`。

### 常用命令

- `./dev.sh build` — 编译 debug APK。
- `./dev.sh install` — 安装到当前设备。
- `./dev.sh run` — 安装并启动 MainActivity。
- `./dev.sh test` — 跑单元测试。
- `./dev.sh ci` — lint + test + assemble。
- `./dev.sh log` — 过滤 gomob 日志。
- `./dev.sh shot <name>` — 截图到 `.dev/screenshots/<name>.png`。
- `./dev.sh harness <name>` — 跑 `tests/harness/<name>/run.sh`。
- `./dev.sh emu-start` / `./dev.sh emu-stop` — 启停 `gomob_test` AVD。

### 设备与 VNC 调试

- USB 一次配对：`./scripts/adb-wifi.sh pair <手机IP:端口> <配对码>`。
- 后续连接：`./scripts/adb-wifi.sh connect <手机IP:端口>`。
- Android 11+ 推荐使用"无线调试"配对模式。
- 用户通过 VNC 远程看桌面；启动 emulator / GUI 时默认走 `DISPLAY=:1` 和 `-gpu host`，不要用 Xvfb headless 把界面藏起来。

## 自分析与自优化（Harness）

**核心原则**：重要且存在不确定性的模块必须具备自分析能力。单元测试验证"代码对不对"，harness 验证"行为好不好"。

**何时必须建 harness**（任一条件）：

1. 涌现行为：多帧融合后的点云密度、重建网格质量、纹理一致性。
2. 参数敏感：时间同步阈值、ICP / 多视角配准阈值、TSDF voxel size、滤波窗口。
3. 长时序依赖：完整扫描过程的漂移、回环、增量重建稳定性。
4. LLM 介入：后期扫描提示、质量评估、自动标注等不确定输出。
5. 多 Agent 协作：多人协作扫描、远程审核、端云任务调度。

**harness 设计规范**（放在 `tests/harness/<名称>/`）：

- `run.sh` — 编译、启动、采样、停止，产出写 `.dev/<名称>/`。
- `analyze.py` — 读取采样数据，输出**可判定结论**：正常 / 警告 / 异常 + 原因。

**开发闭环**：

```text
设计/改动 → 编码 → 单测 → harness 采样 → 分析报告 → 异常时定位根因 → 修复 → 重采样 → 闭环完成
```

- 命中 harness 覆盖的模块，提交前必须跑对应 harness 确认无退化。
- 新建模块命中上述五条判定标准时，先设计 harness，再写业务代码。
- 与扫描质量相关的现有入口优先查 `tests/harness/scan_quality/`、`cv_vin_pipeline/`、`cv_vin_compare/`、`device_sync/`、`tests/native_host/`。

## UI 验证规范（强制）

涉及 Compose 界面、HUD、点击区域、显示/隐藏逻辑、3D 预览画面的改动，不能只靠编译或单元测试结束。

必须执行：

1. `./dev.sh install` 或 `./dev.sh run` 推到真机 / 模拟器。
2. `./dev.sh shot <screen-name>` 截图到 `.dev/screenshots/<screen-name>.png`。
3. 打开截图人工 + 程序协同检查布局、遮挡、比例、信息密度、文字居中、3D 视图非空。
4. 视觉问题继续修，不把"测试通过"当成 UI 完成。

## 设计文档维护规范

- 新增大系统时默认两份文档起步：设计文档（为什么）+ 实施文档（怎么做）。
- 长文档优先补 `*-summary.md`，不继续堆长前言。
- 每个开发阶段结束后同步更新文档，保持文档与代码一致。
- 架构改动同步更新 `docs/architecture/registry/`。
- 变更模块边界 / 依赖 / 能力成熟度时，同步更新 `modules.yaml`、`dependencies.yaml`、`capabilities.yaml`。

## Workflow

- Track tasks in **`TODO.md`**；不另起 plan 文档，不使用本地临时 TODO 替代真理源。
- TODO 条目须附相关设计 / 架构文档路径，例如 `| docs: docs/architecture/04b-multiview-rgbd-reconstruction.md`。
- 跨 Agent 协作读法：`AGENTS.md` → `CLAUDE.md` → `docs/agent-memory/AGENTS_MEMORY.md` → `docs/architecture.md` → `TODO.md`。
- 需要保存记忆时，直接写到 `docs/agent-memory/`，并更新 `AGENTS_MEMORY.md` 索引；不要只写 Claude 本地 memory。
- **记忆写作硬规**：单文件聚焦一个主题，结构为标题 + Why + How to apply；`AGENTS_MEMORY.md` 每条描述不超过 60 字；过时记忆直接删除，不留历史噪音；新增条目前先判断能否合并到旧主题。
- 写 plan / TODO 节 / spec 时遵守 `docs/agent-memory/feedback_plan_writing_quality.md`：无占位符，批判性复审，任务按 harness 可验收单元切。
- Understand context before coding；requirements 不清楚时先问。
- 完成任务后总结做了什么，以及如何验证。

### 工作区隔离（worktree）

multi-step 改动 / 同时多分支并行 / 探索性大改动推荐用 `git worktree` 隔离，避免污染主工作树。

约定：

- 新开 worktree 前先问用户。
- 目录固定 `.worktrees/<branch-name-without-prefix>/`，例如 `feature/scan-fusion` → `.worktrees/scan-fusion/`。
- 每个 worktree 有独立 `.dev/`；跨 worktree 比对时用 `OUTPUT_DIR=.dev/<name>-<branch>/` 显式指定。
- 没有用户明确同意，不在 main / master 直接做 multi-step 大改。

## Git Push 策略

- Codex / Agent 完成代码或文档改动时，可以按任务需要本地 `git commit`。
- 不要自动 `git push` 到远端。
- 只有用户明确要求"push / 推上去 / 推远端"等时，才执行 `git push`。

## Context Compaction

When compacting, always preserve the generated plan and all task steps.
