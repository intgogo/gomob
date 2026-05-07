# 05 — iHawk Color/Depth 标定管线

> 业务定义：iHawk 自身的 Color 镜头和 Depth 镜头是两个独立物理传感器，
> 重建管线和 VIN 拓印都需要把 Depth 点云 / 深度图重投影到 Color 像素坐标
> （或反之）。本文档定义 iHawk 自身两路传感器的标定方案。
>
> **不**涉及手机摄像头（"主摄+深度"路线已废，详见 `01-depth-camera-integration.md` §1）。

## 1. 标定的必要性 — 决策门

SDK 9.9.190 提供：

- `dev.getCameraIntriscParams()` —— 出厂烧入的内参（具体字段 + 是否含 stereo 外参以
  实测为准）
- `dev.getDeviceIntriscParams(FloatBuffer)` —— 内参写入用户提供的 buffer
- `dev.setRegistrationEnable(true)` —— 让 SDK 内部把 Depth 重投影到 Color 像素坐标，
  输出已对齐的深度（depth-to-color registration）

**第一步先实测厂家给的精度**，决定是否要自己标定：

| 测试场景 | 验收指标 | 不达标处理 |
|---------|---------|-----------|
| 棋盘格放 30cm / 50cm / 1m | depth 投到 color 的边缘误差 ≤ 2 px | → 自标定 |
| 平面（白墙）@1m | 拟合平面残差 ≤ 1mm（≥ 95% 像素） | depth 内参待优化 |
| 已知尺寸物体（10cm × 10cm 立方体）@50cm | mesh 边长误差 ≤ 1mm | scale 校准 |

`tests/harness/calibration_smoke/` 跑这三个场景。**实测达标** → 直接用 SDK 出厂参数，
跳过 §3 自标定流程，但保留向导用作"出厂复检"。

## 2. 标定数据契约（`core:model`）

```kotlin
data class CameraIntrinsics(
    val fx: Double, val fy: Double,
    val cx: Double, val cy: Double,
    val distortion: DoubleArray,     // [k1,k2,p1,p2,k3] OpenCV 5 系数
    val width: Int, val height: Int, // 标定时分辨率，注意分辨率切换需重算
)

/** iHawk Color↔Depth 间的相对外参（同一物理设备内，单 stereo pair）。 */
data class StereoExtrinsics(
    val rotation: DoubleArray,       // 行优先 3x3，把 Depth 系 → Color 系
    val translation: DoubleArray,    // 3x1 mm
    val rmsReprojectionPx: Double,   // 标定时 reprojection error，用作健康度指示
)

data class CalibrationResult(
    val deviceSerial: String,        // iHawk SN，用作 key 区分多设备
    val colorIntrinsics: CameraIntrinsics,
    val depthIntrinsics: CameraIntrinsics,
    val stereo: StereoExtrinsics,
    val calibratedAtMs: Long,
    val sampleCount: Int,            // 用了多少个 Charuco 角度
    val source: CalibrationSource,   // SDK_FACTORY / USER_CALIBRATED
)

enum class CalibrationSource { SDK_FACTORY, USER_CALIBRATED }
```

落库走 `core:database` `CalibrationDao`，按 `deviceSerial` 唯一。每次 BerxelService
进入 Streaming 状态后查一次：

- 有标定 → 注入 ScanSession / VinRectify 模块
- 无标定 + SDK_FACTORY 可用 → 直接用厂家参数，标记 source
- 无标定 + SDK_FACTORY 缺失 → UI 提示用户跑标定向导（`feature:calibration`）

## 3. 自标定流程（备用路径）

### 3.1 标定板

**Charuco**（OpenCV 4.x `cv::aruco`）。为什么不用纯棋盘格：

- Charuco 单帧能识别（即使部分遮挡）→ 用户体验好
- 鲁棒于运动模糊
- OpenCV 4 自带 detector，依赖少

板规格建议：A3 大小，5×7 markers，Aruco DICT_5X5_250。

### 3.2 流程（`feature:calibration` 向导）

```
[Step 1: 引导]    UI 显示 Charuco 板要求 + 标定姿势示意（手持 iHawk 拍 N 个角度）
                 N = 12 帧（覆盖前 / 左 / 右 / 上 / 下 / 倾斜各方向 2 张）
                                ↓
[Step 2: 采帧]   每张：同时抓 Color + Depth，detectCharucoBoard 通过才算
                 实时 UI 提示"还差 N 张"+ 当前帧角点检测可视化
                                ↓
[Step 3: 求解]    单目内参（Color、Depth 各一次）→ stereoCalibrate 求 R, t
                 求解走 native（OpenCV cv::calibrateCamera + cv::stereoCalibrate）
                                ↓
[Step 4: 验收]    把求解结果应用回采的 12 帧，看 reprojection error
                 ≤ 1.0 px → 写库；否则 UI 提示"重标定"+ 可视化哪几帧异常
                                ↓
[Step 5: 落库]    `CalibrationDao.upsert(calibration)`，下次扫描自动用
```

**关键工程决策**：

- 标定中所有计算放 native（OpenCV），Kotlin 只做 UI + 状态
- Depth 标定的角点提取需要"深度图角点检测" —— 给 Depth 图涂上 Color 的 grayscale 当
  intensity 输入 OpenCV，避免直接用深度梯度（不稳）
- 求解失败给具体原因（"角点检测不足 X 张" / "stereo error 偏大"），不是笼统报错

### 3.3 native 端 OpenCV 依赖

iHawk SDK 自带 `libopencv_java3.so` —— OpenCV 3.x。OpenCV 4 的 `aruco`/`charuco` 在
OpenCV 3.4 起已可用（`opencv_contrib`）。但 SDK 带的 3.x 版本是**完整版还是裁剪版**
要查 — 详见 §6 native 端实施。

如果 SDK OpenCV 不够，要么：

- 把 OpenCV 4.x 编进自家 `libgomob_native.so`（增大 30-50MB）
- 用更轻量的 Charuco 实现（自写 marker detector，复杂度高）

先选第一条（明确依赖、可控版本），二期评估优化。

## 4. JNI 边界

```kotlin
// core:native-bridge/NativeBridge.kt
object NativeBridge {
    /** Charuco 角点检测，返回 N×3 [u, v, marker_id]；零角点返长度 0 数组 */
    external fun calibDetectCharuco(
        gray: ByteArray, width: Int, height: Int,
        boardSpec: IntArray  // [rows, cols, dict_id, square_size_mm, marker_size_mm]
    ): FloatArray

    /** 单目内参标定。corners: [N_image][N_corners][3]; 返回 [fx,fy,cx,cy,k1,k2,p1,p2,k3,rms] */
    external fun calibCalibrateCamera(
        corners: FloatArray, width: Int, height: Int, boardSpec: IntArray,
    ): DoubleArray

    /** Stereo 外参标定，返回 [r00..r22, tx,ty,tz, rms] */
    external fun calibStereoCalibrate(
        colorCorners: FloatArray, depthCorners: FloatArray,
        colorIntr: DoubleArray, depthIntr: DoubleArray,
        width: Int, height: Int,
    ): DoubleArray
}
```

返回扁平 array 而非 struct：避免 JNI 自定义对象的复杂性，Kotlin 侧解析成
`CameraIntrinsics` / `StereoExtrinsics`。

## 5. 持久化与跨会话复用

`core:database` 加 `calibrations` 表（M-S3 服务端 device 表里也有 calibration_seq —
本地端的标定也要走那条云同步；服务端 schema 已就绪，详见 `TODO.md` M-S3）：

```sql
CREATE TABLE calibrations (
    id TEXT PRIMARY KEY,
    device_serial TEXT NOT NULL,
    color_intrinsics TEXT NOT NULL,    -- JSON
    depth_intrinsics TEXT NOT NULL,    -- JSON
    stereo TEXT NOT NULL,              -- JSON
    source TEXT NOT NULL,              -- SDK_FACTORY / USER_CALIBRATED
    calibrated_at_ms INTEGER NOT NULL,
    sample_count INTEGER NOT NULL,
    rms_reprojection_px REAL NOT NULL
);
CREATE UNIQUE INDEX idx_calib_device ON calibrations(device_serial);
```

App 启动 / iHawk 连接时按 `deviceSerial` 查 → 注入 reconstruction / vin-rectify 管线。

## 6. Harness

`tests/harness/calibration_quality/` 验收：

- 跑 §1 三个测试场景
- 比对 SDK 出厂参数 vs 自标定结果，输出"两套参数 reprojection error 差值"
- 再跑一次：用第二台 iHawk 走同一流程，看跨设备一致性

## 7. 待办

详见 `TODO.md` M2.* 段。
