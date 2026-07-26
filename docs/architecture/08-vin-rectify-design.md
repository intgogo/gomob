# 08 — VIN 数码拓印设计

> 业务定义：用户对着汽车 VIN 钢架（车架号那块**冲压在金属上的字符**）按一下"拍"，
> App 拍一张 iHawk 深度图 + Color 图 → 拟合钢架表面（一般是平面或微弧面）→ 重投影到
> **固定法向距离**的正射图（消除拍摄角度倾斜的透视畸变）→ 出一张 1:1 的"数码拓印图"
> 给后端 OCR 和审核用。
>
> 类比：用复写纸描钢印的"拓印"工艺，但端侧靠深度图自动 +
> 不需要物理接触车架。

> §1–8 是早期端侧 native 方案的设计脉络；当前运行时权威以 §10 原厂逆向 + 服务端还原为准。

## 1. 输入 / 输出契约

### 1.1 输入

- HLSD8 `4160×832` 原始 MJPEG + RS-D550 mode25 `640×128` 原始 u16 LE 视差（数值=真实视差×8）。
- 两颗独立 5fps USB 相机无共同硬触发；快门事务跳过3张彩色、至少收齐3+3帧并选整批最小回调差。
- 原厂 `/root/WindowsR/VIN_BF301208.bin`：高分 RGB 内参/私有畸变/Euler+T 外参 + mode25 深度 K/基线/数据类型。
- 完整标定键：深度序列号、HLSD8 序列号、深度档位、彩色档位。

### 1.2 输出

- `VinRectifyResult`：
  - 用户可见/OCR 彩色规范图固定 `4425×600`；第 9 字符居中、基线水平。原始 RGBD 只采样一次，线性部分只允许旋转与等比尺度。
  - VINCreator 原生链是 `RecMode=2 → 双轴统一25px/mm → 5000×678工作图 → scale=1.0 → 中心裁切(288,39) → 4425×600`，字符尺度不随 Print 画布变化。
  - `Meta.WidthMM/HeightMM` 保留真实物理范围；绝对字号由原厂标定恢复的字符物理节距乘 `25px/mm` 得到，不再使用“120mm→177.78px”人为换算。
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
        ↓ 5000×678 工作图 → 原像素中心裁切为 4425×600 用户/OCR BGR
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
    float pixel_size_mm; // 默认 0.2 → 1200 px = 240 mm 实际宽度
    int width, height; // 用户输出固定 4425×600；5000×678 仅为原厂内部工作图
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
    val outputWidth: Int = 1200,
    val outputHeight: Int = 260,
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
[VIN 入口卡] → HLSD8 彩色 + RS-D550 深度双预览（叠原厂 ROI）
              ↓ 用户调好取景
              [拍] 按钮
              ↓ 点击后 3+3 多帧快门事务，整批选最小回调差
              ↓ 原始 RGBD 上传服务端原厂标定还原
              ↓
[结果页]      上：深度/彩色取景预览
              下：拓印图（服务端 4425×600，界面按比例 Fit）
              ↓ 用户操作：保存（写本地 + 推服务端 OCR）/ 重拍
```

ROI 选框默认：屏幕中心 70% × 30% 的横条（VIN 钢架是横向 17 字符）。用户可拖动调整。

## 6. 与服务端 OCR 的对接

2026-07-10 起，车架号检测识别先使用现场外部算法服务：

```text
Android 权威正射 PNG
  → JWT POST /cv/ocr/v1/vin_recognize
  → Gomob cvengine 服务端代理
  → nanos + RSA-SHA1 sign
  → POST http://192.168.9.166:35000/cv/ocr/v1/vin_detect
```

- Android 不直连算法机，也不持有旧式 RSA 私钥；cv-engine 只从
  `GOMOB_VIN_ALGO_PRIVATE_KEY_FILE` 指向的只读部署密钥挂载加载，源码、镜像和 APK
  均不得包含私钥。现有 JWT + Gateway HMAC 与外部 `nanos/sign` 是两层独立鉴权。
- 外部请求不发送 `skip_image`。真正的单字符切割素材只取所选 VIN item 的
  `more[].origin_image_data`；`result.image[].vin_detect_image` 是整行检测图，不进入 App 契约。
  Gomob 服务端解析厂家 `more` 后归一化为
  `character_crops[{position,character,image{mime_type,data_base64,width,height}}]`，只下发
  `64×128` 彩色单字符 WebP；原始 `more`、`alpha_image_data` 和整行图均不透传 Android。
- 成功必须同时满足 HTTP 2xx、`error_code==0`、`error_msg=="success"`、VIN 非空。
  `error_code=0` 但错误消息非 success 的旧分支仍按失败处理。
- 总体 OCR 置信度取 `scores` 的算术均值；顶层 `score` 可能是字符分数总和，不能当作
  0..1 置信度。`scores`、`more` 数量、`more[].character` 顺序必须与 VIN 完全一致；任一不一致、
  base64/WebP 损坏或尺寸不是 `64×128` 都按上游失败，禁止回退整行图或补造字符素材。
- 该接口没有厂家字形库 verdict。界面仅在文本和服务端计数均为合法 17 位 VIN 时显示
  “识别完成”，其他情况显示“需复核”；不显示“通过/未通过/字形校验”。识别结果区横向展示
  每位真实单字符切图、位置、字符和对应分数，不把整行还原图重复当成切割证据。

## 7. Harness

`tests/harness/vin_rectify_quality/` 验收：

- 录制 N 个真实 VIN 钢架 RGBD pair（不同角度、不同光照、不同距离）
- 跑 `vinRectify` → 拿到拓印图
- 比对：相同 VIN 多角度拍摄的拓印图，结构相似性（SSIM）≥ 0.9
- 比对：同一批真值样本经 `/vin_recognize` 调外部算法，记录完整串准确率、字符准确率和
  非 17 位复核率；外部服务失败必须得出可判定异常，不能回退旧本地 verdict 伪装成功。

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
- `LoadBinStereoParas`：固定 2420B little-endian；前两段 256B 元数据被原厂忽略，payload `0x200` 是唯一显式校验的 version=3，不存在 `camCount` 字段。
- `0x20c` 起为 f64 高分 RGB 相机模型 A，含行/列主点与焦距、私有畸变、Euler 和 mm 平移；`0x294` 模型 B 同构。当前服务端额外要求 A/B 一致作为部署加固。
- `0x340..0x360` 为 mode25 深度块：`cx/cy/f/B/depth_data_type`；type=1 表示 raw disparity×8，原厂用 `z=f·B/(raw·0.125)`。文件无 CRC，部署侧必须外部 SHA-256 白名单。
- 畸变模型 = **任意阶纯偶次径向 `Σk_i·r^{2i}` + Brown 切向(p1,p2) + 前置 FOV/atan 角度映射**（`xn'=fx·xn·atan(r/fx)/r`），
  `UndistortionPointByLM` 用 MINPACK lmdif 迭代求逆（非 OpenCV 标准 5 参，不能用 `undistortPoints`）。
- `setCharRegionsFromYolo`：JNI 自适应解 stride(8/9)；每字符 size=avgW·avgH(>1 才纳入)；全有效角拼 `minAreaRect` 求 VIN 整体框，
  过长宽比门(W≥20,H≥10,W/H≥2)直接用，否则「倍角圆均值」估主方向反旋取轴对齐框；4 角(z=10000 占位)写入相机模型置 `g_vinReady`。

### 10.2 端侧采集契约（已落地，2026-06-18）

`VinCaptureViewModel.capture()` 改为**先无条件落盘原始采集**，手机不运行近似正射：
- 落 `externalFiles/vin_captures/cap_<seq>_<ts>/`：`rgb1300.jpg`(HLSD8 原始 MJPEG) + `depth.yuv`(mode25 u16 LE raw disparity×8) +
  `meta.json`(两相机序列号 + 两路档位 + `DepthSampleFormat` + 同一 host 单调时钟 + burst 诊断)。
- 每拍独立目录、序号续号，`adb pull` 取走脱机自测。

**首批真机数据（2510DRK44C，落 `.dev/vin_captures/`，11 张/2 块板）**：
- 板1 cap_001-006 = `☆LA99FRP32G0LTH013☆`（清晰/基本正对）；板2 cap_007-011 = 另一串（偏暗+反光+带角度）。
- 彩色 **1280×256**（旧预览转储，非当前 4160×832 原始档）；深度 640×128。历史 meta 把 raw disparity×8 误写成 mm，并错误使用 `fy=163.894`，所以“板距约1850mm/尺度过大”结论无效。
- 原厂 BIN 给出 1280×256 mode25 `f=1229.20996,cx=648,cy=130.865`；640×128 统一缩放0.5后 `fx=fy=614.60498,cx=324,cy=65.4325`。当前生产由服务端原厂标定恢复毫米坐标。
- **已推翻的历史误判**：彩色与深度恰好同为 5:1、分辨率恰好 2×，不代表 registered 或同光路。HLSD8 与 RS-D550 是两颗独立相机，存在约 24mm 基线和旋转差；单位外参只能用于早期离线观察，生产必须按完整 rig + 流档位加载双相机标定。
- 当前原厂四角度数据恢复的承印面中位深度约 264–378mm，落在原厂 50–1000mm 有效门内；旧“过标定约3×”由单位误读产生，已撤销。

### 10.3 落地计划

1. **离线还原 harness ✅ 成型且达标**（`tests/harness/vin_restore/`：`run.sh`+`restore_obb.py`+`obb.py`+`analyze.py`，Python cv2+onnxruntime）：
   深度 RANSAC 平面 z=ax+by+d → 倾角>70°门 → 摆正 → `yolo-obb.onnx` VIN OBB(number 框,排星) 四角单应正射(逐像素 remap)
   → 彩色正射还原图；内部再生成签名图用于质量闸、水平校正和 OCR 辅助。
   `analyze.py` 用 EUCLIDEAN ECC 测同 VIN 几何残余(主指标)。**实测结论=正常：同 VIN 各张几何重合，中位残余 0.44~0.48%(4~5px/1000)、旋转<0.6°，
   六张叠加成锐利可读 `☆LA99FRP32G0LTH013☆`**；板2 强反光被超大窗自适应阈值根除→清晰可读。
2. ⚠️→✅ **去阴影二值化真机订正 + 鲁棒重写（2026-06-21，真机 21 组实测）**：原 `GetSignature3G` 的 `adaptiveThreshold(131,15)`
   在另一次真机采集（同钢板不同补光/距离）**两处失效**：① **极性反转**——刻字在该光照下镜面高光灌进刻槽→比底**亮**，固定
   BINARY+反相整片翻成**白字黑底**（墨水占比 91%，端上显示"还原图有问题"）；② **固定边距怕低对比**——字-底差 < C 时整串丢字成碎片。
   **新法**（`signature_binarize`/`signatureBinarize` 两端同步）：bilateral(9,60,60) 保边降噪除钢板微纹理 → **背景除法平照**
   `norm=clip(d÷(GaussianBlur σ21 +1)×180)` 拉平光照不均 → 全局 **Otsu** → **极性归一**（真实墨水稀疏 ~8-12%，前景过半即反相）
   → 去小斑 → OPEN/CLOSE。**质量闸**：归一后墨水占比 > `SigInkMax=0.25` 判废（`ErrLowQuality`→`ok:false`+`reject_reason=low_quality`），
   端侧提示重拍而非显垃圾。实测 21 组：14 组好采集出干净可读签名（墨水 8-12%，含原 91% 垃圾的 6 组 live 转 8-9%），7 组坏采集（框偏/糊/低对比/板2 反光）正确判废。
   `picshadow`=黑像素投影内容裁剪；当前 Go 端把同一旋转/裁切应用回彩色正射图，黑白签名不作为用户可见返回图。
3. ✅ **Go cvengine 端口已落地**（`server/internal/cvengine/restore/` + `POST /cv/ocr/v1/vin_restore`）：纯 Go 无新 cgo——
   OBB 走 `gocv.CreateORTCom` 取原始 [1,6,8400] 张量 Go 侧解码（新增 `gocv.RunCom` 桥 + core `KindCom`）；平面 `z=ax+by+d` 用 `gocv.Solve`
   (SVD-free)；四角单应 8×8 `Solve`（`GetPerspectiveTransform` 仅整型）；去阴影 `adaptiveThreshold(131,15)`。`go build` 绿；
   11 张真机自验 Go vs Python **7/11 逐像素一致、最大差 0.106%**(RNG 种子差,内容同)，Go 出图跑同一 analyze=正常(中位残余 0.48%)。
   运行期 `LD_LIBRARY_PATH` 须含 `/usr/local/lib64`(opencv_world.so.405)。
4. **标定历史订正**：早期把 `1280×256` L' 当成 HLSD8 后得出“彩色=深度2×、零标定”的结论；两颗独立 USB 相机与全分辨率数据已推翻该判断。HLSD8↔RS-D550 外参是生产必需项，且必须绑定完整 rig 与两路流档位；metric 真尺度还依赖逐设备深度标定。
5. ✅ **App 上传 → 服务端还原 → 回显已拉通**（端侧三模块 `compileDebugKotlin` 含 KSP 绿）：
   `CVEngineApi.vinRestore`（multipart `image_binary_rgb1300`+`image_binary_depth`+`depth_w/h`+`fx/fy/cx/cy`+
   `device_id`+`color_device_id`+`color_w/h`+两路 native 单调时钟时间戳，
   经 devserver `/cv/` 前缀反代，App 只带 JWT、HMAC 由 devserver 加）→ `VinRepository.restore()`（`Envelope<VinRestoreResponse>` 解包 +
   base64→PNG 字节）→ `VinCaptureViewModel.capture()`（深度 u16/彩色原始 JPEG 复用 → 落盘 → 上传 →
   **服务端权威 `4425×600` 彩色规范图覆盖 `_rubbing`** + 倾角/内点/字符锚定提示）。手机不再运行近似正射；倾角、同步、字符格架或标定不可用均走 HTTP 200/`ok:false` 结构化判废，上传失败仍保留原始采集。
   历史 `1200` 宽动态高度与 `low_quality` 墨水占比闸已由 §11 当前契约取代。
6. ✅ **OCR 改接外部算法（还原图 → 识别，2026-07-10）**：新增纯 Go
   `server/internal/vinalgo/`，由 cvengine 暴露 `POST /cv/ocr/v1/vin_recognize` 并转调
   `192.168.9.166:35000/cv/ocr/v1/vin_detect`。客户端每请求生成新 `nanos/sign`、拒绝重定向、
   不发送 `skip_image`，严格检查 HTTP/业务码/消息/VIN，并从同一 VIN item 的 `more[]` 提取、
   完整解码校验与 VIN 等长的 `64×128` 单字符彩色 WebP；整行 `vin_detect_image` 不再下发。Go 1.26 会拒绝现场旧 512-bit RSA，
   因此服务端按 RFC 8017 手工完成 PKCS#1 v1.5 编码与模幂，并有兼容公钥验签测试。
   Android 删除默认 `vehicleModelId=10001` 和 `vin_pipeline` 的 verdict/reasons/similarity，
   只上传权威正射 PNG；界面横向展示真实单字符切图、位置、字符和对应置信度，主视图保留字符数、
   平均置信度、来源和耗时。禁止拆整行图或由文本拼装假字符卡。合法 17 位为“已完成”，否则“需复核”，
   不再冒充厂家字形结论；全部单字符 Bitmap 与文本结果经同一 generation 原子发布。
7. ✅ **模型部署走 model-registry（代码就绪）**：yolo-obb 改与 VMASK 同机制由 registry 提供，不再特殊 lazy 加载。
   - `loader.go` 加 `metadata.kind="com"` 分支 → `core.RegisterComONNX`（std 取 `metadata.std`，缺省 1/255）；dev 旁路 `GOMOB_CVENGINE_MODELS="VINOBB:com=/path"` 已支持。
   - `handler.go ensureVinObbModel` 优先复用启动期 loader 注册的 `VINOBB`（`h.models.Get`），仅纯本地无 registry 时才从 `VIN_OBB_MODEL`/默认 `.dev` 兜底。
   - `go build ./...` 绿、loader/restore `go vet` 净；当前 HTTP 契约验收见下方真机 21 组。
   - **ops 播种**（infra-gated，需 MinIO+registry 在跑）：`scripts/seed-vinobb-model.sh`（mc 上传 onnx → `POST /admin/v1/models` kind=com → activate），再把 `VINOBB` 加进生产 `GOMOB_CVENGINE_MODEL_NAMES` 重启。
8. ✅ **真机 live 回线 + 正交还原订正（2026-06-22/23，真机 21 组）**：真机 2510DRK44C 拍→上传→还原→回显跑通（devserver 多次 200）。用户纠正已修（详 finding_vin_ortho_color_upright_2026-06-22）：
   - **输出彩色正射图**：`Restore` 返回 `render()` 后经内部签名图水平化/裁切/尺度归一的彩色 PNG；`signatureBinarize` 只作质量闸、校正和 OCR 辅助。
   - **历史输出比例订正**：该阶段从固定 `1200×260` 改为 1200 宽动态高度；当前已由 §11 的 `5000×678 工作图 + 4425×600 用户图 + 25px/mm` 取代。
   - **字符端正**：原图 VIN 字本正、还原后左右渐斜=残余透视。根因① 彩色内参 `fyc` 错——深度竖直 binning anamorphic(`fy164/fx614`)，彩色近方形 `fyc=fxc`≠`2·fyd`；根因② 平面拟合取中心 ROI 纳入背景。改 **`fyc=fxc` + 只在 OBB 区拟合平面**（inlier 0.44-0.82→0.99-1.0、rms 8.4→4.4）→ 21 组竖笔倾角全≤2°。
   - 验收：应以彩色正射图为准，确认同 VIN 多视角字符大小、水平、位置一致；黑白签名仅作重合分析和 OCR 辅助信号。
   **待**：⑥ OCR 段 VMASK 真机验（dev 栈 0 模型/0 字形库，缺 VMASK 阻断）。

## 11. 固定字符格架与手机端职责收敛（2026-07-11，当前权威）

> 本节取代 §9.1 的端侧即时正射、§10.3 中“动态高度/二次内容校正/端侧估算预览”等旧运行时描述。历史逆向结论保留，但生产行为以本节和 [摘要](08-vin-rectify-design-summary.md) 为准。

### 11.1 为什么整行二次 OBB 不能作为最终校正

输出域重新检测整行 OBB 不具相似变换等变性。同一图经过相似变换后，检测框中心、宽高仍会因背景、星号、腐蚀和阈值变化而漂移；把它再次用于旋转/裁切/缩放会放大误差。历史 plate_a 的中心偏差由 `25.65px` 恶化到 `47.23px`，plate_b 9/9 样本均变差，因此该路径已删除。

### 11.2 17 字符刚性格架

`vins0.onnx` 是 YOLOv5 三输出逐字符模型。对候选字符中心做鲁棒拟合：

```text
cᵢ = o + (i - 8) · p · u,  i=0..16
```

- `o` 是第 9 字符中心；探针落 `(600,130)`，最终用户图落 `4425×600` 的几何中心。
- `u` 是字符基线方向，最终旋到水平。
- `p` 在探针中约 `64px`，只用于检测格架；最终像素节距由同一承印平面上的真实字符节距乘原厂 `25px/mm` 得到，当前原厂 oracle 约 `170.28–170.35px`。
- 最多从 24 个候选中选最规则的 17 个；IRLS/Huber 抑制离群框。
- 平均置信度、格架 RMS、尺度修正或角度不可靠时返回 `text_anchor_unreliable`。

粗规范图只用于逐字符检测。17 个字符中心再通过 HLSD8 像素射线回到三维，但只允许在深度拟合出的承印平面两个正交基内求一条等步长基线；禁止让单张 RGB 内容把基线掀离深度平面。最终中心、水平轴和垂直轴都留在该平面内，双轴正交且共用 `25px/mm`，再从原始彩色图一次 Remap 到 `5000×678` 工作图，最后原像素中心裁切为 `4425×600`。规范自由度严格是平移、旋转和统一尺度，不含 shear、非等比拉伸或自由仿射。原厂 `FlipAndCropImage` 的生产 scale 为 `1.0`，禁止按画布宽度二次拉伸内容。

### 11.3 VINCreator 预览与手机显示契约

- HLSD8 彩色采集 `4160×832`，mode25 深度 `640×128`，原始比例均为 `5:1`。
- 双预览槽全宽等高并直接使用流的原始 `5:1` 比例；画面 `ContentScale.Fit` 完整显示，不制造上下黑边，也不加外层黑框或圆角。框内空间质量不足时提示对准车架号，可靠中位距离 `>400mm` 时显示实际距离并提示“距离太远，请靠近至 40cm 以内”，两种状态都保持红框与禁用快门；达标后绿框并提示“请稳住不动”，稳定后自动拍摄识别。实时指标始终显示框内有效率，但只有覆盖率和原始点支撑同时达标时才附中位距离；低有效率下少量残留深度没有空间代表性，禁止显示不准距离。结果区直接把 VIN、完成/复核状态、字符数、平均置信度和最低置信字符前置；推理耗时只留日志。`4425×600` 尺寸放图外，还原图无角标遮挡和圆角裁切；17 张切图把序号、字符和置信度叠入图内横滑，避免重复标题占用手机纵向空间。
- 预览显示使用 `VinRgbdPairer.snapshot()` 的同一个最近邻回调帧对。彩色保持 HLSD8 视场；深度逐像素按原厂 BIN 执行 `raw disparity×8 → 世界坐标 → R·P+t → 私有 FOV → 私有像素畸变`，缩放到实际彩色预览 Bitmap 尺寸后用 `3×3` forward splat 和 `abs(cameraZ)` z-buffer。该变换只产生显示副本，不改 `DepthFrame`、快门 burst、原始落盘或上传内容。
- cv-engine 的 `GET /cv/ocr/v1/vin_preview_calibration` 只按完整 rig/profile 返回不可变值快照；App 每页每键只请求一次并校验契约、SHA/version、数组长度、有限数和旋转矩阵。深度 Z 公式必须使用原厂全幅 `1229.2099609375`，当前档位 `614.60498046875` 只用于 XY；两者混用会把距离缩半。用户主标签统一为“深度图”，接口加载中或失败时只在副状态标明“标定加载中/原始”，禁止静默伪装已配准。
- RS-D550 + HLSD8 的连接状态和只读设备详情只属于 VIN 使用流程，入口固定在页面顶栏右上角并直接消费 VIN 的真实采集就绪状态；预览画面不悬浮设备按钮，三维扫描根页也不展示该按钮，禁止以 iHawk 状态代指 VIN RGBD 设备。
- 彩色 ROI 使用 `2dp` 方框、四边内缩 `20dp`，中心是 `24dp` 环形准星；不可拍为 `#FFFF0000`，质量门通过后整体变为绿色。Compose 根据实际 viewport 把同一框换算成图像域归一化 ROI，Canvas 与投影质量统计禁止各算一套近似区域。
- ROI 质量门按因果顺序统计：`3×3` splat 后框内有效覆盖 `≥95%`；连续投影中心数/ROI 像素数 `≥15%`，防止少量点被 splat 虚增。连续投影点的 P10、中位距离和 `≥300mm` 比例继续记录，但只用于操作提示、日志和结果说明，不能参与红绿框或快门裁决。预览达标只负责激活按钮，快门每轮 burst 的最终深度帧还要重跑同一门；瞬时判废会继续下一轮，最多 6 轮，全部失败才回到取景且绝不落盘。相机掉线、权限撤销或任一路退出 Streaming 时必须立即清质量态，禁止残留绿框。
- `4425×600` PNG 完整字节保留给 OCR；手机按上限降采样解码显示副本，避免完整 ARGB Bitmap 常驻。
- HLSD8 连续流只保留同一回调的原始 MJPEG，界面真正需要绘制时才降采样解码；不再逐帧走 MJPEG→RGB DirectBuffer→Bitmap 双转换。VIN 配对器为快门 burst 最多保留 12 张大 JPEG 和 12 张深度，采集中暂停预览解码，避免拖慢候选帧收集。

`tests/harness/vin_preview_alignment/` 用原厂 BIN、真机 `cap_036` 和生产 Kotlin 投影器做跨端固定向量与覆盖/性能判定：有效深度点 `61,416`，落彩色视场 `67.9%`，`3×3` 覆盖 `95.4%`，Kotlin P95 小于 `30ms`；固定中位深度近似在 `1040×208` 预览域的 P95 误差 `13.12px`，因此固定平移、裁切或单应都不属于可接受实现。Java 探针直接调用生产 `vinPreviewRoi()`，Python 只消费其导出的 360/411/432dp ROI；cap_023–036 的覆盖、点数、距离分位与 Ready 结论逐项跨端一致。10 个还原成功样本在三种宽度下全部 Ready；cap_023/031/034 距离足但覆盖或点支撑不足，继续保持红框；cap_030 只用于验证 95% 覆盖边界。cap_036 中位距离约 `28.9cm`、`≥300mm` 约 `20%`，仍正确放行；高覆盖约 `44.7cm` 合成帧必须 TooFar。固定序列同时裁决稳定门恰好触发一次、手动/自动快门只认领一次、还原成功只取得一次识别许可。

Android 16 真机已完成当前实现回归：确定性 debug 真渲染中，`40.0%` 低覆盖只显示“实时 · 深度有效率 40.0%”，不出现距离；`98.0% / 45.0cm` 显示“距离太远，请靠近至 40cm 以内”、红框与禁用快门；`98.0% / 33.5cm` 显示“请稳住不动”和稳定进度。真实自动流程连续 4 次由用户点击“重新扫描”开启新会话，每次都在稳定门后只认领一次自动 capture、首轮收齐 `3+5` 候选、回调差 `53.758–54.412ms`，服务端返回严格 `4425×600` 后 exactly-once 启动 OCR，均得到 17 位与 17 张单字符图。最新 `cap_036` 倾角 `40.77°`、中位深度 `245.18mm`、平面 RMS `0.446mm`；两服务各 release 一次并在 600ms 后 stop，无崩溃或 ANR。

### 11.4 手机端只采集、上传和使用

Android 已删除 `NativeBridge.vinOrthoRectify`、`VinOrthoNative`、`FrameRenderer.orthoToBitmap` 和对应 JNI export；`native/vin/ortho_rectify.*` 只保留为 host 几何参考，由 `scripts/native-host-test.sh` 验证，不进入手机 `.so`。

VIN 拍照页的唯一流程是：ROI 覆盖 `≥95%`、原始点支撑 `≥15%`、可靠中位距离 `≤400mm` → 界面提示“请稳住不动” → ViewModel 只对两路时间戳都前进的唯一帧对计数，满足 5 帧、`≥800ms`、相邻间隔 `≤450ms`、中位距离极差 `≤5mm` 后自动认领快门；用户仍可提前点快门，但与自动触发共用同一认领入口 → 记录单调时钟水位 → 严格只收水位后的新帧 → 跳过前 3 张 HLSD8 → 至少收齐 3 张彩色和 3 张深度 → 在整批候选中取回调时间差全局最小的一对 → 对该轮最终深度帧重跑同一空间/距离门 → 超窗、缺帧或质量不足则以新水位继续下一轮，最多 6 轮同步重采 → 原始帧落盘 → 上传服务端 → 等待权威 PNG → 成功后从实际上传 JPEG 解码彩色预览、保留同一帧投影深度与 ROI 指标 → 失效预览、清 pairer/readiness 并释放双相机 → 发布结果态 → 同一 ViewModel 状态机 exactly-once 自动 OCR。burst 最终质量瞬时失败会重新等待稳定；服务端判废、保存或上传错误锁住自动重拍，只允许用户明确操作；OCR 失败保留 PNG 并只允许手动重试。禁止在所选 JPEG 解码失败时拿拍照前实时 Bitmap 冒充“本次拍摄”，也禁止用 Compose `LaunchedEffect` 触发自动拍摄或识别。“重新扫描”先清旧帧和所有锁存并记录新水位，再按 HLSD8 首帧、80ms 沉降、RS-D550 的顺序重新 acquire。两相机用独立轻量 lease 状态锁管理引用计数与 600ms 停流代际，主线程不等待慢速 native stop；旧宽限任务即使越过 delay，也不能关闭后续扫描会话。UI 文案使用“规范化还原”，不再显示“1:1 还原”或“端侧估算”。快门水位由配对器内部 `System.nanoTime()` 提供，与 native `steady_clock` 同属 `CLOCK_MONOTONIC`；禁止用包含设备休眠时长的 `elapsedRealtimeNanos()` 与帧时间比较，也禁止立即复用点击前缓存。

HLSD8 与 RS-D550 实际均为无共同硬触发的 `5fps`。原厂 APK 与 native 逆向确认，快门链没有 UVC still、GPIO 或模组同步调用：它同样采用“跳 3 帧、至少 3+3、`±100ms` 软件筛选、失败最多 6 轮”，而且时间戳来自保存协程的 `System.currentTimeMillis()`；原厂日志一次成功配对为 `41ms`。Gomob 使用更稳定的 host 单调回调时间，并把原厂“首个可配 RGB + 窗内最后一个 depth”改为整批全局最小差。真机连续 12 分钟观测到两路 native 回调固定错相约 `54–59ms`；把启动间隔从 `80ms` 调为 `24ms` 后相位仍约 `59ms`，证明不能靠启动延时可靠满足旧 `25ms` 门。生产回调门仍为半帧周期理论上界 `≤100ms`，但 UI、日志和元数据只称“回调差”。该时间戳不是传感器曝光时刻；`100ms` 不能用来宣称硬同步。

原始采集不是“写到哪算哪”：深度 buffer 必须精确为 `w×h×2`，彩色、深度与经 JSON writer 生成的元数据先写 `.cap_*.tmp`，全部成功后再原子改名为 `cap_*`；进程中断或磁盘不足只会留下可清理的临时目录。服务端响应必须回显两颗物理设备、请求帧对的准确回调差和非空 log ID；除同步门与未发布 rig 外，所有已经选用原厂标定的裁决都必须带 SHA/version。成功 PNG 先校验完整 chunk/CRC 并实际解码，再落 `restored.png + restore.json`，避免损坏图形成 `ok=true` 假审计。

手机端可见的标定、成像控制、backend/profile、DUMP 和 Sonix 调试路由已经从生产导航移除，深度相机页只保留预览和只读设备信息。`StereoCalibViewModel` 的同步三路采集内核暂留，供后续网页远程采集代理复用，但手机用户已不能直接进入。

职责迁移仍缺网页闭环：网页端尚无 VIN 标定会话、远程采集代理、交叉验证和版本发布能力。因此可以确认手机只剩使用界面，但不能宣称网页标定管理已经完成。现有网页激光工位外参是 LiDAR A/B 工位坐标，不是 HLSD8↔RS-D550 的 VIN 双相机外参。

### 11.5 固定坐标 harness 与当前结论

`tests/harness/vin_restore_consistency/` 批量运行生产 Go Restore，并对最终 PNG 再跑 VINCHAR。最终画布先严格检查 `4425×600`；随后对所有样本施加同一套固定 `0.36` 相似变换，居中映射到 `1200×260` 检测探针，再计算固定 ROI Edge-F1/Chamfer/NCC。该换算参数不读取样本内容；禁止 ECC、仿射、单应或逐图配准。

当前权威报告 `.dev/vin_restore_consistency-factory-bf301208-v3/report.json` 使用 BF301208 当前 rig 的四张全分辨率真 RGBD：

- 4/4 生成严格 `4425×600` PNG，最终 VINCHAR 均识别为真值 `LA99FRP32G0LTH013`。
- 倾角 `10.03°–47.64°`，字符中心最大误差 X `0.91px` / Y `1.92px`，水平角最大 `0.237°`。
- 字符节距 `170.02–170.77px`、均值 `170.33px`、CV `0.174%`；原厂 oracle 约 `170.28–170.35px`。
- 字高 `236.1–244.4px`、CV `1.42%`、单张最大相对偏差 `2.92%`；物理宽度 CV `0.63%`。
- 固定坐标 Edge-F1 median `0.793`、Chamfer median `2.72px`；同批 VINCreator oracle 为 `0.532/4.53px`。
- HTTP 真数据契约测试中 4/21 张确有 VIN 的样本成功，其余 17 张没有 VIN，正确返回 `vin_not_detected`。

这证明原厂标定、平面内刚性格架、双轴统一尺度和最终裁切已经闭环。旧 38 组低分辨率历史报告只保留为问题演进证据，不再代表当前生产结果；非等比拉伸仍会伪造字形，继续禁止。

### 11.6 剩余物理阻塞与完成门

生产标定键固定为 `depth serial + HLSD8 serial + depth profile + color profile`。当前键 `BF301208 + 202303111518 + 640×128 + 4160×832` 已由 SHA-256 白名单加载原厂文件；客户端同时检查响应 `width/height`、完整 PNG chunk/CRC 与实际解码结果必须为 `4425×600`。

容器不烘焙标定文件，部署侧把精确 SHA 的 `VIN_BF301208.bin` 只读挂载到 `/var/lib/gomob/vin_calibration/`，并让 model-registry 启动期加载 `VINOBB,VINCHAR`。`ValidateRequiredDependencies` 在 HTTP 监听前 fail-fast；`/readyz` 同时暴露 calibration/models 的 required 与 ready 状态。已发布文件缺失或损坏属于 503 基础设施故障，只有请求的完整 rig/profile 尚未发布才返回 HTTP 200 `calibration_unavailable` 业务判废。

两颗 5fps 相机仍无共同硬触发。当前快门事务只证明 host 回调差：日常生产按原厂半帧边界为 `≤100ms`；当前固定 rig 实测 `53–55ms`，一致性 harness 用 `≤70ms` 捕获相位或处理管线退化。完成门仍要求取得 PTS/SCR，或用同步光学事件标定两条管线的时延，证明曝光等效同步差 `≤25ms`；软件回调时间不能替代曝光时间。

4425px PNG 暂仍通过 base64 envelope 返回，网络拦截器只 peek 前 `64KiB`，不复制完整成功响应。2026-07-16 真机 cap_023–029 连拍 7 次均首轮收齐，6 次成功还原并形成 17 字符锚点、1 次正确 `vin_not_detected`；回调差 `52.517–54.802ms`，原始 RGBD、`restore.json` 与成功 PNG 目录形态均符合契约。随后同一 PID 连续 3 轮进入、双路首帧、退出和 teardown，TOTAL PSS 约 `247→293→268MiB`，无 native fatal 或内存单调增长；真机稳定性门已完成，模拟器不作为替代证据。

手机端只使用，不承担标定或管理。网页端还须补齐 VIN 远程采集代理、姿态与角点质量、交叉验证、审核和按完整 rig/profile 发布版本，服务端 Restore 再按发布版本加载。
