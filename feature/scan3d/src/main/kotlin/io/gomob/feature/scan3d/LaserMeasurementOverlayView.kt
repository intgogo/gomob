package io.gomob.feature.scan3d

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.roundToInt

private val VehicleColor = Color(0xFF4FD1FF)
private val CargoColor = Color(0xFFFFD54A)
private val AxleColor = Color(0xFFFF7A6B)
private val AxleDimensionColor = Color(0xFF9BE07A)
private val LabelTop = Color(0xF52C3842)
private val LabelBottom = Color(0xF50E141A)

/** 用户暂定 App 只显示线框；恢复点云文字前须同步产品确认与渲染 harness。 */
internal const val LASER_MEASUREMENT_TEXT_OVERLAY_ENABLED = false
internal const val LASER_MEASUREMENT_WIREFRAME_DESCRIPTION_PREFIX = "车辆外廓尺寸线框"

private data class ProjectedMeasurementLine(
    val from: Offset,
    val to: Offset,
    val style: MeasurementLineStyle,
    val arrowHeads: Boolean,
)

private data class ProjectedMeasurementLabel(
    val id: Int,
    val anchor: Offset,
    val name: String,
    val value: String,
    val style: MeasurementLabelStyle,
    val verticalOffsetPx: Float,
)

private data class ProjectedMeasurementScene(
    val lines: List<ProjectedMeasurementLine>,
    val labels: List<ProjectedMeasurementLabel>,
)

private data class LabelRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    fun intersects(other: LabelRect, gap: Int): Boolean =
        left < other.right + gap && right + gap > other.left &&
            top < other.bottom + gap && bottom + gap > other.top

    fun overlapArea(other: LabelRect): Int {
        val width = (minOf(right, other.right) - maxOf(left, other.left)).coerceAtLeast(0)
        val height = (minOf(bottom, other.bottom) - maxOf(top, other.top)).coerceAtLeast(0)
        return width * height
    }
}

/** 屏幕空间工程图叠加；无 pointerInput/clickable，不截断底层 SurfaceView 手势。 */
@Composable
internal fun VehicleMeasurementCloudOverlay(
    scene: VehicleMeasurementScene,
    projection: CameraProjectionSnapshot,
    reservedBottomPx: Int,
    modifier: Modifier = Modifier,
) {
    val projected = remember(scene, projection.revision, projection.viewportWidthPx, projection.viewportHeightPx) {
        projectScene(scene, projection)
    }
    Box(
        modifier = modifier.semantics {
            contentDescription =
                "$LASER_MEASUREMENT_WIREFRAME_DESCRIPTION_PREFIX ${projected.lines.size} 条"
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            val arrowLength = 7.dp.toPx()
            val arrowHalfWidth = 3.5.dp.toPx()
            projected.lines.forEach { line ->
                val color = lineColor(line.style)
                val alpha = lineAlpha(line.style)
                drawLine(
                    color = color,
                    start = line.from,
                    end = line.to,
                    strokeWidth = lineWidthDp(line.style).dp.toPx(),
                    cap = StrokeCap.Butt,
                    pathEffect = if (line.style.isExtension()) dash else null,
                    alpha = alpha,
                )
                if (line.arrowHeads) {
                    drawArrowHeads(
                        from = line.from,
                        to = line.to,
                        color = color.copy(alpha = alpha),
                        length = arrowLength,
                        halfWidth = arrowHalfWidth,
                    )
                }
            }
        }
        if (LASER_MEASUREMENT_TEXT_OVERLAY_ENABLED) {
            MeasurementLabelLayer(
                labels = projected.labels,
                reservedBottomPx = reservedBottomPx,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun projectScene(
    scene: VehicleMeasurementScene,
    projection: CameraProjectionSnapshot,
): ProjectedMeasurementScene {
    val lines = scene.lines.mapNotNull { line ->
        val from = projectMeasurementPoint(projection, line.from) ?: return@mapNotNull null
        val to = projectMeasurementPoint(projection, line.to) ?: return@mapNotNull null
        ProjectedMeasurementLine(
            from = Offset(from.x, from.y),
            to = Offset(to.x, to.y),
            style = line.style,
            arrowHeads = line.arrowHeads,
        )
    }
    val labels = scene.labels.mapIndexedNotNull { index, label ->
        val anchor = projectMeasurementPoint(projection, label.anchor) ?: return@mapIndexedNotNull null
        ProjectedMeasurementLabel(
            id = index,
            anchor = Offset(anchor.x, anchor.y),
            name = label.name,
            value = label.value,
            style = label.style,
            verticalOffsetPx = label.verticalOffsetPx,
        )
    }
    return ProjectedMeasurementScene(lines, labels)
}

@Composable
private fun MeasurementLabelLayer(
    labels: List<ProjectedMeasurementLabel>,
    reservedBottomPx: Int,
    modifier: Modifier,
) {
    Layout(
        modifier = modifier,
        content = {
            labels.forEach { label ->
                key(label.id) { MeasurementLabelChip(label) }
            }
        },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val margin = 4.dp.roundToPx()
        val gap = 4.dp.roundToPx()
        // 顶部保留固定 L/W/H 徽章，底部按结果卡实测高度避让；世界锚点不变，只移动文字徽章。
        val safeTop = 32.dp.roundToPx()
        val safeBottom = maxOf(96.dp.roundToPx(), reservedBottomPx + gap)
        val occupied = ArrayList<LabelRect>(placeables.size)
        val placements = placeables.mapIndexed { index, placeable ->
            val label = labels[index]
            val maxX = (constraints.maxWidth - margin - placeable.width).coerceAtLeast(margin)
            val maxY = (constraints.maxHeight - safeBottom - placeable.height).coerceAtLeast(safeTop)
            val desiredX = (label.anchor.x - placeable.width * 0.5f).roundToInt()
            val desiredY = (label.anchor.y + label.verticalOffsetPx - placeable.height * 0.5f)
                .roundToInt()
            val stepX = placeable.width + gap
            val stepY = placeable.height + gap
            val maxDxSteps = ((maxX - margin) / stepX + 1).coerceAtLeast(3)
            val maxDySteps = ((maxY - safeTop) / stepY + 1).coerceAtLeast(3)
            val visited = HashSet<LabelRect>((maxDxSteps * 2 + 1) * (maxDySteps * 2 + 1))
            var nearestFree: LabelRect? = null
            var nearestFreeDistance = Long.MAX_VALUE
            var leastOverlap: LabelRect? = null
            var leastOverlapArea = Int.MAX_VALUE
            var leastOverlapDistance = Long.MAX_VALUE
            for (dy in -maxDySteps..maxDySteps) {
                for (dx in -maxDxSteps..maxDxSteps) {
                    val x = (desiredX + dx * stepX).coerceIn(margin, maxX)
                    val y = (desiredY + dy * stepY).coerceIn(safeTop, maxY)
                    val candidate = LabelRect(x, y, x + placeable.width, y + placeable.height)
                    if (!visited.add(candidate)) continue
                    val distanceX = (x - desiredX).toLong()
                    val distanceY = (y - desiredY).toLong()
                    val distanceSq = distanceX * distanceX + distanceY * distanceY
                    val overlapArea = occupied.sumOf(candidate::overlapArea)
                    if (occupied.none { candidate.intersects(it, gap) }) {
                        if (distanceSq < nearestFreeDistance) {
                            nearestFree = candidate
                            nearestFreeDistance = distanceSq
                        }
                    } else if (
                        overlapArea < leastOverlapArea ||
                        overlapArea == leastOverlapArea && distanceSq < leastOverlapDistance
                    ) {
                        leastOverlap = candidate
                        leastOverlapArea = overlapArea
                        leastOverlapDistance = distanceSq
                    }
                }
            }
            val selected = nearestFree ?: leastOverlap ?: LabelRect(
                left = desiredX.coerceIn(margin, maxX),
                top = desiredY.coerceIn(safeTop, maxY),
                right = desiredX.coerceIn(margin, maxX) + placeable.width,
                bottom = desiredY.coerceIn(safeTop, maxY) + placeable.height,
            )
            occupied += selected
            selected.left to selected.top
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { index, placeable ->
                val (x, y) = placements[index]
                placeable.placeRelative(x = x, y = y)
            }
        }
    }
}

@Composable
private fun MeasurementLabelChip(label: ProjectedMeasurementLabel) {
    val shape = RoundedCornerShape(7.dp)
    val border = when (label.style) {
        MeasurementLabelStyle.DIMENSION -> AxleDimensionColor.copy(alpha = 0.55f)
        MeasurementLabelStyle.LWH -> VehicleColor.copy(alpha = 0.60f)
        MeasurementLabelStyle.CARGO -> CargoColor.copy(alpha = 0.60f)
    }
    val nameColor = when (label.style) {
        MeasurementLabelStyle.DIMENSION -> Color(0xBFD7F0CD)
        MeasurementLabelStyle.LWH -> Color(0xCCC8E8F8)
        MeasurementLabelStyle.CARGO -> Color(0xCCF8ECC8)
    }
    val valueColor = when (label.style) {
        MeasurementLabelStyle.DIMENSION -> Color(0xFFD9FFC8)
        MeasurementLabelStyle.LWH -> Color(0xFFD9F3FF)
        MeasurementLabelStyle.CARGO -> Color(0xFFFFEDB0)
    }
    Row(
        modifier = Modifier
            .semantics {
                contentDescription = "点云尺寸标注 " +
                    listOf(label.name, label.value).filter { it.isNotEmpty() }.joinToString(" ")
            }
            .shadow(7.dp, shape, ambientColor = Color.Black.copy(alpha = 0.65f), spotColor = Color.Black.copy(alpha = 0.65f))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(LabelTop, LabelBottom)))
            .border(1.dp, border, shape)
            .drawWithContent {
                drawContent()
                drawLine(
                    color = Color.White.copy(alpha = 0.40f),
                    start = Offset(7.dp.toPx(), 0.5.dp.toPx()),
                    end = Offset(size.width - 7.dp.toPx(), 0.5.dp.toPx()),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label.name.isNotEmpty()) {
            Text(label.name, fontSize = 10.sp, color = nameColor)
        }
        Text(
            text = label.value,
            fontSize = 12.5.sp,
            lineHeight = 15.625.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = valueColor,
        )
    }
}

private fun DrawScope.drawArrowHeads(
    from: Offset,
    to: Offset,
    color: Color,
    length: Float,
    halfWidth: Float,
) {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
    if (distance <= 1e-3f) return
    val ux = dx / distance
    val uy = dy / distance
    val px = -uy
    val py = ux
    drawArrowTriangle(from, ux, uy, px, py, length, halfWidth, color, reverse = false)
    drawArrowTriangle(to, ux, uy, px, py, length, halfWidth, color, reverse = true)
}

private fun DrawScope.drawArrowTriangle(
    tip: Offset,
    ux: Float,
    uy: Float,
    px: Float,
    py: Float,
    length: Float,
    halfWidth: Float,
    color: Color,
    reverse: Boolean,
) {
    val sign = if (reverse) -1f else 1f
    val baseX = tip.x + ux * length * sign
    val baseY = tip.y + uy * length * sign
    val path = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(baseX + px * halfWidth, baseY + py * halfWidth)
        lineTo(baseX - px * halfWidth, baseY - py * halfWidth)
        close()
    }
    drawPath(path, color)
}

private fun lineColor(style: MeasurementLineStyle): Color = when (style) {
    MeasurementLineStyle.VEHICLE_BOX,
    MeasurementLineStyle.LWH_DIMENSION,
    MeasurementLineStyle.LWH_EXTENSION,
    -> VehicleColor
    MeasurementLineStyle.CARGO_BOX,
    MeasurementLineStyle.CARGO_DIMENSION,
    MeasurementLineStyle.CARGO_EXTENSION,
    -> CargoColor
    MeasurementLineStyle.AXLE -> AxleColor
    MeasurementLineStyle.DIMENSION,
    MeasurementLineStyle.DIMENSION_EXTENSION,
    -> AxleDimensionColor
}

private fun lineAlpha(style: MeasurementLineStyle): Float = when (style) {
    MeasurementLineStyle.VEHICLE_BOX -> 0.90f
    MeasurementLineStyle.CARGO_BOX,
    MeasurementLineStyle.AXLE,
    MeasurementLineStyle.DIMENSION,
    MeasurementLineStyle.LWH_DIMENSION,
    MeasurementLineStyle.CARGO_DIMENSION,
    -> 0.95f
    MeasurementLineStyle.DIMENSION_EXTENSION,
    MeasurementLineStyle.LWH_EXTENSION,
    MeasurementLineStyle.CARGO_EXTENSION,
    -> 0.45f
}

private fun lineWidthDp(style: MeasurementLineStyle): Float = when (style) {
    MeasurementLineStyle.VEHICLE_BOX,
    MeasurementLineStyle.CARGO_BOX,
    -> 1.6f
    MeasurementLineStyle.AXLE -> 2.2f
    MeasurementLineStyle.DIMENSION,
    MeasurementLineStyle.LWH_DIMENSION,
    MeasurementLineStyle.CARGO_DIMENSION,
    -> 1.3f
    MeasurementLineStyle.DIMENSION_EXTENSION,
    MeasurementLineStyle.LWH_EXTENSION,
    MeasurementLineStyle.CARGO_EXTENSION,
    -> 1f
}

private fun MeasurementLineStyle.isExtension(): Boolean = when (this) {
    MeasurementLineStyle.DIMENSION_EXTENSION,
    MeasurementLineStyle.LWH_EXTENSION,
    MeasurementLineStyle.CARGO_EXTENSION,
    -> true
    else -> false
}
