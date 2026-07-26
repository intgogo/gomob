# VIN 正射还原：度量网格架构 + 深度 5x 尺度订正（真机诊断 2026-06-23）

## What

用户报"还原图仍有明显深度透视、不同角度还原不一样；原厂怎么拍都一样且带刻度尺"。21 组真机数据系统诊断后，两处根因被订正（推翻"缺 HLSD8 外参=主因"的初判）：

1. **render 架构错**：旧版「OBB 四角单应(钉角点) + 宽度归一到 1200」会把真实几何掩盖成"看着正"、且尺度随取景变 →
   视角相关。改为 **固定 mm/px 度量网格**（端口 `native/vin/ortho_rectify.cpp` + demo `makeRectifiedProjectedImage`）：
   平面上以 OBB 中心为原点、长轴为 x、固定 `PxMM=0.20` 铺刚性网格，`Q = C + a·right + b·up` 逐点投影采样。
   **不钉角点 → 严格 metric、同 VIN 不同角度同尺寸（视角无关）、可叠刻度尺**。
2. **深度尺度偏大**：根因 = eYs3D mode25 几何解码 `Z = fx×B/(disp/8)` 用了**全幅 fx=1229.205 配 640宽视差**
   （分辨率错配；纯 fx 分辨率 2x，叠加其他更大）。**最终订正因子 0.1116**（真机尺子实测 VIN 宽 120mm 定标：
   live cap_133-139 还原中位宽 74mm@0.0688 → 0.0688×120/74 = 0.1116）。均匀缩放不改平面法向/彩色采样，只订正
   绝对 mm。早期 0.19/0.0688 是中途经验值（伴随后被推翻的 atan 去畸变），见下"内凹"段订正。

## Why（诊断关键，防再走弯路）

- **"明显透视"在 harness 复现不出**：度量网格(零标定)渲染 21 组单张都端正、大体平；`cap_012`(高 tilt) wedge=1.00。
  lean_grad 不随 tilt 走、yaw 扫描非单调 → 那点楔形是**字符内容/二值化噪声，不是干净几何透视**。
- **HLSD8↔depth 视差是小量**：物理估算 ≈(基线/距离)×sin(tilt) ≈ 0.5–2.4%。且历史"彩色=深度精确 2× registered
  零视差"结论是在 **eYs3D L'**(深度自带矫正左目)上测的，**不是 HLSD8**——不矛盾，但也说明大透视≠HLSD8 视差。
- **均匀深度尺度误差不造成透视**（缩放不改平面法向/`Qx/Qz` 彩色采样），只错绝对 mm → 错刻度尺 + 跨角度尺寸不一致。
  这才是"不一样"的主因，**HLSD8 标定修不了**。
- 用户拍板的 B(标定 HLSD8) 因此**降级为后续画质增强**，不是主修；先修架构+尺度，真机复验残留透视再决定。

## How to apply

- **服务端**：`server/internal/cvengine/restore/render.go`
  - `render()` = 度量网格（不再 `perspectiveTransform` 单应 / 不再 `alignColorBySignature` resize-归一，已删 `align.go`）。
  - `drawRuler()` 叠底部水平 + 左侧竖直 mm 刻度（主 50mm 标数 / 次 10mm），烤进返回 PNG（原厂式，app 无需改）。
  - 深度尺度 `depthScale`（默认 `DepthScaleDefault=0.1116`，环境变量 `GOMOB_VIN_DEPTH_SCALE` 可覆盖）在 `plane.go::backprojectROI` 对 z 生效。
  - 彩色投影内参 `kc = {fx·s, fx·s, cx·s, cy·s}`（s=cw/dw≈2，2×depth 近似，各向同性 fyc=fxc）；**不做几何去畸变**（保原始端直）。
  - 输出尺寸动态（OBB 物理宽×PxMM，钳 `MaxOutW/MaxOutH`）；`Meta.WidthMM/HeightMM` 现为订正后绝对 mm。
- **harness**：`tests/harness/vin_restore/ortho_metric.py`（`run.sh` 已指向它）逐函数对齐服务端；`analyze.py` 跑重合。
- **验收**（终态见下"续"段）：cap_133-139 live curl 全 ok、宽 117-122mm(中位 120≈尺子)、还原端直；harness
  analyze=**正常**(组内中位残余 0.41%/NCC 0.68)。cvengine 已重建重启载无去畸变代码(scale 0.1116 烤进默认)。

## 续：atan 去畸变是错路，已下线（2026-06-23 续，订正上一版"已修"）

上一版判"内凹=HLSD8 atan 广角畸变"，逆向原厂 `libcreator_jni.so::applyAtanDistortion`(@0x3845a0, `rd=a·atan(r/a)`,
结构体 `cx@0,cy@8,fx@0x10,fy@0x18`)拟合 HLSD8(`cx=598.4,cy=163.7,a=410.7,f_proj=1050`)并接进服务端去畸变。

**真机 cap_133-139 复验推翻它**：去畸变把**本来端直的钢牌弯成弧**(畸变中心 `cy=163.7` 偏离 256 高图心 128 →
上下不对称弯曲)，且 FOV 校正在**图角留黑楔**啃进 VIN 区 → 还原"完全不对"(用户原话)。对照 `VIN_UNDISTORT=0/1`：
关掉后同 VIN 7 张全端直、宽度 92-95mm 紧密一致；开着是弯+黑楔+宽度 139-155mm 飘。**结论：拟合参数(或模型)
不对，去畸变净负，已删** `undistortHLSD8` + `hlsd8*` 常量；`restore.go` 直接用原始彩色解码。

**为何上一版误判"3~7× 压平"**：只在 cap_131/132 两张测顶边弓，恰好该角度弯曲部分抵消；多张(133-139)一看就露馅。
教训：几何修正必须**多视角多张复验**，别拿 1-2 张的数字下结论。`VIN_BF301215.bin` 仍确认是深度模组 RS-D550
双目矫正(f=1211.79,基线 49.89mm)，**不含 HLSD8**——这条仍成立。

**当前终态(已部署)**：无几何去畸变；`kc={fx·s,fx·s,cx·s,cy·s}`(s≈2)；`DepthScaleDefault=0.1116`(真机 120mm 定标)。
验收：cap_133-139 还原全端直、宽 117-122mm(中位 120≈尺子)、ink 1.5-2.5%；harness analyze=**正常**(同 VIN 6/7 张
组内中位残余 0.41%/NCC 0.68，优于带去畸变的 1.25%)。harness `VIN_UNDISTORT` 默认 0、`VIN_DEPTH_SCALE` 默认 0.092。

## 续3：所有图像后处理下线，转做双相机标定（2026-06-23 终）

去阴影/锐化的来回（拓印观感→"模糊滤镜"→锐化）**用户最终判定"效果都不好，去掉"**：删 `flattenRubbing`，
`Restore` 直接输出**原始彩色正射图** + 刻度尺（`drawRuler(rect)`）。几何正确性**不靠后处理，靠真标定根治**。
（一过性教训留档：`flattenRubbing` 旧版糊=强双边 9/60/60 当分子 + 固定 ×180 压对比成 168-199 窄带；gocv 实有
`NewCLAHE`/`MeanStdDev`/`MultiplyFloat`，早期"无 CLAHE"判断有误。但呈现层处理整体被否，不再投入。）

**下一步 = HLSD8↔depth 双相机标定**（用户 2026-06-23 拍板"现在需要做标定"）。目标：真 HLSD8 内参+畸变 +
HLSD8↔RS-D550 外参 R|t，替掉 `kc=2×depth` 近似 + `R=I,t=0` 假设 → 正射几何正确、视角无关、删 `depthScale` 经验值。
设计已在 [docs/architecture/08-vin-rectify-design.md §9.2]；可复用：`device_calibrations` 表(migration 0009)、
激光 `server/native/lidar/src/calib` 的 ArUco-36h11+solvePnP、cvengine 容器 OpenCV4.6+ArUco。
缺口：gocv(Go)无 calib3d → 标定走 **Python(cv2) 离线 harness**（一次性/设备级，不进请求路径）；外参需标定板同时进
深度相机视角(L' 灰度角点 或 深度 RANSAC 平面对应)。详见 [[finding_vin_rectify_serverside_calib_2026-06-18]]。

## TODO（终态）

- **残留视角相关性已很小**(组内残余 0.41%)，主要来自 OBB 取景抖动 + depth↔HLSD8 微视差(基线~49mm)。若要再压，
  走 ArUco 双相机标定出 R|t(原始 B；`server/native/lidar/src/calib` solvePnP/ArUco 可复用)——但当前已"正常"，非急需。
- **"内凹"是否真存在存疑**：去畸变下线后多张看端直；早期"内凹"可能是 atan 去畸变自身引入 / 旧 render 残留。
  若用户复拍仍报内凹，再用**多张**量化顶边弓(随 tilt 变号才是真视角相关)，别再单张定论。
- **端侧预览内参错**：`VinCaptureViewModel.kt` `HLSD8_FOCAL_FACTOR=6.5`(fx≈8320 窄场)与服务端 2×depth(s≈2)路径
  矛盾；端侧即时预览(IDENTITY_RT)需订正；服务端权威路径不受影响。
- **精定深度尺度**：0.1116 仍是经验值，可注入 eYs3D ZD 表(`SetZdTable` 已留口)从根订正解码。详见 [[finding_eys3d_mode25_real_depth_java_path_2026-06-15]]。
- 字符端正(fyc=fxc)+去阴影质量闸见 [[finding_vin_ortho_color_upright_2026-06-22]] / [[finding_vin_signature_binarize_realdevice_2026-06-21]]。

## 通用教训

正射"不一样/有透视"优先级排查：① **绝对深度尺度**对不对(刻度/跨角度一致性的根)；② render 是否**固定度量网格**(别用
钉角点单应+宽度归一，会掩盖几何+视角相关)；③ 才是双相机外参/视差。均匀深度缩放不改透视——别拿它解释楔形。
诊断要在 harness 复现用户所见，复现不出就先对齐"看的是不是同一张"，别凭报告就投几周标定。
**几何修正(去畸变/外参)必须多视角多张复验再下结论**：atan 去畸变在 cap_131/132 两张测出"3~7× 压平"看着成功，
接进生产后 cap_133-139 一看是把端直钢牌弯成弧+黑楔(净负，已删)。1-2 张的数字会骗人，弯曲在某角度会自抵消。
逆向出原厂公式≠参数对——拟合参数(尤其畸变中心 cy 是否在图心)错一样毁掉。改不动就先回退到"不做"，端直比弯好。
