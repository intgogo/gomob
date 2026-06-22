package io.gomob.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** cv-engine `/cv/ocr/v1/vin_pipeline` 响应（整图 → VMASK → 字符 → 厂家字形库比对 → verdict）。 */
@Serializable
data class VinPipelineResponse(
    val verdict: String = "fail",
    val reasons: List<String> = emptyList(),
    @SerialName("avg_similarity") val avgSimilarity: Double = 0.0,
    @SerialName("min_similarity") val minSimilarity: Double = 0.0,
    val detections: Int = 0,
    val scored: Int = 0,
    @SerialName("vehicle_model_id") val vehicleModelId: String = "",
    @SerialName("batch_id") val batchId: String = "",
    val characters: List<VinPipelineChar> = emptyList(),
)

@Serializable
data class VinPipelineChar(
    val index: Int = 0,
    val character: String = "",
    val similarity: Double = 0.0,
    val status: String = "",
    @SerialName("detection_score") val detectionScore: Double = 0.0,
)

/**
 * cv-engine `/cv/ocr/v1/vin_restore` 响应（深度去透视 + OBB 正射 + 去阴影二值化 → OCR 级签名 PNG base64）。
 *
 * `ok=false` 走 HTTP 200：承印面相对相机倾角 >70°（[tiltDeg]）原厂硬门判废，无 PNG。
 */
@Serializable
data class VinRestoreResponse(
    val ok: Boolean = false,
    @SerialName("result_png_base64") val resultPngBase64: String = "",
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("tilt_deg") val tiltDeg: Double = 0.0,
    @SerialName("width_mm") val widthMm: Double = 0.0,
    @SerialName("height_mm") val heightMm: Double = 0.0,
    @SerialName("theta_deg") val thetaDeg: Double = 0.0,
    @SerialName("inlier_rate") val inlierRate: Double = 0.0,
    val rms: Double = 0.0,
    @SerialName("med_z") val medZ: Double = 0.0,
    @SerialName("num_det") val numDet: Int = 0,
    @SerialName("ink_ratio") val inkRatio: Double = 0.0,
    @SerialName("reject_reason") val rejectReason: String = "",  // ok=false 判废原因：tilt_too_large / low_quality
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("log_id") val logId: String = "",
)
