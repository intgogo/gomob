# JCHY 车辆外廓测量/建模层逆向 + gomob 差距

## What

JCHY_simple_3.0.0（原厂 `D:\software\jchy_simple_3.0.0.2\jchy\`，Windows x64，Qt5+PCL1.8+CUDA+TensorFlow(PointSIFT)+OpenCV+OpenNI2+海康HCNetSDK）= 车辆外廓激光扫描的**测量/建模层**应用。消费两台 LTS-T1 激光单元（2D 线激光+旋转 PTZ+海康相机）扫出的彩色点云，对车辆按车型建模并量出外廓尺寸，对标国标 GB7258-2017 §4.15，产品自名「重点车辆智能核验仪」。

逆向真理源：`/root/WindowsR/JCHY_OFFLINE/`（含 `JCHY.pdb` 完整符号、两 exe、`AlgFuncDLL.dll`、真实会话 `Data/100742/`）；文本产物 `/root/lilw/gomob/.worktrees/laser-scan/.dev/jchy-re/`。完整架构落 `docs/architecture/16-jchy-vehicle-measurement-app.md`。

### 它在 gomob 全链的位置（承上启下）
- 上游采集层（已逆向）= `/root/lilw/lidar`（QtTrainScan，CA-FE 帧→融合点云），gomob 已迁服务端瘦客户端（`docs/architecture/15`）。
- 本层（本次逆向）= JCHY 测量/建模，把融合点云做成「车辆→尺寸数字」。
- gomob 现状只到融合点云，**完全没有测量/建模层**。

### 端到端管线（8 阶段，runtime1.txt 直证顺序 filter→type→cut→calute→flag→height→wheel→image）
①采集/回放(QPtzDevice/OfflineReading) → ②预处理(`CCloudFilter`+`CLocater::filterCloud`：PassThrough/SOR/VoxelGrid/EuclideanCluster<PointXYZ>) → ③上色(`PointCloudColorizer`，parameters_N.json 内外参+30 帧 PTZ 纹理投影，出 XYZRGB PCD) → ④车型判定(几何 `getVehicleType/getTrailerType/BoxOrCangShan` 返回 int，叠 carType 偏移，罐车 `CompareCloudType5/7` 二次确认) → ⑤DL 部件分割(可选，`deepmode` 开关，`CLocater3Dcnn::generate_tensor`→`findObjectD::predict(PC<XYZ>→PC<XYZL>)`→`decodeCloud`，底层 PointSifter+tf_user_ops_pointSIFT.dll) → ⑥尺寸提取(`bound_box`/`cv::RotatedRect`/`getMinMax3D` 取 LWH，`caluteDeepWheel`/`segWheelBottom` 取轴距前后悬，`TankProcess::caluteTank` 取罐体三段+容积，`PointCloudBoard` 取栏板护栏) → ⑦输出(`QProject::sendResult` 写 Result.ini ~40 字段 + CarSQL.db Measure 14 列)。

**关键架构判断**：PCL 经典算子（无 ICP/TSDF/表面重建），测距本质=聚类+OBB；DL 只做轮/罐难分割目标的逐点部件标签（`predict` 出 PointXYZL 直证），最终车型编号由几何分类器判定回填，非 DL 查表。

### 车型体系（26 项，编号即语义分组）
货车 0-15：0 牵引头/1 吊车/2 常规/3 路边清障/4 垃圾清理/5 洒水罐车/6 小型平板/7 水泥搅拌/8 大型平板/9 特殊吊车/10 特殊栏板吊车/11 专项特殊/12 箱式尾板/13 自卸/14 仓栅/15 箱式。挂车 50-59：50 常规/51 光板/52 光板带杆/53 常规罐挂/54 低平板/55 异型/56 箱式/57 仓栅/58 下灰式罐挂/59 水泥罐挂。

### carType 参数表已完整解密（重大成果）
`carType.ini`(1102B) XOR 单层回文 key `00200000070030101030000700002000` → 100% 可读明文，31 行 `Type<n>_x/_y/_z`（mm 三轴偏移），含 Type0..15/Type50..59 + Tank5/7/15 + board + Type2_s。无第二层混淆（整 1102B 严格周期-16、0 字节偏离）。`carType.bin` 是另一份密文，bin^ini 的差 `fe11546a...` 是密文差非 key。还原脚本：`bytes(ini[i]^key[i%16] for i in range(1102))`。

### 测量量字典（Result.ini 实测 carType=2 直证，已二次复核与磁盘逐字段一致）
车长 1777/宽 533/高 759（+第二组 Length2/Width2/Height2 1775/534/761）、轴距 1-4(710/399/261/-1)+总轴距 1370（=各相邻和）、前悬 261/后悬 163、轮数 2、对称度 1；条件项（本车 0）：栏板深度、四向护栏离地高、牵引头 LWH+轴距、罐长宽高+三段(前/中/后=长X直径1X直径2)+倾斜角+容积(填充系数 vol_s=0.96)、货箱内尺寸、屏蔽 HX*、异型 XLength1/2。入 Measure 表仅 length/width/height/wheelbase(总)/xyzwOffset/angle/Topimg/Sideimg/path 14 列，细项只进 Result.ini。

### 设备与网络
QHttpComm(REST `/api/control_scan|device_info|device_status`)+QMultiPortTcp(TCP 多端口+zstd)+QPtzDevice(五路回调)+QLaserSocket(电机命令)+QProject(编排)，与采集层 `/root/lilw/lidar` 同源同名类。两单元 `.101/.102`（setting.ini `[laser_T] laser_count=2`），Laser1-5_Calib 3×4 外参。海康相机=安防监控（端口 8000，存 monpath，写 Measure.plate），不参与点云上色。「在线版」JCHY_ONLINE=实时连激光起扫（独占 `control_scan`/`SCAN_START`），离线版 JCHY_new=回放 `Data/`，**均非云上传**（业务 exe 无 upload/oss/ssl，已 grep 证 0）。

## Why

gomob 激光主线（`docs/15`）到融合点云为止，要做「重点车辆智能核验」对等能力，必须补测量/建模层。JCHY 是现成的、已被二进制坐实的参考实现：管线、车型体系、参数表、各测量算法、合规阈值全部逆向到符号/源文件/配置值级。carType 表已完整解密可直接移植，省去重逆向。明确「PCL 聚类+OBB 测距 + 局部 DL」这一架构，避免 gomob 误入实时 SLAM/TSDF 重路线。

## How to apply

- 新增测量/建模层（建议服务端 worker 或 `native/measurement/`），复刻 8 阶段管线。
- PCL 算子可用 Eigen/gonum 轻量重写（PassThrough/SOR/VoxelGrid/EuclideanCluster/minAreaRect-OBB/getMinMax3D），不必引整套 PCL。
- 直接移植 §4.3 解密的 carType 31 行表 + §4.1 车型枚举 + CalibSetting.ini ROI。
- 几何-only 测量先做（常规/牵引/平板可行）；罐车/异型需 PointSIFT 四套 `*_seg.ckpt`（原厂 models/ 空，须从真机取或重训）。
- 合规判定接 `[LIMT]` 阈值（车长≤12000/宽≤2550/高≤4000/栏板≤1500 mm，vol_s=0.96）。
- 里程碑骨架 M9.1-M9.6（harness 可验收，**待用户拍板再落 TODO.md**）：①管线骨架+ROI+主簇 → ②LWH+轴距+前后悬几何 → ③车型分类+carType 接入 → ④罐体三段+容积+栏板护栏 → ⑤PointSIFT DL → ⑥schema+合规+落库。验收基线 = `Data/100742` 复算误差 <1%（Length≈1777/总轴距≈1370/前后悬 261/163）。

## 未坐实（留缺口）
- carType `_x/_y/_z` 精确消费语义（ROI 偏移/姿态补偿/测量基准）无反汇编直证（medium）。
- PointSIFT 部件类别名单/网络结构（models/ 空，unverifiable）；`predict`/`generate_tensor` int/float 入参语义（low）。
- 容积公式、栏板/护栏参考面、地面 SAC 子类、双激光交叉验证语义、VoxelGrid leaf size/cluster tolerance 数值均仅符号无公式直证。

## 相关
- `docs/architecture/16-jchy-vehicle-measurement-app.md` — 本次逆向完整架构（含 §10 完整未坐实清单）
- `docs/architecture/15-laser-scanner-integration.md` — 上游采集/融合层（gomob 已迁，[[finding_laser_scanner_integration_2026-06-03]]）
- `docs/architecture/05-calibration-pipeline.md` — 标定框架（激光-相机外参可复用）
- 真理源 `/root/WindowsR/JCHY_OFFLINE/` + 文本产物 `.dev/jchy-re/` + 采集层 `/root/lilw/lidar`
