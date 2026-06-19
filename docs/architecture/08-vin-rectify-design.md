# 08 — VIN 数码拓印设计

> 业务定义：用户对着汽车 VIN 钢架（车架号那块**冲压在金属上的字符**）按一下"拍"，
> App 拍一张 iHawk 深度图 + Color 图 → 拟合钢架表面（一般是平面或微弧面）→ 重投影到
> **固定法向距离**的正射图（消除拍摄角度倾斜的透视畸变）→ 出一张 1:1 的"数码拓印图"
> 给后端 OCR 和审核用。
>
> 类比：用复写纸描钢印的"拓印"工艺，但端侧靠深度图自动 +
> 不需要物理接触车架。

## 1. 输入 / 输出契约

### 1.1 输入

- **单帧**iHawk Color + Depth（来自同一物理设备，同时间戳）
  - Color：YUYV → BGR 转换后用作"花纹来源"
  - Depth：16bit mm，钢架平面深度变化 ≤ 5mm（VIN 钢印是浅压痕）
- 内参 / 外参（同 §01-depth-camera-integration §4 标定结果）
- 拍摄距离要求（用户操作侧）：iHawk 距 VIN 板 30-60cm（产品手册定）

### 1.2 输出

- `VinRectifyResult`：
  - 正射图 PNG（1024×512，可调）：1:1 比例 + 法向投影 + 透视已消
  - 元数据：拟合平面参数（n, d）+ resample 距离 mm + 残差统计
- 落 `getFilesDir()/vin_captures/<id>/{rect.png, depth.raw, color.png, meta.json}`

## 2. 核心算法（`vin_rectify_demo.cpp` 的 Android 移植）

参考 Windows 端 `/root/WindowsR/berxel/sdk/Tools/VinRectifyDemo/vin_rectify_demo.cpp`
和设计文档 `VIN_RGBD_Rectification_Design.md`，Android 端复刻：

```
[1] 取一帧 RGBD（Color + Depth 已通过 setRegistrationEnable(true) 对齐到 Color 系）
        ↓
[2] ROI 提取：用户点屏幕选定 VIN 区域（中心 + 边框 4 角）
        ↓ ROI 内深度像素
[3] 平面拟合（RANSAC）：n·P + d = 0
        - inliers 占比 ≥ 95% 视为成功
        - 残差 σ ≤ 1.5mm 视为好平面（金属冲压平整度）
        ↓ (n, d, ROI 内的支撑点)
[4] 构造正射相机：
        - 光轴 = -n（看向钢印面）
        - up 向量 = 投影到平面的世界 y 轴
        - 距离 = 用户配置（默认 30cm）
        - 像素分辨率 = 用户配置（默认 0.2 mm/px）
        ↓ K_ortho（虚拟相机内参）
[5] 像素重投影：对正射图每个 (u, v):
        - 反投影到 3D（在拟合平面上）
        - 投影到 Color 像素 (u_c, v_c)
        - 双线性采样 Color BGR → 写入正射图 (u, v)
        ↓ 1024×512 BGR
[6] 后处理：去噪（中值滤波 3×3）+ 增强（CLAHE）+ 写 PNG
```

### 2.1 平面拟合细节

为什么不直接拿 ROI 中心点的深度？因为：

- iHawk Depth 单点噪声 ±2mm（厂家 spec），单点不靠谱
- VIN 钢架可能有轻微弯曲（保险杠后侧的 VIN 板）
- RANSAC 自动剔异常点（ROI 边缘的非钢架像素）

```cpp
// 伪代码
auto roi_points = backproject_depth_to_3d(depth, intrinsics, roi);  // [N][3]
auto [n, d, inliers] = ransac_plane_fit(
    roi_points,
    /* dist_threshold */ 2.0f,    // mm
    /* max_iter */ 200,
    /* min_inliers_ratio */ 0.95f);
if (inliers.size() / roi_points.size() < 0.95) return Error("plane fit failed");
```

弯曲表面（≥ 1cm 曲率）用**二阶曲面**拟合代替（多项式 z = a x² + b y² + c xy + d x + e y + f），但 v1 先做平面，覆盖 95% 真实场景。

### 2.2 正射相机参数化

```cpp
struct OrthoCam {
    Vec3 origin;       // = ROI 质心 + n * distance_mm（站在平面前方 distance）
    Vec3 axis_z;       // = -n（朝钢印面）
    Vec3 axis_y;       // = (world_y projected onto plane).normalized()
    Vec3 axis_x;       // = axis_y × axis_z
    float pixel_size_mm; // 默认 0.2 → 1024 px = 204.8 mm 实际宽度
    int width, height; // 1024×512
};
```

光轴朝法向 → **消除透视**。`pixel_size_mm` 控制清晰度 vs 视野；产品手册建议 0.2 mm/px
（VIN 字符高度 ~10mm → 50 px 字高，OCR 友好）。

### 2.3 距离归一化

为什么 fixed distance？

- 不同用户拍摄距离不同（30cm vs 60cm）→ 同一 VIN 在 Color 图上像素大小不同
- 后端 OCR 模型期望"标准化输入"
- → 拓印图固定按 0.2 mm/px、固定虚拟相机距离 30cm，OCR 输入永远是同一像素密度

## 3. 数据契约

```kotlin
data class VinRectifyRequest(
    val rgbd: RgbdFramePair,         // Color + Depth 同时间戳
    val roiNormalized: RectF,        // ROI 在 Color 图上的归一化坐标（0..1）
    val orthoDistanceMm: Float = 300f,
    val pixelSizeMm: Float = 0.2f,
    val outputWidth: Int = 1024,
    val outputHeight: Int = 512,
)

data class VinRectifyResult(
    val rectifiedImage: ByteArray,   // PNG 压缩
    val plane: PlaneFit,             // 拟合参数 + 残差
    val orthoCamMetadata: OrthoCamMetadata,
    val captureTimestampMs: Long,
)

data class PlaneFit(
    val normal: FloatArray,          // [nx, ny, nz]，单位向量
    val distance: Float,             // mm，钢架到 iHawk Color 原点的法向距离
    val rmsResidualMm: Float,        // 拟合残差
    val inlierRatio: Float,          // 0..1
)

data class RgbdFramePair(
    val color: ColorFrame,
    val depth: DepthFrame,           // 已 register 到 color 像素坐标
)
```

## 4. JNI 边界

```kotlin
// core:native-bridge/NativeBridge.kt
object NativeBridge {
    /**
     * 单帧 RGBD → VIN 正射拓印图。
     *
     * @param colorBgr Color 帧 BGR888（来自 ColorFrame，YUYV 已转过）
     * @param depth16Mm Depth 帧 mm（已 setRegistrationEnable 对齐到 Color 像素坐标）
     * @param colorIntr [fx,fy,cx,cy,k1,k2,p1,p2,k3]
     * @param roiBox  [u_min, v_min, u_max, v_max] 像素坐标
     * @param config  [ortho_distance_mm, pixel_size_mm, out_w, out_h]
     * @return [orthoBgr, plane_nx, plane_ny, plane_nz, plane_d, rms, inlier_ratio]
     *         orthoBgr 长度 = out_w * out_h * 3；其余是 7 个 double
     */
    external fun vinRectify(
        colorBgr: ByteArray, colorWidth: Int, colorHeight: Int,
        depth16Mm: ByteArray, depthWidth: Int, depthHeight: Int,
        colorIntr: DoubleArray,
        roiBox: IntArray,
        config: FloatArray,
    ): VinRectifyNativeResult  // sealed class，含 error code 或 success payload
}
```

## 5. UI 流程（`feature:scan3d`「VIN 数码拓印」入口）

```
[VIN 入口卡] → 摄像头预览（iHawk Color，叠 ROI 选框）
              ↓ 用户调好取景
              [拍] 按钮
              ↓ BerxelService 抓单帧 RGBD（color+depth 同时间戳，TODO §6 SDK API）
              ↓ NativeBridge.vinRectify
              ↓
[结果页]      左：原 Color + 平面拟合可视化
              右：拓印图（1024×512）
              ↓ 用户操作：保存（写本地 + 推服务端 OCR）/ 重拍
```

ROI 选框默认：屏幕中心 70% × 30% 的横条（VIN 钢架是横向 17 字符）。用户可拖动调整。

## 6. 与服务端 OCR 的对接

服务端已有 cv-engine `vin_pipeline`（M-S10.2a）+ `vin_character_compare_with_ref`：
端侧拓印图 → 服务端 vin_pipeline → 17 字符识别 → 厂家库逐字符比对 → verdict
（pass / warning / fail）。

端侧只负责"出标准化拓印图"，服务端管识别比对。这条线已经跑通（详见 `TODO.md` M-S10
段）。

## 7. Harness

`tests/harness/vin_rectify_quality/` 验收：

- 录制 N 个真实 VIN 钢架 RGBD pair（不同角度、不同光照、不同距离）
- 跑 `vinRectify` → 拿到拓印图
- 比对：相同 VIN 多角度拍摄的拓印图，结构相似性（SSIM）≥ 0.9
- 比对：拓印图喂服务端 vin_pipeline，OCR 准确率 ≥ 95%

## 8. 待办

详见 `TODO.md` M4.* 段。

## 9. 双相机正射图扩展（2026-06-10）

§1–8 假设单帧 RGBD（深度模组自带 color，已对齐到 depth）。**实测扫描机是两颗物理独立 USB 相机**
（`dumpsys usb` 实证）：深度 Etron RS-D550(0x3438:0x0206) + **13MP RGB HLSD8(0x0C45:0x6366, Image+/Sonix,
~4160×832 MJPEG)**。VINCreator 正射图的高分辨率真彩来源是 HLSD8，**不是**深度模组的 L'(1280×256)。
详见 `docs/agent-memory/finding_hlsd8_rgb_second_camera_2026-06-10.md`。

**几何实现**：`native/vin/ortho_rectify.{h,cpp}`（替代旧 `vin_rectify.cpp` NOT_IMPLEMENTED 桩）：

```
OrthoRectify(depth_mm,K_depth, rgb,K_rgb, rt_rgb_from_depth[12], cfg) -> OrthoResult
```

1. depth 反投影到 depth 相机系 3D 点 → RANSAC 主平面拟合（内点精修，法向朝相机）。
2. 平面内构造正交基（up=相机 Y 在平面投影，right=up×n），以内点质心为正射网格中心。
3. 逐正射像素：网格平面点 Q（depth 系）→ **`Q_rgb = R·Q + t`** 变到 RGB 相机系 → 投影 + 双线性采样。

**关键与单相机的差异**：`R|t` 是 **HLSD8↔RS-D550 双相机外参**（两颗独立相机），来自双相机标定
（device-gated，见 `05-calibration-pipeline.md`）。缺标定时退化 `R=I,t=0`（同相机假设）——真机有 ~baseline
视差会偏，**仅调试用，不接假 fallback 当真结果**（第一性原理：不留退化伪路径）。

**验证**：`tests/native_host/ortho_rectify_test.cpp`（合成倾斜平面 RGBD + 世界坐标编码纹理）—— 验平面拟合
（|n·n0|=1.0/rms0.29mm）、覆盖率（91%）、外参投影正确性（中心解码-质心 0.55mm，能捕获漏用 t 的视差偏移）、
metric 尺度（相邻正射像素世界位移比 1.008）。挂 `scripts/native-host-test.sh`。

### 9.1 端侧接线（2026-06-18，code-complete 待真机验证）

JNI/契约/采集/UI 已接通，第一光用 eYs3D 自带 L' 彩色 + `R|t=单位阵`跑通全链路：

- **JNI** `native/jni/jni_bridge.cpp::vinOrthoRectify`：删 `vinRectify` 的 `NOT_IMPLEMENTED` 桩，零拷贝
  `GetDirectBufferAddress` 取 depth/rgb → 调 `gomob::vin::OrthoRectify` → 回裸 RGB888+mask+plane（**不在 native 编 PNG**）。
  同时删死桩 `native/vin/vin_rectify.cpp`。
- **契约** `NativeBridge.vinOrthoRectify(depth16Mm+depthIntr[4] / rgb888+rgbIntr[4] / rtRgbFromDepth[12] / config[6])`
  → `VinOrthoNative(rgb,mask,width,height,planeNormalAndD[4],planeStats[2],covered)`。config=
  `[pixel_size_mm,out_w,out_h,plane_dist_thresh_mm,ransac_iter,min_inlier_ratio]`。
- **采集** `VinCaptureViewModel.capture()`：配对最近 depth + eYs3D L' → `vinOrthoRectify` → `FrameRenderer.orthoToBitmap`
  （mask==0 透明）→ 显示在 `RubbingPaper`。OCR 路留 `recognize()` 待接。
- **第一光为何用 L'**：L' 与深度是**同一矫正左目**（`Eys3dCameraService` 两帧均 `rsd550Intrinsics` 同标定），
  `R|t=单位阵`几何成立、**非假兜底**；HLSD8 高清需真标定（见 §9.2）。

### 9.2 标定计算上服务端（架构决策 2026-06-18）

终态彩色源 = **HLSD8 13MP**（清晰度远胜 L'）。HLSD8 与 RS-D550 是两颗独立 USB 相机，flash 无任何外参，
`R|t`(HLSD8↔depth) 必须自标定。**决策：标定计算放服务端，app 只采集图像对上传**（用户拍板）：

- **为何上云**：native 集成 OpenCV(+ArUco) 成本高（Android 侧只有 Berxel OpenCV3 无干净 aruco；`native/calibration`
  是桩）。而 **cvengine 容器已带 OpenCV 4.6 + ArUco/calib3d**（`server/Dockerfile.cvengine`、`server/scripts/laser-cgo-setup.sh`），
  契合「端拍云算」重建主线。native 保持零 OpenCV；正射 `OrthoRectify`（纯 Eigen）留端侧每拍快算。
- **可复用基建**：① 设备标定持久化 `server/migrations/0009_devices.up.sql`（`devices` + `device_calibrations`，版本化、
  sha256、`GET /v1/devices/{id}/calibrations/latest`）已存在；② 激光管线已有 ArUco-36h11 + solvePnP 相机间外参
  （lidar core，与用户 15cm ArUco 靶同 dict）；③ 标定**外参/内参与距离无关**，可在 50–80cm 舒服距离标、用于 VIN 近距，
  且**不用深度流**（只需 HLSD8 彩色 + eYs3D L' 两张 2D 图，depth 在 L' 同坐标系 → `R|t`(HLSD8←L')=`R|t`(HLSD8←depth)）。
- **标定算法**：两机同拍 ArUco 板 → 各 solvePnP 得 `T_cam_from_board` → 合成
  `T_HLSD8_from_L' = T_HLSD8_from_board · (T_L'_from_board)^-1`；HLSD8 内参由同批多视角 `calibrateCamera`/`stereoCalibrate` 出。
- **待建**：服务端 stereo 标定端点（复用 cvengine OpenCV4.6 aruco + lidar solvePnP）+ app 标定采集页（复用既有
  「Color↔Depth 标定」入口/`CalibrationScreen` 框架，采 N≥10 组多姿态上传）+ 端侧持久化 `K_hlsd8`/`R|t`。
  落地后 `capture()` 改喂 HLSD8 + 加载标定，**正射代码不变**。

**live 拉通进度**：见 `TODO.md` M6.9.5（① 第一光 L'+单位阵 ✅ code-complete 待真机；② 服务端标定 + HLSD8 切换 待建）。
详见 `docs/agent-memory/finding_vin_rectify_serverside_calib_2026-06-18.md`。

## 10. 还原算法全量上服务端 + 原厂逆向规格（架构决策 2026-06-18）

> 用户拍板两条：①「具体的拍完照之后的后处理还原逻辑和算法，根据原厂逆向工程来对齐」；
> ②「把还原相关的算法都放到服务端去」。决策：**整条还原管线移到服务端**，端侧只「拍 + 存原始 + 上传」；
> 运行时 = **扩 Go cvengine（gocv）**；保真度 = **直接上原厂全保真**（不做简化 MVP）。
> 端侧旧 `native/vin/ortho_rectify.{h,cpp}` + JNI `vinOrthoRectify` 降级为「拍到了」即时近似预览，不再是真还原路径。

### 10.1 原厂真理源 = VINCreator APK 逆向

原厂 = `VINCreator_standard_target34_v1.4.11`（`/root/WindowsR/berxel/sdk/` 下 APK；eYs3D 非 Berxel）。
逆向工具：NDK `llvm-objdump`（host binutils 无 aarch64 后端）+ `androguard 4.0.1`（dex 反编）。
4 月那份 `Tools/VinRectifyDemo/vin_rectify_demo.cpp` + `docs/VIN_RGBD_Rectification_Design.md` 是**旧逆向移植/设计稿**
（单相机 registered 假设 `cx=x*colorW/depthW`、forward splat + hole fill），**非原厂真源码**，不作对齐基准。

**原厂还原 = 重型 OpenCV + ONNX 管线**，端侧不该扛 → 服务端是对的家。两层：

**A. Java/Kotlin 侧（`classes.dex`，已完整反编）**
- `com.esp.uvc.main.CameraPresenter.performImageRestoration(base, sub)`：编排入口。读
  `<base>/result/<sub>/` 下 `*_rgb1300.jpg`(HLSD8 彩色) + `*_depth.yuv`(深度 16bit) → YOLO OBB →
  打包 regions[] → `CreatorNative.processAll(...)` → `*_restored.jpg`。返回码 0=成功 / 60=校验完成 /
  61=校验失败 / 其它=错误。
- `processAll` 真签名（由 `processAll$default` 桥还原，默认 brightness=0/contrast=0/gamma=1.0f）：
  `processAll(deviceID, rgbPath, depthPath, outPath, "/VIN/param"目录, regions[], 站名, 操作员, 记录VIN, "front"|"rear", brightness:int, contrast:int, gamma:float)`。
  `"front"|"rear"` = `bIsRotated180` → **后置 VIN 的 180° 翻转**（不是端侧 `(0,-1,0)` 硬凑）。
- `com.esp.uvc.main.ONNXDetector`（纯 Kotlin，全 recovered）：
  - 模型 `assets/model/yolo-obb.onnx`（YOLO11s-OBB，输入 `images[1,3,640,640]`，输出 `output0[1,6,8400]`，单类 `number`）。
  - `preprocess`：letterbox 640×640、pad=114、/255、NCHW RGB、**无 mean/std**；记 `ratio/padX/padY`。
  - `postprocess`：`output[1,6,8400]` 转置→每行 `[cx,cy,w,h,score,angleRad]`；**score≥0.5** 保留；
    去 letterbox 还原到原图坐标；`createRotatedCorners(cx,cy,w,h,angleDeg)`→`sortCorners`(按 x+y/x−y 排 TL/TR/BR/BL)；
    `rotatedNms` IoU>0.4（Sutherland-Hodgman 多边形裁剪算旋转 IoU）。
  - `DetectionRegion.toNativePackedArray(true)` = **9 floats** `[TLx,TLy,TRx,TRy,BRx,BRy,BLx,BLy,angleDeg]`，
    坐标在 **rgb1300 像素系**，`angleDeg=computeOrientationDegrees`(取长轴朝向，归一化 [-90,90))；
    regions[] = 各字符 9 floats 拼接。本版 `deskewAngleDeg=0`、rawRegions==restoreRegions==regions。

**B. native 侧（`libcreator_jni.so`，OpenCV C++；2026-06-18 工作流逐函数反汇编 + 对抗校验，高置信）**

> ⚠ 订正早期臆测：`picshadow` **不是去阴影**、`postProcessV3G` **不是亮度/对比度/gamma**——两者都是**几何/裁剪**函数。
> 真正的二值化/去阴影/gamma 在**上游**（`GetSignature3G`/`CaptchaRecog` 一带，尚未逐函数逆向）。

`ImageRestorerFunc::restoreImageFlow`(总入口,0x37f48c) 真实数据流：
1. `readDepthYuv` → u16。深度单位：camMode==1 走视差 `Z=(f·50)/(raw·0.125)`，否则 **raw 直接当 mm**。失败码 -2。
2. 检测签名/铭牌框 4 角（`IsCheckImageForOrigin`/m_param 两路；199/200 哨兵收缩+clamp）。命中置 `outCheckFlag`。
3. **点云**：4 角按 **Z=248** 反投影出采样矩形，矩形内**逐像素**采深度，过门 **50mm<z<1000mm**，反投影
   `Xw=(u−cx)·z/fx, Yw=(cy−v)·z/fx, Zw=z`（Y 翻转、**X/Y 都用 fx**）。点云 <100 → 判废**码 36**。
4. **RANSAC 平面 `z=ax+by+d`**（最少 **50** 次迭代，maxIter 447；3 点分支 CV_32F+solve(LU)，精修分支 CV_64F+SVD；
   点到面距 `|ax+by+d−z|/√(a²+b²+1)`，阈值 = 全局 float@0xce0 待取）。
5. **倾角门**：`tilt=acos(1/√(a²+b²+1))·180/π`，**|tilt|>70° 判废码 34**。
6. `GetRotationMatrixForPlane`：**R = RotY(θy)·RotX(θx)**，`θx=−atan2(b,1)`、`θy=atan2(a,√(b²+1))`（弧度，无 π/180）；
   `RotX=[[1,0,0],[0,c,−s],[0,s,c]]`、`RotY=[[c,0,s],[0,1,0],[−s,0,c]]`，CV_64F。
7. **metric 画布**：`scale`(px/mm) 按 `g_nRecMode` = 1→20 / 2→25 / else→10；4 角经 `GetPlaneXYZ`(像素射线∩平面解 3×3,CV_32F)
   +`GetRotatedXY`(`R·(P−T)+T`,CV_64F) 投到平面 2D，min/max 各加边距{±10,±5}，`outW/H=int(1+(max−min)·scale)`，
   **上限 40000×10000 → 码 40**。
8. 128 灰底画布 + `getPerspectiveTransform(src4角, dst画布角)`(DECOMP_LU) + `warpPerspective`(主路径动态尺寸；
   `PerspectiveTrans` 固定 300×300+INTER_CUBIC **只 CaptchaRecog 用**)。
9. `postProcessV3G`(img,region,W,H,centering,gamma=1.0f,flip=false)：**四角合法性裁剪**——合法角(5≤x≤W−5,5≤y≤H−5)
   ≥3→`minAreaRect`+`getRotationMatrix2D`去斜；<3→`cropImage`(128 灰底居中 ROI)。**无光度运算**。
10. `picshadow`(img,rect)：**对已二值化图(前景==0黑)按黑像素行/列投影取最长连续行带 + 累计>10 定左右界，裁紧致框+5px**，
    原地 clone 替换。**无任何滤波/形态学**。→ 写 `result_temp.jpg` → `IsCheckImage`/`IsCheckImageForOrigin`(码 62)。

`CCameraModel`（标定 bin + 畸变）：
- `LoadBinStereoParas`：bin **硬校验 camCount==3**；字段偏移 cx@0 cy@8 fx@0x10 fy@0x18，畸变条 5 double/40B，外参条 3 double/24B，
  两 256B 保留块 + 256B map1/map2；末段 11 个 int 立体头。**我们自标定输出须按此布局**（或绕开，见 §10.3）。
- 畸变模型 = **任意阶纯偶次径向 `Σk_i·r^{2i}` + Brown 切向(p1,p2) + 前置 FOV/atan 角度映射**（`xn'=fx·xn·atan(r/fx)/r`），
  `UndistortionPointByLM` 用 MINPACK lmdif 迭代求逆（非 OpenCV 标准 5 参，不能用 `undistortPoints`）。
- `setCharRegionsFromYolo`：JNI 自适应解 stride(8/9)；每字符 size=avgW·avgH(>1 才纳入)；全有效角拼 `minAreaRect` 求 VIN 整体框，
  过长宽比门(W≥20,H≥10,W/H≥2)直接用，否则「倍角圆均值」估主方向反旋取轴对齐框；4 角(z=10000 占位)写入相机模型置 `g_vinReady`。

### 10.2 端侧采集契约（已落地，2026-06-18）

`VinCaptureViewModel.capture()` 改为**先无条件落盘原始采集**（端侧近似正射降为 best-effort 预览）：
- 落 `externalFiles/vin_captures/cap_<seq>_<ts>/`：`rgb1300.jpg`(HLSD8 JPEG q95) + `depth.yuv`(u16 LE metric mm) +
  `meta.json`(deviceId + 彩色/深度尺寸 + **深度真内参**[反投影必需] + colorPixelType + ts + seq)。
- 每拍独立目录、序号续号，`adb pull` 取走脱机自测。

**首批真机数据（2510DRK44C，落 `.dev/vin_captures/`，11 张/2 块板）**：
- 板1 cap_001-006 = `☆LA99FRP32G0LTH013☆`（清晰/基本正对）；板2 cap_007-011 = 另一串（偏暗+反光+带角度）。
- 彩色 **1280×256**（pixelType=HLSD8_RGB24，**非 13MP 全分辨率**；待查 HLSD8 是否能出更高模式，影响 OCR 上限）；
  深度 640×128、内参 fx=614.6025/fy=163.894/cx=324/cy=64.382（各向异性）、有效 79~81%、板距 ~1850mm。
- ⚠ **彩色=深度精确 2×**（同 5:1 aspect）：离线实测证 **registered（同光路、无视差、零标定可行）**——按「color 内参=2×depth、外参单位阵」
  反向正射，VIN **零重影**、几何正确。HLSD8 高清标定路（M6.9.5b）非重合必需，仅为提清晰度。
- ⚠ **深度尺度疑似过标定 ~3×**：实测板距中位 ~1850mm，但**原厂深度有效门是 50mm<z<1000mm**（设计要求距板 30-60cm）。
  我们读 1850 → 要么 eYs3D 深度过标定 ~3×、要么真拍远了。**对重合不致命**（按 OBB 宽归一化已规避；原厂走 metric px/mm 则需真尺度）。
  待核：用已知尺寸物量测 eYs3D depth 真尺度（关联 M6.5 flash 内参）。

### 10.3 落地计划

1. **离线还原 harness ✅ 成型且达标**（`tests/harness/vin_restore/`：`run.sh`+`restore_obb.py`+`obb.py`+`analyze.py`，Python cv2+onnxruntime）：
   深度 RANSAC 平面 z=ax+by+d → 倾角>70°门 → 摆正 → `yolo-obb.onnx` VIN OBB(number 框,排星) 四角单应正射(逐像素 remap)
   → **原厂 GetSignature3G 去阴影二值化(`adaptiveThreshold` GAUSSIAN/BINARY/blockSize=131/C=15 + erode(CROSS3)/dilate(RECT5)/去小斑/CLOSE+OPEN)**。
   `analyze.py` 用 EUCLIDEAN ECC 测同 VIN 几何残余(主指标)。**实测结论=正常：同 VIN 各张几何重合，中位残余 0.44~0.48%(4~5px/1000)、旋转<0.6°，
   六张叠加成锐利可读 `☆LA99FRP32G0LTH013☆`**；板2 强反光被超大窗自适应阈值根除→清晰可读。
2. ✅ **原厂真去阴影已落定**：`GetSignature3G` 的 `adaptiveThreshold(blockSize=131,C=15)` 是唯一去阴影/二值化算子(非 CLAHE/gamma)，
   blockSize 远大于笔画→去金属反光梯度。`picshadow`=黑像素投影内容裁剪(留作 Go 端口 `picshadow_crop` 参照；harness 主锚定用 OBB 单应框已排星，故未叠用)。
3. ✅ **Go cvengine 端口已落地**（`server/internal/cvengine/restore/` + `POST /cv/ocr/v1/vin_restore`）：纯 Go 无新 cgo——
   OBB 走 `gocv.CreateORTCom` 取原始 [1,6,8400] 张量 Go 侧解码（新增 `gocv.RunCom` 桥 + core `KindCom`）；平面 `z=ax+by+d` 用 `gocv.Solve`
   (SVD-free)；四角单应 8×8 `Solve`（`GetPerspectiveTransform` 仅整型）；去阴影 `adaptiveThreshold(131,15)`。`go build` 绿；
   11 张真机自验 Go vs Python **7/11 逐像素一致、最大差 0.106%**(RNG 种子差,内容同)，Go 出图跑同一 analyze=正常(中位残余 0.48%)。
   运行期 `LD_LIBRARY_PATH` 须含 `/usr/local/lib64`(opencv_world.so.405)。
4. **标定**：实测 registered（彩色=深度2× 零视差）→ 重合零标定即成；HLSD8 高清标定(M6.9.5b)仅提清晰度，非必需。
   metric 真尺度（原厂 px/mm 路）需 eYs3D depth 真标定（关联 M6.5/depth 50-1000mm 门）。
5. ✅ **App 上传 → 服务端还原 → 回显已拉通**（端侧三模块 `compileDebugKotlin` 含 KSP 绿）：
   `CVEngineApi.vinRestore`（multipart `image_binary_rgb1300`+`image_binary_depth`+`depth_w/h`+`fx/fy/cx/cy`+`device_id`，
   经 devserver `/cv/` 前缀反代，App 只带 JWT、HMAC 由 devserver 加）→ `VinRepository.restore()`（`Envelope<VinRestoreResponse>` 解包 +
   base64→PNG 字节）→ `VinCaptureViewModel.capture()`（深度 u16/彩色 JPEG 压一次复用 → 落盘 → 端侧 `OrthoRectify` 即时占位预览 →
   **上传服务端权威签名图覆盖 `_rubbing`** + 倾角/内点/检出提示）。tilt 判废走 HTTP 200/`ok:false` 友好提示；上传失败走「已存可重试」不丢盘。
   服务端 HTTP 契约 `TestVinRestoreHTTPContract` 用真机 11 张数据直打 `h.VinRestore`：11/11 返合法 PNG（1200×260，tilt 1.9–7.0°）。
6. ✅ **OCR 接 `vin_pipeline`（还原签名 → 识别）**：`VinCaptureViewModel.recognize()` 改喂**服务端还原签名 PNG**（`restoredSignaturePng`，
   去阴影 OCR 级二值图，比原始 color 更利识别；还原未成功则提示先拍照，不退化喂 color）；`ScanCaptureScreen`「确认」按钮接 `recognize()`，
   观察 `vm.state` 四态，`VinCharCompare`/`VinSummary` 去掉硬编码演示 VIN + 假厂商/年份解码，改渲染真 `VinResult`（逐字符 character+similarity
   归一 0..100、verdict/检出/比对/字形均值）。`vin_pipeline` 按 magic 字节 IMDecode，PNG 通吃。`:feature:scan3d` 编译绿。
7. ✅ **模型部署走 model-registry（代码就绪）**：yolo-obb 改与 VMASK 同机制由 registry 提供，不再特殊 lazy 加载。
   - `loader.go` 加 `metadata.kind="com"` 分支 → `core.RegisterComONNX`（std 取 `metadata.std`，缺省 1/255）；dev 旁路 `GOMOB_CVENGINE_MODELS="VINOBB:com=/path"` 已支持。
   - `handler.go ensureVinObbModel` 优先复用启动期 loader 注册的 `VINOBB`（`h.models.Get`），仅纯本地无 registry 时才从 `VIN_OBB_MODEL`/默认 `.dev` 兜底。
   - `go build ./...` 绿、loader/restore `go vet` 净、HTTP 契约 httptest 仍 11/11（兜底未坏）。
   - **ops 播种**（infra-gated，需 MinIO+registry 在跑）：`scripts/seed-vinobb-model.sh`（mc 上传 onnx → `POST /admin/v1/models` kind=com → activate），再把 `VINOBB` 加进生产 `GOMOB_CVENGINE_MODEL_NAMES` 重启。
   **待**：⑧ 真机 live 回线（拍 → 上传还原 → 确认 OCR → 回显；**VMASK 对二值还原签名的识别质量需真机验**，服务端 vin_restore 契约已真机数据验，device/infra-gated）。
