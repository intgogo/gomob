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
    /** 同一张规范图叠四周毫米刻度尺的展示副本；识别一律用 [resultPngBase64] 那张干净图。 */
    @SerialName("ruler_png_base64") val rulerPngBase64: String = "",
    @SerialName("character_metrics") val characterMetrics: VinCharacterMetricsDto? = null,
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

/**
 * 规范图上车架号字符串的物理度量。
 *
 * mm 是承印平面上的真实尺寸，px 是 4425×600 画布像素，两者恒差 [pixelsPerMM] 倍（25），
 * 与图上四周刻度尺同一把尺子——用户拿刻度尺量到的读数必须与这里的数值一致。
 */
@Serializable
data class VinCharacterMetricsDto(
    @SerialName("pixels_per_mm") val pixelsPerMM: Double = 0.0,
    /** 首字符左缘到末字符右缘。 */
    @SerialName("total_width_mm") val totalWidthMm: Double = 0.0,
    @SerialName("total_width_px") val totalWidthPx: Double = 0.0,
    /** 首末字符中心跨距 = 16 × 节距。 */
    @SerialName("center_span_mm") val centerSpanMm: Double = 0.0,
    /** 相邻字符中心节距。 */
    @SerialName("pitch_mm") val pitchMm: Double = 0.0,
    @SerialName("pitch_px") val pitchPx: Double = 0.0,
    /** 字符之间的空隙 = 节距 − 字宽中位数。 */
    @SerialName("gap_mm") val gapMm: Double = 0.0,
    @SerialName("gap_px") val gapPx: Double = 0.0,
    @SerialName("char_width_mm") val charWidthMm: Double = 0.0,
    @SerialName("char_width_px") val charWidthPx: Double = 0.0,
    @SerialName("char_height_mm") val charHeightMm: Double = 0.0,
    @SerialName("char_height_px") val charHeightPx: Double = 0.0,
    @SerialName("left_px") val leftPx: Double = 0.0,
    @SerialName("right_px") val rightPx: Double = 0.0,
    @SerialName("baseline_y_px") val baselineYPx: Double = 0.0,
    val characters: List<VinCharacterMetricDto> = emptyList(),
)

/** 单个字符的度量；[character] 来自检测器，仅作诊断参考，权威文本以 OCR 为准。 */
@Serializable
data class VinCharacterMetricDto(
    val index: Int = 0,
    val character: String = "",
    val score: Double = 0.0,
    @SerialName("center_x_px") val centerXPx: Double = 0.0,
    @SerialName("center_y_px") val centerYPx: Double = 0.0,
    @SerialName("width_mm") val widthMm: Double = 0.0,
    @SerialName("height_mm") val heightMm: Double = 0.0,
)
