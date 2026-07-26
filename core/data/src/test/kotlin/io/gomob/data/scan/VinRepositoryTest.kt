package io.gomob.data.scan

import com.google.common.truth.Truth.assertThat
import io.gomob.network.CVEngineApi
import io.gomob.network.Envelope
import io.gomob.network.dto.VinCharacterCropResponse
import io.gomob.network.dto.VinCropImageResponse
import io.gomob.network.dto.VinPreviewCalibrationKeyResponse
import io.gomob.network.dto.VinPreviewCalibrationResponse
import io.gomob.network.dto.VinPreviewColorCalibrationResponse
import io.gomob.network.dto.VinPreviewDepthCalibrationResponse
import io.gomob.network.dto.VinRecognizeResponse
import io.gomob.network.dto.VinRestoreResponse
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okio.Buffer
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

class VinRepositoryTest {
    @Test
    fun `预览标定按完整rig档位请求并严格映射原厂投影`() = runTest {
        val api = FakeCVEngineApi()
        val result = VinRepository(api).previewCalibration(
            VinPreviewCalibrationKey(
                depthSerial = "bf301208",
                colorSerial = "202303111518",
                depthWidth = 640,
                depthHeight = 128,
                colorWidth = 4160,
                colorHeight = 832,
            ),
        )

        assertThat(api.previewCalibrationKey).isEqualTo(
            VinPreviewCalibrationKey("BF301208", "202303111518", 640, 128, 4160, 832),
        )
        assertThat(result.calibrationSha256).isEqualTo(FACTORY_CALIBRATION_SHA256)
        assertThat(result.calibrationVersion).isEqualTo(3)
        assertThat(result.depth.disparityFocal).isEqualTo(1229.2099609375)
        assertThat(result.depth.projectionFocalX).isEqualTo(614.60498046875)
        assertThat(result.color.rotation).hasSize(9)
        assertThat(result.color.distortion).hasSize(5)
    }

    @Test
    fun `预览标定拒绝串线数组错误或混用Z焦距`() = runTest {
        val cases = listOf<(VinPreviewCalibrationResponse) -> VinPreviewCalibrationResponse>(
            { it.copy(key = it.key.copy(colorSerial = "OTHER")) },
            { it.copy(color = it.color.copy(rotation = it.color.rotation.dropLast(1))) },
            { it.copy(depth = it.depth.copy(disparityFocal = it.depth.projectionFocalX)) },
        )
        cases.forEach { mutate ->
            val api = FakeCVEngineApi().apply { previewCalibrationResponse = mutate(factoryPreviewResponse()) }
            val error = runCatching {
                VinRepository(api).previewCalibration(
                    VinPreviewCalibrationKey("BF301208", "202303111518", 640, 128, 4160, 832),
                )
            }.exceptionOrNull()
            assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `识别只上传原始 PNG multipart 并映射外部结果`() = runTest {
        val api = FakeCVEngineApi()
        val repository = VinRepository(api)
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

        val result = repository.recognize(png)

        val part = api.recognizeImage ?: error("vinRecognize 未调用")
        assertThat(part.headers?.get("Content-Disposition"))
            .contains("name=\"image_binary\"; filename=\"vin.png\"")
        assertThat(part.body.contentType().toString()).isEqualTo("image/png")
        val uploaded = Buffer().also { part.body.writeTo(it) }.readByteArray()
        assertThat(uploaded.asList()).containsExactlyElementsIn(png.asList()).inOrder()
        assertThat(result.vin).isEqualTo("LA99FRP32G0LTH013")
        assertThat(result.status).isEqualTo(VinRecognitionStatus.Completed)
        assertThat(result.characterScores).hasSize(17)
        assertThat(result.characterCrops).hasSize(17)
        assertThat(result.characterCrops.first().position).isEqualTo(1)
        assertThat(result.characterCrops.first().character).isEqualTo("L")
        assertThat(result.characterCrops.first().confidence).isEqualTo(0.91)
        assertThat(result.characterCrops.first().image.mimeType).isEqualTo("image/webp")
        assertThat(result.characterCrops.first().image.width).isEqualTo(64)
        assertThat(result.characterCrops.first().image.height).isEqualTo(128)
        assertThat(webpDimensions(result.characterCrops.first().image.bytes)).isEqualTo(64 to 128)
        assertThat(result.characterCrops.last().position).isEqualTo(17)
        assertThat(result.characterCrops.last().character).isEqualTo("3")
    }

    @Test
    fun `识别拒绝损坏或元数据不一致的算法切割图`() = runTest {
        val invalidPayloads = listOf(
            VinCropImageResponse("image/png", TEST_CHARACTER_WEBP_BASE64, 64, 128),
            VinCropImageResponse("image/webp", "%%%", 64, 128),
            VinCropImageResponse("image/webp", Base64.getEncoder().encodeToString("hello".toByteArray()), 64, 128),
            VinCropImageResponse("image/webp", TEST_ONE_PIXEL_WEBP_BASE64, 64, 128),
            VinCropImageResponse("image/webp", TEST_CHARACTER_WEBP_BASE64, 63, 128),
        )

        invalidPayloads.forEach { crop ->
            val api = FakeCVEngineApi().apply { recognizeCropImage = crop }
            runCatching { VinRepository(api).recognize(byteArrayOf(1)) }
                .onSuccess { error("非法切割图不应映射成功：$crop") }
        }
    }

    @Test
    fun `识别拒绝逐字符数量顺序字符分数或均值不一致`() = runTest {
        val cases = listOf<(FakeCVEngineApi) -> Unit>(
            { it.recognizeScores = List(16) { 0.91 } },
            { it.recognizeConfidence = 0.5 },
            {
                it.recognizeCharacterCrops = it.defaultCharacterCrops().dropLast(1)
            },
            {
                it.recognizeCharacterCrops = it.defaultCharacterCrops().toMutableList().apply {
                    this[1] = this[1].copy(position = 3)
                }
            },
            {
                it.recognizeCharacterCrops = it.defaultCharacterCrops().toMutableList().apply {
                    this[1] = this[1].copy(character = "X")
                }
            },
        )

        cases.forEach { mutate ->
            val api = FakeCVEngineApi().apply(mutate)
            runCatching { VinRepository(api).recognize(byteArrayOf(1)) }
                .onSuccess { error("不一致的逐字符契约不应映射成功") }
        }
    }

    @Test
    fun `还原上传两路 native 时间戳并映射同步判废`() = runTest {
        val api = FakeCVEngineApi().apply { restoreEnabled = true }
        val repository = VinRepository(api)

        val result = repository.restore(
            rgbJpeg = byteArrayOf(1, 2),
            depthU16 = byteArrayOf(3, 4),
            depthW = 1,
            depthH = 1,
            fx = 1.0,
            fy = 1.0,
            cx = 0.0,
            cy = 0.0,
            depthDeviceId = "depth-a",
            colorDeviceId = "color-a",
            colorW = 4160,
            colorH = 832,
            colorTimestampUs = 1_000_000,
            depthTimestampUs = 1_040_000,
        )

        assertThat(api.colorTimestampUs).isEqualTo("1000000")
        assertThat(api.depthTimestampUs).isEqualTo("1040000")
        assertThat(api.depthDeviceId).isEqualTo("depth-a")
        assertThat(api.colorDeviceId).isEqualTo("color-a")
        assertThat(api.colorProfile).isEqualTo("4160x832")
        assertThat(result.ok).isFalse()
        assertThat(result.rejectReason).isEqualTo(VinRestoreRejectReason.RgbdOutOfSync)
        assertThat(result.syncDeltaUs).isEqualTo(40_000)
        assertThat(result.textAnchor).isNull()
    }

    @Test
    fun `还原完整映射字符锚定元数据`() = runTest {
        val api = FakeCVEngineApi().apply {
            restoreEnabled = true
            restoreResponse = VinRestoreResponse(
                ok = false,
                anchorCount = 17,
                anchorCandidateCount = 19,
                anchorPitchPx = 63.5,
                anchorRmsPx = 5.2,
                anchorMeanScore = 0.72,
                anchorHeightPx = 58.0,
                anchorRotationDeg = 0.2,
                anchorScale = 1.01,
                syncDeltaUs = 12_000,
                rejectReason = "text_anchor_unreliable",
                deviceId = "depth-a",
                colorDeviceId = "color-a",
                logId = "restore-anchor",
                calibrationSha256 = FACTORY_CALIBRATION_SHA256,
                calibrationVersion = 3,
            )
        }

        val result = VinRepository(api).restore(
            rgbJpeg = byteArrayOf(1, 2),
            depthU16 = byteArrayOf(3, 4),
            depthW = 1,
            depthH = 1,
            fx = 1.0,
            fy = 1.0,
            cx = 0.0,
            cy = 0.0,
            depthDeviceId = "depth-a",
            colorDeviceId = "color-a",
            colorW = 4160,
            colorH = 832,
            colorTimestampUs = 1_000_000,
            depthTimestampUs = 1_012_000,
        )

        assertThat(result.rejectReason).isEqualTo(VinRestoreRejectReason.TextAnchorUnreliable)
        assertThat(result.textAnchor?.count).isEqualTo(17)
        assertThat(result.textAnchor?.candidateCount).isEqualTo(19)
        assertThat(result.textAnchor?.pitchPx).isEqualTo(63.5)
    }

    @Test
    fun `还原成功但未锚定十七位时拒绝旧服务端响应`() = runTest {
        val api = FakeCVEngineApi().apply {
            restoreEnabled = true
            restoreResponse = VinRestoreResponse(
                ok = true,
                resultPngBase64 = "iVBORw0KGgo=",
                anchorCount = 0,
                syncDeltaUs = 12_000,
                deviceId = "depth-a",
                colorDeviceId = "color-a",
                logId = "restore-old",
                calibrationSha256 = FACTORY_CALIBRATION_SHA256,
                calibrationVersion = 3,
            )
        }

        val error = runCatching {
            VinRepository(api).restore(
                rgbJpeg = byteArrayOf(1, 2),
                depthU16 = byteArrayOf(3, 4),
                depthW = 1,
                depthH = 1,
                fx = 1.0,
                fy = 1.0,
                cx = 0.0,
                cy = 0.0,
                depthDeviceId = "depth-a",
                colorDeviceId = "color-a",
                colorW = 4160,
                colorH = 832,
                colorTimestampUs = 1_000_000,
                depthTimestampUs = 1_012_000,
            )
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `还原成功只接受元数据与PNG均为4425乘600`() = runTest {
        val api = FakeCVEngineApi().apply {
            restoreEnabled = true
            restoreResponse = successfulRestoreResponse(4425, 600, 4425, 600)
        }

        val result = restore(api)

        assertThat(result.ok).isTrue()
        assertThat(pngDimensions(requireNotNull(result.png))).isEqualTo(4425 to 600)
        assertThat(result.calibrationSha256).isEqualTo(FACTORY_CALIBRATION_SHA256)
        assertThat(result.calibrationVersion).isEqualTo(3)
    }

    @Test
    fun `还原成功拒绝缺少原厂标定审计身份的旧服务端响应`() = runTest {
        val api = FakeCVEngineApi().apply {
            restoreEnabled = true
            restoreResponse = successfulRestoreResponse(4425, 600, 4425, 600).copy(
                calibrationSha256 = "",
                calibrationVersion = 0,
            )
        }

        val error = runCatching { restore(api) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("calibration_sha256")
    }

    @Test
    fun `未检测到VIN按明确原因映射并保留标定身份`() = runTest {
        val api = FakeCVEngineApi().apply {
            restoreEnabled = true
            restoreResponse = VinRestoreResponse(
                ok = false,
                syncDeltaUs = 12_000,
                rejectReason = "vin_not_detected",
                deviceId = "depth-a",
                colorDeviceId = "color-a",
                logId = "restore-no-vin",
                calibrationSha256 = FACTORY_CALIBRATION_SHA256,
                calibrationVersion = 3,
            )
        }

        val result = restore(api)

        assertThat(result.rejectReason).isEqualTo(VinRestoreRejectReason.VinNotDetected)
        assertThat(result.calibrationSha256).isEqualTo(FACTORY_CALIBRATION_SHA256)
        assertThat(result.depthDeviceId).isEqualTo("depth-a")
        assertThat(result.colorDeviceId).isEqualTo("color-a")
    }

    @Test
    fun `还原响应相机身份串线时拒绝`() = runTest {
        val api = FakeCVEngineApi().apply {
            restoreEnabled = true
            restoreResponse = successfulRestoreResponse(4425, 600, 4425, 600).copy(
                colorDeviceId = "other-color",
            )
        }

        val error = runCatching { restore(api) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("彩色相机身份串线")
    }

    @Test
    fun `还原成功拒绝错误响应画布元数据`() = runTest {
        val api = FakeCVEngineApi().apply {
            restoreEnabled = true
            restoreResponse = successfulRestoreResponse(1200, 260, 4425, 600)
        }

        val error = runCatching { restore(api) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("1200×260")
    }

    @Test
    fun `还原成功拒绝PNG实际尺寸与契约不符`() = runTest {
        val api = FakeCVEngineApi().apply {
            restoreEnabled = true
            restoreResponse = successfulRestoreResponse(4425, 600, 1200, 260)
        }

        val error = runCatching { restore(api) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(error).hasMessageThat().contains("PNG 实际尺寸")
    }

    @Test
    fun `PNG尺寸检查拒绝只有IHDR的截断伪文件`() {
        assertThat(pngDimensions(truncatedPngHeader(4425, 600))).isNull()
    }

    private suspend fun restore(api: FakeCVEngineApi): VinRestoreOutcome = VinRepository(api).restore(
        rgbJpeg = byteArrayOf(1, 2),
        depthU16 = byteArrayOf(3, 4),
        depthW = 1,
        depthH = 1,
        fx = 1.0,
        fy = 1.0,
        cx = 0.0,
        cy = 0.0,
        depthDeviceId = "depth-a",
        colorDeviceId = "color-a",
        colorW = 4160,
        colorH = 832,
        colorTimestampUs = 1_000_000,
        depthTimestampUs = 1_012_000,
    )

    private fun successfulRestoreResponse(
        responseWidth: Int,
        responseHeight: Int,
        pngWidth: Int,
        pngHeight: Int,
    ) = VinRestoreResponse(
        ok = true,
        resultPngBase64 = Base64.getEncoder().encodeToString(pngImage(pngWidth, pngHeight)),
        width = responseWidth,
        height = responseHeight,
        anchorCount = 17,
        syncDeltaUs = 12_000,
        deviceId = "depth-a",
        colorDeviceId = "color-a",
        logId = "restore-success",
        calibrationSha256 = FACTORY_CALIBRATION_SHA256,
        calibrationVersion = 3,
    )

    private fun truncatedPngHeader(width: Int, height: Int): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        0x00, 0x00, 0x00, 0x0D,
        0x49, 0x48, 0x44, 0x52,
        (width ushr 24).toByte(), (width ushr 16).toByte(),
        (width ushr 8).toByte(), width.toByte(),
        (height ushr 24).toByte(), (height ushr 16).toByte(),
        (height ushr 8).toByte(), height.toByte(),
    )

    private fun pngImage(width: Int, height: Int): ByteArray {
        val ihdr = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).apply {
                writeInt(width)
                writeInt(height)
                writeByte(1) // 1-bit grayscale
                writeByte(0)
                writeByte(0)
                writeByte(0)
                writeByte(0)
                flush()
            }
        }.toByteArray()
        val scanlineBytes = (width + 7) / 8
        val raw = ByteArray((scanlineBytes + 1) * height)
        val compressed = ByteArrayOutputStream().also { bytes ->
            DeflaterOutputStream(bytes).use { it.write(raw) }
        }.toByteArray()
        return ByteArrayOutputStream().also { png ->
            png.write(PNG_SIGNATURE)
            writePngChunk(png, "IHDR", ihdr)
            writePngChunk(png, "IDAT", compressed)
            writePngChunk(png, "IEND", byteArrayOf())
        }.toByteArray()
    }

    private fun writePngChunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }.value
        DataOutputStream(output).apply {
            writeInt(data.size)
            write(typeBytes)
            write(data)
            writeInt(crc.toInt())
            flush()
        }
    }

    private class FakeCVEngineApi : CVEngineApi {
        var previewCalibrationKey: VinPreviewCalibrationKey? = null
        var previewCalibrationResponse = factoryPreviewResponse()
        var recognizeImage: MultipartBody.Part? = null
        var restoreEnabled = false
        var colorTimestampUs: String? = null
        var depthTimestampUs: String? = null
        var depthDeviceId: String? = null
        var colorDeviceId: String? = null
        var colorProfile: String? = null
        var recognizeCropImage = VinCropImageResponse(
            mimeType = "image/webp",
            dataBase64 = TEST_CHARACTER_WEBP_BASE64,
            width = 64,
            height = 128,
        )
        var recognizeVin = "LA99FRP32G0LTH013"
        var recognizeScores = List(recognizeVin.length) { 0.91 }
        var recognizeConfidence = 0.91
        var recognizeCharacterCrops: List<VinCharacterCropResponse>? = null
        var restoreResponse = VinRestoreResponse(
            ok = false,
            syncDeltaUs = 40_000,
            rejectReason = "rgbd_out_of_sync",
            deviceId = "depth-a",
            colorDeviceId = "color-a",
            logId = "restore-sync-reject",
        )

        override suspend fun vinPreviewCalibration(
            depthSerial: String,
            colorSerial: String,
            depthWidth: Int,
            depthHeight: Int,
            colorWidth: Int,
            colorHeight: Int,
        ): Envelope<VinPreviewCalibrationResponse> {
            previewCalibrationKey = VinPreviewCalibrationKey(
                depthSerial,
                colorSerial,
                depthWidth,
                depthHeight,
                colorWidth,
                colorHeight,
            )
            return Envelope(code = 0, data = previewCalibrationResponse)
        }

        override suspend fun vinRecognize(image: MultipartBody.Part): Envelope<VinRecognizeResponse> {
            recognizeImage = image
            return Envelope(
                code = 0,
                data = VinRecognizeResponse(
                    provider = "gosmart",
                    vin = recognizeVin,
                    confidence = recognizeConfidence,
                    characterScores = recognizeScores,
                    characterCount = recognizeVin.length,
                    logId = "log-1",
                    inferMs = 327,
                    characterCrops = recognizeCharacterCrops ?: defaultCharacterCrops(),
                ),
            )
        }

        fun defaultCharacterCrops(): List<VinCharacterCropResponse> =
            recognizeVin.mapIndexed { index, character ->
                VinCharacterCropResponse(
                    position = index + 1,
                    character = character.toString(),
                    image = recognizeCropImage,
                )
            }

        override suspend fun vinRestore(
            rgb: MultipartBody.Part,
            depth: MultipartBody.Part,
            depthW: RequestBody,
            depthH: RequestBody,
            fx: RequestBody,
            fy: RequestBody,
            cx: RequestBody,
            cy: RequestBody,
            deviceId: RequestBody,
            colorDeviceId: RequestBody,
            colorW: RequestBody,
            colorH: RequestBody,
            colorTimestampUs: RequestBody,
            depthTimestampUs: RequestBody,
        ): Envelope<VinRestoreResponse> {
            if (!restoreEnabled) error("本测试不应调用 vinRestore")
            this.depthDeviceId = deviceId.utf8()
            this.colorDeviceId = colorDeviceId.utf8()
            this.colorProfile = "${colorW.utf8()}x${colorH.utf8()}"
            this.colorTimestampUs = colorTimestampUs.utf8()
            this.depthTimestampUs = depthTimestampUs.utf8()
            return Envelope(
                code = 0,
                data = restoreResponse,
            )
        }

        private fun RequestBody.utf8(): String = Buffer().also { writeTo(it) }.readUtf8()
    }

    private companion object {
        fun factoryPreviewResponse() = VinPreviewCalibrationResponse(
            contractVersion = 1,
            projectionModel = "vincreator_factory_v3",
            occlusionMetric = "absolute_camera_z",
            key = VinPreviewCalibrationKeyResponse(
                depthSerial = "BF301208",
                colorSerial = "202303111518",
                depthWidth = 640,
                depthHeight = 128,
                colorWidth = 4160,
                colorHeight = 832,
            ),
            calibrationSha256 = FACTORY_CALIBRATION_SHA256,
            calibrationVersion = 3,
            depth = VinPreviewDepthCalibrationResponse(
                sampleFormat = "disparity_x8_u16",
                dataType = 1,
                referenceWidth = 1280,
                referenceHeight = 256,
                principalColumn = 324.0,
                principalRow = 65.43250274658203,
                projectionFocalX = 614.60498046875,
                projectionFocalY = 614.60498046875,
                disparityFocal = 1229.2099609375,
                baselineMm = 49.98929977416992,
                disparityUnit = 0.125,
                validDepthMinMm = 50.0,
                validDepthMaxMm = 1000.0,
            ),
            color = VinPreviewColorCalibrationResponse(
                principalRow = 1274.610937612,
                principalColumn = 2119.555128713,
                focalRow = 5737.022753971,
                focalColumn = 5642.090890116,
                distortion = listOf(
                    1.282934287418e-08,
                    1.624058936172e-05,
                    4.424457479974e-07,
                    -1.42047938331e-05,
                    -1.777752630382e-07,
                ),
                rotation = listOf(
                    0.988181353727503, -0.001554393417785, 0.153281427467200,
                    0.000002789863576, -0.999948403706616, -0.010158243785565,
                    0.153289308620975, 0.010038614729785, -0.988130362895914,
                ),
                translationMm = listOf(1.475623094293, 24.99666656691, -8.735002017036),
            ),
        )

        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
        const val FACTORY_CALIBRATION_SHA256 =
            "1a87dc030c50d532503218fbb026a453b2c0fa9b17df5316da60782d8d7bf5d2"
        const val TEST_ONE_PIXEL_WEBP_BASE64 =
            "UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA"
        const val TEST_CHARACTER_WEBP_BASE64 =
            "UklGRhIDAABXRUJQVlA4IAYDAABQEACdASpAAIAAAAAAJaQAT279Y/Fn9qu0z8Fet/E7Zhfd/yq5E/Tt+N3qn/sf41/kBnMXzX+x/lBqNP5O8yLGZ/efyA+Bj/M+2b35fTv+o9hr+lf4j8uv7uZw/l4x1JX0nlhWCj5ztqFGwvV425m2ILVF+5dNuMwJ6AGslk7zIi9StoK4sL0hiegAAP7//5pv9Sf7BX0OTMZRf5i6M7RoJn3voJ60apUtARdTmglBLw/b+i9KdZLx9ke5JOjn+GmdDxW+v/gwzypthub9HPpnATfsTLKos2yMZmgF/iROFeEPnFaxvTMGk3Az9A08eNJT7LZS0qANd7z1BfQbl1xjWbLz6Dlo/bduP8Gp7tHXy1wVvNI2rvF5jkOCZNdacqb9Hl6zyqsVerNXPpoRx9FKI0WrP2M5tcX5i0yPBs0zU4EtvJ2kBsKdEkI9xEyhq66RlpePvWLPke5Ap5QLLx3Olz2iYbgHZA5cD1UNq/TpiQ8sm1FsHGENDpMOJRoZqqu32UkGS8VGRcg46lXOSniLfGallVU8u1iuHVGLZ3Y/nxTWPOSOZexBw3yFsxPlfSSGehk01a65dQOuG2qOTg/j6XhZF0e4rvX9ElShha7w/JHU2/GDf5VC5bj0RnvJZ/6CHn57m5EB78FUo6hmtM/TjBYdjxsuSjrNawRv+C/DU1b/V3JzD4rZ0Moc9ylqko/Z9/tXR2VzciLedcOcIkY5A6Q1gNMe1VLClzqb8+ou1o+FnRjdQpEY1jHIyIvlsnlJ0c0lp2orsqKP5Y7M9Rj1uaRPVAwmRzOF4c5a/zQWod9TezPs7HH/tm+x+TUpZwaUALzc9sw331qmNA8DVX29N4cF/BnenSEmweHzcw7B842aCwuGocw6+gTUbw1eGAAhpYctF3buFrE5g6tZsXATUwh9Z5byXqRec2eTn3CQxJnMfS59uStt+Y6UH9Bt7+D+0u//lVVZueuBMPVohD+QVpAF9O5Xb4M1sP/+bfbObmh40N6uqqUXfUR/0XuBL/sCaUGPwAA="
    }
}
