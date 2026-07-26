# 激光双机外参标定：仅中心点 umeyama 在 ≤4/共面标记下偏 ~20° → 改用 4 角点 6DoF（2026-06-17）

## 现象

用户反馈"标定之后点云还是没对齐"。从 MinIO 拉 scan 166 三朵云（unit_a/unit_b/fused）数值诊断：

- `fused = A ∪ stored_BToA·B`（残差 0），`fa==unit_a`：**融合 plumbing 正确**。
- `det(R)=+1`：**无镜像/反射**；`F·stored·F` 共轭修正无改善：**不是 FlipVertical 帧错位 bug**。
- 当前 B→A 最近邻中位 **480mm**；ICP 从标记外参精修最好到 ~150mm、且要再转 **~20°/66cm**。
- 结论：**标记标定解出的 B→A 外参本身偏约 20°/66cm**，是标定精度问题，不是融合/翻转代码 bug。

## Why（根因）

`site_marker_calib` 旧解算只取每标记**中心点**(solvePnP tvec)做 `umeyama`。现场只有 **≤4 个、且大致
共面**（平铺地面/桌面）的标记时，少量中心点对旋转**欠约束**：RMS 看着小（自洽），外参却偏十几到二十几度。
ICP 也救不动（双机视角差大 ~148° → 重叠少；且从 20° 偏差初值跳不出局部最优）。注意 `cameraToWorld` 本身
没问题（colorizer 上色复用同函数且工作正常），坐标系帧转换也对——纯粹是中心点法在稀疏/共面布置下退化。

## How to apply（修复，已落地）

每标记用 **4 角点**（solvePnP 位姿 × 标记物点，带朝向）代替仅中心点：
- `MarkerCenterObs` 加 `corners_cam[4]`；前端 `detectUnitCenters` / framing `detectFrame` 用 `Rodrigues(rvec)`
  算 4 角点相机系坐标。
- `aggregateMarkerWorld`→`aggregateMarkerCorners`（每标记 4 角点投世界、跨帧均值）；`solveSiteExtrinsic`
  对所有公共标记的 4 角点做 umeyama。
- **单个标记的 4 角点即非共线、含朝向 → 完全约束 6DoF 旋转**，少量/共面也解得准。核心求解器最低 `min_common=2`；
  但 production site 保存门仍要求公共标记 ≥4、RMS≤5mm，不能把“数学可解”当“生产可验收”。
- host 测试 `test_site_marker_calib.cpp`：**2 共面标记复原 B→A 到机器精度(2e-16)**；2mm 噪声下误差 <1cm。

修复在 `lidar_cli`（标定走 exec，非 cgo 融合库）：worker CWD=`/root/lilw/gomob` 默认 exec
`server/native/lidar/build/lidar_cli` → 重建该二进制即对下次标定生效，无需重启 worker；min_common 的 Go
默认改动才需重建 worker。

**用户须知**：旧 scan 166 用的是旧外参，不会自动变好；必须用新 lidar_cli 重标、把质量指标保存到服务端；site revision 改变后还要在确认空工位的前提下重采 A/B raw 背景，再重新扫描。

诊断脚本与采样留 `.dev/scan166/`。相关：[[finding_two_cameras_hlsd8_rgb_2026-06-10]]、设计文档 `docs/architecture/17-laser-camera-lidar-calibration.md` §多单元拼接。
