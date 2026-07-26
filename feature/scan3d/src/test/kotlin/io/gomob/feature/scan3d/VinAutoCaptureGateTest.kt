package io.gomob.feature.scan3d

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VinAutoCaptureGateTest {
    @Test
    fun `五个双路前进的稳定帧对在八百毫秒时只触发一次`() {
        val gate = VinAutoCaptureGate()

        repeat(4) { index ->
            val decision = gate.observe(readyObservation(index, distanceMm = 300.0 + index))
            assertThat(decision).isInstanceOf(VinAutoCaptureDecision.Stabilizing::class.java)
        }

        assertThat(gate.observe(readyObservation(4, distanceMm = 304.0)))
            .isEqualTo(VinAutoCaptureDecision.Trigger)
        assertThat(gate.observe(readyObservation(5, distanceMm = 303.0)))
            .isEqualTo(VinAutoCaptureDecision.Triggered)
    }

    @Test
    fun `重复帧对和单路前进都不能增加稳定计数`() {
        val gate = VinAutoCaptureGate()
        val first = readyObservation(0)
        assertStabilizing(gate.observe(first), frames = 1, durationUs = 0L)
        assertStabilizing(gate.observe(first), frames = 1, durationUs = 0L)
        assertStabilizing(
            gate.observe(first.copy(colorTimestampUs = first.colorTimestampUs + 200_000L)),
            frames = 1,
            durationUs = 0L,
        )
        assertStabilizing(
            gate.observe(first.copy(depthTimestampUs = first.depthTimestampUs + 200_000L)),
            frames = 1,
            durationUs = 0L,
        )
        assertStabilizing(gate.observe(readyObservation(1)), frames = 2, durationUs = 200_000L)
    }

    @Test
    fun `太远质量不足长断帧和距离跳变都会重新计时`() {
        val gate = VinAutoCaptureGate()
        assertStabilizing(gate.observe(readyObservation(0, 300.0)), 1, 0L)
        assertStabilizing(gate.observe(readyObservation(1, 302.0)), 2, 200_000L)

        assertThat(gate.observe(readyObservation(2, 450.0, tooFar = true)))
            .isEqualTo(VinAutoCaptureDecision.Waiting)
        assertStabilizing(gate.observe(readyObservation(3, 300.0)), 1, 0L)

        assertThat(gate.observe(insufficientObservation(4))).isEqualTo(VinAutoCaptureDecision.Waiting)
        assertStabilizing(gate.observe(readyObservation(5, 300.0)), 1, 0L)
        assertStabilizing(gate.observe(readyObservation(8, 301.0)), 1, 0L)
        assertStabilizing(gate.observe(readyObservation(9, 308.0)), 1, 0L)
    }

    @Test
    fun `reset后必须重新积累完整稳定窗口`() {
        val gate = VinAutoCaptureGate()
        repeat(5) { index -> gate.observe(readyObservation(index)) }
        gate.reset()

        repeat(4) { index ->
            assertThat(gate.observe(readyObservation(index + 10)))
                .isInstanceOf(VinAutoCaptureDecision.Stabilizing::class.java)
        }
        assertThat(gate.observe(readyObservation(14))).isEqualTo(VinAutoCaptureDecision.Trigger)
    }

    private fun readyObservation(
        index: Int,
        distanceMm: Double = 300.0,
        tooFar: Boolean = false,
    ): VinAutoCaptureObservation {
        val timestampUs = 1_000_000L + index * 200_000L
        val metrics = readyMetrics(if (tooFar) 450.0 else distanceMm)
        return VinAutoCaptureObservation(
            colorTimestampUs = timestampUs,
            depthTimestampUs = timestampUs + 50_000L,
            quality = if (tooFar) VinCaptureQuality.TooFar(metrics) else VinCaptureQuality.Ready(metrics),
        )
    }

    private fun insufficientObservation(index: Int): VinAutoCaptureObservation {
        val timestampUs = 1_000_000L + index * 200_000L
        val metrics = readyMetrics(300.0).copy(coverageRatio = 0.5, projectedPointRatio = 0.05)
        return VinAutoCaptureObservation(
            colorTimestampUs = timestampUs,
            depthTimestampUs = timestampUs + 50_000L,
            quality = VinCaptureQuality.Insufficient(metrics),
        )
    }

    private fun readyMetrics(distanceMm: Double) = VinDepthRoiMetrics(
        totalPixels = 100_000,
        validPixels = 98_000,
        coverageRatio = 0.98,
        projectedPoints = 18_000,
        projectedPointRatio = 0.18,
        distanceP10Mm = distanceMm - 10.0,
        distanceMedianMm = distanceMm,
        farEnoughRatio = 0.5,
    )

    private fun assertStabilizing(
        decision: VinAutoCaptureDecision,
        frames: Int,
        durationUs: Long,
    ) {
        assertThat(decision).isInstanceOf(VinAutoCaptureDecision.Stabilizing::class.java)
        decision as VinAutoCaptureDecision.Stabilizing
        assertThat(decision.readyFrames).isEqualTo(frames)
        assertThat(decision.stableDurationUs).isEqualTo(durationUs)
    }
}
