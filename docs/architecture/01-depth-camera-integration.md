# 01 — 深度相机集成（核心专题）

> 本文档定义 Berxel iHawk 深度相机与 Android 主摄像头的"深度绑定"方案。
> 是整个工程**最关键**的专题，下游所有功能（融合 / 重建 / 标定）都建立在本节定义的接口之上。

## 1. 目标

把外接 Berxel iHawk 深度相机（USB-C OTG）和 Android 主摄像头**绑定**为一台逻辑上的 RGBD 扫描设备：

- **同步采集**：双流时间戳偏差 ≤ 5ms，不达标的帧丢弃
- **空间对齐**：双摄外参一次性标定 + 跨会话复用
- **深度绑定的语义**：业务层只看到一个 `RgbdFrame`，不再关心是哪两个物理设备来的

## 2. 当前已有素材

### 2.1 Windows 端 SDK（已挂载到本机 SMB）

详见 `docs/agent-memory/reference_berxel_sdk_locations.md`。关键路径：

- 头文件：`/root/WindowsR/berxel/sdk/Include/BerxelHawk*.h`
- Mix HD RGBD 样例：`/root/WindowsR/berxel/sdk/Samples/HawkMixHDColorDepth/`
- VIN RGBD 1:1 正射还原 demo（**最相关**）：`/root/WindowsR/berxel/sdk/Tools/VinRectifyDemo/vin_rectify_demo.cpp`
- VIN Qt 界面（**最相关**）：`/root/WindowsR/berxel/sdk/Tools/VinRectifyGui/src/`
- 设计文档（**必读**）：
  - `/root/WindowsR/berxel/sdk/docs/VIN_RGBD_Rectification_Design.md`
  - `/root/WindowsR/berxel/sdk/docs/HD_RGB_Texture_Projection_Design.md`
  - `/root/WindowsR/berxel/sdk/docs/Camera_Settings_Audit_20260425.md`
  - `/root/WindowsR/berxel/sdk/docs/MixHD_1280x800_probe_20260424.md`

### 2.2 Berxel Android SDK（已到位 2026-05-06）

- `third_party/berxel-android/libs/BerxelSDK.jar` 9.9.190（含 com.berxel.berxelInterface.api.* + assets/params*.bin 6MB×3 校准/滤波查找表）
- `third_party/berxel-android/jniLibs/{arm64-v8a,armeabi-v7a}/libBerxel*.so` + `libopencv_java3.so`
- 反编译笔记见 memory `finding_berxel_sdk_internals_2026-05-07.md`：SDK 内部 `BerxelHawkUsbManager` 构造时立即 `registerReceiver`（2-参签名，Android 14+ 必报 SecurityException 故业务侧需 ContextWrapper 拦），`requestDevicePermission` 用 PendingIntent flag=0（Android 12+ 必抛 IllegalArgumentException，DFU 升级模式雷点）

> **VINCreator APK 与 Berxel 无关**：`/root/WindowsR/berxel/sdk/VINCreator_*.apk` 是 eYs3D / Etron 摄像头 + jiangdg
> AndroidUSBCamera 框架做的 VIN 字符识别 demo（包名 `com.vin.uvc`，主类 `com.esp.uvc.*` + `com.jiangdg.demo.*`），
> .so 是 `libESPDI/libeysov/libUVCCamera`，**没有 com.berxel 类**。仅 USB OTG 权限处理流可作通用参考，跟 Berxel iHawk 接入无关。

### 2.3 Windows 端实测的关键事实（直接影响 Android 端设计）

来自 `MixHD_1280x800_probe_20260424.md` 与 `Camera_Settings_Audit_20260425.md`：

- iHawk 072 Mix HD `1280×800 @8fps` 可设置但读帧 `ret=-11`，需要降级 `640×400`
- 单独彩色流可达 `1920×1080`，但**不是** Mix HD 配准 RGB 用的那一路
- 彩色流支持分辨率：`640×400 / 1280×800 / 1920×1080`（**不**是常规 1280×720）

→ Android 端实现必须复刻 Windows VinRectifyGui 的"探测能用模式 → 缓存模式"逻辑。

## 3. 接入路径分阶段方案

### M1 — 物理通路打通

- **M1.1 USB OTG 设备发现 + 权限**
  - `AndroidManifest.xml` 已声明 `android.hardware.usb.host`
  - `usb_device_filter.xml` 占位 VID/PID 0x0000，等 Berxel SDK 到位后换真值
  - Activity `USB_DEVICE_ATTACHED` intent-filter 已注册，插入设备自动唤起
  - 运行期 `UsbManager.requestPermission` 申请访问权限
- **M1.2 主摄像头采集**
  - `feature:calibration` / `feature:scan` 用 CameraX `ImageAnalysis` 拿 YUV_420_888 帧（同 Windows 端原生分辨率，避开 1280×720 假设）
  - 内参从 `Camera2 CameraCharacteristics.LENS_INTRINSIC_CALIBRATION` 读，**不**自己估
- **M1.3 深度流采集**
  - 厂商 SDK 在位：调用 Berxel Android SDK 通过 JNI 注入 `gomob_native`
  - 厂商 SDK 缺位：走通用 UVC 路径（`libuvc` 或 AOSP `UsbDeviceConnection.bulkTransfer`），但**不糊**伪 Berxel 接口

### M2 — 时间戳同步（公理级别）

参考 Windows VinRectifyDemo 的同步逻辑。Android 端方案：

```
Camera2 frame timestamp 单位 ns（System.nanoTime() 同源）
Berxel frame timestamp 单位 us（厂商时基）→ 用首帧标定到 nanoTime 同基准

每个 RGB 帧来：放入 RGBQueue（容量 N，按时间戳排序）
每个 Depth 帧来：在 RGBQueue 找最近邻 |Δt| ≤ SYNC_THRESHOLD_NS（默认 5ms）
  - 命中：组装 RgbdFrame 推到 fusion 阶段
  - 未命中：丢帧，记入 .dev/sync_drops.log
```

时间戳偏差超阈值的帧**丢弃**，不"内插"、不"凑合用"。这是公理。

**Harness 触发**：参数敏感（SYNC_THRESHOLD_NS）+ 长时序依赖 →
`tests/harness/rgbd_sync/`（M1.3 时一并建）。

### M3 — 双摄外参标定

参考 `VIN_RGBD_Rectification_Design.md` 的方案，移植到 Android：

- 标定板：Charuco（鲁棒于运动模糊，移动场景比纯棋盘好）
- 流程（`feature:calibration` UI 向导）：
  1. 引导用户按 N 个角度拍 Charuco（每次同时抓 RGB + Depth）
  2. 单目内参标定（RGB / Depth 各一次）
  3. 立体外参标定（求解 R / t）
  4. 标定结果写 Room（`core:database` `CalibrationDao`），跨会话复用
- 验收指标（`tests/harness/calibration_quality/`）：
  - reprojection error ≤ 1.0 px (RGB) / ≤ 0.5 mm (Depth)
  - 跨标定一致性：两次独立标定结果旋转/平移差异 ≤ 阈值

### M4 — 主从语义

外参标定后，业务层看到的"绑定相机"语义：

- **主摄像头是"主"**：定义参考坐标系（世界 = RGB 相机系）
- **深度相机是"从"**：所有深度点云通过外参变换到 RGB 系
- 这条规则一旦确立，后续 fusion / reconstruction 全部按 RGB 系工作，避免坐标系混乱

`core:model.StereoExtrinsics` 字段就是按这条语义命名的：`R` / `t` 把 depth 系 → rgb 系。

## 4. JNI 边界（详见 `03-jni-boundary.md`）

`io.gomob.nativebridge.NativeBridge` 已定义两个核心方法：

```kotlin
external fun depthToPointCloud(depth, w, h, fx, fy, cx, cy): FloatArray
external fun colorizePointCloud(points, rgb, ..., R, t): ByteArray
```

后续 M1.3 / M2 / M3 会陆续追加 `attachUsbDevice` / `startStreams` /
`registerSyncCallback` 等。**所有**新增 native 入口必须经 NativeBridge 暴露，不允许散点。

## 5. 厂商 SDK 缺位的退化路径（**已不适用** 2026-05-06）

Berxel 官方 Android SDK（jar + 多 ABI .so + assets/params*.bin）已到位 `third_party/berxel-android/`，
当前直接走官方 jar 路径，无需 dlopen/dlsym 桩。本节保留作历史记录。

## 6. 待办（落到 TODO.md）

| ID | 项 |
|----|----|
| M1.1 | USB OTG 设备发现 + 权限授予 + 热插拔处理 |
| M1.2 | CameraX RGB 采集 + 内参读取 + 帧时间戳归一化 |
| M1.3 | Berxel SDK 接入（厂商 SDK 在位/缺位两条路径决策点） + RGBD 同步 harness |
| M1.4 | 单帧点云 Filament 可视化 — 端到端最小闭环 |
| M2.* | 双摄外参标定向导 + harness（见 `05-calibration-pipeline.md`） |

详见 `TODO.md`。
