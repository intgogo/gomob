# 00 — 架构总览

## 一图看全

```
┌─────────────────────────────────────────────────────────────────┐
│ 7. 表现层    feature:scan / feature:gallery / feature:calibration│
│              / feature:settings + core:ui + core:designsystem   │
├─────────────────────────────────────────────────────────────────┤
│ 6. 应用层    core:domain (用例) ← core:data (仓库) ← core:database│
│              + core:network                                      │
├─────────────────────────────────────────────────────────────────┤
│ 5. 重建层    native/reconstruction/  TSDF / MC / Poisson / Tex  │
├─────────────────────────────────────────────────────────────────┤
│ 4. 融合层    native/fusion/  外参投影 / 颜色回填 / 深度补洞     │
├─────────────────────────────────────────────────────────────────┤
│ 3. 几何层    native/depth/  反投影 / 畸变矫正 / 滤波            │
├─────────────────────────────────────────────────────────────────┤
│ 2. 同步层    CameraX(ImageAnalysis) + Berxel UsbDevice          │
│              ※ 同时间戳门禁（公理）                              │
├─────────────────────────────────────────────────────────────────┤
│ 1. 硬件层    USB-C OTG (Berxel iHawk) + Camera2/CameraX 主摄    │
└─────────────────────────────────────────────────────────────────┘
            ↕ 双向：core:native-bridge (JNI 唯一入口)
```

## 依赖图（仅允许的箭头）

```
app
 ├─→ feature:scan ──┐
 ├─→ feature:gallery┤
 ├─→ feature:calib ─┤── 全部 ─→ core:ui ─→ core:designsystem
 └─→ feature:settings                  ─→ core:model
                                       ─→ core:domain ─→ core:data ─→ core:database
                                                                    ─→ core:network
                                       ─→ core:native-bridge ─→ native/* (CMake)
                                                              └─→ third_party/berxel-android/
```

**禁止**：
- `core:*` 任何模块依赖 `feature:*`
- `core:designsystem` 反向依赖 `core:ui`
- `feature:*` 之间互相依赖（要共享走 core 层）
- 业务模块直接 `System.loadLibrary`（必须通过 `core:native-bridge.NativeBridge`）

## 模块职责（一行）

| 模块 | 职责 |
|------|------|
| `app` | 应用入口、Application、MainActivity、顶层 NavHost |
| `core:common` | Result / 异常 / 通用工具 |
| `core:model` | 跨层数据契约（RgbdFrame / Intrinsics / Extrinsics ...） |
| `core:data` | 仓库实现 → Domain 用例输入 |
| `core:database` | Room 表 + DAO（扫描元信息 / 标定参数 / 设备记录） |
| `core:domain` | 用例（UseCase）+ 业务规则；不依赖 Android Framework |
| `core:designsystem` | Material3 主题 / Token / 公共组件 |
| `core:ui` | 公共 Composable（PlaceholderScreen / 状态壳） |
| `core:network` | OkHttp + Retrofit（资源同步 / 模型下发） |
| `core:native-bridge` | JNI 唯一入口（Kotlin object NativeBridge） |
| `feature:scan` | 扫描主流程（CameraX + 深度服务 + Compose 实时预览 + 前台服务） |
| `feature:gallery` | 历史扫描列表 + 详情（Filament 3D 渲染） |
| `feature:calibration` | 双摄内/外参标定向导 |
| `feature:settings` | 偏好设置（DataStore） |

## 配置与产物

- 构建产物 / 日志 / 截图统一进 `.dev/`（gitignored）
- 标定参数等运行时配置进 Room（`core:database`）
- 扫描预设 YAML 进 `assets/`（编译期打包）
