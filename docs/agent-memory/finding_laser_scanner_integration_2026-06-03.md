# 激光扫描设备集成（车辆外廓双单元 LIDAR-PTZ） 2026-06-03

## What

在「3D 车辆外廓扫描」页新增**激光设备**（顶栏切换设备：激光 / Berxel）。激光 = 两台网络扫描单元
LTS-T1（.101/.102，HTTP :4000 + TCP 流 :4010 PTS / :4002 LDR / :4001 ENC / :4003 IMG），把
`/root/lilw/lidar`（逆向 Windows 原厂、byte-verified）的几何管线迁进 gomob。

## Why（架构判断）

激光 ≠ RGBD 主线：TCP 而非 USB、点集而非 Color+Depth、两单元 ICP/site 配准而非云端 TSDF、产物 PCD 而非
GLB。⇒ 不套「相机→上云融合」、不污染 `CameraSource`（不伪造帧）。

**用户拍板（first-principles）**：①Kotlin 网络 + native 几何；②端侧融合不上云；③site-extrinsic 优先 +
ICP 兜底；④子网扫描发现设备。**不引 PCL**：lidar 只用 union/RandomSample/CropBox + ICP，全 Eigen 重写
（<200 LOC），ICP 复用 `reconstruction/IcpRegister`。native/lidar 全程 **mm**（对齐 gomob）。

## How to apply

- 设计文档：`docs/architecture/15-laser-scanner-integration.md`；任务：`TODO.md` M8 章。
- 隔离：worktree `.worktrees/laser-scan`，分支 `feat/laser-scan-integration`（避开 feat/rgbd-stream-client 上 923 行 berxel WIP）。
- **M8.1 已完成并提交**：`native/lidar/`（lidar_types/cloud_build/fusion/registration/scan_vehicle/io_pcd），
  host 单测 `scripts/lidar-host-test.sh` 全过（ICP 复原 180° yaw 误差 0.07mm），零 PCL 进 `libgomob_native.so`。
- 待办：M8.2 解帧需 vendor zstd（NDK 不自带）；M8.3 Kotlin `LaserScanner`（子网发现+OkHttp+Socket）；
  M8.4 JNI `lidar*`+core:model；M8.5-6 VM 设备切换+UI（复用 `PointCloud3dView`/Shutter 按钮）；M8.7-8 真机+harness。
- 真理源：几何逆向在 `/root/lilw/lidar/re/`；site-extrinsic 离线桌面产 4×4 JSON（lidar `site-extrinsic` 子命令），端侧只读。

## 进展更新（2026-06-04）

**架构已落定为「服务端下沉 + App 瘦客户端」（M8'，非上面 M8.x 端侧 JNI 方案）**。端到端 through-App 真机已跑通，
设备控制面（原厂功能键）已补齐。权威细节见 `docs/architecture/15-laser-scanner-integration.md` §9.7/§9.8、`TODO.md` M8'。

- **M8'-E2 全链验证（commit fde8b7a/7297314）**：App→devserver(:18808)→laserworker(:18087,laser_cgo)→.101/.102→NATS→ws→App。
  devserver（dev all-in-one 网关）补 `newLaserProxy`+`startLaserBridge` 后才接通激光。融合视图全黑由 `PointCloud3dView.autoFit`
  修复；**align 默认 icp→none**（ICP 对固定双机位不稳，site 外参接线是后续）。
- **M8'-F 设备控制面（commit 230c986/c2bdf5f）**：服务端 5 条 literal 路由 `/v1/scans/laser/device-{status,info,command,scan-settings,calib}`
  （+`?unit=a|b`，避 wildcard 路由冲突）；App `LaserDeviceControl.kt` ModalBottomSheet（状态/控制/扫描设置/标定/信息四组）。
- **M8'-A5 交互打磨（commit 2635cd7）**：切镜头不收缩、镜头直渲真实点云（每帧 autoFit）、相机式三键底栏（撤销/开始/完成）。
- **⚠️ 安全铁律**：共享物理设备的破坏性操作（SOFT_REBOOT/标定写入/IP）必须走 App 二次确认弹窗或用户显式批准，**agent 不自主执行**。
- **硬件**：`.102` 正常；`.101` 控制板掉线（硬件级，CLEAR_ERROR 返 403），起扫返 502，须软件复位或断电恢复。
