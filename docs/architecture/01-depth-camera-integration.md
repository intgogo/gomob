# 01 — Berxel iHawk 接入专题（核心专题）

> 本文档定义 Berxel iHawk 深度相机的接入方案。
> 是整个工程**最关键**的专题，下游所有功能（重建 / VIN 拓印 / 标定）都建立在本节定义的接口之上。

## 1. 目标

把外接 Berxel iHawk 深度相机（USB-C OTG）作为整个 App 的**唯一**视觉输入：

- **同设备双流**：iHawk 自身有 Color + Depth 两路传感器，**同一物理设备**，硬件级时间戳同步
- **Color↔Depth 标定**：iHawk 自身两路传感器之间的相对外参一次性标定 + 跨会话复用
- **业务层只看到 iHawk**：不再涉及 Android 主摄；手机摄像头不参与扫描管线

> **重大决策（2026-05-07）**：早期"主摄 + 深度双摄合一"路线已**整体砍掉**。原因：
> 业务实际需要的两条主线（三维外廓扫描 / VIN 数码拓印）都只用 iHawk 自带的双流，
> 接入手机主摄会引入跨设备时间戳同步、多套内参标定、坐标系混乱等真实复杂度，
> 而不带来产品价值。`feature:scan3d` 入口卡片代表的两个功能，都基于 iHawk 单设备
> 内部的 Color + Depth 工作。

## 2. 当前已有素材

### 2.1 Berxel Android SDK（已到位 + 已 patch 2026-05-07）

- `third_party/berxel-android/libs/BerxelSDK.jar` 9.9.190（含 com.berxel.berxelInterface.api.* + assets/params*.bin 6MB×3 校准/滤波查找表）
- `third_party/berxel-android/jniLibs/{arm64-v8a,armeabi-v7a}/libBerxel*.so` + `libopencv_java3.so`
- `patches/berxel-android/{BerxelJarPatch.java,patch.sh}` —— ASM bytecode patch 修
  Android 12+/14+ PendingIntent 兼容（详见 memory `finding_berxel_sdk_internals_2026-05-07.md`）
- `BerxelService.kt` 已实测通过：MIX 模式 Color 640×400@30 + Depth 640×400@30 双流 29 fps 稳定

### 2.2 Windows 端 SDK 资源（参考实现）

详见 memory `reference_berxel_sdk.md`。关键路径：

- 头文件：`/root/WindowsR/berxel/sdk/Include/BerxelHawk*.h`
- Mix HD RGBD 样例：`/root/WindowsR/berxel/sdk/Samples/HawkMixHDColorDepth/`
- VIN 正射还原 demo（**最相关**）：`/root/WindowsR/berxel/sdk/Tools/VinRectifyDemo/vin_rectify_demo.cpp`
- VIN Qt 界面：`/root/WindowsR/berxel/sdk/Tools/VinRectifyGui/src/`
- 设计文档（**必读**）：
  - `/root/WindowsR/berxel/sdk/docs/VIN_RGBD_Rectification_Design.md`
  - `/root/WindowsR/berxel/sdk/docs/HD_RGB_Texture_Projection_Design.md`
  - `/root/WindowsR/berxel/sdk/docs/Camera_Settings_Audit_20260425.md`
  - `/root/WindowsR/berxel/sdk/docs/MixHD_1280x800_probe_20260424.md`

### 2.3 Windows 实测的关键事实（直接影响 Android 端设计）

来自 `MixHD_1280x800_probe_20260424.md` 与 `Camera_Settings_Audit_20260425.md`：

- iHawk 072 Mix HD `1280×800 @8fps` 可设置但读帧 `ret=-11`，需要降级 `640×400`
- 单独彩色流可达 `1920×1080`，但**不是** Mix HD 配准 RGB 用的那一路
- 彩色流支持分辨率：`640×400 / 1280×800 / 1920×1080`（**不**是常规 1280×720）

→ Android 端实现必须复刻 Windows VinRectifyGui 的"探测能用模式 → 缓存模式"逻辑。

## 3. 接入栈

### 3.1 USB OTG 接入（已实测通过）

- `AndroidManifest.xml` 已声明 `android.hardware.usb.host`
- `usb_device_filter.xml` 已填实测 VID=0x0603 + product `Berxel iHawk071`
- Activity `USB_DEVICE_ATTACHED` intent-filter 把 attach 事件路由到 `MainActivity.consumeUsbAttachIntent`
  → `BerxelService.attachAuthorizedDevice(usbDevice)` 把 intent extras 里带权限的 UsbDevice 喂给 service
- `BerxelService.startInternal` 在 `usbManager.openDevice` 失败时主动调 `requestPermission` 兜底
  （IMMUTABLE | UPDATE_CURRENT，配合自家广播 receiver）

### 3.2 SDK Context 生命周期

`BerxelService` 是 Hilt `@Singleton`，整个 App 进程独占一个 SDK Context + 一台 device：

```
start() → SdkCompatContextWrapper(appContext) → BerxelHawkContext.getBerxelContext(...)
       → ctx.CreateDevice() → dev.openDevice(callback)
       → onDeviceStausOpenSuccess
         → setStreamFlagMode(MIX) → setFrameMode(Color/Depth) → startStreams
         → 起两个 reader 线程 (berxel-color-reader / berxel-depth-reader) 拉帧
```

异常路径全部归并到 `BerxelDeviceState` sealed 类型。详见
`core/native-bridge/src/main/kotlin/io/gomob/nativebridge/berxel/BerxelService.kt`。

### 3.3 Color / Depth 双流（同一物理设备）

iHawk 的 Color 与 Depth 来自**同一硬件**的两个传感器：

| 维度 | Color stream | Depth stream |
|------|-------------|--------------|
| 物理传感器 | iHawk RGB CMOS | iHawk 结构光 / ToF |
| 时间戳 | SDK Frame.timeStamp（μs，同时基） | 同上 |
| 分辨率 | 640×400 / 1280×800 / 1920×1080 | 同 Color 系列 |
| 像素格式 | YUYV / NV21（视 PixelType） | 16bit 深度（mm，0=无效） |
| 内参 | iHawk Color 镜头出厂内参 | iHawk Depth 镜头出厂内参 |

SDK 提供：

- `dev.getCameraIntriscParams()` —— 返回出厂烧入的内参（Color/Depth 哪一路、是否含外参，以实测为准；见 §4 标定）
- `dev.setRegistrationEnable(true)` —— 让 SDK 内部把 Depth 重投影到 Color 像素坐标，**输出已对齐的深度**
- `dev.setStreamFlagMode(MIX)` —— Color + Depth 同时出帧

> 时间戳同步：因为来自同一物理设备，**硬件级同步**，不需要软件层"最近邻匹配 + 阈值丢帧"
> 那一套（早期方案 §M2 的内容已废）。

## 4. iHawk Color/Depth 标定（仍然需要，重定向）

> 早期版本认为标定是"主摄↔深度"——已废。当前是 iHawk 自身两路传感器间的标定，目的：
> 三维外廓扫描的**彩色点云着色**和 VIN 拓印的**Color/Depth 联合分析**都需要双流空间对齐。

### 4.1 是否需要自标定 — 实测决策

SDK 已提供出厂内外参 + `setRegistrationEnable(true)` 软对齐。**先实测厂家给的对齐
精度**，决定是否要自己标定：

- 实测条件：放置棋盘格 / 已知尺寸物体在 30cm / 50cm / 1m 三个距离
- 验证指标：开 `setRegistrationEnable(true)` 后，深度点云投到 Color 图像上的边缘
  误差是否 ≤ 2 px（视产品需求收紧）
- 不达标 → 走自标定路径（`feature:calibration` 向导），把 SDK 出厂参数当先验初值

### 4.2 自标定路径（备用，详见 `05-calibration-pipeline.md`）

- 标定板：Charuco（运动鲁棒）
- 流程：用户按 N 个角度同时抓 iHawk Color + Depth 两路 → 单目内参（Color、Depth 各一次）
  → 立体外参（Color↔Depth 间 R, t）→ 写 Room `CalibrationDao` 跨会话复用
- 验收指标：reprojection error ≤ 1.0 px（Color）/ ≤ 0.5 mm（Depth）

### 4.3 主从语义

标定后业务层的坐标系约定：

- **Color 系是"主"**：定义参考坐标系；UI 预览 / 纹理回填 / 正射图都按 Color 像素坐标走
- **Depth 系是"从"**：所有 depth 点云通过 `StereoExtrinsics(R, t)` 变换到 Color 系

`core:model.StereoExtrinsics` 字段语义：`R` / `t` 把 **depth 系 → color 系**（同一 iHawk 内部）。

## 5. JNI 边界（详见 `03-jni-boundary.md`）

`io.gomob.nativebridge.NativeBridge` 暴露的方法：

```kotlin
external fun depthToPointCloud(depth, w, h, fx, fy, cx, cy): FloatArray
external fun colorizePointCloud(points, rgb, ..., R, t): ByteArray  // R,t = iHawk Depth→Color 外参
external fun icpRegister(srcPoints, dstPoints, initialPose): FloatArray  // 多帧配准
external fun tsdfFuse(sessionHandle, points, pose): Long  // 增量融合
external fun tsdfExtractMesh(sessionHandle): MeshBuffer  // Marching Cubes
external fun orthoRectify(depth, color, ..., distance): ByteArray  // VIN 拓印用
```

**所有**新增 native 入口必须经 `NativeBridge` 暴露，业务层不允许散点 `external fun` /
`System.loadLibrary`。

## 6. 待办（落到 TODO.md，详见同文件）

| ID | 项 |
|----|----|
| ✅ M1.1 | Berxel SDK 接入 + USB OTG + Android 12+/14+ PendingIntent patch + 真机 streaming 走通 |
| M1.2 | iHawk Color/Depth 帧字节流暴露（DirectByteBuffer / 零拷贝）+ 实时预览 |
| M1.3 | iHawk 内参读取 + setRegistrationEnable 精度实测 |
| M2.* | iHawk Color↔Depth 标定（如 M1.3 不达标）—— `05-calibration-pipeline.md` |
| M3.* | 三维外廓扫描重建 —— `04-reconstruction-pipeline.md` |
| M4.* | VIN 数码拓印 —— `08-vin-rectify-design.md` |
