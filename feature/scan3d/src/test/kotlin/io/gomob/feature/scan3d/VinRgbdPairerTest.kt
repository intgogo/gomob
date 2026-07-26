package io.gomob.feature.scan3d

import com.google.common.truth.Truth.assertThat
import io.gomob.model.CameraIntrinsics
import io.gomob.model.ColorFrame
import io.gomob.model.DepthFrame
import java.nio.ByteBuffer
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class VinRgbdPairerTest {
    @Test
    fun colorArrivesAfterDecodeStillMatchesHistoricalNearestDepth() {
        val clock = TestClock(1_080_000)
        val pairer = VinRgbdPairer(25_000, 250_000, monotonicNowUs = clock::nowUs)
        pairer.offerDepth(depth(1_000_000, 1))
        pairer.offerDepth(depth(1_033_000, 2))
        pairer.offerDepth(depth(1_066_000, 3))

        pairer.offerColor(color(1_035_000, 9))

        val pair = pairer.snapshot()
        assertThat(pair).isNotNull()
        assertThat(pair!!.depth.frameIndex).isEqualTo(2)
        assertThat(pair.timestampDeltaUs).isEqualTo(2_000)
    }

    @Test
    fun overTolerancePairIsRejectedInsteadOfUsingLatestFrames() {
        val clock = TestClock(2_150_000)
        val pairer = VinRgbdPairer(100_000, 250_000, monotonicNowUs = clock::nowUs)
        pairer.offerDepth(depth(2_000_000, 1))
        pairer.offerColor(color(2_100_001, 1))

        assertThat(pairer.snapshot()).isNull()
        assertThat(pairer.nearestDeltaUs()).isEqualTo(100_001)
    }

    @Test
    fun independentFiveFpsStreamsAcceptFixedPhaseNearestPair() {
        val clock = TestClock(2_250_000)
        val pairer = VinRgbdPairer(100_000, 250_000, monotonicNowUs = clock::nowUs)
        pairer.offerColor(color(2_100_000, 1))
        pairer.offerDepth(depth(2_154_300, 1))

        val pair = pairer.snapshot()

        assertThat(pair).isNotNull()
        assertThat(pair!!.timestampDeltaUs).isEqualTo(54_300)
    }

    @Test
    fun fiveFpsHalfPeriodBoundaryIsInclusive() {
        val clock = TestClock(2_350_000)
        val pairer = VinRgbdPairer(100_000, 250_000, monotonicNowUs = clock::nowUs)
        pairer.offerColor(color(2_200_000, 1))
        pairer.offerDepth(depth(2_300_000, 1))

        assertThat(pairer.snapshot()!!.timestampDeltaUs).isEqualTo(100_000)
    }

    @Test
    fun stalePairCannotBeCapturedAfterStreamsStop() {
        val clock = TestClock(3_050_000)
        val pairer = VinRgbdPairer(25_000, 100_000, monotonicNowUs = clock::nowUs)
        pairer.offerDepth(depth(3_000_000, 1))
        pairer.offerColor(color(3_005_000, 1))

        assertThat(pairer.snapshot()).isNotNull()
        clock.value = 3_200_000
        assertThat(pairer.snapshot()).isNull()
    }

    @Test
    fun newerGoodPairReplacesOlderPair() {
        val clock = TestClock(4_040_000)
        val pairer = VinRgbdPairer(25_000, 250_000, monotonicNowUs = clock::nowUs)
        pairer.offerColor(color(4_000_000, 1))
        pairer.offerDepth(depth(4_004_000, 1))
        pairer.offerColor(color(4_030_000, 2))
        pairer.offerDepth(depth(4_032_000, 2))

        val pair = pairer.snapshot()
        assertThat(pair).isNotNull()
        assertThat(pair!!.color.frameIndex).isEqualTo(2)
        assertThat(pair.depth.frameIndex).isEqualTo(2)
        assertThat(pair.timestampDeltaUs).isEqualTo(2_000)
    }

    @Test
    fun laterWorseDepthCannotReplaceCloserDepthForSameColor() {
        val clock = TestClock(5_030_000)
        val pairer = VinRgbdPairer(25_000, 250_000, monotonicNowUs = clock::nowUs)
        pairer.offerColor(color(5_000_000, 1))
        pairer.offerDepth(depth(5_002_000, 1))
        pairer.offerDepth(depth(5_020_000, 2))

        val pair = pairer.snapshot()
        assertThat(pair).isNotNull()
        assertThat(pair!!.depth.frameIndex).isEqualTo(1)
        assertThat(pair.timestampDeltaUs).isEqualTo(2_000)
    }

    @Test
    fun newestColorAnchorsSnapshotWhenBothPairsAreInsideWindow() {
        val clock = TestClock(6_060_000)
        val pairer = VinRgbdPairer(25_000, 250_000, monotonicNowUs = clock::nowUs)
        pairer.offerColor(color(6_000_000, 1))
        pairer.offerDepth(depth(6_001_000, 1))
        pairer.offerColor(color(6_030_000, 2))
        pairer.offerDepth(depth(6_048_000, 2))

        val pair = pairer.snapshot()
        assertThat(pair).isNotNull()
        assertThat(pair!!.color.frameIndex).isEqualTo(2)
        assertThat(pair.depth.frameIndex).isEqualTo(2)
        assertThat(pair.timestampDeltaUs).isEqualTo(18_000)
    }

    @Test
    fun captureFreshnessUsesTheSameMonotonicClockOwnedByThePairer() {
        val clock = TestClock(7_050_000)
        val pairer = VinRgbdPairer(25_000, 100_000, monotonicNowUs = clock::nowUs)
        pairer.offerDepth(depth(7_000_000, 1))
        pairer.offerColor(color(7_005_000, 1))

        assertThat(pairer.snapshot()).isNotNull()
        clock.value += 200_000
        assertThat(pairer.snapshot()).isNull()
    }

    @Test
    fun awaitSnapshotIgnoresCachedFramesAndReturnsBothFramesAfterClick() = runTest {
        val clock = TestClock(8_080_000)
        val pairer = VinRgbdPairer(100_000, 250_000, monotonicNowUs = clock::nowUs)
        pairer.offerColor(color(8_000_000, 1))
        pairer.offerDepth(depth(8_054_300, 1))
        val requestUs = 8_060_000L

        val awaited = async { pairer.awaitSnapshot(requestUs, 500) }
        runCurrent()
        assertThat(awaited.isCompleted).isFalse()

        clock.value = 8_280_000
        pairer.offerColor(color(8_200_000, 2))
        pairer.offerDepth(depth(8_254_300, 2))
        runCurrent()

        val pair = awaited.await()
        assertThat(pair).isNotNull()
        assertThat(pair!!.color.frameIndex).isEqualTo(2)
        assertThat(pair.depth.frameIndex).isEqualTo(2)
        assertThat(pair.timestampDeltaUs).isEqualTo(54_300)
    }

    @Test
    fun awaitSnapshotTimesOutWhenOnlyOneStreamAdvances() = runTest {
        val clock = TestClock(9_100_000)
        val pairer = VinRgbdPairer(100_000, 250_000, monotonicNowUs = clock::nowUs)
        val awaited = async { pairer.awaitSnapshot(9_000_000, 500) }
        pairer.offerColor(color(9_050_000, 1))

        advanceTimeBy(501)

        assertThat(awaited.await()).isNull()
    }

    @Test
    fun nearestDeltaDoesNotReportStaleFrames() {
        val clock = TestClock(10_080_000)
        val pairer = VinRgbdPairer(100_000, 100_000, monotonicNowUs = clock::nowUs)
        pairer.offerColor(color(10_000_000, 1))
        pairer.offerDepth(depth(10_054_300, 1))
        assertThat(pairer.nearestDeltaUs()).isEqualTo(54_300)

        clock.value = 10_200_000

        assertThat(pairer.nearestDeltaUs()).isNull()
    }

    @Test
    fun clearImmediatelyInvalidatesFreshFramesBeforeNewScan() {
        val clock = TestClock(10_080_000)
        val pairer = VinRgbdPairer(100_000, 250_000, monotonicNowUs = clock::nowUs)
        pairer.offerColor(color(10_000_000, 1))
        pairer.offerDepth(depth(10_054_300, 1))
        assertThat(pairer.snapshot()).isNotNull()

        pairer.clear()

        assertThat(pairer.snapshot()).isNull()
        assertThat(pairer.nearestDeltaUs()).isNull()
    }

    @Test
    fun burstSkipsThreeColorFramesWaitsForThreeCandidatesAndChoosesGlobalMinimum() = runTest {
        val clock = TestClock(12_200_000)
        val pairer = VinRgbdPairer(
            maxDeltaUs = 100_000,
            maxAgeUs = 100_000,
            colorCapacity = 12,
            depthCapacity = 12,
            monotonicNowUs = clock::nowUs,
        )
        val awaited = async {
            pairer.awaitBurst(
                minTimestampUs = 11_000_000,
                skipColorFrames = 3,
                minColorFrames = 3,
                minDepthFrames = 3,
                timeoutMs = 1_000,
            )
        }
        listOf(11_154_000L, 11_354_000L, 11_554_000L, 11_790_000L, 11_950_000L, 12_104_000L)
            .forEachIndexed { index, timestampUs -> pairer.offerDepth(depth(timestampUs, index + 1)) }
        listOf(11_100_000L, 11_300_000L, 11_500_000L, 11_700_000L, 11_900_000L)
            .forEachIndexed { index, timestampUs -> pairer.offerColor(color(timestampUs, index + 1)) }
        runCurrent()

        assertThat(awaited.isCompleted).isFalse()

        pairer.offerColor(color(12_100_000, 6))
        runCurrent()
        val result = awaited.await()

        assertThat(result.timedOut).isFalse()
        assertThat(result.colorCount).isEqualTo(3)
        assertThat(result.depthCount).isEqualTo(6)
        assertThat(result.bestDeltaUs).isEqualTo(4_000)
        assertThat(result.pair!!.color.frameIndex).isEqualTo(6)
        assertThat(result.pair.depth.frameIndex).isEqualTo(6)
    }

    @Test
    fun burstFramesRemainValidAfterOrdinaryFreshnessWindowExpires() = runTest {
        val clock = TestClock(14_000_000)
        val pairer = VinRgbdPairer(100_000, 100_000, monotonicNowUs = clock::nowUs)
        pairer.offerColor(color(13_100_000, 1))
        pairer.offerColor(color(13_300_000, 2))
        pairer.offerColor(color(13_500_000, 3))
        pairer.offerDepth(depth(13_154_000, 1))
        pairer.offerDepth(depth(13_354_000, 2))
        pairer.offerDepth(depth(13_504_000, 3))

        val result = pairer.awaitBurst(
            minTimestampUs = 13_000_000,
            skipColorFrames = 0,
            minColorFrames = 3,
            minDepthFrames = 3,
            timeoutMs = 100,
        )

        assertThat(pairer.snapshot(13_000_000)).isNull()
        assertThat(result.pair).isNotNull()
        assertThat(result.bestDeltaUs).isEqualTo(4_000)
    }

    @Test
    fun burstStrictlyRejectsFramesAtClickWatermark() = runTest {
        val clock = TestClock(15_500_000)
        val pairer = VinRgbdPairer(100_000, 250_000, monotonicNowUs = clock::nowUs)
        pairer.offerColor(color(15_000_000, 1))
        pairer.offerDepth(depth(15_000_000, 1))
        val awaited = async {
            pairer.awaitBurst(
                minTimestampUs = 15_000_000,
                skipColorFrames = 0,
                minColorFrames = 1,
                minDepthFrames = 1,
                timeoutMs = 500,
            )
        }
        runCurrent()
        assertThat(awaited.isCompleted).isFalse()

        pairer.offerColor(color(15_200_000, 2))
        pairer.offerDepth(depth(15_204_000, 2))
        runCurrent()

        val result = awaited.await()
        assertThat(result.pair!!.color.frameIndex).isEqualTo(2)
        assertThat(result.pair.depth.frameIndex).isEqualTo(2)
    }

    @Test
    fun completeBurstOverCallbackWindowIsRejectedWithDiagnosticDelta() = runTest {
        val clock = TestClock(23_500_000)
        val pairer = VinRgbdPairer(100_000, 250_000, monotonicNowUs = clock::nowUs)
        repeat(3) { index ->
            val baseUs = 20_000_000L + index * 1_000_000L
            pairer.offerColor(color(baseUs, index + 1))
            pairer.offerDepth(depth(baseUs + 100_001L, index + 1))
        }

        val result = pairer.awaitBurst(
            minTimestampUs = 19_000_000,
            skipColorFrames = 0,
            minColorFrames = 3,
            minDepthFrames = 3,
            timeoutMs = 100,
        )

        assertThat(result.timedOut).isFalse()
        assertThat(result.pair).isNull()
        assertThat(result.bestDeltaUs).isEqualTo(100_001)
    }

    private fun color(timestampUs: Long, frameIndex: Int) = ColorFrame(
        timestampUs = timestampUs,
        frameIndex = frameIndex,
        width = 2,
        height = 1,
        data = ByteBuffer.allocateDirect(6),
        pixelType = "HLSD8_MJPEG",
        intrinsics = intrinsics(2, 1),
    )

    private fun depth(timestampUs: Long, frameIndex: Int) = DepthFrame(
        timestampUs = timestampUs,
        frameIndex = frameIndex,
        width = 2,
        height = 1,
        data = ByteBuffer.allocateDirect(4),
        intrinsics = intrinsics(2, 1),
        registeredToColor = false,
    )

    private fun intrinsics(width: Int, height: Int) = CameraIntrinsics(
        fx = 1.0,
        fy = 1.0,
        cx = 1.0,
        cy = 0.5,
        distortion = DoubleArray(5),
        width = width,
        height = height,
    )

    private class TestClock(var value: Long) {
        fun nowUs(): Long = value
    }
}
