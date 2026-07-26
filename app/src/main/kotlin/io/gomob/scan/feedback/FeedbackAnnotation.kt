package io.gomob.scan.feedback

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

internal const val FeedbackStrokeMaxPoints = 512
internal const val FeedbackStrokeSampleDistance = 0.003f
internal const val FeedbackStrokeMinSpan = 0.025f
internal const val FeedbackStrokeMinLength = 0.08f
internal const val FeedbackStrokeBoundsPadding = 0.006f
internal const val FeedbackStrokeMaxClosureGapRatio = 0.8f
internal const val FeedbackStrokeMinAreaRatio = 0.08f

internal data class FeedbackPoint(
    val x: Float,
    val y: Float,
)

internal data class FeedbackBounds(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
)

internal fun normalizedFeedbackPoint(
    xPx: Float,
    yPx: Float,
    widthPx: Int,
    heightPx: Int,
): FeedbackPoint {
    require(widthPx > 0 && heightPx > 0)
    return FeedbackPoint(
        x = (xPx / widthPx).coerceIn(0f, 1f),
        y = (yPx / heightPx).coerceIn(0f, 1f),
    )
}

internal fun appendFeedbackPoint(
    points: List<FeedbackPoint>,
    point: FeedbackPoint,
    minDistance: Float = FeedbackStrokeSampleDistance,
    maxPoints: Int = FeedbackStrokeMaxPoints,
): List<FeedbackPoint> {
    if (points.isEmpty()) return listOf(point)
    if (points.size >= maxPoints) return points
    val last = points.last()
    val dx = point.x - last.x
    val dy = point.y - last.y
    if (dx * dx + dy * dy < minDistance * minDistance) return points
    return points + point
}

internal fun feedbackBounds(
    points: List<FeedbackPoint>,
    padding: Float = FeedbackStrokeBoundsPadding,
): FeedbackBounds? {
    if (points.isEmpty()) return null
    val left = (points.minOf { it.x } - padding).coerceIn(0f, 1f)
    val top = (points.minOf { it.y } - padding).coerceIn(0f, 1f)
    val right = (points.maxOf { it.x } + padding).coerceIn(0f, 1f)
    val bottom = (points.maxOf { it.y } + padding).coerceIn(0f, 1f)
    return FeedbackBounds(
        x = left,
        y = top,
        w = (right - left).coerceAtLeast(0f),
        h = (bottom - top).coerceAtLeast(0f),
    )
}

internal fun isMeaningfulFeedbackStroke(points: List<FeedbackPoint>): Boolean {
    if (points.size < 6) return false
    val bounds = feedbackBounds(points, padding = 0f) ?: return false
    if (bounds.w < FeedbackStrokeMinSpan || bounds.h < FeedbackStrokeMinSpan) return false
    var length = 0f
    for (index in 1 until points.size) {
        val dx = points[index].x - points[index - 1].x
        val dy = points[index].y - points[index - 1].y
        length += sqrt(dx * dx + dy * dy)
    }
    if (length < FeedbackStrokeMinLength) return false

    val closeDx = points.last().x - points.first().x
    val closeDy = points.last().y - points.first().y
    val closeGap = sqrt(closeDx * closeDx + closeDy * closeDy)
    if (closeGap > max(bounds.w, bounds.h) * FeedbackStrokeMaxClosureGapRatio) return false

    var twiceArea = 0f
    for (index in points.indices) {
        val current = points[index]
        val next = points[(index + 1) % points.size]
        twiceArea += current.x * next.y - next.x * current.y
    }
    val area = abs(twiceArea) / 2f
    return area >= bounds.w * bounds.h * FeedbackStrokeMinAreaRatio
}
