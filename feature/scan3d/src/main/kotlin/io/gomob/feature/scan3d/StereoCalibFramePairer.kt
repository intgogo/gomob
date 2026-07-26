package io.gomob.feature.scan3d

import io.gomob.model.ColorFrame
import io.gomob.model.DepthFrame
import java.util.ArrayDeque

/** 同一时刻的 HLSD8 + L' + Depth 标定帧组。 */
internal data class StereoCalibFrameSet(
    val hlsd8: ColorFrame,
    val lprime: ColorFrame,
    val depth: DepthFrame,
    val hlsd8LprimeDeltaUs: Long,
    val lprimeDepthDeltaUs: Long,
)

/**
 * 双相机标定三路最近邻配对器。
 *
 * 标定外参对时间错配比普通拍摄更敏感：一张板在两张图里必须是同一姿态。
 * HLSD8 为锚点找最近 L'，再为该 L' 找同帧 depth；新鲜度与 native 统一使用 CLOCK_MONOTONIC，
 * 任一时间差超窗均拒绝采集。
 */
internal class StereoCalibFramePairer(
    private val maxDeltaUs: Long,
    private val maxAgeUs: Long,
    private val hlsd8Capacity: Int = 3,
    private val lprimeDepthCapacity: Int = 12,
    private val monotonicNowUs: () -> Long = { System.nanoTime() / 1_000L },
) {
    private val hlsd8Frames = ArrayDeque<ColorFrame>()
    private val lprimeFrames = ArrayDeque<ColorFrame>()
    private val depthFrames = ArrayDeque<DepthFrame>()

    init {
        require(maxDeltaUs > 0) { "maxDeltaUs 必须大于 0" }
        require(maxAgeUs > 0) { "maxAgeUs 必须大于 0" }
        require(hlsd8Capacity >= 2) { "hlsd8Capacity 至少为 2" }
        require(lprimeDepthCapacity >= 2) { "lprimeDepthCapacity 至少为 2" }
    }

    @Synchronized
    fun offerHlsd8(frame: ColorFrame) = appendBounded(hlsd8Frames, frame, hlsd8Capacity)

    @Synchronized
    fun offerLprime(frame: ColorFrame) = appendBounded(lprimeFrames, frame, lprimeDepthCapacity)

    @Synchronized
    fun offerDepth(frame: DepthFrame) = appendBounded(depthFrames, frame, lprimeDepthCapacity)

    @Synchronized
    fun snapshot(): StereoCalibFrameSet? {
        val nowUs = monotonicNowUs()
        var best: StereoCalibFrameSet? = null
        for (hlsd8 in hlsd8Frames) {
            if (nowUs - hlsd8.timestampUs !in 0..maxAgeUs) continue
            val lprime = lprimeFrames
                .asSequence()
                .filter { nowUs - it.timestampUs in 0..maxAgeUs }
                .minByOrNull { delta(it.timestampUs, hlsd8.timestampUs) }
                ?: continue
            val hlDelta = delta(hlsd8.timestampUs, lprime.timestampUs)
            if (hlDelta > maxDeltaUs) continue
            val depth = depthFrames
                .asSequence()
                .filter { nowUs - it.timestampUs in 0..maxAgeUs }
                .minByOrNull { delta(it.timestampUs, lprime.timestampUs) }
                ?: continue
            val ldDelta = delta(lprime.timestampUs, depth.timestampUs)
            if (ldDelta > maxDeltaUs) continue
            val candidate = StereoCalibFrameSet(hlsd8, lprime, depth, hlDelta, ldDelta)
            if (best == null || hlsd8.timestampUs > best.hlsd8.timestampUs) best = candidate
        }
        return best
    }

    private fun <T> appendBounded(queue: ArrayDeque<T>, value: T, capacity: Int) {
        queue.addLast(value)
        while (queue.size > capacity) queue.removeFirst()
    }

    private fun delta(a: Long, b: Long): Long = if (a >= b) a - b else b - a
}
