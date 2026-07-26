package io.gomob.feature.scan3d

import com.google.common.truth.Truth.assertThat
import io.gomob.model.CameraIntrinsics
import io.gomob.model.ColorFrame
import io.gomob.model.DepthFrame
import java.nio.ByteBuffer
import org.junit.Test

class StereoCalibFramePairerTest {
    @Test
    fun threeStreamsSelectSameMomentInsteadOfIndependentLatestFrames() {
        val clock = TestClock(1_050_000)
        val pairer = StereoCalibFramePairer(25_000, 250_000, monotonicNowUs = clock::nowUs)
        pairer.offerLprime(color(1_000_000, 10, "EYS3D_RGB24"))
        pairer.offerDepth(depth(1_001_000, 10))
        pairer.offerLprime(color(1_040_000, 11, "EYS3D_RGB24"))
        pairer.offerDepth(depth(1_041_000, 11))
        pairer.offerHlsd8(color(1_005_000, 20, "HLSD8_MJPEG"))

        val set = pairer.snapshot()
        assertThat(set).isNotNull()
        assertThat(set!!.lprime.frameIndex).isEqualTo(10)
        assertThat(set.depth.frameIndex).isEqualTo(10)
        assertThat(set.hlsd8LprimeDeltaUs).isEqualTo(5_000)
        assertThat(set.lprimeDepthDeltaUs).isEqualTo(1_000)
    }

    @Test
    fun anyOverWindowLegRejectsCalibrationCapture() {
        val clock = TestClock(2_040_000)
        val pairer = StereoCalibFramePairer(10_000, 250_000, monotonicNowUs = clock::nowUs)
        pairer.offerHlsd8(color(2_000_000, 1, "HLSD8_MJPEG"))
        pairer.offerLprime(color(2_006_000, 1, "EYS3D_RGB24"))
        pairer.offerDepth(depth(2_030_000, 1))

        assertThat(pairer.snapshot()).isNull()
    }

    @Test
    fun staleTripleCannotBeCaptured() {
        val clock = TestClock(3_200_000)
        val pairer = StereoCalibFramePairer(25_000, 100_000, monotonicNowUs = clock::nowUs)
        pairer.offerHlsd8(color(3_000_000, 1, "HLSD8_MJPEG"))
        pairer.offerLprime(color(3_001_000, 1, "EYS3D_RGB24"))
        pairer.offerDepth(depth(3_002_000, 1))

        assertThat(pairer.snapshot()).isNull()
    }

    private fun color(timestampUs: Long, frameIndex: Int, pixelType: String) = ColorFrame(
        timestampUs = timestampUs,
        frameIndex = frameIndex,
        width = 2,
        height = 1,
        data = ByteBuffer.allocateDirect(6),
        pixelType = pixelType,
        intrinsics = intrinsics(),
        encodedJpeg = if (pixelType == "HLSD8_MJPEG") byteArrayOf(1, 2, 3) else null,
    )

    private fun depth(timestampUs: Long, frameIndex: Int) = DepthFrame(
        timestampUs = timestampUs,
        frameIndex = frameIndex,
        width = 2,
        height = 1,
        data = ByteBuffer.allocateDirect(4),
        intrinsics = intrinsics(),
        registeredToColor = false,
    )

    private fun intrinsics() = CameraIntrinsics(
        fx = 1.0,
        fy = 1.0,
        cx = 1.0,
        cy = 0.5,
        distortion = DoubleArray(5),
        width = 2,
        height = 1,
    )

    private class TestClock(var value: Long) {
        fun nowUs(): Long = value
    }
}
