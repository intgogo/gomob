package io.gomob.feature.scan3d

import io.gomob.model.ColorFrame
import io.gomob.model.DepthFrame
import io.gomob.model.RgbdFramePair
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

internal data class VinRgbdBurstResult(
    val pair: RgbdFramePair?,
    val colorCount: Int,
    val depthCount: Int,
    val bestDeltaUs: Long?,
    val timedOut: Boolean,
)

/**
 * VIN 两颗独立 USB 相机的回调时间配对器。
 *
 * 两路帧都必须使用 native 收帧回调的同一 host 单调时钟。普通 [snapshot] 只看仍然新鲜的缓存；快门
 * [awaitBurst] 则建立点击后的独立采集事务：跳过指定彩色帧，至少收齐多张彩色和深度，再从整批候选中
 * 选回调时间差全局最小的一对。超过回调窗的帧不下发，连续快门也不会复用点击前数据。
 *
 * 回调时间不是传感器曝光时间。本类只能复刻并改进 VINCreator 的软件 burst，不能把结果冒充硬同步。
 */
internal class VinRgbdPairer(
    private val maxDeltaUs: Long,
    private val maxAgeUs: Long,
    private val colorCapacity: Int = 12,
    private val depthCapacity: Int = 12,
    private val monotonicNowUs: () -> Long = { System.nanoTime() / 1_000L },
) {
    private val colors = ArrayDeque<ColorFrame>()
    private val depths = ArrayDeque<DepthFrame>()
    private val revisionCounter = AtomicLong(0L)
    private val revisions = MutableStateFlow(0L)

    init {
        require(maxDeltaUs > 0) { "maxDeltaUs 必须大于 0" }
        require(maxAgeUs > 0) { "maxAgeUs 必须大于 0" }
        require(colorCapacity >= 2) { "colorCapacity 至少为 2" }
        require(depthCapacity >= 2) { "depthCapacity 至少为 2" }
    }

    @Synchronized
    fun offerColor(frame: ColorFrame) {
        appendBounded(colors, frame, colorCapacity)
        signalFrameArrival()
    }

    @Synchronized
    fun offerDepth(frame: DepthFrame) {
        appendBounded(depths, frame, depthCapacity)
        signalFrameArrival()
    }

    @Synchronized
    fun snapshot(minTimestampUs: Long = Long.MIN_VALUE): RgbdFramePair? {
        val nowUs = monotonicNowUs()
        var best: RgbdFramePair? = null
        for (color in colors) {
            if (color.timestampUs < minTimestampUs) continue
            val colorAgeUs = nowUs - color.timestampUs
            if (colorAgeUs !in 0..maxAgeUs) continue
            val depth = depths
                .asSequence()
                .filter { it.timestampUs >= minTimestampUs }
                .filter { nowUs - it.timestampUs in 0..maxAgeUs }
                .minByOrNull { timestampDeltaUs(it.timestampUs, color.timestampUs) }
                ?: continue
            val deltaUs = timestampDeltaUs(color.timestampUs, depth.timestampUs)
            if (deltaUs > maxDeltaUs) continue
            val candidate = RgbdFramePair(color, depth, deltaUs)
            val current = best
            if (
                current == null ||
                color.timestampUs > current.color.timestampUs ||
                (color.timestampUs == current.color.timestampUs && deltaUs < current.timestampDeltaUs)
            ) {
                best = candidate
            }
        }
        return best
    }

    suspend fun awaitSnapshot(minTimestampUs: Long, timeoutMs: Long): RgbdFramePair? {
        require(timeoutMs > 0) { "timeoutMs 必须大于 0" }
        return withTimeoutOrNull(timeoutMs) {
            var observedRevision = revisions.value
            while (true) {
                snapshot(minTimestampUs)?.let { return@withTimeoutOrNull it }
                observedRevision = revisions.first { it != observedRevision }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
    }

    /**
     * 等待一次完整快门 burst。
     *
     * [minTimestampUs] 是点击水位，严格只接受晚于水位的新帧。[skipColorFrames] 对齐 VINCreator 在快门后
     * 丢弃 3 张 HLSD8 帧的行为；收齐 [minColorFrames] 与 [minDepthFrames] 后才做全局最小差配对。
     * burst 内的早期帧即使等待期间超过 [maxAgeUs] 也仍属于本次事务，不按普通预览新鲜度误删。
     */
    suspend fun awaitBurst(
        minTimestampUs: Long,
        skipColorFrames: Int,
        minColorFrames: Int,
        minDepthFrames: Int,
        timeoutMs: Long,
    ): VinRgbdBurstResult {
        require(skipColorFrames >= 0) { "skipColorFrames 不能小于 0" }
        require(minColorFrames > 0) { "minColorFrames 必须大于 0" }
        require(minDepthFrames > 0) { "minDepthFrames 必须大于 0" }
        require(timeoutMs > 0) { "timeoutMs 必须大于 0" }
        require(colorCapacity >= skipColorFrames + minColorFrames) {
            "colorCapacity 必须容纳跳帧和候选帧"
        }
        require(depthCapacity >= minDepthFrames) { "depthCapacity 必须容纳候选帧" }

        val completed = withTimeoutOrNull(timeoutMs) {
            var observedRevision = revisions.value
            while (true) {
                val result = burstSnapshot(
                    minTimestampUs = minTimestampUs,
                    skipColorFrames = skipColorFrames,
                    minColorFrames = minColorFrames,
                    minDepthFrames = minDepthFrames,
                    timedOut = false,
                )
                if (result.colorCount >= minColorFrames && result.depthCount >= minDepthFrames) {
                    return@withTimeoutOrNull result
                }
                observedRevision = revisions.first { it != observedRevision }
            }
            @Suppress("UNREACHABLE_CODE")
            null
        }
        return completed ?: burstSnapshot(
            minTimestampUs = minTimestampUs,
            skipColorFrames = skipColorFrames,
            minColorFrames = minColorFrames,
            minDepthFrames = minDepthFrames,
            timedOut = true,
        )
    }

    @Synchronized
    fun nearestDeltaUs(minTimestampUs: Long = Long.MIN_VALUE): Long? {
        val nowUs = monotonicNowUs()
        val newestColor = colors
            .asSequence()
            .filter { it.timestampUs >= minTimestampUs }
            .filter { nowUs - it.timestampUs in 0..maxAgeUs }
            .maxByOrNull { it.timestampUs }
            ?: return null
        return depths
            .asSequence()
            .filter { it.timestampUs >= minTimestampUs }
            .filter { nowUs - it.timestampUs in 0..maxAgeUs }
            .minOfOrNull { timestampDeltaUs(it.timestampUs, newestColor.timestampUs) }
    }

    fun nowUs(): Long = monotonicNowUs()

    @Synchronized
    fun clear() {
        colors.clear()
        depths.clear()
        signalFrameArrival()
    }

    @Synchronized
    private fun burstSnapshot(
        minTimestampUs: Long,
        skipColorFrames: Int,
        minColorFrames: Int,
        minDepthFrames: Int,
        timedOut: Boolean,
    ): VinRgbdBurstResult {
        val colorCandidates = colors
            .asSequence()
            .filter { it.timestampUs > minTimestampUs }
            .sortedBy { it.timestampUs }
            .drop(skipColorFrames)
            .toList()
        val depthCandidates = depths
            .asSequence()
            .filter { it.timestampUs > minTimestampUs }
            .sortedBy { it.timestampUs }
            .toList()

        var bestColor: ColorFrame? = null
        var bestDepth: DepthFrame? = null
        var bestDeltaUs: Long? = null
        var bestNewestTimestampUs = Long.MIN_VALUE
        for (color in colorCandidates) {
            for (depth in depthCandidates) {
                val deltaUs = timestampDeltaUs(color.timestampUs, depth.timestampUs)
                val newestTimestampUs = maxOf(color.timestampUs, depth.timestampUs)
                val currentBestDeltaUs = bestDeltaUs
                if (
                    currentBestDeltaUs == null ||
                    deltaUs < currentBestDeltaUs ||
                    (deltaUs == currentBestDeltaUs && newestTimestampUs > bestNewestTimestampUs)
                ) {
                    bestColor = color
                    bestDepth = depth
                    bestDeltaUs = deltaUs
                    bestNewestTimestampUs = newestTimestampUs
                }
            }
        }

        val complete = colorCandidates.size >= minColorFrames && depthCandidates.size >= minDepthFrames
        val acceptedPair = if (complete && bestDeltaUs != null && bestDeltaUs <= maxDeltaUs) {
            RgbdFramePair(requireNotNull(bestColor), requireNotNull(bestDepth), bestDeltaUs)
        } else {
            null
        }
        return VinRgbdBurstResult(
            pair = acceptedPair,
            colorCount = colorCandidates.size,
            depthCount = depthCandidates.size,
            bestDeltaUs = bestDeltaUs,
            timedOut = timedOut,
        )
    }

    private fun <T> appendBounded(queue: ArrayDeque<T>, value: T, capacity: Int) {
        queue.addLast(value)
        while (queue.size > capacity) queue.removeFirst()
    }

    private fun signalFrameArrival() {
        revisions.value = revisionCounter.incrementAndGet()
    }

    private fun timestampDeltaUs(a: Long, b: Long): Long = if (a >= b) a - b else b - a
}
