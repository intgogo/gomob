# 激光 A 站(.101)相机纹理链路补齐 2026-06-15

## Why（背景与根因）

激光车辆外廓扫描里"镜头 A 没颜色"长期被当成硬件问题，真因是**软件链路从来只为 .102(B) 相机写过**：
原 `lidar_scan.cpp` live 路径只起一条 `ipB:4003` 图像采集线程，`emitColorizedUnitB` 固定用
config_102/calib_102 投影并发 `unit=1` 色；runner.go 把 unit A 点云**硬涂中性灰** `0x0072777d`
（`fusedUnitANeutralRGB`），fused 云 A 段也是灰。A 站 RGB 相机（IMX415）修好只是必要前提，
不补软件链路照样灰。2026-06-15 真机验证补齐后 A 出真实色：unit=0 mapped≈30.7% 与 B≈30.0% 持平。

## How to apply（复用要点）

- **每台 LTS-T1 相机标定在设备内**，`lidar_cli device calib <ip> out.json` 直接拉（`device info`
  里 `calib=1` 表示有）。calib_101/calib_102 都是这么来的，不需要现场重标。
- **.101/.102 的相机与雷达名义安装四元数相同**（`camera_rot_quat=[.5,.5,-.5,-.5]`、
  `lidar_rot_quat=[.5,.5,.5,.5]`）→ `config_1xx_live.yaml` 可逐字共用。per-station 差异**全在
  calib JSON**：`CameraModel::applyCalibration` 覆盖内参/畸变/corr/b2w，config YAML 只供
  `camera.fixed_transform` + `texture_mapping`（见 colorizer.cpp `fromConfig`/`applyCalibration`）。
- **C++**：`emitColorizedUnit(unit, cloud, images, cb, user)` 按 unit 取 env——A=
  `GOMOB_LASER_TEXTURE_CONFIG_A/_CALIB_A`（缺省 out_live/config_101_live.yaml/calib_101.json），
  B=不带后缀（102）。live 起 `tiA(ipA:4003)`+`tiB(ipB:4003)` 双图像线程，扫后各 `emitColorizedUnit(0/1,...)`。
- **Go runner**：`OnColorPoints` 分 unit=0→rgbA / unit=1→rgbB；`unit_a` 同 unit_b 走
  `PutCloudXYZRGB`；`buildFusedRGB(cloudA,cloudB,cloudFus,rgbA,rgbB)` A 段优先 rgbA，缺色才退灰。
  颜色与刚体变换无关 → 各单元用各自相机在各自系上色，融合按点序(A 后接 B)拼装即一致。
- **验证判定**：日志 `[laser texture] unit=0 mapped=K`，A/B mapped 率应同量级(~30%，错标定会接近 0)；
  `unit_a.pcd` 头 `FIELDS x y z rgb`；无 "101 纹理…回退写 XYZ" 警告。
- **构建链（2026-06-15 已迁入仓内）**：lidar 服务端 C++ 源迁到 **`server/native/lidar/`**（PCL 管线，
  区别于 Android 端 PCL-free 的 `native/lidar/`）；标定资产在 `server/native/lidar/calib/`。改 C++ 后跑
  **`server/scripts/laser-cgo-setup.sh`**（从在树源 cmake 建 `lidar_scan` + 软链 `.a`/头进 gitignore 的
  `server/internal/laser/native/`）→ 再 `cd server && go build -tags laser_cgo ./cmd/laserworker`。
  cgo `CFLAGS/LDFLAGS` 用 `${SRCDIR}/native`（=server/internal/laser/native）找 .a+头。大体量
  `re/`(1.4G 逆向录制)、`out_live/`(1G 样本)、`sample/` 留外部 `/root/lilw/lidar` 不迁；那批数据无版本控制，换机记得备份。

关联 [[finding_laser_scanner_integration_2026-06-03]]、[[finding_laser_roam_percamera_cropbox_2026-06-05]]。
