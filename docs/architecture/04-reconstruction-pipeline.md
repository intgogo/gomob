# 04 — 三维外廓扫描重建管线

> 业务定义：用户手持 iHawk 相机围着目标物体转一圈，App 把所有帧的深度数据迭代融合
> 成一个**完整物体点云**，再生成**三维网格模型**（mesh）和高密度点云模型。
> 单设备来源（iHawk 自身），不涉及手机主摄。

## 1. 输入 / 输出契约

### 1.1 输入

- iHawk Color stream + Depth stream（同一物理设备，硬件级时间戳同步）
- 内参（fx, fy, cx, cy）—— SDK `getCameraIntriscParams()` 给出，必要时自标定补
- 外参 `StereoExtrinsics(R, t)` 把 Depth → Color 系（详见 `01-depth-camera-integration.md` §4）
- 用户操作：按"开始" → 围绕目标转一圈 → 按"停止"（或自动判停）

### 1.2 输出

- `PointCloudAsset`：高密度点云（PLY 二进制）+ 元数据（帧数 / 位姿轨迹 / 半径覆盖）
- `MeshAsset`：三维网格（glTF 2.0 含纹理 / OBJ）
- `ScanSession`：扫描会话元信息写 Room（`core:database`），文件落 `getFilesDir()/scans/<id>/`

## 2. 管线分层

```
[采集层]   BerxelService.colorStat / depthStat reader 线程
              ↓ DirectByteBuffer 零拷贝
[预处理]   深度滤波（双边 / 时域中值）+ 转点云（depthToPointCloud JNI）
              ↓ Vec3 点云 + Color 像素索引
[配准]     ICP 增量配准（当前帧 → 最近 K 关键帧）
              ↓ 6DoF 位姿（rotation 四元数 + translation 向量）
[融合]     TSDF voxel grid 累积（按位姿把当前帧深度积分进 TSDF）
              ↓ 体素网格（隐式 SDF）
[提取]     Marching Cubes 出三角面 + 点云重采样
              ↓ vertices / faces / normals
[纹理]     Color 系内多关键帧纹理烘焙（projection mapping）
              ↓ uv + texture atlas
[导出]     glTF 2.0 / OBJ / PLY，写文件 + 写 Room
```

各层之间走 native 内部，Kotlin 只看到一个 `ScanSession` 句柄 + `nextFrame(rgbd)` /
`finalize(): Mesh` 入口。详见 §6 JNI 边界。

## 3. 关键技术决策

### 3.1 配准：增量 ICP（关键帧策略）

为什么不是 SLAM？SLAM（如 ORB-SLAM、KinectFusion 全 SLAM 模式）解决"未知场景下相机在
全局世界中的位姿"，问题更难、更慢。我们的场景**简化**：

- 用户**主动**围绕目标转，不需要回环检测
- 物体相对静止，只有相机在动
- 相邻帧位姿差很小（30 fps，每帧旋转角 ≤ 12°）

→ 用增量 ICP（Iterative Closest Point）：每帧用上一帧位姿做初值，把当前帧点云对齐到
**关键帧**累积体。关键帧策略：

- 每隔 N 帧（默认 5）+ 位姿变化超阈值（旋转角 ≥ 5° 或平移 ≥ 5cm）→ 标关键帧入 KFGraph
- 关键帧间做闭环检测（用 FPFH 描述子做特征匹配，可选；先做开环版）

### 3.2 融合：TSDF（Truncated Signed Distance Function）

为什么不是直接堆积点云？

- 点云：每帧加几万点，转一圈累积到几百万点；除噪难、出 mesh 难
- TSDF：体素网格存"点到表面的有符号距离"，多帧观测在同一体素积分平均 →
  **天然降噪 + 易出 mesh**

关键参数：

| 参数 | 默认值 | 备注 |
|------|--------|------|
| voxel size | 2 mm | 物体尺寸约 30cm 时合适；大物体（≥1m）调到 5mm |
| truncation distance | 4 × voxel size | 经验值 |
| grid extent | 自适应 / 用户标定边框 | 避免 0,0,0 居中假设 |
| weight clamp | 100 | 体素权重上限，避免老观测主导 |

`tests/harness/recon_quality/` 跑一组合成数据 + 真扫数据，验证 voxel size 调 2x / 0.5x
对最终 mesh 的几何误差影响。

### 3.3 提取：Marching Cubes + 后处理

- Marching Cubes 经典实现（`open3d` 或自写 C++ 内核，先用自写避免拉重依赖）
- 后处理：连通体过滤（去除离散小簇）+ 法向重定向 + 网格简化（QEM 边坍缩）

### 3.4 纹理烘焙

- 关键帧 Color 图（与位姿一一对应）+ mesh 的每个三角面 → 选最适合的视角（法向夹角最小）
- UV unwrap（xatlas 库或简单 cube projection）→ 烘到 1024×1024 / 2048×2048 atlas

### 3.5 自动判停（产品体验）

用户不需要主动按"停止"。判停信号：

- 相机轨迹回到起点附近（位移 ≤ 10cm 且方向接近原点观察方向）
- 或扫描时长 ≥ 60s
- 或位姿变化连续 30 帧 ≤ 阈值（用户停手）

判停后端侧不阻塞 UI，后台跑 Marching Cubes + 纹理烘焙，UI 显示进度条 + 末态查看。

## 4. 数据契约（`core:model`）

```kotlin
data class DepthFrame(
    val timestampUs: Long, val frameIndex: Int,
    val width: Int, val height: Int,
    val data: ByteBuffer,             // direct buffer，16bit mm depth
    val intrinsics: CameraIntrinsics, // iHawk Depth 内参
)

data class ColorFrame(
    val timestampUs: Long, val frameIndex: Int,
    val width: Int, val height: Int,
    val data: ByteBuffer,             // direct buffer，YUYV / NV21（按 PixelType）
    val intrinsics: CameraIntrinsics, // iHawk Color 内参
)

data class PointCloud(
    val points: FloatBuffer,          // [x0,y0,z0, x1,y1,z1, ...] 单位 mm
    val colors: ByteBuffer?,          // [r0,g0,b0, r1,g1,b1, ...]，可空（无纹理点云）
    val count: Int,
)

data class Pose6D(
    val rotationQuat: FloatArray,     // x,y,z,w
    val translation: FloatArray,      // [tx,ty,tz] mm
)

data class ScanSession(
    val id: String,                   // UUID
    val createdAtMs: Long,
    val deviceSerial: String,
    val keyframeCount: Int,
    val totalFrameCount: Int,
    val pointCloudPath: String,       // 文件路径（.ply）
    val meshPath: String,             // .gltf / .obj
    val texturePath: String?,         // 可空
    val coverageRatio: Float,         // 0..1，扫描覆盖估计
)
```

## 5. JNI 边界

```kotlin
// core:native-bridge/NativeBridge.kt
object NativeBridge {
    // 已有
    external fun depthToPointCloud(depth, w, h, fx, fy, cx, cy): FloatArray
    external fun colorizePointCloud(points, rgb, ..., R, t): ByteArray  // 用 §1.1 外参

    // 新增（M3 重建管线）
    /** 创建一个扫描会话，返回 native session handle（Long） */
    external fun scanSessionCreate(voxelSizeMm: Float, gridExtentMm: Float): Long
    /** 喂一帧深度+pose，session 内部 TSDF 累积 */
    external fun scanSessionIngest(handle: Long, depth: ByteBuffer, pose: FloatArray): Int
    /** 增量 ICP 把 src 点云配到 dst（或上次 session 累积体），返回新 pose */
    external fun icpRegister(srcPoints: FloatArray, dstPoints: FloatArray, initial: FloatArray): FloatArray
    /** 提 mesh + 点云；写到给定文件路径 */
    external fun scanSessionFinalize(handle: Long, outDir: String): MeshBuffer
    /** 释放 session handle */
    external fun scanSessionClose(handle: Long)
}
```

零拷贝原则：所有大缓冲区（depth ByteBuffer / color ByteBuffer / point cloud FloatBuffer）
走 `DirectByteBuffer.GetDirectBufferAddress` 直接读 native 内存，**不**用 JNI 数组拷贝。

## 6. Harness（自分析与自优化）

`tests/harness/recon_quality/` 触发条件 = 涌现行为（多帧融合的密度/几何）+ 参数敏感
（voxel size、ICP 收敛阈值、关键帧间隔）。

- `run.sh`：编译 → 推到设备 → 跑预录的 RGBD 序列（合成 + 真采）→ 拉 mesh + 点云到
  `.dev/recon-quality/<case>/`
- `analyze.py`：mesh 与 ground truth 比 hausdorff / chamfer；点云覆盖度；ICP 收敛轨迹
  → 输出"正常 / 警告 / 异常 + 原因"

合成 ground truth：用 Open3D / PyTorch3D 给一个标准三角面（如 Stanford Bunny / 立方体）
渲染 100 帧不同视角的 RGBD → 跑管线 → 比对原 mesh。

## 7. 待办

详见 `TODO.md` M3.* 段。**不**在本文件维护任务清单，避免双源失同步。
