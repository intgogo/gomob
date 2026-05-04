# TODO

> 单一真理源。**不另起 `docs/plans/`**。完成后从此处删除，记录留 git log。
>
> 写作纪律：`docs/agent-memory/feedback_plan_writing_quality.md`（无占位符 / 任务按 harness 可验收单元切）。

## M0 — 工程基线（进行中）

| ID | 项 | 状态 | 文档 |
|----|----|------|------|
| M0.1 | Android SDK / NDK / build-tools 安装 (`/opt/android-sdk`) | ⏳ 进行中 | `scripts/ensure-android-sdk.sh` |
| M0.2 | 多模块 Gradle 骨架 + convention 插件 | ⏳ 进行中 | `docs/architecture/02-app-architecture.md` |
| M0.3 | gradle wrapper + `./dev.sh doctor` 通过 | ☐ | — |
| M0.4 | git 初始化 + 首次 `./gradlew help` 同步通过 | ☐ | — |
| M0.5 | 真机验证：`./dev.sh install && ./dev.sh shot home` 跑通 | ☐ | `CLAUDE.md` UI 验证规范 |

## M1 — 深度相机接入（未启动）

| ID | 项 | 状态 | 文档 |
|----|----|------|------|
| M1.1 | 反编译 VINCreator APK 摸清 Berxel Android 端 .so 接口 | ☐ | `docs/architecture/01-depth-camera-integration.md` |
| M1.2 | USB OTG 设备发现 + 权限授予 + 热插拔处理 | ☐ | `docs/architecture/01-depth-camera-integration.md` §USB 接入 |
| M1.3 | RGBD 双流采集（CameraX + Berxel SDK） + 时间戳同步 harness | ☐ | `tests/harness/rgbd_sync/` |
| M1.4 | 单帧点云可视化（Filament） — 端到端最小闭环 | ☐ | `docs/architecture/04-reconstruction-pipeline.md` |

## M2 — 双摄外参标定（未启动）

| ID | 项 | 状态 | 文档 |
|----|----|------|------|
| M2.1 | 标定板设计（棋盘 / Charuco）+ 流程文档 | ☐ | `docs/architecture/05-calibration-pipeline.md` |
| M2.2 | feature:calibration UI 向导 + 数据采集 + 求解 | ☐ | 同上 |
| M2.3 | 标定结果落库 + 跨会话复用 | ☐ | `core:database` |

## M3 — 实时融合 + 重建（未启动）

| ID | 项 | 状态 | 文档 |
|----|----|------|------|
| M3.1 | 主从外参投影 colorize 单元测试 + harness | ☐ | `tests/harness/fusion_quality/` |
| M3.2 | TSDF voxel grid + Marching Cubes 出 mesh | ☐ | `docs/architecture/04-reconstruction-pipeline.md` |
| M3.3 | 纹理烘焙 + glTF 导出 | ☐ | 同上 |
| M3.4 | feature:gallery Filament 渲染回看 | ☐ | — |

## M4 — 工程治理 / Harness（贯穿）

| ID | 项 | 状态 |
|----|----|------|
| M4.1 | rgbd_sync harness（采样 + analyze.py） | ☐ |
| M4.2 | fusion_quality harness | ☐ |
| M4.3 | recon_quality harness | ☐ |
| M4.4 | `docs/architecture/registry/` 机器可校验真理源（modules / dependencies） | ☐ |
