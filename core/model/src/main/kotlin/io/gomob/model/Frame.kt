package io.gomob.model

/**
 * 单帧 RGBD 数据契约（领域层视角，跨进程/JNI 传递时再做拷贝/序列化）。
 *
 * 字段单位:
 *  - timestampNs: System.nanoTime() 同步参考；同源 RGB+Depth 必须同时间戳
 *  - depth: 单位毫米；0 表示无效
 *  - intrinsics: 标定后的内参（fx/fy/cx/cy）+ 畸变系数 k1..k5
 */
data class RgbdFrame(
    val timestampNs: Long,
    val rgb: ByteArray,
    val rgbWidth: Int,
    val rgbHeight: Int,
    val depth: ShortArray,
    val depthWidth: Int,
    val depthHeight: Int,
    val intrinsics: CameraIntrinsics,
)

data class CameraIntrinsics(
    val fx: Double, val fy: Double,
    val cx: Double, val cy: Double,
    val distortion: DoubleArray,
)

/** 主摄像头与深度相机的外参 — 标定阶段产出，扫描阶段只读。 */
data class StereoExtrinsics(
    val rotation: DoubleArray,    // 行优先 3x3
    val translation: DoubleArray, // 3x1，单位毫米
)
