package io.gomob.nativebridge

import java.nio.ByteBuffer
import java.nio.FloatBuffer

/**
 * Kotlin 侧到 native 的唯一入口。
 *
 * 设计约定：
 * - 所有 JNI 方法集中在本类，业务层禁止散点 `System.loadLibrary` / 散点 `external fun`
 * - native 内部模块（depth / fusion / reconstruction / vin / calibration）通过函数前缀区分
 * - 出错走 [NativeException]（含 errorCode + 文本），不靠 -1 / null 之类哑值
 * - **大数据缓冲**走 [ByteBuffer.allocateDirect]（DirectByteBuffer），native 用
 *   `GetDirectBufferAddress` 直接读，**不**走 JNI 数组拷贝；小元数据用普通数组
 *
 * Why "single entry point"：JNI 边界只有一道，上层只 import 这个 object，避免 JNI 散落
 * 到处导致符号污染、加载顺序问题、生命周期错乱（详见 docs/architecture/03-jni-boundary.md）。
 */
object NativeBridge {

    init {
        System.loadLibrary("gomob_native")
    }

    /** 库版本（编译时打入）。Smoke 用：能跑到这里说明 .so 加载成功 + 链接齐备。 */
    external fun version(): String

    // ===== depth/* =====

    /**
     * 把 16bit 深度帧反投影成相机坐标系点云（mm）。返回扁平 [x0,y0,z0, x1,y1,z1, ...]，
     * 长度 = 3 × width × height（深度=0 的像素直接出 (0,0,0)，调用方按需过滤）。
     */
    external fun depthToPointCloud(
        depth: ShortArray,
        width: Int, height: Int,
        fx: Double, fy: Double,
        cx: Double, cy: Double,
    ): FloatArray

    // ===== fusion/* =====

    /**
     * 给定 iHawk Color↔Depth 的 stereo 外参 (R, t)，把 depth 系点云投到 color 像素坐标
     * 取色，返回每点 RGB 序列（[r0,g0,b0, r1,g1,b1, ...]，长度 = 3 × pointCount）。
     *
     * @param rotationRowMajor 行优先 3×3，**Depth 系 → Color 系**（注意方向）
     * @param translation 3×1 mm
     */
    external fun colorizePointCloud(
        points: FloatArray,
        rgb: ByteArray,
        rgbWidth: Int, rgbHeight: Int,
        rgbFx: Double, rgbFy: Double, rgbCx: Double, rgbCy: Double,
        rotationRowMajor: DoubleArray,
        translation: DoubleArray,
    ): ByteArray

    // ===== reconstruction/* — 三维外廓扫描重建管线 =====
    //
    // 详见 docs/architecture/04-reconstruction-pipeline.md。
    // 核心思想：用户转一圈 → 每帧 ICP 配准当前帧到关键帧累积体 → TSDF 体素积分 →
    //          停止后 Marching Cubes 出 mesh + 关键帧纹理烘焙。

    /**
     * 增量 ICP 配准。把 [srcPoints] 对齐到 [dstPoints]（或 session 累积体），
     * 返回配准后的位姿 [tx, ty, tz, qx, qy, qz, qw]（7 个 float，单位 mm + 单位四元数）。
     *
     * @param srcPoints 当前帧点云（FloatArray 扁平 [x,y,z, ...]）
     * @param dstPoints 参考点云
     * @param initialPose 7 元素初值 [tx,ty,tz,qx,qy,qz,qw]，可用上一帧位姿
     */
    external fun icpRegister(
        srcPoints: FloatArray,
        dstPoints: FloatArray,
        initialPose: FloatArray,
    ): FloatArray

    /**
     * 创建一个扫描会话。返回 native session handle（Long），后续 Ingest/Finalize 用。
     *
     * @param voxelSizeMm TSDF 体素边长（默认 2mm，物体 ≤ 30cm 时；大物体调到 5mm）
     * @param gridExtentMm TSDF 网格边长（mm），决定可重建空间立方体大小
     */
    external fun scanSessionCreate(voxelSizeMm: Float, gridExtentMm: Float): Long

    /**
     * 喂一帧深度 + pose，session 内部 TSDF 累积。
     *
     * @param handle 来自 [scanSessionCreate]
     * @param depthBuffer Direct ByteBuffer（16bit mm depth），由 reader 线程零拷贝
     * @param width depth 宽
     * @param height depth 高
     * @param intr [fx, fy, cx, cy]（depth 镜头内参）
     * @param pose 7 元素位姿 [tx,ty,tz,qx,qy,qz,qw]
     * @return 累积的关键帧数
     */
    external fun scanSessionIngest(
        handle: Long,
        depthBuffer: ByteBuffer,
        width: Int, height: Int,
        intr: DoubleArray,
        pose: FloatArray,
    ): Int

    /**
     * 提取 mesh + 点云，写到 [outDir] 下 cloud.ply / mesh.gltf。返回简要统计（顶点数 / 面数 / 关键帧数）。
     */
    external fun scanSessionFinalize(handle: Long, outDir: String): IntArray

    /** 释放 session handle。 */
    external fun scanSessionClose(handle: Long)

    // ===== vin/* — VIN 数码拓印 =====
    //
    // 详见 docs/architecture/08-vin-rectify-design.md。

    /**
     * 单帧 RGBD → VIN 正射拓印图。
     *
     * @param colorBgr Color 帧 BGR888（YUYV 已转过；DirectByteBuffer 零拷贝）
     * @param depth16Mm Depth 帧 16bit mm（已 setRegistrationEnable 对齐到 Color 像素坐标；
     *                  DirectByteBuffer 零拷贝）
     * @param colorIntr [fx,fy,cx,cy,k1,k2,p1,p2,k3] 9 元素
     * @param roiBox    [u_min, v_min, u_max, v_max] 像素坐标，VIN 区域
     * @param config    [ortho_distance_mm, pixel_size_mm, out_w, out_h]
     * @return [VinRectifyNative] 含 PNG 字节 + 拟合元数据
     */
    external fun vinRectify(
        colorBgr: ByteBuffer, colorWidth: Int, colorHeight: Int,
        depth16Mm: ByteBuffer, depthWidth: Int, depthHeight: Int,
        colorIntr: DoubleArray,
        roiBox: IntArray,
        config: FloatArray,
    ): VinRectifyNative

    // ===== calibration/* — iHawk Color/Depth 标定 =====
    //
    // 详见 docs/architecture/05-calibration-pipeline.md。
    // SDK 出厂参数 + setRegistrationEnable 不达标时走这条路；OpenCV cv::aruco + cv::stereoCalibrate。

    /**
     * Charuco 角点检测。返回 N×4 扁平数组 [u, v, marker_id, charuco_id]；零角点返长度 0 数组。
     *
     * @param gray 单通道灰度（Color 转 gray 或 Depth 当 intensity）
     * @param boardSpec [rows, cols, dict_id, square_size_mm × 100, marker_size_mm × 100]
     *                  （后两项 ×100 转整数避坑）
     */
    external fun calibDetectCharuco(
        gray: ByteArray, width: Int, height: Int,
        boardSpec: IntArray,
    ): FloatArray

    /**
     * 单目内参标定。
     *
     * @param corners 多张图角点扁平 [img0_corners, img1_corners, ...]
     * @param cornersPerImage 每张图角点数
     * @return [fx, fy, cx, cy, k1, k2, p1, p2, k3, rms]
     */
    external fun calibCalibrateCamera(
        corners: FloatArray, cornersPerImage: IntArray,
        width: Int, height: Int, boardSpec: IntArray,
    ): DoubleArray

    /**
     * Stereo 外参标定（iHawk Color↔Depth）。
     *
     * @return [r00..r22, tx, ty, tz, rms]（13 个 double，rotation 行优先）
     */
    external fun calibStereoCalibrate(
        colorCorners: FloatArray, depthCorners: FloatArray,
        cornersPerImage: IntArray,
        colorIntr: DoubleArray, depthIntr: DoubleArray,
        width: Int, height: Int,
    ): DoubleArray
}

/**
 * VIN 拓印 native 返回结果。
 *
 * 字段：
 * - [pngBytes] 拓印图 PNG 编码字节
 * - [planeNormalAndD] [nx, ny, nz, d] 平面方程 n·P + d = 0
 * - [planeStats] [rms_residual_mm, inlier_ratio]
 */
data class VinRectifyNative(
    val pngBytes: ByteArray,
    val planeNormalAndD: FloatArray,
    val planeStats: FloatArray,
    val outputWidth: Int,
    val outputHeight: Int,
) {
    override fun equals(other: Any?): Boolean = other is VinRectifyNative &&
        pngBytes.contentEquals(other.pngBytes) &&
        planeNormalAndD.contentEquals(other.planeNormalAndD) &&
        planeStats.contentEquals(other.planeStats) &&
        outputWidth == other.outputWidth && outputHeight == other.outputHeight
    override fun hashCode(): Int = pngBytes.contentHashCode() * 31 +
        planeNormalAndD.contentHashCode() * 31 +
        planeStats.contentHashCode() * 31 + outputWidth * 31 + outputHeight
}

class NativeException(val errorCode: Int, message: String) : RuntimeException(message)

/** native 错误码常量（与 jni_bridge.cpp 对齐）。 */
object NativeError {
    const val NOT_IMPLEMENTED = 1
    const val INVALID_ARG = 2
    const val ALLOC_FAIL = 3
    const val SDK_ERROR = 4
    const val PLANE_FIT_FAIL = 100
    const val ICP_NOT_CONVERGED = 101
    const val SESSION_HANDLE_INVALID = 102
    const val CHARUCO_NOT_DETECTED = 200
    const val CALIB_RMS_TOO_HIGH = 201
}
