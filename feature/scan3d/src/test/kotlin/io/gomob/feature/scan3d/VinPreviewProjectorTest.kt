package io.gomob.feature.scan3d

import com.google.common.truth.Truth.assertThat
import io.gomob.data.scan.VinPreviewCalibration
import io.gomob.data.scan.VinPreviewCalibrationKey
import io.gomob.data.scan.VinPreviewColorCalibration
import io.gomob.data.scan.VinPreviewDepthCalibration
import io.gomob.model.CameraIntrinsics
import io.gomob.model.DepthFrame
import io.gomob.model.DepthSampleFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import org.junit.Test

class VinPreviewProjectorTest {
    private val projector = VinPreviewProjector(factoryCalibration())

    @Test
    fun `Kotlin投影与服务端三个原厂固定向量一致`() {
        val vectors = listOf(
            Vector(324, 65, 279.38392092918923, 62.21943173082844),
            Vector(344, 55, 306.56828818086933, 48.60381214126974),
            Vector(304, 75, 252.1950535978917, 75.82674330390832),
        )

        vectors.forEach { vector ->
            val projected = requireNotNull(
                projector.projectCoordinate(1300, vector.column, vector.row, 640, 128),
            )
            assertThat(abs(projected.x - vector.x)).isLessThan(1e-9)
            assertThat(abs(projected.y - vector.y)).isLessThan(1e-9)
            assertThat(abs(projected.depthMm - 378.13750906277164)).isLessThan(1e-9)
        }
    }

    @Test
    fun `完整视差帧投到彩色预览并用三乘三填充`() {
        val width = 640
        val height = 128
        val data = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(width * height) { data.putShort(1300.toShort()) }
        data.flip()
        val frame = DepthFrame(
            timestampUs = 1,
            frameIndex = 1,
            width = width,
            height = height,
            data = data,
            intrinsics = CameraIntrinsics(614.60498046875, 614.60498046875, 324.0, 65.43250274658203, doubleArrayOf(), width, height),
            registeredToColor = false,
            sampleFormat = DepthSampleFormat.DISPARITY_X8_U16,
        )

        val result = requireNotNull(
            projector.project(
                frame = frame,
                outputWidth = 1040,
                outputHeight = 208,
                roi = productionRoi(),
            ),
        )

        assertThat(result.validDepthPoints).isEqualTo(width * height)
        assertThat(result.pointsInColorView).isGreaterThan(30_000)
        assertThat(result.coveredPixels.toDouble() / (result.width * result.height)).isGreaterThan(0.9)
        assertThat(result.pixels.count { it != 0 }).isEqualTo(result.coveredPixels)
        val metrics = requireNotNull(result.roiMetrics)
        assertThat(metrics.coverageRatio).isGreaterThan(VIN_CAPTURE_MIN_ROI_COVERAGE)
        assertThat(metrics.projectedPointRatio).isGreaterThan(VIN_CAPTURE_MIN_ROI_PROJECTED_POINT_RATIO)
        assertThat(metrics.farEnoughRatio).isEqualTo(1.0)
        assertThat(vinCaptureQuality(metrics)).isInstanceOf(VinCaptureQuality.Ready::class.java)
    }

    @Test
    fun `框内有效深度充分时近距离不额外禁拍`() {
        val frame = uniformDepthFrame(rawDisparityX8 = 1700)
        val result = requireNotNull(
            projector.project(frame, 1040, 208, productionRoi()),
        )
        val metrics = requireNotNull(result.roiMetrics)

        assertThat(metrics.coverageRatio).isGreaterThan(VIN_CAPTURE_MIN_ROI_COVERAGE)
        assertThat(metrics.projectedPointRatio).isGreaterThan(VIN_CAPTURE_MIN_ROI_PROJECTED_POINT_RATIO)
        assertThat(metrics.distanceP10Mm).isLessThan(VIN_GUIDANCE_DISTANCE_MM)
        assertThat(metrics.farEnoughRatio).isEqualTo(0.0)
        assertThat(vinCaptureQuality(metrics)).isInstanceOf(VinCaptureQuality.Ready::class.java)
    }

    @Test
    fun `无效深度不能被三乘三填充伪装成可拍`() {
        val result = requireNotNull(
            projector.project(
                uniformDepthFrame(rawDisparityX8 = 0),
                1040,
                208,
                productionRoi(),
            ),
        )
        val metrics = requireNotNull(result.roiMetrics)

        assertThat(metrics.coverageRatio).isEqualTo(0.0)
        assertThat(metrics.projectedPoints).isEqualTo(0)
        assertThat(metrics.distanceP10Mm).isNull()
        assertThat(vinCaptureQuality(metrics)).isInstanceOf(VinCaptureQuality.Insufficient::class.java)
    }

    @Test
    fun `质量门同时约束覆盖率原始点支撑和四十厘米上限`() {
        val ready = VinDepthRoiMetrics(
            totalPixels = 100_000,
            validPixels = 95_000,
            coverageRatio = VIN_CAPTURE_MIN_ROI_COVERAGE,
            projectedPoints = 15_000,
            projectedPointRatio = VIN_CAPTURE_MIN_ROI_PROJECTED_POINT_RATIO,
            distanceP10Mm = 250.0,
            distanceMedianMm = 265.0,
            farEnoughRatio = 0.0,
        )

        assertThat(vinCaptureQuality(ready)).isInstanceOf(VinCaptureQuality.Ready::class.java)
        assertThat(vinCaptureQuality(ready.copy(coverageRatio = 0.949999)))
            .isInstanceOf(VinCaptureQuality.Insufficient::class.java)
        assertThat(vinCaptureQuality(ready.copy(projectedPointRatio = 0.149999)))
            .isInstanceOf(VinCaptureQuality.Insufficient::class.java)
        assertThat(vinCaptureQuality(ready.copy(farEnoughRatio = 1.0)))
            .isInstanceOf(VinCaptureQuality.Ready::class.java)
        assertThat(vinCaptureQuality(ready.copy(distanceMedianMm = 399.9)))
            .isInstanceOf(VinCaptureQuality.Ready::class.java)
        assertThat(vinCaptureQuality(ready.copy(distanceMedianMm = 400.0)))
            .isInstanceOf(VinCaptureQuality.Ready::class.java)
        assertThat(vinCaptureQuality(ready.copy(distanceMedianMm = 400.1)))
            .isInstanceOf(VinCaptureQuality.TooFar::class.java)
        assertThat(
            vinCaptureQuality(
                ready.copy(
                    coverageRatio = 0.50,
                    projectedPointRatio = 0.05,
                    distanceMedianMm = 450.0,
                ),
            ),
        ).isInstanceOf(VinCaptureQuality.Insufficient::class.java)
        assertThat(vinCaptureQuality(null)).isEqualTo(VinCaptureQuality.Waiting)
    }

    @Test
    fun `超过四十厘米的高覆盖合成帧仍然判为太远`() {
        val result = requireNotNull(
            projector.project(uniformDepthFrame(rawDisparityX8 = 1100), 1040, 208, productionRoi()),
        )
        val metrics = requireNotNull(result.roiMetrics)

        assertThat(metrics.coverageRatio).isGreaterThan(VIN_CAPTURE_MIN_ROI_COVERAGE)
        assertThat(metrics.projectedPointRatio).isGreaterThan(VIN_CAPTURE_MIN_ROI_PROJECTED_POINT_RATIO)
        assertThat(metrics.distanceMedianMm).isGreaterThan(VIN_CAPTURE_MAX_DISTANCE_MM)
        assertThat(vinCaptureQuality(metrics)).isInstanceOf(VinCaptureQuality.TooFar::class.java)
        assertThat(vinCaptureGuidance(vinCaptureQuality(metrics))).isEqualTo(VIN_CAPTURE_TOO_FAR_GUIDANCE)
    }

    @Test
    fun `非原厂视差格式或错误档位不生成假对齐图`() {
        val data = ByteBuffer.allocateDirect(2).order(ByteOrder.LITTLE_ENDIAN).putShort(1300.toShort()).apply { flip() }
        val frame = DepthFrame(
            timestampUs = 1,
            frameIndex = 1,
            width = 1,
            height = 1,
            data = data,
            intrinsics = CameraIntrinsics(1.0, 1.0, 0.0, 0.0, doubleArrayOf(), 1, 1),
            registeredToColor = false,
            sampleFormat = DepthSampleFormat.MILLIMETERS_U16,
        )

        assertThat(projector.project(frame, 1040, 208)).isNull()
    }

    private fun uniformDepthFrame(rawDisparityX8: Int): DepthFrame {
        val width = 640
        val height = 128
        val data = ByteBuffer.allocateDirect(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        repeat(width * height) { data.putShort(rawDisparityX8.toShort()) }
        data.flip()
        return DepthFrame(
            timestampUs = 1,
            frameIndex = 1,
            width = width,
            height = height,
            data = data,
            intrinsics = CameraIntrinsics(
                614.60498046875,
                614.60498046875,
                324.0,
                65.43250274658203,
                doubleArrayOf(),
                width,
                height,
            ),
            registeredToColor = false,
            sampleFormat = DepthSampleFormat.DISPARITY_X8_U16,
        )
    }

    private fun productionRoi(): VinPreviewRoi = requireNotNull(
        vinPreviewRoi(
            viewportWidthPx = 411f,
            viewportHeightPx = 411f / VINCREATOR_VIEWPORT_ASPECT,
            imageAspect = VINCREATOR_STREAM_ASPECT,
            insetPx = 20f,
        ),
    )

    private data class Vector(val column: Int, val row: Int, val x: Double, val y: Double)

    private fun factoryCalibration() = VinPreviewCalibration(
        contractVersion = 1,
        projectionModel = "vincreator_factory_v3",
        occlusionMetric = "absolute_camera_z",
        key = VinPreviewCalibrationKey("BF301208", "202303111518", 640, 128, 4160, 832),
        calibrationSha256 = "1a87dc030c50d532503218fbb026a453b2c0fa9b17df5316da60782d8d7bf5d2",
        calibrationVersion = 3,
        depth = VinPreviewDepthCalibration(
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
        color = VinPreviewColorCalibration(
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
}
