package io.gomob.feature.scan3d

import kotlin.math.max

internal const val VIN_AUTO_CAPTURE_MIN_READY_FRAMES = 5
internal const val VIN_AUTO_CAPTURE_MIN_STABLE_US = 800_000L
internal const val VIN_AUTO_CAPTURE_MAX_READY_GAP_US = 450_000L
internal const val VIN_AUTO_CAPTURE_MAX_DISTANCE_SPAN_MM = 5.0

/** 自动拍摄只消费两路时间戳都前进的唯一 RGBD 帧对。 */
internal data class VinAutoCaptureObservation(
    val colorTimestampUs: Long,
    val depthTimestampUs: Long,
    val quality: VinCaptureQuality,
)

/** 5fps 预览的稳定判定结果。 */
internal sealed interface VinAutoCaptureDecision {
    data object Waiting : VinAutoCaptureDecision
    data class Stabilizing(
        val readyFrames: Int,
        val stableDurationUs: Long,
    ) : VinAutoCaptureDecision
    data object Trigger : VinAutoCaptureDecision
    data object Triggered : VinAutoCaptureDecision
}

/** 手动快门与自动快门共用的捕获来源。 */
internal enum class VinCaptureOrigin {
    Manual,
    Auto,
}

/**
 * 连续质量帧稳定门。
 *
 * Ready 只说明当前空间质量与距离达标；自动拍摄还要求连续唯一帧对、足够墙钟跨度，且 ROI 中位
 * 距离的极差不超过原厂稳定门的 5mm。单路时间戳未前进只忽略，不重复计数。
 */
internal class VinAutoCaptureGate(
    private val minReadyFrames: Int = VIN_AUTO_CAPTURE_MIN_READY_FRAMES,
    private val minStableUs: Long = VIN_AUTO_CAPTURE_MIN_STABLE_US,
    private val maxReadyGapUs: Long = VIN_AUTO_CAPTURE_MAX_READY_GAP_US,
    private val maxDistanceSpanMm: Double = VIN_AUTO_CAPTURE_MAX_DISTANCE_SPAN_MM,
) {
    private var firstPairTimestampUs = Long.MIN_VALUE
    private var lastPairTimestampUs = Long.MIN_VALUE
    private var lastColorTimestampUs = Long.MIN_VALUE
    private var lastDepthTimestampUs = Long.MIN_VALUE
    private var minDistanceMm = Double.POSITIVE_INFINITY
    private var maxDistanceMm = Double.NEGATIVE_INFINITY
    private var readyFrames = 0
    private var triggered = false

    init {
        require(minReadyFrames >= 2) { "自动拍摄至少需要两帧" }
        require(minStableUs > 0L) { "自动拍摄稳定时长必须大于 0" }
        require(maxReadyGapUs > 0L) { "自动拍摄最大帧间隔必须大于 0" }
        require(maxDistanceSpanMm >= 0.0) { "自动拍摄距离波动不能小于 0" }
    }

    fun observe(observation: VinAutoCaptureObservation): VinAutoCaptureDecision {
        if (triggered) return VinAutoCaptureDecision.Triggered
        val ready = observation.quality as? VinCaptureQuality.Ready
        val distanceMm = ready?.metrics?.distanceMedianMm
        if (
            ready == null || distanceMm == null || !distanceMm.isFinite() ||
            observation.colorTimestampUs <= 0L || observation.depthTimestampUs <= 0L
        ) {
            clearRun()
            return VinAutoCaptureDecision.Waiting
        }

        if (readyFrames > 0) {
            val samePair = observation.colorTimestampUs == lastColorTimestampUs &&
                observation.depthTimestampUs == lastDepthTimestampUs
            val onlyOneSourceAdvanced =
                (observation.colorTimestampUs == lastColorTimestampUs &&
                    observation.depthTimestampUs > lastDepthTimestampUs) ||
                    (observation.depthTimestampUs == lastDepthTimestampUs &&
                        observation.colorTimestampUs > lastColorTimestampUs)
            if (samePair || onlyOneSourceAdvanced) return currentStabilizingDecision()
        }

        val pairTimestampUs = max(observation.colorTimestampUs, observation.depthTimestampUs)
        val timestampsMovedBack = readyFrames > 0 &&
            (observation.colorTimestampUs < lastColorTimestampUs ||
                observation.depthTimestampUs < lastDepthTimestampUs)
        val pairGapTooLarge = readyFrames > 0 && pairTimestampUs - lastPairTimestampUs > maxReadyGapUs
        val nextMinDistanceMm = minOf(minDistanceMm, distanceMm)
        val nextMaxDistanceMm = maxOf(maxDistanceMm, distanceMm)
        val distanceBecameUnstable = readyFrames > 0 &&
            nextMaxDistanceMm - nextMinDistanceMm > maxDistanceSpanMm

        if (readyFrames == 0 || timestampsMovedBack || pairGapTooLarge || distanceBecameUnstable) {
            startRun(observation, pairTimestampUs, distanceMm)
        } else {
            readyFrames++
            lastColorTimestampUs = observation.colorTimestampUs
            lastDepthTimestampUs = observation.depthTimestampUs
            lastPairTimestampUs = pairTimestampUs
            minDistanceMm = nextMinDistanceMm
            maxDistanceMm = nextMaxDistanceMm
        }

        val durationUs = stableDurationUs()
        if (readyFrames >= minReadyFrames && durationUs >= minStableUs) {
            triggered = true
            return VinAutoCaptureDecision.Trigger
        }
        return VinAutoCaptureDecision.Stabilizing(readyFrames, durationUs)
    }

    /** 手动快门认领本轮后同样锁住自动触发，避免同一帧双拍。 */
    fun markTriggered() {
        triggered = true
    }

    fun reset() {
        triggered = false
        clearRun()
    }

    private fun startRun(
        observation: VinAutoCaptureObservation,
        pairTimestampUs: Long,
        distanceMm: Double,
    ) {
        firstPairTimestampUs = pairTimestampUs
        lastPairTimestampUs = pairTimestampUs
        lastColorTimestampUs = observation.colorTimestampUs
        lastDepthTimestampUs = observation.depthTimestampUs
        minDistanceMm = distanceMm
        maxDistanceMm = distanceMm
        readyFrames = 1
    }

    private fun clearRun() {
        firstPairTimestampUs = Long.MIN_VALUE
        lastPairTimestampUs = Long.MIN_VALUE
        lastColorTimestampUs = Long.MIN_VALUE
        lastDepthTimestampUs = Long.MIN_VALUE
        minDistanceMm = Double.POSITIVE_INFINITY
        maxDistanceMm = Double.NEGATIVE_INFINITY
        readyFrames = 0
    }

    private fun currentStabilizingDecision(): VinAutoCaptureDecision =
        if (readyFrames <= 0) {
            VinAutoCaptureDecision.Waiting
        } else {
            VinAutoCaptureDecision.Stabilizing(readyFrames, stableDurationUs())
        }

    private fun stableDurationUs(): Long =
        if (firstPairTimestampUs == Long.MIN_VALUE || lastPairTimestampUs == Long.MIN_VALUE) {
            0L
        } else {
            (lastPairTimestampUs - firstPairTimestampUs).coerceAtLeast(0L)
        }
}

/**
 * 自动工作流只发出一次捕获与一次识别许可；判废/上传错误锁存，只有用户手动重拍或重新扫描才能继续。
 */
internal class VinAutoCaptureWorkflow(
    private val gate: VinAutoCaptureGate = VinAutoCaptureGate(),
) {
    private enum class CapturePhase { Armed, Started, TerminalLocked }

    private var capturePhase = CapturePhase.Armed
    private var recognitionInFlight = false
    private var recognitionCompleted = false

    fun observe(observation: VinAutoCaptureObservation): VinAutoCaptureDecision =
        if (capturePhase == CapturePhase.Armed) gate.observe(observation) else VinAutoCaptureDecision.Triggered

    fun tryStartCapture(origin: VinCaptureOrigin): Boolean = when (capturePhase) {
        CapturePhase.Armed -> {
            capturePhase = CapturePhase.Started
            gate.markTriggered()
            true
        }
        CapturePhase.Started -> false
        CapturePhase.TerminalLocked -> {
            if (origin == VinCaptureOrigin.Auto) {
                false
            } else {
                capturePhase = CapturePhase.Started
                recognitionInFlight = false
                recognitionCompleted = false
                gate.markTriggered()
                true
            }
        }
    }

    /** burst 最终质量瞬时失败时没有形成采集，重新等待一段全新的稳定窗口。 */
    fun rearmAfterTransientQualityFailure() {
        capturePhase = CapturePhase.Armed
        recognitionInFlight = false
        recognitionCompleted = false
        gate.reset()
    }

    fun lockAfterCaptureFailure() {
        capturePhase = CapturePhase.TerminalLocked
        gate.markTriggered()
    }

    /** 还原成功后原子取得一次自动识别许可。 */
    fun onRestoreSuccess(): Boolean {
        capturePhase = CapturePhase.TerminalLocked
        return tryStartRecognition()
    }

    fun tryStartRecognition(): Boolean {
        if (recognitionInFlight || recognitionCompleted) return false
        recognitionInFlight = true
        return true
    }

    fun finishRecognition(success: Boolean) {
        recognitionInFlight = false
        recognitionCompleted = success
    }

    fun reset() {
        capturePhase = CapturePhase.Armed
        recognitionInFlight = false
        recognitionCompleted = false
        gate.reset()
    }
}
