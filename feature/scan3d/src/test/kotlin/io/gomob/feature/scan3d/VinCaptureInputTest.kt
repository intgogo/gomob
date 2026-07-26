package io.gomob.feature.scan3d

import io.gomob.model.DepthSampleFormat
import io.gomob.nativebridge.camera.CameraSourceState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VinCaptureInputTest {
    @Test
    fun factoryInputRequiresFullResolutionAndCameraSerial() {
        assertNull(
            vinCaptureInputError(
                pixelType = "HLSD8_MJPEG",
                encodedWidth = 4160,
                encodedHeight = 832,
                depthDeviceSerial = "BF301208",
                colorDeviceSerial = "HLSD8-001",
            ),
        )
    }

    @Test
    fun previewResolutionIsRejectedInsteadOfUsingWrongCalibration() {
        assertEquals(
            "HLSD8 当前为 1280×256，必须使用原厂 4160×832 采集档",
            vinCaptureInputError("HLSD8_MJPEG", 1280, 256, "BF301208", "HLSD8-001"),
        )
    }

    @Test
    fun phoneModelCannotReplaceCameraSerial() {
        assertEquals(
            "未读取到深度相机序列号，无法选择逐设备标定",
            vinCaptureInputError("HLSD8_MJPEG", 4160, 832, null, "HLSD8-001"),
        )
    }

    @Test
    fun colorCameraSerialIsPartOfTheRigCalibrationKey() {
        assertEquals(
            "未读取到 HLSD8 彩色相机序列号，无法选择双相机标定",
            vinCaptureInputError("HLSD8_MJPEG", 4160, 832, "BF301208", null),
        )
    }

    @Test
    fun depthCameraColorFallbackIsRejected() {
        assertEquals(
            "未连接 HLSD8 彩色相机，当前画面不能用于 VIN 工厂正射",
            vinCaptureInputError("EYS3D_RGB24", 1280, 256, "BF301208", "HLSD8-001"),
        )
    }

    @Test
    fun metricDepthCannotBeMisreadAsFactoryDisparity() {
        assertEquals(
            "RS-D550 深度流不是原厂 mode25 视差格式，禁止送入 VIN 工厂还原",
            vinCaptureInputError(
                "HLSD8_MJPEG",
                4160,
                832,
                "BF301208",
                "HLSD8-001",
                DepthSampleFormat.MILLIMETERS_U16,
            ),
        )
    }

    @Test
    fun vinCreatorPreviewAndRestoreDimensionsRemainExact() {
        assertEquals(5f, VINCREATOR_STREAM_ASPECT, 0f)
        assertEquals(5f, VINCREATOR_VIEWPORT_ASPECT, 0f)
        assertEquals(4425, VINCREATOR_RESTORE_W)
        assertEquals(600, VINCREATOR_RESTORE_H)
        assertEquals(4425f / 600f, VINCREATOR_RESTORE_ASPECT, 0f)
    }

    @Test
    fun factoryRestoreBitmapIsDownsampledOnlyForDisplay() {
        assertEquals(4, vinRubbingPreviewSampleSize(4425, VIN_RUBBING_PREVIEW_MAX_W))
        assertEquals(1, vinRubbingPreviewSampleSize(1280, VIN_RUBBING_PREVIEW_MAX_W))
    }

    @Test
    fun nativeAspectPreviewDoesNotCreateLetterbox() {
        val rect = vinFitImageRect(viewportWidth = 1080f, viewportHeight = 1080f / 5f, imageAspect = 5f)

        assertEquals(0f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(1080f, rect.width, 0.001f)
        assertEquals(216f, rect.height, 0.001f)
    }

    @Test
    fun oddPixelRemainderUsesTheSameCenterAlignmentAsComposeImage() {
        val rect = vinFitImageRect(viewportWidth = 1156f, viewportHeight = 231f, imageAspect = 5f)

        assertEquals(1f, rect.left, 0.001f)
        assertEquals(0f, rect.top, 0.001f)
        assertEquals(1155f, rect.width, 0.001f)
        assertEquals(231f, rect.height, 0.001f)
    }

    @Test
    fun twentyDpFrameAndDepthGateShareTheSameNormalizedRoi() {
        val roi = requireNotNull(
            vinPreviewRoi(
                viewportWidthPx = 1080f,
                viewportHeightPx = 216f,
                imageAspect = 5f,
                insetPx = 60f,
            ),
        )

        assertEquals(60f / 1080f, roi.left, 0.0001f)
        assertEquals(60f / 216f, roi.top, 0.0001f)
        assertEquals(1f - 60f / 1080f, roi.right, 0.0001f)
        assertEquals(1f - 60f / 216f, roi.bottom, 0.0001f)
    }

    @Test
    fun depthPreviewReadyLabelDoesNotExposeAlignmentWording() {
        assertEquals("深度图", vinDepthPreviewLabel(VinPreviewAlignmentState.WaitingForRig))
        assertEquals("深度图（标定加载中）", vinDepthPreviewLabel(VinPreviewAlignmentState.Loading))
        assertEquals("深度图", vinDepthPreviewLabel(VinPreviewAlignmentState.Ready("sha", 3)))
        assertEquals("深度图（原始）", vinDepthPreviewLabel(VinPreviewAlignmentState.Unavailable("缺标定")))
    }

    @Test
    fun captureGuidanceMatchesProductWording() {
        assertEquals("请对准车架号区域，距离保持在 40cm 以内", VIN_CAPTURE_GUIDANCE)
        assertEquals("距离太远，请靠近至 40cm 以内", VIN_CAPTURE_TOO_FAR_GUIDANCE)
        assertEquals("请稳住不动，稳定后将自动拍摄识别", VIN_AUTO_CAPTURE_STEADY_GUIDANCE)
    }

    @Test
    fun depthMetricsExposeCoverageAndMedianDistance() {
        val metrics = VinDepthRoiMetrics(
            totalPixels = 100_000,
            validPixels = 98_200,
            coverageRatio = 0.982,
            projectedPoints = 17_118,
            projectedPointRatio = 0.17118,
            distanceP10Mm = 242.0,
            distanceMedianMm = 264.0,
            farEnoughRatio = 0.169,
        )

        assertEquals("实时 · 深度有效率 98.2% · 距离约 26.4cm", vinDepthMetricsText(metrics, captured = false))
        assertEquals("本次拍摄 · 深度有效率 98.2% · 距离约 26.4cm", vinDepthMetricsText(metrics, captured = true))
        assertEquals(
            "实时 · 深度有效率 70.0%",
            vinDepthMetricsText(metrics.copy(coverageRatio = 0.70), captured = false),
        )
        assertEquals(
            "实时 · 深度有效率 98.2%",
            vinDepthMetricsText(metrics.copy(projectedPointRatio = 0.10), captured = false),
        )
        assertEquals(
            "实时 · 深度有效率 98.2% · 距离约 45.0cm",
            vinDepthMetricsText(metrics.copy(distanceMedianMm = 450.0), captured = false),
        )
    }

    @Test
    fun shutterRequiresBothPhysicalFirstFrames() {
        val waitingDepth = vinCaptureReadiness(
            hasDedicatedColorSource = true,
            colorFrameReady = true,
            depthFrameReady = false,
            colorState = CameraSourceState.Streaming("HLSD8", 0, 0),
            depthState = CameraSourceState.Streaming("RS-D550", 640, 128),
        )
        assertFalse(waitingDepth.ready)
        assertEquals("正在等待 RS-D550 深度首帧…", waitingDepth.message)

        val ready = vinCaptureReadiness(
            hasDedicatedColorSource = true,
            colorFrameReady = true,
            depthFrameReady = true,
            colorState = CameraSourceState.Streaming("HLSD8", 0, 0),
            depthState = CameraSourceState.Streaming("RS-D550", 640, 128),
        )
        assertTrue(ready.ready)
        assertEquals("RGBD 双路已就绪", ready.message)
    }

    @Test
    fun cameraErrorIsShownBeforeCaptureStarts() {
        val state = vinCaptureReadiness(
            hasDedicatedColorSource = true,
            colorFrameReady = false,
            depthFrameReady = false,
            colorState = CameraSourceState.Error("USB 被占用"),
            depthState = CameraSourceState.Opening,
        )
        assertFalse(state.ready)
        assertEquals("HLSD8 彩色相机异常：USB 被占用", state.message)
    }
}
