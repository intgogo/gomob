# 15 — 激光扫描设备集成（车辆外廓 · 双单元 LIDAR-PTZ）

> 在「3D 车辆外廓扫描」页新增**激光设备**：顶栏切换设备，激光页 = 融合点云（复用）+ 两单元各自点云 + 操作键；Berxel 页保持现状。
> 设计文档（为什么）。实施进度见 `TODO.md` M8 章；几何逆向真理源见 `/root/lilw/lidar/re/`。

## 1. 背景与目标

激光硬件 = 两台一体化扫描单元 LTS-T1（2D 线激光 Pico100 + 旋转 PTZ 轴），分置车两侧扫整车外廓。
网络设备：HTTP REST `:4000`（`control_scan`/`device_status`/`device_info`）+ 原始 TCP 扫描流
`:4010`(PTS 笛卡尔) / `:4002`(LDR 极坐标) / `:4001`(ENC) / `:4003`(IMG)。帧 = `CA FE` 头 + CRC-16/MODBUS +
可选 zstd。一套已 byte-verified 的 C++ 管线在 `/root/lilw/lidar`（逆向 Windows 原厂 QtTrainScan）。

**目标**：把该管线迁进 gomob，端侧驱动两台激光扫一台车、ICP/site 配准融合出外廓点云，在车辆外廓页可视化。

## 2. 关键判断：激光 ≠ RGBD 主线

| 维度 | RGBD（berxel/eYs3D） | 激光（.101/.102） |
|---|---|---|
| 连接 | USB OTG / libusb | TCP/HTTP 局域网 |
| 数据 | Color+Depth 帧 | PTS/LDR 点集（无 RGB） |
| 融合 | 云端 TSDF 多视角 | 两单元 ICP/site + union（轻量点集运算） |
| 产物 | GLB mesh | PCD 点云 |

⇒ 不套用「相机 → 上云融合」主线，不污染 `CameraSource`（不伪造 color/depth 帧）。

## 3. 架构（已定，2026-06-03 用户拍板）

**三层拆分：Kotlin 管网络 + native 做纯函数几何 + 端侧融合（不上云）。**

- **决策 A = Kotlin 网络 + native 几何**：OkHttp/`java.net.Socket` 在 Kotlin 管控制/抓流/状态门控；
  native 只做纯函数融合。JNI 边界保持「纯数据进出」（对照 `scanSessionIngest`）。
- **决策 B = 端侧 native 融合**：两单元 union+ICP 轻量，端侧秒级出点云、零服务端改动、断网可用。
- **决策 C = site-extrinsic 优先 + ICP 兜底**：离线标定冻结 4×4 存 `core:database`/asset，端侧读；无则
  跑 4-yaw ICP。对照 `scan_vehicle.cpp`（site > icp > identity）。
- **决策 E = 子网扫描发现**：`LaserScanner` 扫局域网，对候选 IP 探 `/api/device_info` 匹配 LTS-T1，
  自动发现两台；换网/换 IP 不用改配置。

### 为什么不引 PCL
`native/` 只投放 Eigen 3.4（无 PCL）。lidar 用到的 PCL 仅 union/RandomSample/CropBox 3 算子 + ICP，
全部可用 Eigen 在 <200 LOC 重写（已做，见 `native/lidar/fusion.cpp`），ICP 直接**复用
`reconstruction/IcpRegister`**（Eigen SVD/Umeyama）。交叉编译 PCL 进 NDK 是数周不确定工作量且违背
「single libgomob_native.so」，故彻底不引 PCL/Ceres/OpenCV/yaml（标定离线产 site-extrinsic，端侧只读 4×4）。

### 单位约定
native/lidar **全程 mm**（对齐 gomob native），与桌面 lidar 的米制差 ×1000。

## 4. 模块落点

| `/root/lilw/lidar/src` | gomob 落点 | 状态 |
|---|---|---|
| cloud/types.h | `native/lidar/lidar_types.h`（Cloud=`vector<Vector3f>`） | ✅ M8.1 |
| cloud/cloud_build（lineToWorld） | `native/lidar/cloud_build.*` | ✅ M8.1 |
| cloud/fusion（union/keep/crop） | `native/lidar/fusion.*`（去 PCL） | ✅ M8.1 |
| cloud/registration（ICP 4-yaw） | `native/lidar/registration.*`（包 `IcpRegister`） | ✅ M8.1 |
| pipeline/scan_vehicle | `native/lidar/scan_vehicle.*` | ✅ M8.1 |
| cloud/io_pcd | `native/lidar/io_pcd.*`（最小 PCD writer） | ✅ M8.1 |
| device/scan_stream（CA FE/CRC/zstd 解帧） | `native/lidar/scan_stream.*` + `NativeBridge.lidarParseFrames` | ⏳ M8.2 |
| device/http_client | Kotlin `LaserScanner`（OkHttp 重写） | ⏳ M8.3 |
| device/stream_capture（socket+轮询） | Kotlin `LaserScanner`（Socket+协程重写） | ⏳ M8.3 |
| calib/*（Ceres）、texture/*（OpenCV）、config/*（yaml）、app/main_cli | **不迁** | — |

### JNI 新签名（`NativeBridge`，前缀 `lidar*`）
```kotlin
external fun lidarParseFrames(raw: ByteArray, frameType: String): FloatArray         // 解帧→点云 mm
external fun lidarReconstructVehicle(unitA: FloatArray, unitB: FloatArray,
    alignMethod: String, siteExtrinsic: DoubleArray, keepRatio: Float,
    cropMin: FloatArray, cropMax: FloatArray, outPcdPath: String?): FloatArray        // 融合→点云 mm
external fun lidarLastResult(): LidarScanResult                                       // 统计
```
**无状态单次调用**（抓流已在 Kotlin 完成，native 只做纯函数融合），比 berxel 有状态 `scanSession*` 简单。

## 5. UI（feature:scan3d）

- 顶栏 `BackHeader.trailing` 加 `DeviceSwitcher`（分段「激光/Berxel」）。
- `CaptureBody` 按 `deviceMode` 分流；激光体 = `FusedCloudPanel`（复用 `PointCloud3dView`）+
  `DualUnitCloudRow`（两个同款 `PointCloud3dView` 喂 unitA/unitB）+ `LaserCaptureBar`（开始扫描/融合/重来，复用
  `ShutterButton`/`RoundSideButton`，无 8 方位环）。
- VM：`VehicleContourScanViewModel` 加 `deviceMode`/`laserState` + `LaserScanController` 委托；切设备时
  release 旧源/acquire 新源/reset 子状态。`LaserScanState`：Idle/Connecting/Scanning/Fusing/Completed/Error。

## 6. 复用 vs 新建

复用零改：`PointCloud3dView`、`ShutterButton`/`RoundSideButton`/`PreviewPane`/`BackHeader`、
`reconstruction/IcpRegister`、Eigen。新建：`native/lidar/*`、`NativeBridge.lidar*`、`core:model` 的
`LidarScanResult`/`LaserScanState`、`LaserScanner`(network)、`DeviceSwitcher`/`LaserCaptureBody`、VM 分支。

## 7. 待办依赖

- **zstd**：PTS 帧 zstd 压缩，NDK 不自带 libzstd ⇒ M8.2 需 vendor zstd（`third_party/zstd-android`）或静态编入。
- **site-extrinsic 标定**：离线桌面产 4×4 JSON（`/root/lilw/lidar` 已有 `site-extrinsic` 子命令），端侧只读。
- **INTERNET 权限** + 局域网（手机需接入激光 Wi-Fi/AP）。

## 8. 验收

M8.1 host 单测 `scripts/lidar-host-test.sh`：union/keep/crop 计数精确、ICP 复原 180° yaw（误差 0.07mm）、
reconstructVehicle ICP/site 双路、lineToWorld 前向链正确，**零 PCL 链接通过**。后续 harness 见 TODO M8.7/8.8。
