package io.gomob.feature.scan3d

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VinAutoCaptureGateTest {
    // 5fps 预览下 3 秒窗口 = 相邻帧 200ms × 15 个间隔，即第 16 帧（index 15）才够时长。
    private val framesToTrigger = 16

    @Test
    fun `连续稳定满三秒才触发且只触发一次`() {
        val gate = VinAutoCaptureGate()

        repeat(framesToTrigger - 1) { index ->
            val decision = gate.observe(readyObservation(index))
            assertThat(decision).isInstanceOf(VinAutoCaptureDecision.Stabilizing::class.java)
        }

        assertThat(gate.observe(readyObservation(framesToTrigger - 1)))
            .isEqualTo(VinAutoCaptureDecision.Trigger)
        assertThat(gate.observe(readyObservation(framesToTrigger)))
            .isEqualTo(VinAutoCaptureDecision.Triggered)
    }

    // 帧数早在第 5 帧就够了，3 秒时长才是真正的门；不能因为帧数满就提前拍。
    @Test
    fun `帧数够但时长不足三秒不得触发`() {
        val gate = VinAutoCaptureGate()
        repeat(framesToTrigger - 1) { index ->
            val decision = gate.observe(readyObservation(index))
            assertThat(decision).isInstanceOf(VinAutoCaptureDecision.Stabilizing::class.java)
        }
        val last = gate.observe(readyObservation(framesToTrigger - 2)) // 时间戳回退，不推进窗口
        assertThat(last).isNotEqualTo(VinAutoCaptureDecision.Trigger)
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
    fun `太远质量不足和长断帧都会重新计时`() {
        val gate = VinAutoCaptureGate()
        assertStabilizing(gate.observe(readyObservation(0, 300.0)), 1, 0L)
        assertStabilizing(gate.observe(readyObservation(1, 302.0)), 2, 200_000L)

        assertThat(gate.observe(readyObservation(2, 450.0, tooFar = true)))
            .isEqualTo(VinAutoCaptureDecision.Waiting)
        assertStabilizing(gate.observe(readyObservation(3, 300.0)), 1, 0L)

        assertThat(gate.observe(insufficientObservation(4))).isEqualTo(VinAutoCaptureDecision.Waiting)
        assertStabilizing(gate.observe(readyObservation(5, 300.0)), 1, 0L)
        // index 5 → 8 间隔 600ms，超过 450ms 断帧上限，重新计时。
        assertStabilizing(gate.observe(readyObservation(8, 301.0)), 1, 0L)
    }

    // 极差门是 7mm：恰好 7mm 仍算稳定，超过一点就重开窗口。边界写死，免得改参数时悄悄漂移。
    @Test
    fun `距离极差恰好七毫米仍稳定超过则重新计时`() {
        val gate = VinAutoCaptureGate()
        assertStabilizing(gate.observe(readyObservation(0, 300.0)), 1, 0L)
        assertStabilizing(gate.observe(readyObservation(1, 307.0)), 2, 200_000L)

        val exceeded = VinAutoCaptureGate()
        assertStabilizing(exceeded.observe(readyObservation(0, 300.0)), 1, 0L)
        assertStabilizing(exceeded.observe(readyObservation(1, 307.1)), 1, 0L)
    }

    // 稳到一半抖一下就得从头再来，不能把抖动前后的两段拼成一次「稳定 3 秒」。
    @Test
    fun `窗口中途距离跳变要从头累计整三秒`() {
        val gate = VinAutoCaptureGate()
        repeat(10) { index -> gate.observe(readyObservation(index, 300.0)) }
        assertStabilizing(gate.observe(readyObservation(10, 320.0)), 1, 0L)

        repeat(framesToTrigger - 2) { offset ->
            val decision = gate.observe(readyObservation(11 + offset, 320.0))
            assertThat(decision).isInstanceOf(VinAutoCaptureDecision.Stabilizing::class.java)
        }
        assertThat(gate.observe(readyObservation(11 + framesToTrigger - 2, 320.0)))
            .isEqualTo(VinAutoCaptureDecision.Trigger)
    }

    @Test
    fun `reset后必须重新积累完整稳定窗口`() {
        val gate = VinAutoCaptureGate()
        repeat(framesToTrigger) { index -> gate.observe(readyObservation(index)) }
        gate.reset()

        repeat(framesToTrigger - 1) { index ->
            assertThat(gate.observe(readyObservation(index + 100)))
                .isInstanceOf(VinAutoCaptureDecision.Stabilizing::class.java)
        }
        assertThat(gate.observe(readyObservation(100 + framesToTrigger - 1)))
            .isEqualTo(VinAutoCaptureDecision.Trigger)
    }

    // 倒计时必须从 3 起、走到 1、且不出现 0——读到 0 的那一刻已经在拍了。
    @Test
    fun `倒计时按秒从三读到一`() {
        assertThat(vinStabilizingCountdownSeconds(0L)).isEqualTo(3)
        assertThat(vinStabilizingCountdownSeconds(400_000L)).isEqualTo(3)
        assertThat(vinStabilizingCountdownSeconds(1_000_000L)).isEqualTo(2)
        assertThat(vinStabilizingCountdownSeconds(1_600_000L)).isEqualTo(2)
        assertThat(vinStabilizingCountdownSeconds(2_000_000L)).isEqualTo(1)
        assertThat(vinStabilizingCountdownSeconds(2_900_000L)).isEqualTo(1)
        assertThat(vinStabilizingCountdownSeconds(3_000_000L)).isEqualTo(1)
        assertThat(vinStabilizingCountdownSeconds(9_000_000L)).isEqualTo(1)
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
