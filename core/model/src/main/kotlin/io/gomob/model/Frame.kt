package io.gomob.model

import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * iHawk 单设备的彩色帧。
 *
 * 字段单位：
 * - timestampUs: SDK 内部时基（μs）；与同设备 [DepthFrame] 同一帧序的 timestamp 必须相同
 * - data: DirectByteBuffer，零拷贝直读 native；像素格式由 [pixelType] 描述
 *   （iHawk 出 YUYV，端侧用 NativeBridge 转 BGR888 再喂给重建/拓印 / Compose 渲染）
 */
data class ColorFrame(
    val timestampUs: Long,
    val frameIndex: Int,
    val width: Int,
    val height: Int,
    val data: ByteBuffer,
    /** SDK PixelType 枚举名（如 BERXEL_HAWK_PIXEL_TYPE_RGB_24BIT） */
    val pixelType: String,
    val intrinsics: CameraIntrinsics,
)

/**
 * iHawk 单设备的深度帧。
 *
 * 字段单位：
 * - data: DirectByteBuffer，**16bit unsigned mm 深度（小端、纯整数毫米）**；0 = 无效（厂家约定）。
 *   SDK 原始格式可能是 12.4 / 13.3 定点，BerxelService 抽帧时已统一右移转成纯 mm，
 *   下游所有 consumer（重建 / VIN 拓印 / colormap 渲染）直接当 mm 读，不要再 shift。
 * - depth 是否已 register 到 color 像素坐标取决于 SDK setRegistrationEnable —— 用
 *   [registeredToColor] 显式标记
 */
data class DepthFrame(
    val timestampUs: Long,
    val frameIndex: Int,
    val width: Int,
    val height: Int,
    val data: ByteBuffer,
    val intrinsics: CameraIntrinsics,
    /** true = SDK 已把 depth 重投影到 color 像素坐标（registration on）；
     *  false = 原始 depth，沿用 depth 镜头自身坐标 */
    val registeredToColor: Boolean,
    /** 逐像素 confidence（uint8，与 [data] 同尺寸 W*H）：255=raw 高置信、衰减值=时域补/弱置信、
     *  **0=无效或飞点**。null = 未提供（旧链路/未启用时域降噪）。量测/点云/重建应优先按此取点：
     *  conf>0 才用、飞点(conf==0)天然跳过；保持 [data] 为 raw 测量真值不被改写。 */
    val confidence: ByteBuffer? = null,
)

/**
 * iHawk 单设备的 Color + Depth 同步帧对（VIN 拓印用）。
 *
 * `timestampDeltaUs == 0` 表示 SDK/MIX 硬同步帧；Native UVC 双流路径可发最近邻软同步帧，
 * 此时保留 color/depth 原始时间戳，并把时间差写入 [timestampDeltaUs] 供下游按阈值取舍。
 */
data class RgbdFramePair(
    val color: ColorFrame,
    val depth: DepthFrame,
    val timestampDeltaUs: Long = abs(color.timestampUs - depth.timestampUs),
) {
    init {
        require(timestampDeltaUs == abs(color.timestampUs - depth.timestampUs)) {
            "color/depth 时间差字段不一致：$timestampDeltaUs vs ${abs(color.timestampUs - depth.timestampUs)}"
        }
    }
}

/** 相机内参（fx/fy/cx/cy + 畸变系数 + 标定时分辨率）。分辨率切换需重算。 */
data class CameraIntrinsics(
    val fx: Double, val fy: Double,
    val cx: Double, val cy: Double,
    /** [k1, k2, p1, p2, k3] OpenCV 5 系数；都为 0 表示未做畸变标定（SDK 出厂值大多如此） */
    val distortion: DoubleArray,
    val width: Int,
    val height: Int,
) {
    override fun equals(other: Any?): Boolean = other is CameraIntrinsics &&
        fx == other.fx && fy == other.fy && cx == other.cx && cy == other.cy &&
        distortion.contentEquals(other.distortion) &&
        width == other.width && height == other.height
    override fun hashCode(): Int =
        (((fx.hashCode() * 31 + fy.hashCode()) * 31 + cx.hashCode()) * 31 + cy.hashCode()) *
            31 + distortion.contentHashCode() + width * 31 + height
}

/**
 * iHawk 自身 Color↔Depth 间的相对外参（同一物理设备内的 stereo pair）。
 *
 * - rotation：行优先 3×3，把 Depth 系坐标 → Color 系坐标
 * - translation：3×1 mm
 * - rmsReprojectionPx：标定时 reprojection 误差，作健康度指示
 *
 * **早期版本**这个类型语义是"主摄↔深度"——已废，详见 docs/architecture/01 §1。
 */
data class StereoExtrinsics(
    val rotation: DoubleArray,
    val translation: DoubleArray,
    val rmsReprojectionPx: Double,
) {
    override fun equals(other: Any?): Boolean = other is StereoExtrinsics &&
        rotation.contentEquals(other.rotation) &&
        translation.contentEquals(other.translation) &&
        rmsReprojectionPx == other.rmsReprojectionPx
    override fun hashCode(): Int = rotation.contentHashCode() * 31 +
        translation.contentHashCode() * 31 + rmsReprojectionPx.hashCode()
}

/**
 * 标定结果完整契约，按 [deviceSerial] 唯一。
 *
 * 现状：纯内存 / 进程内传递的数据契约，**当前未持久化**——core:database 里没有对应 Entity/DAO。
 * (历史注释曾写"落 Room"，但从未落地，已订正避免误读。) 跨会话复用的持久化方案待定，
 * 终态见 docs/architecture(标定持久化专题)；真要落库时在 core:database 补 Entity + DAO + migration。
 */
data class CalibrationResult(
    val deviceSerial: String,
    val colorIntrinsics: CameraIntrinsics,
    val depthIntrinsics: CameraIntrinsics,
    val stereo: StereoExtrinsics,
    val calibratedAtMs: Long,
    val sampleCount: Int,
    val source: CalibrationSource,
)

enum class CalibrationSource { SDK_FACTORY, USER_CALIBRATED }

/**
 * 三维点云（含可选颜色），重建管线中间产物。
 *
 * 用 NIO Buffer：[points] 是 FloatBuffer，DirectByteBuffer 包装的 [x,y,z, ...] 序列；
 * [colors] 是 ByteBuffer，[r,g,b, ...] 同长度（每点 3 bytes），可空。
 */
data class PointCloud(
    val points: java.nio.FloatBuffer,
    val colors: ByteBuffer?,
    val count: Int,
)

/** 6 自由度位姿（四元数 + 平移）。重建时每帧一个，关键帧轨迹累积成扫描路径。 */
data class Pose6D(
    /** 四元数 [x, y, z, w] */
    val rotationQuat: FloatArray,
    /** 平移 [tx, ty, tz] mm */
    val translation: FloatArray,
) {
    override fun equals(other: Any?): Boolean = other is Pose6D &&
        rotationQuat.contentEquals(other.rotationQuat) &&
        translation.contentEquals(other.translation)
    override fun hashCode(): Int =
        rotationQuat.contentHashCode() * 31 + translation.contentHashCode()
}

/**
 * 三维外廓扫描会话元信息。
 *
 * 现状：纯内存数据契约，**当前未持久化到 Room**——core:database 无对应 Entity/DAO，
 * 仅约定了下列文件落 getFilesDir()/scans/<id>/。(历史注释曾写"持久化到 Room"，从未落地，已订正。)
 * 终态要做会话历史列表时再在 core:database 补 Entity/DAO/migration。
 *
 * 文件布局：
 *   scans/<id>/cloud.ply      — 高密度点云
 *   scans/<id>/mesh.gltf      — 三维网格（含纹理）
 *   scans/<id>/mesh.obj       — OBJ 备份
 *   scans/<id>/preview.png    — 缩略图
 *   scans/<id>/keyframes/...  — 关键帧 Color 图（纹理烘焙后可选清理）
 */
data class ScanSession(
    val id: String,
    val createdAtMs: Long,
    val deviceSerial: String,
    val keyframeCount: Int,
    val totalFrameCount: Int,
    val pointCloudPath: String,
    val meshPath: String,
    val texturePath: String?,
    /** 0..1，扫描覆盖估计（关键帧轨迹围成的球面占比） */
    val coverageRatio: Float,
    val durationMs: Long,
)

/** VIN 拓印的平面拟合结果（钢架表面单帧拟合）。 */
data class PlaneFit(
    /** [nx, ny, nz] 单位向量 */
    val normal: FloatArray,
    /** mm，钢架到 Color 相机原点的法向距离 */
    val distance: Float,
    /** 拟合残差（mm） */
    val rmsResidualMm: Float,
    /** RANSAC inlier 占比（0..1），≥ 0.95 视为可信平面 */
    val inlierRatio: Float,
) {
    override fun equals(other: Any?): Boolean = other is PlaneFit &&
        normal.contentEquals(other.normal) && distance == other.distance &&
        rmsResidualMm == other.rmsResidualMm && inlierRatio == other.inlierRatio
    override fun hashCode(): Int = normal.contentHashCode() * 31 +
        distance.hashCode() * 31 + rmsResidualMm.hashCode() * 31 + inlierRatio.hashCode()
}

/** VIN 拓印结果（单帧 RGBD → 1:1 正射图）。 */
data class VinRectifyResult(
    /** PNG 编码的拓印图（默认 1024×512，0.2 mm/px） */
    val rectifiedPng: ByteArray,
    val plane: PlaneFit,
    /** 虚拟正射相机的法向距离 mm（默认 300） */
    val orthoDistanceMm: Float,
    /** 虚拟正射相机像素物理尺寸 mm/px（默认 0.2） */
    val pixelSizeMm: Float,
    val outputWidth: Int,
    val outputHeight: Int,
    val captureTimestampMs: Long,
) {
    override fun equals(other: Any?): Boolean = other is VinRectifyResult &&
        rectifiedPng.contentEquals(other.rectifiedPng) && plane == other.plane &&
        orthoDistanceMm == other.orthoDistanceMm && pixelSizeMm == other.pixelSizeMm &&
        outputWidth == other.outputWidth && outputHeight == other.outputHeight &&
        captureTimestampMs == other.captureTimestampMs
    override fun hashCode(): Int = rectifiedPng.contentHashCode() * 31 + plane.hashCode()
}
