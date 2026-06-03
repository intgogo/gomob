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
