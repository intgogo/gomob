package io.gomob.nativebridge.berxel

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Berxel 设备出厂标定参数 — 对应 SDK `BerxelHawkDeviceIntrinsicParams` 结构（156 字节）。
 *
 * Layout（来自 BerxelHawkDefines.h）：
 * ```
 * struct BerxelHawkDeviceIntrinsicParams {
 *     int8_t colorIntrinsicParams[36];        // 9 × float: fx, fy, cx, cy, k1, k2, p1, p2, k3
 *     int8_t irIntrinsicParams[36];           // 9 × float
 *     int8_t liteIrIntrinsicParams[36];       // 9 × float
 *     int8_t rotateIntrinsicParams[36];       // 9 × float (3×3 旋转矩阵 row-major)
 *     int8_t translationIntrinsicParams[12];  // 3 × float (平移向量 mm)
 * };
 * // 36×4 + 12 = 156 字节
 * ```
 *
 * Byte order: LE float32（Berxel x86_64 host 自然字节序）。
 */
data class BerxelDeviceParams(
    val colorIntrinsic: CameraIntrinsic,
    val irIntrinsic: CameraIntrinsic,
    val liteIrIntrinsic: CameraIntrinsic,
    /** color→ir 旋转矩阵 3×3，row-major。 */
    val colorToIrRotation: FloatArray,
    /** color→ir 平移向量 3 元素（mm）。 */
    val colorToIrTranslation: FloatArray,
) {
    init {
        require(colorToIrRotation.size == 9) { "rotation must be 9 floats (3×3), got ${colorToIrRotation.size}" }
        require(colorToIrTranslation.size == 3) { "translation must be 3 floats, got ${colorToIrTranslation.size}" }
    }

    // 默认 equals/hashCode 对 FloatArray 走 reference，data class 自动生成的也是 reference 等价 —
    // 这里不强求 deep equality，跟 [DepthFrame] 同思路保持轻量。
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    companion object {
        const val TOTAL_BYTES = 156
        const val INTRINSIC_BYTES = 36
        const val ROTATION_BYTES = 36
        const val TRANSLATION_BYTES = 12

        /** 从 156 字节 raw blob 解析。失败抛 [IllegalArgumentException]。 */
        fun fromBytes(bytes: ByteArray): BerxelDeviceParams {
            require(bytes.size == TOTAL_BYTES) {
                "expected $TOTAL_BYTES bytes, got ${bytes.size}"
            }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            return BerxelDeviceParams(
                colorIntrinsic = CameraIntrinsic.read(buf),
                irIntrinsic = CameraIntrinsic.read(buf),
                liteIrIntrinsic = CameraIntrinsic.read(buf),
                colorToIrRotation = FloatArray(9) { buf.float },
                colorToIrTranslation = FloatArray(3) { buf.float },
            )
        }
    }
}

/**
 * 单目相机内参 + 径向 / 切向畸变系数。
 *
 * 9 个 float = (fx, fy, cx, cy, k1, k2, p1, p2, k3)，OpenCV `calibrateCamera` 直接吃。
 */
data class CameraIntrinsic(
    val fx: Float,
    val fy: Float,
    val cx: Float,
    val cy: Float,
    val k1: Float,
    val k2: Float,
    val p1: Float,
    val p2: Float,
    val k3: Float,
) {
    /** 转 3×3 内参矩阵 row-major：[fx 0 cx; 0 fy cy; 0 0 1]。 */
    fun cameraMatrix(): FloatArray = floatArrayOf(
        fx, 0f, cx,
        0f, fy, cy,
        0f, 0f, 1f,
    )

    /** 畸变系数 5 元素：[k1, k2, p1, p2, k3]。 */
    fun distCoeffs(): FloatArray = floatArrayOf(k1, k2, p1, p2, k3)

    companion object {
        const val BYTES = 36

        /** 从 ByteBuffer 当前位置读 36 字节，advance position。 */
        fun read(buf: ByteBuffer): CameraIntrinsic = CameraIntrinsic(
            fx = buf.float, fy = buf.float, cx = buf.float, cy = buf.float,
            k1 = buf.float, k2 = buf.float, p1 = buf.float, p2 = buf.float, k3 = buf.float,
        )
    }
}
