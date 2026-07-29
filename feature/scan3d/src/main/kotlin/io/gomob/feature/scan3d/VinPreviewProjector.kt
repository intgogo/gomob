package io.gomob.feature.scan3d

import io.gomob.data.scan.VinPreviewCalibration
import io.gomob.model.DepthFrame
import io.gomob.model.DepthSampleFormat
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal const val VIN_CAPTURE_MIN_ROI_COVERAGE = 0.95
internal const val VIN_CAPTURE_MIN_ROI_PROJECTED_POINT_RATIO = 0.15
internal const val VIN_GUIDANCE_DISTANCE_MM = 300.0
internal const val VIN_CAPTURE_MAX_DISTANCE_MM = 400.0
internal const val VIN_CAPTURE_GUIDANCE = "请对准车架号区域，距离保持在 40cm 以内"
internal const val VIN_CAPTURE_TOO_FAR_GUIDANCE = "距离太远，请靠近至 40cm 以内"
internal const val VIN_AUTO_CAPTURE_STEADY_GUIDANCE = "请稳住不动，稳定后将自动拍摄识别"

/** 彩色预览图像域中的归一化取景框；绘框与深度质量统计共用同一份坐标。 */
internal data class VinPreviewRoi(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val isValid: Boolean
        get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            left in 0f..1f && top in 0f..1f && right in 0f..1f && bottom in 0f..1f &&
            right > left && bottom > top
}

/** 原厂投影后，彩色取景框内部的深度完整度与距离分布。 */
internal data class VinDepthRoiMetrics(
    val totalPixels: Int,
    val validPixels: Int,
    val coverageRatio: Double,
    val projectedPoints: Int,
    val projectedPointRatio: Double,
    val distanceP10Mm: Double?,
    val distanceMedianMm: Double?,
    val farEnoughRatio: Double,
)

/** 快门质量门；Waiting 不得退化为可拍。 */
internal sealed interface VinCaptureQuality {
    data object Waiting : VinCaptureQuality
    data class Insufficient(val metrics: VinDepthRoiMetrics) : VinCaptureQuality
    data class TooFar(val metrics: VinDepthRoiMetrics) : VinCaptureQuality
    data class Ready(val metrics: VinDepthRoiMetrics) : VinCaptureQuality
}

/** 距离只有在覆盖率和原始点支撑均充分时才可信。 */
internal fun vinHasReliableDistance(metrics: VinDepthRoiMetrics): Boolean =
    metrics.coverageRatio >= VIN_CAPTURE_MIN_ROI_COVERAGE &&
        metrics.projectedPointRatio >= VIN_CAPTURE_MIN_ROI_PROJECTED_POINT_RATIO &&
        metrics.distanceMedianMm?.isFinite() == true

internal fun vinCaptureQuality(metrics: VinDepthRoiMetrics?): VinCaptureQuality = when {
    metrics == null -> VinCaptureQuality.Waiting
    !vinHasReliableDistance(metrics) -> VinCaptureQuality.Insufficient(metrics)
    requireNotNull(metrics.distanceMedianMm) > VIN_CAPTURE_MAX_DISTANCE_MM -> VinCaptureQuality.TooFar(metrics)
    else -> VinCaptureQuality.Ready(metrics)
}

internal fun vinCaptureGuidance(quality: VinCaptureQuality): String = when (quality) {
    is VinCaptureQuality.TooFar -> VIN_CAPTURE_TOO_FAR_GUIDANCE
    else -> VIN_CAPTURE_GUIDANCE
}

/** 服务端原厂标定驱动的深度预览投影结果。 */
internal data class VinProjectedDepth(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val validDepthPoints: Int,
    val pointsInColorView: Int,
    val coveredPixels: Int,
    val roiMetrics: VinDepthRoiMetrics?,
)

/** 单个深度像素在彩色预览域中的连续坐标，供跨端固定向量测试。 */
internal data class VinPreviewCoordinate(
    val x: Double,
    val y: Double,
    val depthMm: Double,
    val cameraDistanceMm: Double,
)

/**
 * 把 RS-D550 mode25 原始视差逐像素投到 HLSD8 预览域。
 *
 * 轴顺序、FOV 与像素畸变完整复刻 VINCreator；输出只用于显示，原始 [DepthFrame] 不被改写。
 */
internal class VinPreviewProjector(
    private val calibration: VinPreviewCalibration,
) {
    private val depth = calibration.depth
    private val color = calibration.color
    private val rotation = color.rotation.toDoubleArray()
    private val translation = color.translationMm.toDoubleArray()
    private val distortion = color.distortion.toDoubleArray()

    fun project(
        frame: DepthFrame,
        outputWidth: Int,
        outputHeight: Int,
        roi: VinPreviewRoi? = null,
    ): VinProjectedDepth? {
        if (
            frame.sampleFormat != DepthSampleFormat.DISPARITY_X8_U16 ||
            frame.width != calibration.key.depthWidth ||
            frame.height != calibration.key.depthHeight ||
            outputWidth <= 0 || outputHeight <= 0
        ) {
            return null
        }
        val total = frame.width * frame.height
        val source = frame.data.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply { rewind() }
        if (source.remaining() != total * 2) return null

        val range = estimateDepthRange(source, total)
        val pixels = IntArray(outputWidth * outputHeight)
        val zBuffer = FloatArray(pixels.size) { Float.POSITIVE_INFINITY }
        val activeRoi = roi?.takeIf { it.isValid }
        val roiDepthHistogram = if (activeRoi != null) {
            IntArray(ceil(depth.validDepthMaxMm).toInt().coerceAtLeast(1) + 1)
        } else {
            null
        }
        var roiProjectedPoints = 0
        var roiFarEnoughPoints = 0
        var validDepthPoints = 0
        var pointsInColorView = 0

        for (index in 0 until total) {
            val raw = source.getShort(index * 2).toInt() and 0xFFFF
            if (raw == 0) continue
            val column = index % frame.width
            val row = index / frame.width
            val projected = projectCoordinate(raw, column, row, outputWidth, outputHeight) ?: continue
            validDepthPoints++
            if (projected.x < 0.0 || projected.x >= outputWidth || projected.y < 0.0 || projected.y >= outputHeight) {
                continue
            }
            pointsInColorView++
            if (
                activeRoi != null &&
                projected.x >= activeRoi.left * outputWidth && projected.x < activeRoi.right * outputWidth &&
                projected.y >= activeRoi.top * outputHeight && projected.y < activeRoi.bottom * outputHeight
            ) {
                roiProjectedPoints++
                if (projected.depthMm >= VIN_GUIDANCE_DISTANCE_MM) roiFarEnoughPoints++
                roiDepthHistogram?.let { histogram ->
                    histogram[projected.depthMm.roundToInt().coerceIn(histogram.indices)]++
                }
            }
            val centerX = projected.x.roundToInt()
            val centerY = projected.y.roundToInt()
            val colorArgb = turboColor(
                ((projected.depthMm - range.first) / (range.second - range.first)).toFloat().coerceIn(0f, 1f),
            )
            val cameraDistance = projected.cameraDistanceMm.toFloat()
            for (offsetY in -SPLAT_RADIUS..SPLAT_RADIUS) {
                val y = centerY + offsetY
                if (y !in 0 until outputHeight) continue
                for (offsetX in -SPLAT_RADIUS..SPLAT_RADIUS) {
                    val x = centerX + offsetX
                    if (x !in 0 until outputWidth) continue
                    val outputIndex = y * outputWidth + x
                    if (cameraDistance < zBuffer[outputIndex]) {
                        zBuffer[outputIndex] = cameraDistance
                        pixels[outputIndex] = colorArgb
                    }
                }
            }
        }
        return VinProjectedDepth(
            width = outputWidth,
            height = outputHeight,
            pixels = pixels,
            validDepthPoints = validDepthPoints,
            pointsInColorView = pointsInColorView,
            coveredPixels = pixels.count { it != 0 },
            roiMetrics = if (activeRoi != null && roiDepthHistogram != null) {
                measureRoi(
                    pixels = pixels,
                    width = outputWidth,
                    height = outputHeight,
                    roi = activeRoi,
                    projectedPoints = roiProjectedPoints,
                    farEnoughPoints = roiFarEnoughPoints,
                    depthHistogram = roiDepthHistogram,
                )
            } else {
                null
            },
        )
    }

    internal fun projectCoordinate(
        rawDisparityX8: Int,
        column: Int,
        row: Int,
        outputWidth: Int,
        outputHeight: Int,
    ): VinPreviewCoordinate? {
        if (
            rawDisparityX8 <= 0 ||
            column !in 0 until calibration.key.depthWidth ||
            row !in 0 until calibration.key.depthHeight ||
            outputWidth <= 0 || outputHeight <= 0
        ) {
            return null
        }
        val z = depth.disparityFocal * depth.baselineMm / (rawDisparityX8 * depth.disparityUnit)
        if (z <= depth.validDepthMinMm || z >= depth.validDepthMaxMm || !z.isFinite()) return null

        val verticalUp = (depth.principalRow - row) * z / depth.projectionFocalY
        val horizontalRight = (column - depth.principalColumn) * z / depth.projectionFocalX
        val camera0 = rotation[0] * verticalUp + rotation[1] * horizontalRight + rotation[2] * z + translation[0]
        val camera1 = rotation[3] * verticalUp + rotation[4] * horizontalRight + rotation[5] * z + translation[1]
        val camera2 = rotation[6] * verticalUp + rotation[7] * horizontalRight + rotation[8] * z + translation[2]
        if (abs(camera2) <= 1e-12 || !camera2.isFinite()) return null

        var rowDelta = color.focalRow * camera0 / camera2
        var columnDelta = color.focalColumn * camera1 / camera2
        val radius = hypot(rowDelta, columnDelta)
        if (radius > 1e-12) {
            rowDelta = color.focalRow * atan(radius / color.focalRow) * rowDelta / radius
            columnDelta = color.focalColumn * atan(radius / color.focalColumn) * columnDelta / radius
        }

        val k = distortion[0]
        val p1 = distortion[1]
        val p2 = distortion[2]
        val s1 = distortion[3]
        val s2 = distortion[4]
        val radius2 = rowDelta * rowDelta + columnDelta * columnDelta
        val distortedRow = rowDelta + k * rowDelta * radius2 +
            p1 * (3 * rowDelta * rowDelta + columnDelta * columnDelta) +
            2 * p2 * rowDelta * columnDelta + s1 * radius2
        val distortedColumn = columnDelta + k * columnDelta * radius2 +
            p2 * (rowDelta * rowDelta + 3 * columnDelta * columnDelta) +
            2 * p1 * rowDelta * columnDelta + s2 * radius2
        val colorColumn = color.principalColumn + distortedColumn
        val colorRow = color.principalRow + distortedRow
        val x = colorColumn * outputWidth / calibration.key.colorWidth
        val y = colorRow * outputHeight / calibration.key.colorHeight
        if (!x.isFinite() || !y.isFinite()) return null
        return VinPreviewCoordinate(x, y, z, abs(camera2))
    }

    private fun estimateDepthRange(source: java.nio.ByteBuffer, total: Int): Pair<Double, Double> {
        val minDepth = depth.validDepthMinMm.toInt().coerceAtLeast(0)
        val maxDepth = depth.validDepthMaxMm.toInt().coerceAtLeast(minDepth + 1)
        val histogram = IntArray(maxDepth + 1)
        var valid = 0
        for (index in 0 until total) {
            val raw = source.getShort(index * 2).toInt() and 0xFFFF
            if (raw == 0) continue
            val z = depth.disparityFocal * depth.baselineMm / (raw * depth.disparityUnit)
            val millimeter = z.roundToInt()
            if (millimeter in minDepth..maxDepth) {
                histogram[millimeter]++
                valid++
            }
        }
        if (valid < MIN_RANGE_SAMPLES) return DEFAULT_RANGE_MIN_MM to DEFAULT_RANGE_MAX_MM
        val lowRank = max(1, (valid * 0.02).roundToInt())
        val highRank = max(lowRank + 1, (valid * 0.98).roundToInt())
        var accumulated = 0
        var low = minDepth
        for (millimeter in minDepth..maxDepth) {
            accumulated += histogram[millimeter]
            if (accumulated >= lowRank) {
                low = millimeter
                break
            }
        }
        accumulated = 0
        var high = maxDepth
        for (millimeter in minDepth..maxDepth) {
            accumulated += histogram[millimeter]
            if (accumulated >= highRank) {
                high = millimeter
                break
            }
        }
        if (high - low < MIN_RANGE_SPAN_MM) {
            val middle = (low + high) / 2
            low = max(minDepth, middle - MIN_RANGE_SPAN_MM / 2)
            high = min(maxDepth, low + MIN_RANGE_SPAN_MM)
        }
        return low.toDouble() to max(high, low + 1).toDouble()
    }

    private fun measureRoi(
        pixels: IntArray,
        width: Int,
        height: Int,
        roi: VinPreviewRoi,
        projectedPoints: Int,
        farEnoughPoints: Int,
        depthHistogram: IntArray,
    ): VinDepthRoiMetrics? {
        if (!roi.isValid || pixels.size != width * height) return null
        val left = floor((roi.left * width).toDouble()).toInt().coerceIn(0, width)
        val right = ceil((roi.right * width).toDouble()).toInt().coerceIn(left, width)
        val top = floor((roi.top * height).toDouble()).toInt().coerceIn(0, height)
        val bottom = ceil((roi.bottom * height).toDouble()).toInt().coerceIn(top, height)
        if (right <= left || bottom <= top) return null

        var validPixels = 0
        for (y in top until bottom) {
            val rowStart = y * width
            for (x in left until right) {
                if (pixels[rowStart + x] != 0) validPixels++
            }
        }
        val totalPixels = (right - left) * (bottom - top)
        val distanceP10Mm = histogramPercentile(depthHistogram, projectedPoints, 0.10)
        val distanceMedianMm = histogramPercentile(depthHistogram, projectedPoints, 0.50)
        return VinDepthRoiMetrics(
            totalPixels = totalPixels,
            validPixels = validPixels,
            coverageRatio = validPixels.toDouble() / totalPixels,
            projectedPoints = projectedPoints,
            projectedPointRatio = projectedPoints.toDouble() / totalPixels,
            distanceP10Mm = distanceP10Mm,
            distanceMedianMm = distanceMedianMm,
            farEnoughRatio = if (projectedPoints > 0) {
                farEnoughPoints.toDouble() / projectedPoints
            } else {
                0.0
            },
        )
    }

    private fun histogramPercentile(histogram: IntArray, sampleCount: Int, quantile: Double): Double? {
        if (sampleCount <= 0 || quantile !in 0.0..1.0) return null
        val rank = max(1, ceil(sampleCount * quantile).toInt())
        var accumulated = 0
        for (millimeter in histogram.indices) {
            accumulated += histogram[millimeter]
            if (accumulated >= rank) return millimeter.toDouble()
        }
        return null
    }

    private fun turboColor(t: Float): Int {
        val (red, green, blue) = when {
            t < 0.25f -> {
                val u = t / 0.25f
                Triple(lerp(48, 47, u), lerp(18, 173, u), lerp(59, 245, u))
            }
            t < 0.5f -> {
                val u = (t - 0.25f) / 0.25f
                Triple(lerp(47, 105, u), lerp(173, 235, u), lerp(245, 75, u))
            }
            t < 0.75f -> {
                val u = (t - 0.5f) / 0.25f
                Triple(lerp(105, 233, u), lerp(235, 197, u), lerp(75, 49, u))
            }
            else -> {
                val u = (t - 0.75f) / 0.25f
                Triple(lerp(233, 122, u), lerp(197, 4, u), lerp(49, 3, u))
            }
        }
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun lerp(start: Int, end: Int, t: Float): Int =
        (start + (end - start) * t).toInt().coerceIn(0, 255)

    private companion object {
        const val SPLAT_RADIUS = 1
        const val MIN_RANGE_SAMPLES = 32
        const val MIN_RANGE_SPAN_MM = 100
        const val DEFAULT_RANGE_MIN_MM = 200.0
        const val DEFAULT_RANGE_MAX_MM = 800.0
    }
}
