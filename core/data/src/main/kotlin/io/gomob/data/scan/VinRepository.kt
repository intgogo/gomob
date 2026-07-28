package io.gomob.data.scan

import io.gomob.network.ApiException
import io.gomob.network.CVEngineApi
import io.gomob.network.dto.VinCharacterMetricsDto
import java.util.Base64
import java.util.zip.CRC32
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

enum class VinRecognitionStatus {
    Completed,
    NeedsReview,
}

/** 服务端严格校验后的单字符 WebP。 */
data class VinCropImage(
    val mimeType: String,
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
)

/** 与 VIN 位置、字符和置信度绑定的真实单字符切割图。 */
data class VinCharacterCrop(
    val position: Int,
    val character: String,
    val confidence: Double,
    val image: VinCropImage,
)

/** VIN 纯 OCR 结果（端侧领域类型，剥离 core:network DTO）。 */
data class VinRecognitionResult(
    val provider: String,
    val vin: String,
    val confidence: Double,
    val characterScores: List<Double>,
    val characterCount: Int,
    val logId: String,
    val inferMs: Long,
    val characterCrops: List<VinCharacterCrop>,
) {
    val status: VinRecognitionStatus
        get() = if (
            characterCount == VIN_LENGTH &&
            VIN_PATTERN.matches(vin)
        ) {
            VinRecognitionStatus.Completed
        } else {
            VinRecognitionStatus.NeedsReview
        }

    companion object {
        const val VIN_LENGTH = 17
        private val VIN_PATTERN = Regex("^[A-HJ-NPR-Z0-9]{$VIN_LENGTH}$")
    }
}

/** 服务端逐字符格架诊断；手机端只据此给出可操作结论，不承担标定管理。 */
data class VinTextAnchorMetrics(
    val count: Int,
    val candidateCount: Int,
    val pitchPx: Double,
    val rmsPx: Double,
    val meanScore: Double,
    val heightPx: Double,
    val rotationDeg: Double,
    val scale: Double,
)

/**
 * 规范图上车架号字符串的物理度量（端侧领域类型）。
 *
 * mm 值是承印平面上的真实尺寸，px 值是 4425×600 画布像素，两者恒差 [pixelsPerMm] 倍；
 * 与还原图四周的毫米刻度尺同源，用户在图上量到的读数必须与这里一致。
 */
data class VinCharacterMetrics(
    val pixelsPerMm: Double,
    val totalWidthMm: Double,
    val totalWidthPx: Double,
    val centerSpanMm: Double,
    val pitchMm: Double,
    val pitchPx: Double,
    val gapMm: Double,
    val gapPx: Double,
    val charWidthMm: Double,
    val charWidthPx: Double,
    val charHeightMm: Double,
    val charHeightPx: Double,
    val leftPx: Double,
    val rightPx: Double,
    val baselineYPx: Double,
    val characters: List<VinCharacterMetric>,
)

/** 单个字符的度量；[character] 仅作诊断参考，权威文本以 OCR 为准。 */
data class VinCharacterMetric(
    val index: Int,
    val character: String,
    val score: Double,
    val centerXPx: Double,
    val centerYPx: Double,
    val widthMm: Double,
    val heightMm: Double,
)

/** 服务端权威还原的结构化判废原因。 */
sealed interface VinRestoreRejectReason {
    data object TiltTooLarge : VinRestoreRejectReason
    data object VinNotDetected : VinRestoreRejectReason
    data object RgbdOutOfSync : VinRestoreRejectReason
    data object TextAnchorUnreliable : VinRestoreRejectReason
    data object CalibrationUnavailable : VinRestoreRejectReason
    data class Unknown(val raw: String) : VinRestoreRejectReason

    companion object {
        fun fromWire(raw: String): VinRestoreRejectReason? = when (raw) {
            "" -> null
            "tilt_too_large" -> TiltTooLarge
            "vin_not_detected" -> VinNotDetected
            "rgbd_out_of_sync" -> RgbdOutOfSync
            "text_anchor_unreliable" -> TextAnchorUnreliable
            "calibration_unavailable" -> CalibrationUnavailable
            else -> Unknown(raw)
        }
    }
}

/**
 * VIN 数码拓印还原结果（端侧领域类型）。[png] 已从服务端 base64 解码为 PNG 字节；
 * [ok]=false 表服务端拒绝当前采集（[png] 为 null）。
 */
data class VinRestoreOutcome(
    val ok: Boolean,
    val png: ByteArray?,
    /** 叠四周毫米刻度尺的展示副本；识别一律用 [png] 那张干净图。 */
    val rulerPng: ByteArray?,
    val metrics: VinCharacterMetrics?,
    val width: Int,
    val height: Int,
    val tiltDeg: Double,
    val widthMm: Double,
    val heightMm: Double,
    val inlierRate: Double,
    val rms: Double,
    val medZ: Double,
    val numDet: Int,
    val textAnchor: VinTextAnchorMetrics?,
    val syncDeltaUs: Long,
    val rejectReason: VinRestoreRejectReason?,
    val logId: String,
    val calibrationSha256: String = "",
    val calibrationVersion: Int = 0,
    val depthDeviceId: String = "",
    val colorDeviceId: String = "",
)

/** VIN 预览标定的完整 rig/profile 键。 */
data class VinPreviewCalibrationKey(
    val depthSerial: String,
    val colorSerial: String,
    val depthWidth: Int,
    val depthHeight: Int,
    val colorWidth: Int,
    val colorHeight: Int,
)

/** 手机只读消费的 VINCreator 预览投影参数。 */
data class VinPreviewCalibration(
    val contractVersion: Int,
    val projectionModel: String,
    val occlusionMetric: String,
    val key: VinPreviewCalibrationKey,
    val calibrationSha256: String,
    val calibrationVersion: Int,
    val depth: VinPreviewDepthCalibration,
    val color: VinPreviewColorCalibration,
)

data class VinPreviewDepthCalibration(
    val principalColumn: Double,
    val principalRow: Double,
    val projectionFocalX: Double,
    val projectionFocalY: Double,
    val disparityFocal: Double,
    val baselineMm: Double,
    val disparityUnit: Double,
    val validDepthMinMm: Double,
    val validDepthMaxMm: Double,
)

data class VinPreviewColorCalibration(
    val principalRow: Double,
    val principalColumn: Double,
    val focalRow: Double,
    val focalColumn: Double,
    val distortion: List<Double>,
    val rotation: List<Double>,
    val translationMm: List<Double>,
)

internal const val VIN_RESTORE_CANVAS_W = 4425
internal const val VIN_RESTORE_CANVAS_H = 600
internal const val VIN_CHARACTER_CROP_WIDTH = 64
internal const val VIN_CHARACTER_CROP_HEIGHT = 128
internal const val VIN_CHARACTER_CROP_MAX_COUNT = 32
internal const val VIN_CHARACTER_CROP_MAX_BYTES = 256 shl 10
internal const val VIN_CHARACTER_CROPS_MAX_TOTAL_BYTES = 2 shl 20

/** 校验完整 PNG chunk/CRC 并读取 IHDR，避免仅凭 24 字节伪头把损坏结果当成功。 */
internal fun pngDimensions(png: ByteArray): Pair<Int, Int>? {
    if (png.size < 45) return null
    val signature = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    if (!png.copyOfRange(0, signature.size).contentEquals(signature)) return null
    var offset = signature.size
    var dimensions: Pair<Int, Int>? = null
    var sawImageData = false
    var firstChunk = true
    while (offset <= png.size - 12) {
        val lengthLong = readUnsignedBigEndianInt(png, offset)
        if (lengthLong > Int.MAX_VALUE) return null
        val length = lengthLong.toInt()
        val nextOffsetLong = offset.toLong() + 12L + lengthLong
        if (nextOffsetLong > png.size) return null
        val dataOffset = offset + 8
        val crcOffset = dataOffset + length
        val nextOffset = nextOffsetLong.toInt()

        val crc = CRC32().apply { update(png, offset + 4, 4 + length) }.value
        if (crc != readUnsignedBigEndianInt(png, crcOffset)) return null

        when {
            chunkTypeEquals(png, offset + 4, "IHDR") -> {
                if (!firstChunk || length != 13 || dimensions != null) return null
                val width = readBigEndianInt(png, dataOffset)
                val height = readBigEndianInt(png, dataOffset + 4)
                if (width <= 0 || height <= 0) return null
                dimensions = width to height
            }
            chunkTypeEquals(png, offset + 4, "IDAT") -> sawImageData = true
            chunkTypeEquals(png, offset + 4, "IEND") -> {
                if (length != 0 || !sawImageData || nextOffset != png.size) return null
                return dimensions
            }
        }
        firstChunk = false
        offset = nextOffset
    }
    return null
}

private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

private fun readUnsignedBigEndianInt(bytes: ByteArray, offset: Int): Long =
    readBigEndianInt(bytes, offset).toLong() and 0xFFFF_FFFFL

private fun chunkTypeEquals(bytes: ByteArray, offset: Int, expected: String): Boolean =
    expected.indices.all { bytes[offset + it] == expected[it].code.toByte() }

/** 解析 WebP 容器中的画布尺寸，覆盖 VP8 / VP8L / VP8X。 */
internal fun webpDimensions(webp: ByteArray): Pair<Int, Int>? {
    if (webp.size < 20 || !asciiEquals(webp, 0, "RIFF") || !asciiEquals(webp, 8, "WEBP")) return null
    val riffSize = readUnsignedLittleEndianInt(webp, 4)
    if (riffSize != webp.size.toLong() - 8L) return null

    var offset = 12
    while (offset <= webp.size - 8) {
        val chunkSizeLong = readUnsignedLittleEndianInt(webp, offset + 4)
        if (chunkSizeLong > Int.MAX_VALUE) return null
        val chunkSize = chunkSizeLong.toInt()
        val dataOffset = offset + 8
        val dataEndLong = dataOffset.toLong() + chunkSizeLong
        if (dataEndLong > webp.size) return null
        val dimensions = when {
            asciiEquals(webp, offset, "VP8 ") && chunkSize >= 10 &&
                webp[dataOffset + 3] == 0x9D.toByte() &&
                webp[dataOffset + 4] == 0x01.toByte() &&
                webp[dataOffset + 5] == 0x2A.toByte() -> {
                (readUnsignedLittleEndianShort(webp, dataOffset + 6) and 0x3FFF) to
                    (readUnsignedLittleEndianShort(webp, dataOffset + 8) and 0x3FFF)
            }
            asciiEquals(webp, offset, "VP8L") && chunkSize >= 5 &&
                webp[dataOffset] == 0x2F.toByte() -> {
                val bits = readUnsignedLittleEndianInt(webp, dataOffset + 1)
                ((bits and 0x3FFF).toInt() + 1) to (((bits shr 14) and 0x3FFF).toInt() + 1)
            }
            asciiEquals(webp, offset, "VP8X") && chunkSize >= 10 -> {
                (readUnsignedLittleEndian24(webp, dataOffset + 4) + 1) to
                    (readUnsignedLittleEndian24(webp, dataOffset + 7) + 1)
            }
            else -> null
        }
        if (dimensions != null) {
            return dimensions.takeIf { it.first > 0 && it.second > 0 }
        }
        val paddedSize = chunkSizeLong + (chunkSizeLong and 1L)
        val nextOffset = dataOffset.toLong() + paddedSize
        if (nextOffset > webp.size || nextOffset <= offset) return null
        offset = nextOffset.toInt()
    }
    return null
}

private fun asciiEquals(bytes: ByteArray, offset: Int, expected: String): Boolean =
    offset >= 0 && offset + expected.length <= bytes.size &&
        expected.indices.all { bytes[offset + it] == expected[it].code.toByte() }

private fun readUnsignedLittleEndianShort(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun readUnsignedLittleEndian24(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16)

private fun readUnsignedLittleEndianInt(bytes: ByteArray, offset: Int): Long =
    ((bytes[offset].toLong() and 0xFF)) or
        ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFF) shl 24)

/** VIN 识别仓库：把服务端权威正射图交给外部算法 OCR。 */
@Singleton
class VinRepository @Inject constructor(
    private val cvEngine: CVEngineApi,
) {
    /** 按完整物理 rig/profile 获取一次服务端权威预览投影参数。 */
    suspend fun previewCalibration(key: VinPreviewCalibrationKey): VinPreviewCalibration {
        require(key.depthSerial.isNotBlank() && key.colorSerial.isNotBlank()) { "VIN 预览标定相机序列号为空" }
        require(
            key.depthWidth > 0 && key.depthHeight > 0 && key.colorWidth > 0 && key.colorHeight > 0,
        ) { "VIN 预览标定流档位非法" }
        val normalizedKey = key.copy(
            depthSerial = key.depthSerial.trim().uppercase(),
            colorSerial = key.colorSerial.trim().uppercase(),
        )
        val resp = cvEngine.vinPreviewCalibration(
            depthSerial = normalizedKey.depthSerial,
            colorSerial = normalizedKey.colorSerial,
            depthWidth = normalizedKey.depthWidth,
            depthHeight = normalizedKey.depthHeight,
            colorWidth = normalizedKey.colorWidth,
            colorHeight = normalizedKey.colorHeight,
        ).data ?: throw ApiException(50001, 500, "VIN 预览标定响应缺数据")

        require(resp.contractVersion == VIN_PREVIEW_CONTRACT_VERSION) { "VIN 预览标定契约版本不支持" }
        require(resp.projectionModel == VIN_PREVIEW_PROJECTION_MODEL) { "VIN 预览投影模型不支持" }
        require(resp.occlusionMetric == VIN_PREVIEW_OCCLUSION_METRIC) { "VIN 预览遮挡模型不支持" }
        val responseKey = VinPreviewCalibrationKey(
            depthSerial = resp.key.depthSerial.trim().uppercase(),
            colorSerial = resp.key.colorSerial.trim().uppercase(),
            depthWidth = resp.key.depthWidth,
            depthHeight = resp.key.depthHeight,
            colorWidth = resp.key.colorWidth,
            colorHeight = resp.key.colorHeight,
        )
        require(responseKey == normalizedKey) { "VIN 预览标定响应 rig/profile 串线" }
        require(VIN_CALIBRATION_SHA256.matches(resp.calibrationSha256)) { "VIN 预览标定 SHA-256 非法" }
        require(resp.calibrationVersion > 0) { "VIN 预览标定版本非法" }

        val depthValues = listOf(
            resp.depth.principalColumn,
            resp.depth.principalRow,
            resp.depth.projectionFocalX,
            resp.depth.projectionFocalY,
            resp.depth.disparityFocal,
            resp.depth.baselineMm,
            resp.depth.disparityUnit,
            resp.depth.validDepthMinMm,
            resp.depth.validDepthMaxMm,
        )
        require(depthValues.all(Double::isFinite)) { "VIN 预览深度参数包含 NaN/Inf" }
        require(resp.depth.sampleFormat == VIN_PREVIEW_SAMPLE_FORMAT && resp.depth.dataType == 1) {
            "VIN 预览深度格式不是原厂 disparity×8"
        }
        require(resp.depth.referenceWidth > 0 && resp.depth.referenceHeight > 0) { "VIN 预览深度参考档位非法" }
        require(
            resp.depth.principalColumn in 0.0..<normalizedKey.depthWidth.toDouble() &&
                resp.depth.principalRow in 0.0..<normalizedKey.depthHeight.toDouble() &&
                resp.depth.projectionFocalX > 0.0 && resp.depth.projectionFocalY > 0.0 &&
                resp.depth.disparityFocal > 0.0 && resp.depth.baselineMm > 0.0 && resp.depth.disparityUnit > 0.0 &&
                resp.depth.validDepthMinMm > 0.0 && resp.depth.validDepthMaxMm > resp.depth.validDepthMinMm,
        ) { "VIN 预览深度参数越界" }
        val expectedScaleX = resp.depth.referenceWidth.toDouble() / normalizedKey.depthWidth
        val expectedScaleY = resp.depth.referenceHeight.toDouble() / normalizedKey.depthHeight
        require(abs(resp.depth.disparityFocal / resp.depth.projectionFocalX - expectedScaleX) <= 1e-6) {
            "VIN 预览 Z 焦距与当前档位 X 焦距不自洽"
        }
        require(abs(resp.depth.disparityFocal / resp.depth.projectionFocalY - expectedScaleY) <= 1e-6) {
            "VIN 预览 Z 焦距与当前档位 Y 焦距不自洽"
        }

        require(resp.color.distortion.size == 5) { "VIN 预览彩色畸变数组长度非法" }
        require(resp.color.rotation.size == 9) { "VIN 预览旋转数组长度非法" }
        require(resp.color.translationMm.size == 3) { "VIN 预览平移数组长度非法" }
        val colorValues = listOf(
            resp.color.principalRow,
            resp.color.principalColumn,
            resp.color.focalRow,
            resp.color.focalColumn,
        ) + resp.color.distortion + resp.color.rotation + resp.color.translationMm
        require(colorValues.all(Double::isFinite)) { "VIN 预览彩色参数包含 NaN/Inf" }
        require(resp.color.focalRow > 0.0 && resp.color.focalColumn > 0.0) { "VIN 预览彩色焦距非法" }
        validateRotation(resp.color.rotation)

        return VinPreviewCalibration(
            contractVersion = resp.contractVersion,
            projectionModel = resp.projectionModel,
            occlusionMetric = resp.occlusionMetric,
            key = responseKey,
            calibrationSha256 = resp.calibrationSha256.lowercase(),
            calibrationVersion = resp.calibrationVersion,
            depth = VinPreviewDepthCalibration(
                principalColumn = resp.depth.principalColumn,
                principalRow = resp.depth.principalRow,
                projectionFocalX = resp.depth.projectionFocalX,
                projectionFocalY = resp.depth.projectionFocalY,
                disparityFocal = resp.depth.disparityFocal,
                baselineMm = resp.depth.baselineMm,
                disparityUnit = resp.depth.disparityUnit,
                validDepthMinMm = resp.depth.validDepthMinMm,
                validDepthMaxMm = resp.depth.validDepthMaxMm,
            ),
            color = VinPreviewColorCalibration(
                principalRow = resp.color.principalRow,
                principalColumn = resp.color.principalColumn,
                focalRow = resp.color.focalRow,
                focalColumn = resp.color.focalColumn,
                distortion = resp.color.distortion.toList(),
                rotation = resp.color.rotation.toList(),
                translationMm = resp.color.translationMm.toList(),
            ),
        )
    }

    /**
     * @param imagePng 服务端权威正射 PNG 字节
     */
    suspend fun recognize(imagePng: ByteArray): VinRecognitionResult {
        val image = MultipartBody.Part.createFormData(
            "image_binary", "vin.png",
            imagePng.toRequestBody("image/png".toMediaTypeOrNull()),
        )
        val resp = cvEngine.vinRecognize(image).data
            ?: throw ApiException(50001, 500, "VIN 识别响应缺数据")

        require(resp.provider.isNotBlank()) { "VIN 识别响应 provider 为空" }
        require(resp.vin.isNotBlank()) { "VIN 识别响应 vin 为空" }
        require(resp.confidence.isFinite() && resp.confidence in 0.0..1.0) {
            "VIN 识别响应 confidence 越界"
        }
        require(resp.characterScores.all { it.isFinite() && it in 0.0..1.0 }) {
            "VIN 识别响应 character_scores 越界"
        }
        require(resp.vin.all { it.code in 0x20..0x7E }) { "VIN 识别响应 vin 不是单字节可见字符" }
        require(resp.characterCount == resp.vin.length && resp.characterCount in 1..VIN_CHARACTER_CROP_MAX_COUNT) {
            "VIN 识别响应 character_count 与 vin 不一致"
        }
        require(resp.characterScores.size == resp.characterCount) {
            "VIN 识别响应 character_scores 数量与 vin 不一致"
        }
        require(abs(resp.confidence - resp.characterScores.average()) <= 1e-9) {
            "VIN 识别响应 confidence 与字符分数均值不一致"
        }
        require(resp.characterCrops.size == resp.characterCount) {
            "VIN 识别响应单字符切割图数量与 vin 不一致"
        }
        require(resp.inferMs >= 0) { "VIN 识别响应 infer_ms 非法" }
        var totalCropBytes = 0
        val characterCrops = resp.characterCrops.mapIndexed { index, crop ->
            val expectedPosition = index + 1
            val expectedCharacter = resp.vin[index].toString()
            require(crop.position == expectedPosition) {
                "VIN 识别响应第 $expectedPosition 位切割图位置不连续"
            }
            require(crop.character == expectedCharacter) {
                "VIN 识别响应第 $expectedPosition 位切割图字符与 vin 不一致"
            }
            val cropResponse = crop.image
            require(cropResponse.mimeType.equals("image/webp", ignoreCase = true)) {
                "VIN 识别响应第 $expectedPosition 位切割图 MIME 非法"
            }
            require(
                cropResponse.width == VIN_CHARACTER_CROP_WIDTH &&
                    cropResponse.height == VIN_CHARACTER_CROP_HEIGHT,
            ) { "VIN 识别响应第 $expectedPosition 位切割图尺寸非法" }
            val cropBytes = runCatching { Base64.getDecoder().decode(cropResponse.dataBase64) }
                .getOrElse {
                    throw IllegalArgumentException(
                        "VIN 识别响应第 $expectedPosition 位切割图 base64 非法",
                        it,
                    )
                }
            require(cropBytes.isNotEmpty() && cropBytes.size <= VIN_CHARACTER_CROP_MAX_BYTES) {
                "VIN 识别响应第 $expectedPosition 位切割图大小非法"
            }
            totalCropBytes += cropBytes.size
            require(totalCropBytes <= VIN_CHARACTER_CROPS_MAX_TOTAL_BYTES) {
                "VIN 识别响应单字符切割图总大小非法"
            }
            require(webpDimensions(cropBytes) == (VIN_CHARACTER_CROP_WIDTH to VIN_CHARACTER_CROP_HEIGHT)) {
                "VIN 识别响应第 $expectedPosition 位切割图实际尺寸与元数据不一致"
            }
            VinCharacterCrop(
                position = crop.position,
                character = crop.character,
                confidence = resp.characterScores[index],
                image = VinCropImage(
                    mimeType = "image/webp",
                    bytes = cropBytes,
                    width = cropResponse.width,
                    height = cropResponse.height,
                ),
            )
        }
        return VinRecognitionResult(
            provider = resp.provider,
            vin = resp.vin,
            confidence = resp.confidence,
            characterScores = resp.characterScores,
            characterCount = resp.characterCount,
            logId = resp.logId,
            inferMs = resp.inferMs,
            characterCrops = characterCrops,
        )
    }

    /**
     * VIN 数码拓印还原（原厂全保真，全程服务端 Go cvengine）。端侧只把采集帧原样上传：
     *
     * @param rgbJpeg   HLSD8 彩色 JPEG 字节（服务端解码取彩色尺寸）
     * @param depthU16  RS-D550 mode25 原始视差，u16 LE、数值为真实视差×8，长度 = depthW*depthH*2
     * @param depthW/depthH 深度尺寸
     * @param fx/fy/cx/cy   端侧帧内参；服务端对已知 mode25 档按发布标定复核并覆盖，不能盲信客户端值
     * @param depthDeviceId 深度相机物理序列号
     * @param colorDeviceId HLSD8 物理序列号；外参按完整相机 rig 选择，禁止只凭深度序列号复用
     */
    suspend fun restore(
        rgbJpeg: ByteArray,
        depthU16: ByteArray,
        depthW: Int,
        depthH: Int,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
        depthDeviceId: String,
        colorDeviceId: String,
        colorW: Int,
        colorH: Int,
        colorTimestampUs: Long,
        depthTimestampUs: Long,
    ): VinRestoreOutcome {
        val text = "text/plain".toMediaTypeOrNull()
        val rgbPart = MultipartBody.Part.createFormData(
            "image_binary_rgb1300", "rgb1300.jpg",
            rgbJpeg.toRequestBody("image/jpeg".toMediaTypeOrNull()),
        )
        val depthPart = MultipartBody.Part.createFormData(
            "image_binary_depth", "depth.u16",
            depthU16.toRequestBody("application/octet-stream".toMediaTypeOrNull()),
        )
        val resp = cvEngine.vinRestore(
            rgbPart, depthPart,
            depthW.toString().toRequestBody(text), depthH.toString().toRequestBody(text),
            fx.toString().toRequestBody(text), fy.toString().toRequestBody(text),
            cx.toString().toRequestBody(text), cy.toString().toRequestBody(text),
            depthDeviceId.toRequestBody(text),
            colorDeviceId.toRequestBody(text),
            colorW.toString().toRequestBody(text),
            colorH.toString().toRequestBody(text),
            colorTimestampUs.toString().toRequestBody(text),
            depthTimestampUs.toString().toRequestBody(text),
        ).data ?: throw ApiException(50001, 500, "VIN 还原响应缺数据")

        val expectedSyncDeltaUs = if (colorTimestampUs >= depthTimestampUs) {
            colorTimestampUs - depthTimestampUs
        } else {
            depthTimestampUs - colorTimestampUs
        }
        require(resp.deviceId == depthDeviceId) {
            "VIN 还原响应深度相机身份串线: ${resp.deviceId} != $depthDeviceId"
        }
        require(resp.colorDeviceId == colorDeviceId) {
            "VIN 还原响应彩色相机身份串线: ${resp.colorDeviceId} != $colorDeviceId"
        }
        require(resp.syncDeltaUs == expectedSyncDeltaUs) {
            "VIN 还原响应同步差 ${resp.syncDeltaUs} != 请求帧对 $expectedSyncDeltaUs"
        }
        require(resp.logId.isNotBlank()) { "VIN 还原响应 log_id 为空" }
        val rejectReason = VinRestoreRejectReason.fromWire(resp.rejectReason)
        if (!resp.ok) require(rejectReason != null) { "VIN 还原判废响应缺少 reject_reason" }

        val png = if (resp.ok && resp.resultPngBase64.isNotEmpty()) {
            runCatching { Base64.getDecoder().decode(resp.resultPngBase64) }
                .getOrElse { throw IllegalArgumentException("VIN 还原成功响应 PNG base64 非法", it) }
        } else {
            null
        }
        val rulerPng = if (resp.ok && resp.rulerPngBase64.isNotEmpty()) {
            runCatching { Base64.getDecoder().decode(resp.rulerPngBase64) }
                .getOrElse { throw IllegalArgumentException("VIN 刻度尺图 base64 非法", it) }
        } else {
            null
        }
        if (resp.ok) {
            require(resp.resultPngBase64.isNotEmpty()) { "VIN 还原成功响应缺 PNG" }
            require(resp.anchorCount == VinRecognitionResult.VIN_LENGTH) {
                "VIN 还原成功响应字符锚定数 ${resp.anchorCount} != ${VinRecognitionResult.VIN_LENGTH}"
            }
            require(resp.rejectReason.isEmpty()) { "VIN 还原成功响应不应携带判废原因" }
            require(resp.width == VIN_RESTORE_CANVAS_W && resp.height == VIN_RESTORE_CANVAS_H) {
                "VIN 还原成功响应画布 ${resp.width}×${resp.height} != " +
                    "$VIN_RESTORE_CANVAS_W×$VIN_RESTORE_CANVAS_H"
            }
            require(pngDimensions(requireNotNull(png)) == (VIN_RESTORE_CANVAS_W to VIN_RESTORE_CANVAS_H)) {
                "VIN 还原成功响应 PNG 实际尺寸不是 $VIN_RESTORE_CANVAS_W×$VIN_RESTORE_CANVAS_H"
            }
            // 刻度尺图必须与干净图同画布：尺寸一旦不同，图上的毫米读数就与度量对不上。
            require(resp.rulerPngBase64.isNotEmpty()) { "VIN 还原成功响应缺刻度尺图" }
            require(
                pngDimensions(requireNotNull(rulerPng)) ==
                    (VIN_RESTORE_CANVAS_W to VIN_RESTORE_CANVAS_H),
            ) {
                "VIN 刻度尺图实际尺寸不是 $VIN_RESTORE_CANVAS_W×$VIN_RESTORE_CANVAS_H"
            }
            require(resp.characterMetrics != null) { "VIN 还原成功响应缺字符度量" }
        }
        val calibrationIdentityRequired = resp.ok || when (rejectReason) {
            VinRestoreRejectReason.RgbdOutOfSync,
            VinRestoreRejectReason.CalibrationUnavailable -> false
            else -> true
        }
        if (calibrationIdentityRequired) {
            require(VIN_CALIBRATION_SHA256.matches(resp.calibrationSha256)) {
                "VIN 还原结果缺少合法 calibration_sha256"
            }
            require(resp.calibrationVersion > 0) {
                "VIN 还原结果缺少合法 calibration_version"
            }
        } else if (resp.calibrationSha256.isNotEmpty() || resp.calibrationVersion != 0) {
            require(VIN_CALIBRATION_SHA256.matches(resp.calibrationSha256) && resp.calibrationVersion > 0) {
                "VIN 还原判废响应携带了不完整的标定审计身份"
            }
        }
        val textAnchor = if (resp.anchorCount > 0 || resp.anchorCandidateCount > 0) {
            VinTextAnchorMetrics(
                count = resp.anchorCount,
                candidateCount = resp.anchorCandidateCount,
                pitchPx = resp.anchorPitchPx,
                rmsPx = resp.anchorRmsPx,
                meanScore = resp.anchorMeanScore,
                heightPx = resp.anchorHeightPx,
                rotationDeg = resp.anchorRotationDeg,
                scale = resp.anchorScale,
            )
        } else {
            null
        }
        return VinRestoreOutcome(
            ok = resp.ok,
            png = png,
            rulerPng = rulerPng,
            metrics = resp.characterMetrics?.toDomain(),
            width = resp.width,
            height = resp.height,
            tiltDeg = resp.tiltDeg,
            widthMm = resp.widthMm,
            heightMm = resp.heightMm,
            inlierRate = resp.inlierRate,
            rms = resp.rms,
            medZ = resp.medZ,
            numDet = resp.numDet,
            textAnchor = textAnchor,
            syncDeltaUs = resp.syncDeltaUs,
            rejectReason = rejectReason,
            logId = resp.logId,
            calibrationSha256 = resp.calibrationSha256.lowercase(),
            calibrationVersion = resp.calibrationVersion,
            depthDeviceId = resp.deviceId,
            colorDeviceId = resp.colorDeviceId,
        )
    }

    private companion object {
        val VIN_CALIBRATION_SHA256 = Regex("^[0-9a-fA-F]{64}$")
        const val VIN_PREVIEW_CONTRACT_VERSION = 1
        const val VIN_PREVIEW_PROJECTION_MODEL = "vincreator_factory_v3"
        const val VIN_PREVIEW_OCCLUSION_METRIC = "absolute_camera_z"
        const val VIN_PREVIEW_SAMPLE_FORMAT = "disparity_x8_u16"
    }
}

/**
 * 把服务端字符度量映射为端侧领域类型，同时校验它自洽。
 *
 * 校验不是形式主义：这些数值会被当作实测尺寸读出来，一旦 mm 与 px 两套读数对不上，
 * 用户拿图上刻度尺量到的结果就会与显示的数字矛盾——那比不显示更糟。
 */
private fun VinCharacterMetricsDto.toDomain(): VinCharacterMetrics {
    require(pixelsPerMM > 0.0) { "VIN 字符度量缺少合法 pixels_per_mm" }
    require(characters.size == VinRecognitionResult.VIN_LENGTH) {
        "VIN 字符度量条目 ${characters.size} != ${VinRecognitionResult.VIN_LENGTH}"
    }
    require(pitchMm > 0.0 && charWidthMm > 0.0 && charHeightMm > 0.0) {
        "VIN 字符度量含非正尺寸: pitch=$pitchMm width=$charWidthMm height=$charHeightMm"
    }
    require(totalWidthMm >= centerSpanMm) {
        "VIN 字符串总宽 $totalWidthMm 小于中心跨距 $centerSpanMm"
    }
    fun requireConsistent(name: String, mm: Double, px: Double) {
        require(abs(mm * pixelsPerMM - px) <= MetricsPxToleranceCanvasPx) {
            "VIN 字符度量 $name 的 mm/px 读数不一致: ${mm}mm × $pixelsPerMM != ${px}px"
        }
    }
    requireConsistent("total_width", totalWidthMm, totalWidthPx)
    requireConsistent("pitch", pitchMm, pitchPx)
    requireConsistent("char_width", charWidthMm, charWidthPx)
    requireConsistent("char_height", charHeightMm, charHeightPx)
    characters.forEachIndexed { position, character ->
        require(character.index == position) {
            "VIN 字符度量顺序错乱: 第 $position 项 index=${character.index}"
        }
        require(character.widthMm > 0.0 && character.heightMm > 0.0) {
            "VIN 第 $position 个字符度量含非正尺寸"
        }
    }
    return VinCharacterMetrics(
        pixelsPerMm = pixelsPerMM,
        totalWidthMm = totalWidthMm,
        totalWidthPx = totalWidthPx,
        centerSpanMm = centerSpanMm,
        pitchMm = pitchMm,
        pitchPx = pitchPx,
        gapMm = gapMm,
        gapPx = gapPx,
        charWidthMm = charWidthMm,
        charWidthPx = charWidthPx,
        charHeightMm = charHeightMm,
        charHeightPx = charHeightPx,
        leftPx = leftPx,
        rightPx = rightPx,
        baselineYPx = baselineYPx,
        characters = characters.map {
            VinCharacterMetric(
                index = it.index,
                character = it.character,
                score = it.score,
                centerXPx = it.centerXPx,
                centerYPx = it.centerYPx,
                widthMm = it.widthMm,
                heightMm = it.heightMm,
            )
        },
    )
}

/** mm×25 与 px 的允许偏差：服务端两套值同源计算，容差只为吸收 JSON 浮点往返。 */
private const val MetricsPxToleranceCanvasPx = 0.01

private fun validateRotation(rotation: List<Double>) {
    fun dot(rowA: Int, rowB: Int): Double = (0..2).sumOf { column ->
        rotation[rowA * 3 + column] * rotation[rowB * 3 + column]
    }
    for (row in 0..2) {
        require(abs(dot(row, row) - 1.0) <= 1e-6) { "VIN 预览旋转矩阵未归一化" }
        for (other in row + 1..2) {
            require(abs(dot(row, other)) <= 1e-6) { "VIN 预览旋转矩阵不正交" }
        }
    }
    val determinant =
        rotation[0] * (rotation[4] * rotation[8] - rotation[5] * rotation[7]) -
            rotation[1] * (rotation[3] * rotation[8] - rotation[5] * rotation[6]) +
            rotation[2] * (rotation[3] * rotation[7] - rotation[4] * rotation[6])
    require(abs(determinant - 1.0) <= 1e-6) { "VIN 预览旋转矩阵行列式非法" }
}
