# 15 — 激光扫描设备集成（车辆外廓 · 双单元 LIDAR-PTZ）

> 在「3D 车辆外廓扫描」页新增**激光设备**：顶栏切换设备，激光页 = 融合点云（复用）+ 两单元各自点云 + 操作键；Berxel 页保持现状。
> 设计文档（为什么）。实施进度见 `TODO.md` M8' 章；几何逆向真理源见 `/root/lilw/lidar/re/`。
>
> ⚠️ **架构变更（2026-06-03，M8'）：激光连接 / 采集 / 融合全部下沉服务端，App 退化为瘦客户端（只显示 + 操作）。**
> **下面 §1–§7 为已被取代的端侧 native 方案**（保留作几何/单位/逆向背景）；**现行权威方案见 §9「M8' 服务端版」**。
> 用户拍板（M8'）：①C++ 管线 **cgo 链 `lidar_core.a`**（带逐帧点回调）；②融合 **半复用**（新 `laser_scan_jobs` 表 + 复用
> `scan.fusion_done` 实时桥）；③点云 **ws/gRPC 流式推点**（采集中实时预览）；④laserworker **同网段**直连 `.101/.102`。

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

---

## 9. M8' 服务端版（现行权威，2026-06-03）

激光「连接 + 采集 + 融合」全部在服务端；App = 瘦客户端（设备切换 + 三朵点云显示 + 操作键）。

### 9.1 服务端：新服务 `cmd/laserworker`（不扩 cmd/device）
`cmd/device` 语义=用户绑定的物理传感器（Berxel SN + 版本化标定）；激光是网络采集基础设施（`.101/.102`，
按需扫描会话）。两者生命周期/伸缩/依赖不同 ⇒ 新建 `cmd/laserworker`（:18087，env `GOMOB_LASERWORKER_HTTP_ADDR`），
同构 `cmd/fusionworker`。职责：HTTP 探活 `.101/.102:4000` → **cgo 调 C++ 管线**采集+融合 → 三朵 PCD（fused+unitA+unitB）+
calib 落 MinIO → `laser_scan_jobs` 表（自有，`FOR UPDATE SKIP LOCKED` 同 ScanFusionRepo 范式）→ 完成发 NATS。

**到达激光 LAN**：laserworker 与 `.101/.102` 同网段（dev `--network host`，本机已在 192.168.9.0/24）。控制面 Go
`net/http` 打 `:4000`；采集面 **不在 Go 手写 TCP CA-FE/CRC/zstd** —— 交 C++（已 byte 验证）。

### 9.2 C++ 托管：cgo 链 `lidar_core.a` + 新 C-ABI（带逐帧点回调）
在 `/root/lilw/lidar` 新增 `src/lib/lidar_scan.{h,cpp}`（纯 C-ABI，extern "C"），包装现有
`captureSweep`+`reconstructVehicle`，关键是**逐帧/逐批点回调**支撑流式：
```c
typedef void (*LidarPointCB)(void* user, int unit, const float* xyz_mm, int n, float h_angle_deg);
typedef void (*LidarStatusCB)(void* user, const char* state, int frames_a, int frames_b);
typedef struct { int pts_a, pts_b, fused, after_crop; float b_to_a[16]; char align[16]; } LidarScanResult;
// 起一次实时扫描（连接+采集+融合）。align: "icp"|"none"|"site"。阻塞至完成/错误。0=成功。
int lidar_scan_live(const char* ipA, const char* ipB, const char* align, const char* site_json,
                    float keep_ratio, LidarPointCB on_points, LidarStatusCB on_status,
                    void* user, LidarScanResult* out);
void lidar_scan_cancel(void);   // 协作取消（SCAN_STOP + 停抓流）
```
构建：`lidar` CMake 出 `liblidar_scan.a`（精简 STATIC，仅激光子集）；gomob server 经 cgo `#cgo LDFLAGS`
链入。Dockerfile 对照 `Dockerfile.cvengine` 多阶段。
> 终态钩子已就位：流式逐帧进度即靠 `on_points` 回调（capture 循环每解一批 PTS 帧即回调 unit=0/1，融合后回调 unit=2）。

**✅ cgo 链路已端到端验证（2026-06-03，真机录制数据）**：`liblidar_scan.a`（含 device/cloud(fusion/registration/cloud_build/io_pcd)/config/lib，**仅 PCL 核心**：common/io/kdtree/search/octree/filters/registration/sample_consensus/features + flann + yaml-cpp + zstd + boost_system/filesystem — **无 OpenCV/Ceres/VTK/Qt**）。Go cgo PoC（C trampoline 转 Go `//export`）调 `lidar_scan_replay`，**流式点穿过 cgo 进 Go**：a=1642122 b=2497893 fused=4140015（与 result 精确一致），状态 scanning→fusing→done。M8'-G3 直接照此 cgo pattern + LDFLAGS 实现。
```
// 验证过的 cgo LDFLAGS（M8'-G3 用）：
#cgo CFLAGS: -I<lidar>/src
#cgo LDFLAGS: -L<libdir> -llidar_scan -lpcl_common -lpcl_io -lpcl_kdtree -lpcl_search -lpcl_octree \
  -lpcl_filters -lpcl_registration -lpcl_sample_consensus -lpcl_features -lflann_cpp -lyaml-cpp -lzstd \
  -lboost_system -lboost_filesystem -lstdc++ -lpthread -lm
// 回调：C trampoline(精确匹配 const float*) → goPointCB/goStatusCB(//export)；//export 形参名须唯一(不能全 _)。
```

### 9.3 融合 job 流（半复用）
```
App POST /v1/scans/laser → laserworker 建 laser_scan_jobs(capturing) 返回 scan_id/session_key
  → 后台: HTTP 探活 → cgo lidar_scan_live(on_points 流式 → ws 推点) → 三 PCD 落 MinIO
  → UPDATE done → NATS publish "scan.fusion_done" {owner_user_id, session_key, kind:"laser", object_keys, points}
  → 复用 internal/signaling/fusion_bridge.go(按 owner_user_id 路由 ws) → App 收事件
```
复用零改：`fusion_bridge.go`（NATS→ws，领域无关）、asset presign / MinIO `gomob-assets`。**不复用** RGBD `/fuse`
（RgbdShot→Open3D TSDF→GLB，与激光 PCD/ICP 语义不兼容），**新建 `laser_scan_jobs` 表**（migration 0018）。

### 9.4 App⇄server 契约（经 gateway :18808）
新增 1 条 gateway 路由 `{Prefix:"/v1/scans/laser", Target:targetLaserWorker="http://127.0.0.1:18087"}`；
`/v1/ws`、`/v1/assets/` 复用不动。
- `POST /v1/scans/laser {inspection_id?, unit_a_ip?, unit_b_ip?, align, keep_ratio}` → 201 {scan_id, session_key, status:"capturing"}。状态机 capturing→fusing→done|failed。
- `POST /v1/scans/laser/{scan_id}/stop` → SCAN 停止（cgo `lidar_scan_cancel`）。
- **流式推点（选定）**：`WS /v1/ws?token=` 复用 signaling，采集中推增量点帧
  `{type:"laser.points", payload:{session_key, unit:0|1|2, points:[...mm], h_angle_deg}}`；完成发
  `{type:"scan.fusion_done", payload:{kind:"laser", session_key, result_object_key, unit_a/b_object_key, points, align_method}}`。
  断线兜底 `GET /v1/scans/laser/{scan_id}` 拉状态 + presign 取最终三朵 PCD。
> proto/laser.proto（新建）定义 NATS payload + 流帧；服务间 REST（与 fusionworker/asset 一致）。

### 9.5 App 瘦客户端
- 顶栏 `DeviceSwitcher`（段控 激光/Berxel）；`VehicleContourScanScreen` 加 deviceMode 分支。
- `LaserCaptureBody`：fused `PointCloud3dView`（上 60%）+ unitA/unitB `PointCloud3dView` 并排（下 40%）+ `LaserControlBar`（开始/停止）。LASER 模式隐藏 `DualPreviewRow`/`AngleRing`/`CompletedPanel`(GLB)。
- `LaserScanViewModel`/`LaserScanState`（Idle→Connecting→Scanning→Processing→Completed|Error，无 Uploading）：调 §9.4 路由，ws 收 `laser.points` 增量喂三朵云、收 `scan.fusion_done` 终态。`LaserScanService`(OkHttp REST + ws)。
- **端侧 native/lidar（M8.1）处置：保留休眠 + DEPRECATED 标注 + 从 .so 构建剔除**（作服务端 cgo 的已验证参照与 debug 退路；显式禁用即非运行时假 fallback；休眠码≠stub）。稳定一里程碑后再评估删除。

### 9.6 复用 vs 新建（M8'）
复用零改：`PointCloud3dView`、`fusion_bridge.go`、asset presign、gateway 反代、`/v1/ws` signaling、`lidar_core`(STATIC)。
新建：lidar `lib/lidar_scan.*`(C-ABI)；server `cmd/laserworker`、`internal/laser/*`、migration 0018、proto/laser.proto、gateway 路由；App `DeviceSwitcher`/`LaserCaptureBody`/`LaserScanViewModel`/`LaserScanService`。
