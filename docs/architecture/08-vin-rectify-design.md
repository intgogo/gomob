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
