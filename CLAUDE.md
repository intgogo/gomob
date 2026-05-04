# CLAUDE.md

本文件为 Claude Code (claude.ai/code) 在此仓库中工作时提供指导。Codex 等其它 Agent 也读本文件。

**语言规范：所有对话、代码注释、文档一律使用中文**
**构建产物与运行日志统一输出到 `.dev/` 目录**
**遇到需要用户决策的设计分叉 / 多选项 / 参数取值时，必须启用 `AskUserQuestion` 工具结构化提问**

## ★ 顶级原则 — 第一性，不妥协

**遇到选择时，按第一性原理推导最优解，开发和设计上不做妥协。**

- 分叉时不能默认选"最小改动 / 风险最小"。先回到目标和因果链，推导何为对，然后做对的。
- 实现成本高 / 跨模块大不是退到妥协解的理由 — 难和对的，直接做。
- 不允许"v1 桩 / MVP 后续优化"蒙混次优。临时方案要明记 TODO 指向终态。
- 模块 B 不就绪时不在 A 里加 fallback / 退化路径 — 是补 B，不是退 A。
- 现有 stub（固定值返回 / 单分支硬编码）直接接真实数据源，不留。
- 详见 `docs/agent-memory/principle_first_principles_no_compromise.md`。

## 项目概述

**gomob** 是一款 Android 端 3D 扫描应用。核心改造：把外接 Berxel iHawk 深度相机
与手机主摄像头**深度绑定**，做"双摄合一"的高精度 3D 扫描设备。

**硬件路径**：USB-C OTG 接 Berxel 深度相机 + 手机内置主摄像头同步采集 → 端侧
JNI 层做配准 / 点云融合 / 重建 → Compose UI 实时预览 + Filament 3D 回看。

**软件栈**：Kotlin + Jetpack Compose + NDK（C++17） + Hilt + Room + Filament + CameraX。
工程结构参照 [Now in Android](https://github.com/android/nowinandroid) 多模块蓝本。

## 文档结构

- `AGENTS.md` — **跨 Agent 统一接入入口**（Claude / Codex / 其它协作 Agent 先读这里）
- `docs/agent-memory/` — **唯一权威记忆位置**，索引 `AGENTS_MEMORY.md`
- `docs/architecture.md` — **架构总入口**
- `docs/architecture/00-overview.md` — 7 层分层 + 模块映射
- `docs/architecture/01-depth-camera-integration.md` — **深度相机绑定专题**（USB OTG / 双摄外参标定 /
  RGBD 同步 / Berxel SDK 接入计划，引用 Windows 端 `/root/WindowsR/berxel/sdk/` 的 `VIN_RGBD_Rectification_Design.md` /
  `HD_RGB_Texture_Projection_Design.md`）
- `docs/architecture/02-app-architecture.md` — **App 多模块切分**（app / core/* / feature/* / native / third_party 边界与依赖规则）
- `docs/architecture/03-jni-boundary.md` — **JNI 边界契约**（哪些数据走 JNI、哪些走 SharedMemory / DirectByteBuffer / NIO）
- `docs/architecture/04-reconstruction-pipeline.md` — **重建管线设计**（采集 → 同步 → 配准 → 融合 → TSDF/Marching Cubes → 纹理）
- `docs/architecture/05-calibration-pipeline.md` — **标定管线设计**（双摄内参 + 外参一次性标定流程）
- `docs/architecture/registry/` — **机器可校验治理真理源**（modules / dependencies / capabilities 等）

## 架构概要

### 多模块切分（参考 Now in Android）

```
app/                        应用入口（MainActivity / Application / Navigation 顶层 NavHost）
build-logic/convention/     Gradle 约定插件（gomob.android.application / .library / .compose / .feature / .hilt / .native / .jvm.library）
core/
├── common/                 通用工具 / Result / 异常类型
├── model/                  跨层数据契约（RgbdFrame / CameraIntrinsics / StereoExtrinsics / ScanSession ...）
├── data/                   仓库层（DataSource → Repository → Domain 用例输入）
├── database/               Room 实体与 DAO（扫描元信息、标定参数、设备记录）
├── domain/                 用例（UseCase）+ 业务规则（不依赖 Android Framework）
├── designsystem/           Material3 主题 / Token / 公共组件
├── ui/                     公共 Composable（PlaceholderScreen / 状态壳）
├── network/                OkHttp + Retrofit + Serialization（资源同步 / 模型下发）
└── native-bridge/          JNI 边界（NativeBridge object 是 Kotlin 调进 C++ 的唯一入口）

feature/
├── scan/                   扫描主流程（CameraX + 深度服务 + Compose 实时预览 + 前台服务）
├── gallery/                历史扫描列表 + 详情（Filament 渲染回看）
├── calibration/            双摄内/外参标定向导
└── settings/               偏好设置（DataStore）

native/                     C++17 原生代码（CMake 单一 .so：libgomob_native.so）
├── depth/                  深度图反投影 / 噪声滤波 / 时间对齐
├── fusion/                 主从外参投影 / 颜色回填 / 深度补洞
├── reconstruction/         TSDF / Poisson / Marching Cubes / 纹理烘焙
└── jni/                    JNI 入口（仅本目录有 extern "C" + JNICALL）

third_party/
└── berxel-android/         Berxel 官方 Android SDK 投放点（aar/ + jniLibs/<abi>/ + include/ + docs/）

assets/                     非代码资源（标定参考图、demo 模型、设备元数据）
docs/                       架构文档 + 跨 Agent 记忆
scripts/                    工具脚本（ensure-android-sdk / adb-wifi / build / test）
tests/                      集成测试 + harness（自分析与自优化）
.dev/                       构建产物 / 日志 / 截图（gitignored）
```

### 依赖规则（硬约束）

- **上层依赖下层，下层绝不依赖上层**：feature → core/* → native-bridge → native；core/* 之间允许同层 model/common 互相被依赖，**禁止环**
- **数据契约下沉到 `core:model`**：跨模块传递的所有结构定义在这里；feature/core 其它模块只 import，不再发明
- **JNI 边界唯一**：`core:native-bridge` 是 Kotlin → C++ 的唯一通道；feature 不允许直接 `System.loadLibrary` / `external fun`
- **Berxel SDK 隔离在 `third_party/`**：业务模块不直接 import Berxel API，统一通过 `core:native-bridge` 暴露的抽象方法调用
- **Compose 与 Material3 只通过 `core:designsystem` / `core:ui` 暴露**：feature 不直接 import 自定义私有色板 / 字体

### 核心设计原则

- **第一性优先**：先建模（`core:model` 数据契约 → JNI 签名 → C++ 实现）后编码；不接受"先写个简单的看看"
- **数据驱动**：标定参数、设备型号、扫描预设全部 YAML / Proto 配置化，C++/Kotlin 只做求解器
- **JNI 单一入口**：`io.gomob.nativebridge.NativeBridge` 是 Kotlin → C++ 的唯一桥；散点 `external fun` 一律拒绝合并
- **大数据走零拷贝**：RGBD 帧大数据走 `DirectByteBuffer` / `Hardware Buffer`；JNI 数组拷贝只用于小元数据
- **同步性是公理**：RGBD 双流必须**同时间戳**才能进入 fusion；时间戳偏差 > 阈值的帧丢弃，不"凑合用"
- **离线可运行**：网络失败 / 厂商 SDK 缺失时，端侧管线必须能跑通通用 UVC 路径，不阻塞工程演进

## 开发环境（CentOS 9）

- Java 17 OpenJDK（已装）
- Android SDK / NDK / build-tools / platform-tools(adb) — 装在 `/opt/android-sdk`，
  环境变量 `ANDROID_HOME=/opt/android-sdk`，`dev.sh` 自动装载
- 一键自检：`./dev.sh doctor` — 校验 SDK / NDK / CMake / Java 版本是否齐
- 一键补装：`./scripts/ensure-android-sdk.sh` — 缺啥补啥

### 局域网无线调试

- USB 一次配对：`./scripts/adb-wifi.sh pair <手机IP:端口> <配对码>`
- 后续连接：`./scripts/adb-wifi.sh connect <手机IP:端口>`
- Android 11+ 推荐使用"无线调试"配对模式，不需要 USB 也能调试

## 自分析与自优化（Harness）

继承 gogame 的 harness 强制规则。**重要且存在不确定性**的模块必须有 harness：

| 五条触发标准 | 在本项目的对应 |
|------------|--------------|
| 涌现行为 | 多帧融合后的点云密度 / 重建网格质量（无法单帧推断） |
| 参数敏感 | 时间同步阈值 / TSDF voxel size / 滤波窗口 |
| 长时序依赖 | 全场扫描全过程（漂移、回环、增量重建） |
| LLM 介入 | 后期可能引入 LLM 做扫描提示 / 质量评估 |
| 多 Agent 协作 | 暂无；预留 |

**约定（沿袭 gogame）**：每个 harness 由两部分组成，放在 `tests/harness/<名称>/`：

| 组件 | 文件 | 职责 |
|------|------|------|
| 采样器 | `run.sh` | 编译 → 推到设备 → 触发场景 → 拉日志 / 截图 / 帧 dump 到 `.dev/<名称>/` |
| 分析器 | `analyze.py` | 读取采样数据 → 输出健康度报告 + 异常告警，可独立反复运行 |

**分析器必须输出可判定结论**："正常 / 警告 / 异常 + 原因"，不是打印一堆数字让人看。

## UI 验证规范（强制）

涉及 Compose 界面、HUD、点击区域、显示/隐藏逻辑的改动，**不能**只靠编译或单元测试结束。

必须执行：
1. `./dev.sh install` 推到真机或模拟器
2. `./dev.sh shot <screen-name>` 自动跳到指定 screen 并截图到 `.dev/screenshots/<screen-name>.png`
3. 打开截图人工 + 程序协同检查布局 / 遮挡 / 比例 / 信息密度 / 文字居中
4. 视觉问题继续修，**不要把"测试通过"当成 UI 完成**

## 设计文档维护规范

- 新增大系统时默认两份文档起步：设计文档（"为什么"） + 实施文档（"怎么做"）
- 长文档优先补 `*-summary.md`，而不是继续堆长前言
- 每个开发阶段结束后同步更新文档，保持文档与代码一致
- 所有架构改动同步更新 `docs/architecture/registry/`（modules.yaml / dependencies.yaml）

## Workflow

- Track tasks in **`TODO.md`**；不另起 plan 文档（"单一真理源"）
- TODO 条目须附上相关设计 / 架构文档路径（如 `| docs: docs/architecture/01-depth-camera-integration.md`）
- 跨 Agent 协作时先读 `AGENTS.md` → `CLAUDE.md` → `docs/agent-memory/AGENTS_MEMORY.md` → `TODO.md`
- 写 plan / TODO 节 / spec 时遵守 `docs/agent-memory/feedback_plan_writing_quality.md`
  （无占位符 / 批判性复审 / 任务按 harness 可验收单元切而非时间切）
- Understand context before coding；ask when requirements are unclear
- 完成任务后总结做了什么 + 怎么验证

### 工作区隔离 (worktree)

multi-step 改动 / 同时多分支并行 / 探索性大改动**推荐用 `git worktree` 隔离**：

```bash
git worktree add .worktrees/<name> -b feature/<topic>
cd .worktrees/<name>
./dev.sh build
```

约定：
- **目录固定** `.worktrees/<branch-name-without-prefix>/`（`feature/scan-fusion` → `.worktrees/scan-fusion/`）
- **harness 输出**每个 worktree 有独立 `.dev/`，跨 worktree 比对用 `OUTPUT_DIR=.dev/<name>-<branch>/` 显式指定
- **不在 main 直接动手** multi-step 实施，没用户明确同意不许

## Git Push 策略

- Codex / Agent 完成代码或文档改动时，可以按任务需要本地 `git commit`
- 不要自动 `git push` 到远端
- 只有用户明确要求"push / 推上去 / 推远端"等时，才执行 `git push`

## Context Compaction

When compacting, always preserve the generated plan and all task steps.
