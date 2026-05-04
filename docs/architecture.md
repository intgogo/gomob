# gomob 架构总入口

> 先看本文，再按需深入专题。

## 一句话定位

把外接 Berxel iHawk 深度相机和 Android 主摄像头**深度绑定**，做成一台移动 3D 扫描设备。

## 七层分层（从下到上）

| 层 | 职责 | 在本仓的实体 |
|----|------|-------------|
| 1. 设备/硬件层 | USB-C OTG / CameraX 驱动 / Berxel SDK | `core:native-bridge` 暴露的 `attachUsbDevice` 等 + `third_party/berxel-android/` |
| 2. 同步采集层 | RGBD 双流读取 + 时间戳同步 + 内参附加 | `native/depth/`、CameraX `ImageAnalysis` 流 |
| 3. 几何层 | 反投影 / 畸变矫正 / 噪声滤波 | `native/depth/depth_projection.cpp` |
| 4. 配准与融合层 | 外参变换 / 颜色回填 / 深度补洞 | `native/fusion/colorize.cpp` |
| 5. 重建层 | TSDF 体素 / Marching Cubes / Poisson / 纹理烘焙 | `native/reconstruction/` |
| 6. 应用层 | 用例编排 / 状态机 / 持久化 | `core:domain`、`core:data`、`core:database` |
| 7. 表现层 | Compose UI / Filament 3D 预览 | `feature:scan`、`feature:gallery`、`feature:calibration`、`feature:settings`、`core:ui`、`core:designsystem` |

**依赖方向**：上层依赖下层，下层绝不依赖上层。详见 `02-app-architecture.md`。

## 专题文档

| 文件 | 主题 | 状态 |
|------|------|------|
| `architecture/00-overview.md` | 7 层分层 + 模块映射 + 依赖图 | 草稿 |
| `architecture/01-depth-camera-integration.md` | 深度相机绑定（USB OTG / 双摄外参标定 / RGBD 同步 / Berxel SDK 接入） | 草稿 |
| `architecture/02-app-architecture.md` | App 多模块切分 + 依赖规则 | 草稿 |
| `architecture/03-jni-boundary.md` | JNI 边界契约（数据通道 / 错误模型 / 零拷贝） | 草稿 |
| `architecture/04-reconstruction-pipeline.md` | 重建管线（采集 → 同步 → 配准 → 融合 → TSDF/MC → 纹理） | 待写 |
| `architecture/05-calibration-pipeline.md` | 双摄内/外参标定 | 待写 |

## 设计原则速览

详见 `CLAUDE.md`：

- **第一性优先** — 先建模后编码；不接受"先写个简单的看看"
- **数据驱动** — 标定参数 / 设备型号 / 扫描预设 YAML 配置化
- **JNI 单一入口** — `core:native-bridge.NativeBridge` 是 Kotlin → C++ 唯一桥
- **大数据零拷贝** — RGBD 走 `DirectByteBuffer`/`HardwareBuffer`，JNI 数组只用于元数据
- **同步性是公理** — 双流时间戳偏差超阈值丢帧，不"凑合用"
- **离线可运行** — 厂商 SDK 缺位时走通用 UVC 路径，不阻塞工程演进

## Registry 真理源

机器可校验的治理元数据放在 `architecture/registry/`：

- `modules.yaml` — 模块清单 + 归属层
- `dependencies.yaml` — 模块间允许的依赖关系
- `capabilities.yaml` — 能力成熟度矩阵

后续接 `scripts/archgov.kts`（占位）做 CI 守门。
