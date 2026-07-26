package io.gomob.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** cv-engine `/cv/ocr/v1/vin_recognize` 响应（外部算法纯 OCR，不含厂家字形 verdict）。 */
@Serializable
data class VinRecognizeResponse(
    val provider: String,
    val vin: String,
    val confidence: Double,
    @SerialName("character_scores") val characterScores: List<Double>,
    @SerialName("character_count") val characterCount: Int,
    @SerialName("log_id") val logId: String,
    @SerialName("infer_ms") val inferMs: Long,
    @SerialName("character_crops") val characterCrops: List<VinCharacterCropResponse>,
)

/** 外部算法单字符切割项，位置从 1 开始且必须与 VIN 顺序一致。 */
@Serializable
data class VinCharacterCropResponse(
    val position: Int,
    val character: String,
    val image: VinCropImageResponse,
)

/** 服务端已全量解码校验的 `64×128` 单字符 WebP。 */
@Serializable
data class VinCropImageResponse(
    @SerialName("mime_type") val mimeType: String,
    @SerialName("data_base64") val dataBase64: String,
    val width: Int,
    val height: Int,
)

/** cv-engine 下发的 VINCreator 原厂预览投影快照。 */
@Serializable
data class VinPreviewCalibrationResponse(
    @SerialName("contract_version") val contractVersion: Int,
    @SerialName("projection_model") val projectionModel: String,
    @SerialName("occlusion_metric") val occlusionMetric: String,
    val key: VinPreviewCalibrationKeyResponse,
    @SerialName("calibration_sha256") val calibrationSha256: String,
    @SerialName("calibration_version") val calibrationVersion: Int,
    val depth: VinPreviewDepthCalibrationResponse,
    val color: VinPreviewColorCalibrationResponse,
)

@Serializable
data class VinPreviewCalibrationKeyResponse(
    @SerialName("depth_serial") val depthSerial: String,
    @SerialName("color_serial") val colorSerial: String,
    @SerialName("depth_width") val depthWidth: Int,
    @SerialName("depth_height") val depthHeight: Int,
    @SerialName("color_width") val colorWidth: Int,
    @SerialName("color_height") val colorHeight: Int,
)

@Serializable
data class VinPreviewDepthCalibrationResponse(
    @SerialName("sample_format") val sampleFormat: String,
    @SerialName("data_type") val dataType: Int,
    @SerialName("reference_width") val referenceWidth: Int,
    @SerialName("reference_height") val referenceHeight: Int,
    @SerialName("principal_column") val principalColumn: Double,
    @SerialName("principal_row") val principalRow: Double,
    @SerialName("projection_focal_x") val projectionFocalX: Double,
    @SerialName("projection_focal_y") val projectionFocalY: Double,
    @SerialName("disparity_focal") val disparityFocal: Double,
    @SerialName("baseline_mm") val baselineMm: Double,
    @SerialName("disparity_unit") val disparityUnit: Double,
    @SerialName("valid_depth_min_mm") val validDepthMinMm: Double,
    @SerialName("valid_depth_max_mm") val validDepthMaxMm: Double,
)

@Serializable
data class VinPreviewColorCalibrationResponse(
    @SerialName("principal_row") val principalRow: Double,
    @SerialName("principal_column") val principalColumn: Double,
    @SerialName("focal_row") val focalRow: Double,
    @SerialName("focal_column") val focalColumn: Double,
    @SerialName("distortion_pixel_k_p1_p2_s1_s2") val distortion: List<Double>,
    @SerialName("rotation_row_major") val rotation: List<Double>,
    @SerialName("translation_mm") val translationMm: List<Double>,
)

/**
 * cv-engine `/cv/ocr/v1/vin_restore` 响应（承印面正射 + 17 字符刚性规范化 → 彩色 PNG base64）。
 *
 * `ok=false` 走 HTTP 200：倾角、RGBD 同步或 17 位字符格架不可靠时判废，无 PNG。
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
    @SerialName("anchor_count") val anchorCount: Int = 0,
    @SerialName("anchor_candidate_count") val anchorCandidateCount: Int = 0,
    @SerialName("anchor_pitch_px") val anchorPitchPx: Double = 0.0,
    @SerialName("anchor_rms_px") val anchorRmsPx: Double = 0.0,
    @SerialName("anchor_mean_score") val anchorMeanScore: Double = 0.0,
    @SerialName("anchor_height_px") val anchorHeightPx: Double = 0.0,
    @SerialName("anchor_rotation_deg") val anchorRotationDeg: Double = 0.0,
    @SerialName("anchor_scale") val anchorScale: Double = 0.0,
    @SerialName("calibration_sha256") val calibrationSha256: String = "",
    @SerialName("calibration_version") val calibrationVersion: Int = 0,
    @SerialName("sync_delta_us") val syncDeltaUs: Long = 0,
    @SerialName("reject_reason") val rejectReason: String = "",
    @SerialName("device_id") val deviceId: String = "",
    @SerialName("color_device_id") val colorDeviceId: String = "",
    @SerialName("log_id") val logId: String = "",
)
