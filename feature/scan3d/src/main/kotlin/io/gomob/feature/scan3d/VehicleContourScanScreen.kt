package io.gomob.feature.scan3d

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

const val SCAN_VEHICLE_ROUTE = "scan3d/vehicle"

private data class ScanAngle(
    val label: String,
    val name: String,
    val deg: Float,
)

private val VehicleAngles = listOf(
    ScanAngle("前", "正前", 0f),
    ScanAngle("右前", "右前 45°", 45f),
    ScanAngle("右", "正右", 90f),
    ScanAngle("右后", "右后 45°", 135f),
    ScanAngle("后", "正后", 180f),
    ScanAngle("左后", "左后 45°", 225f),
    ScanAngle("左", "正左", 270f),
    ScanAngle("左前", "左前 45°", 315f),
)

@Composable
fun VehicleContourScanRoute(onBack: () -> Unit) {
    var active by remember { mutableIntStateOf(2) }
    val shots = remember { listOf(2, 1, 1, 0, 1, 0, 1, 0) }
    val captured = shots.count { it > 0 }
    val totalShots = shots.sum()

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = "车辆外廓扫描",
            eyebrow = "三维扫描",
            onBack = onBack,
            trailing = {
                VehicleHeaderProgress(captured = captured, totalShots = totalShots)
            },
        )
        Column(Modifier.weight(1f).fillMaxWidth()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val contentSize = vehicleContentSize(maxWidth = maxWidth, maxHeight = maxHeight)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 8.dp),
                ) {
                    item { FusedCloudPanel(shots = shots, totalShots = totalShots, height = contentSize.cloudHeight) }
                    item { DualPreviewRow(height = contentSize.previewHeight) }
                    item {
                        AngleRing(
                            active = active,
                            shots = shots,
                            height = contentSize.ringHeight,
                            ringSize = contentSize.ringSize,
                            onSelect = { active = it },
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
            VehicleCaptureBar(
                active = active,
                shots = shots,
                captured = captured,
                onNext = { active = nextUncapturedAngle(active, shots) },
            )
        }
    }
}

private data class VehicleContentSize(
    val cloudHeight: Dp,
    val previewHeight: Dp,
    val ringHeight: Dp,
    val ringSize: Dp,
)

private fun vehicleContentSize(
    maxWidth: Dp,
    maxHeight: Dp,
): VehicleContentSize {
    val panelWidth = (maxWidth - 40.dp).coerceAtLeast(240.dp)
    val naturalCloud = panelWidth * 0.75f
    val naturalPreview = (panelWidth - 1.dp) / 2f
    val naturalRing = 176.dp
    val fixedGap = 40.dp
    val naturalBody = naturalCloud + naturalPreview + naturalRing
    val targetBody = (maxHeight - fixedGap).coerceAtLeast(1.dp)
    val scale = (targetBody / naturalBody).coerceIn(0.68f, 1f)

    return VehicleContentSize(
        cloudHeight = (naturalCloud * scale).coerceIn(132.dp, naturalCloud),
        previewHeight = (naturalPreview * scale).coerceIn(104.dp, naturalPreview),
        ringHeight = (naturalRing * scale).coerceIn(148.dp, naturalRing),
        ringSize = (156.dp * scale).coerceIn(128.dp, 156.dp),
    )
}

private fun nextUncapturedAngle(active: Int, shots: List<Int>): Int {
    for (step in 1..8) {
        val next = (active + step) % 8
        if (shots[next] == 0) return next
    }
    return active
}

@Composable
private fun VehicleHeaderProgress(
    captured: Int,
    totalShots: Int,
) {
    Row(
        modifier = Modifier.padding(start = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
    ) {
        MiniProgress(captured = captured)
        Text(
            "$captured",
            style = Gomob.type.numInline.copy(fontSize = 13.sp),
            color = Gomob.colors.accentStrong,
        )
        Text(
            "/8 · ${totalShots}张",
            style = Gomob.type.numInline.copy(fontSize = 11.sp),
            color = Gomob.colors.fg2,
        )
    }
}

@Composable
private fun MiniProgress(captured: Int) {
    Row(
        modifier = Modifier.width(96.dp).height(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(8) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(Gomob.shapes.r1)
                    .background(if (index < captured) Gomob.colors.accent else Gomob.colors.bg3)
                    .border(
                        BorderStroke(1.dp, if (index < captured) Gomob.colors.accentLine else Gomob.colors.line1),
                        Gomob.shapes.r1,
                    ),
            )
        }
    }
}

@Composable
private fun FusedCloudPanel(
    shots: List<Int>,
    totalShots: Int,
    height: Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            .height(height)
            .clip(Gomob.shapes.r3)
            .background(Color(0xFF060912)),
    ) {
        FusedCloudCanvas(shots = shots)
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 12.dp, top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("融合点云", style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.1.em), color = Gomob.colors.accent)
            Text(
                "$totalShots 帧 · ${(0.7f + totalShots * 0.18f).formatOne()}M 点",
                style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.08.em),
                color = Gomob.colors.fg2,
            )
        }
        AxisBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
    }
}

private fun Float.formatOne(): String = String.format(java.util.Locale.US, "%.1f", this)

@Composable
private fun FusedCloudCanvas(shots: List<Int>) {
    val acc = Gomob.colors.accent
    val accentStrong = Gomob.colors.accentStrong
    val fg3 = Gomob.colors.fg3
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF060912), Color(0xFF0A0E16), Color(0xFF050810))),
            size = size,
        )
        drawOval(
            color = acc.copy(alpha = 0.06f),
            topLeft = Offset(size.width * 0.08f, size.height * 0.86f),
            size = Size(size.width * 0.84f, size.height * 0.05f),
        )
        repeat(7) { i ->
            val x1 = size.width * (0.20f + i * 0.10f)
            val x2 = size.width * (i / 6f)
            drawLine(
                color = Color(0xFF78A0C8).copy(alpha = 0.06f),
                start = Offset(x1, size.height * 0.96f),
                end = Offset(x2, size.height * 0.78f),
                strokeWidth = 0.4.dp.toPx(),
            )
        }
        drawLine(Color(0xFF78A0C8).copy(alpha = 0.10f), Offset(0f, size.height * 0.92f), Offset(size.width, size.height * 0.92f), strokeWidth = 0.5.dp.toPx())
        drawLine(Color(0xFF78A0C8).copy(alpha = 0.05f), Offset(0f, size.height * 0.82f), Offset(size.width, size.height * 0.82f), strokeWidth = 0.4.dp.toPx())

        VehicleAngles.forEachIndexed { angleIndex, angle ->
            val n = shots[angleIndex]
            if (n == 0) return@forEachIndexed
            repeat(20 * n) { k ->
                val t = ((k * 9.7f + angleIndex * 17f) % 100f) / 100f
                val r = ((k * 13 + angleIndex * 7) % 100) / 100f
                val cx = size.width * (0.12f + t * 0.76f)
                val cy = size.height * (0.58f + sin(t * PI).toFloat() * -0.25f)
                val jx = sin(k * 1.7f + angleIndex).toFloat() * size.width * 0.04f + (r - 0.5f) * size.width * 0.06f
                val jy = cos(k * 2.1f + angleIndex).toFloat() * size.height * 0.02f + (r - 0.5f) * size.height * 0.03f
                val y = cy + jy
                val depth = ((y - size.height * 0.42f) / (size.height * 0.50f)).coerceIn(0f, 1f)
                drawCircle(
                    color = accentStrong.copy(alpha = 0.45f + (1f - depth) * 0.40f),
                    radius = (0.7f + (k % 3) * 0.25f).dp.toPx(),
                    center = Offset(cx + jx, y),
                )
            }
            val labelX = size.width * (0.5f + 0.38f * sin(angle.deg * PI.toFloat() / 180f))
            val labelY = size.height * (0.54f - 0.30f * cos(angle.deg * PI.toFloat() / 180f))
            if (n == 0) {
                drawCircle(fg3.copy(alpha = 0.3f), radius = 2.dp.toPx(), center = Offset(labelX, labelY))
            }
        }
        drawRect(
            color = acc.copy(alpha = 0.45f),
            topLeft = Offset(size.width * 0.10f, size.height * 0.54f),
            size = Size(size.width * 0.80f, size.height * 0.36f),
            style = Stroke(width = 0.6.dp.toPx()),
        )
        shots.forEachIndexed { i, count ->
            if (count == 0) {
                val angle = VehicleAngles[i]
                val x = size.width * (0.5f + 0.38f * sin(angle.deg * PI.toFloat() / 180f))
                val y = size.height * (0.54f - 0.28f * cos(angle.deg * PI.toFloat() / 180f))
                drawCircle(fg3.copy(alpha = 0.25f), radius = 1.3.dp.toPx(), center = Offset(x, y))
            }
        }
    }
}

@Composable
private fun AxisBadge(modifier: Modifier = Modifier) {
    val danger = Gomob.colors.danger
    val ok = Gomob.colors.ok
    val accent = Gomob.colors.accent
    Canvas(modifier.size(36.dp)) {
        val c = Offset(size.width * 0.50f, size.height * 0.58f)
        drawLine(danger, c, Offset(size.width * 0.88f, c.y), strokeWidth = 1.dp.toPx())
        drawLine(ok, c, Offset(c.x, size.height * 0.12f), strokeWidth = 1.dp.toPx())
        drawLine(accent, c, Offset(size.width * 0.22f, size.height * 0.86f), strokeWidth = 1.dp.toPx())
    }
}

@Composable
private fun DualPreviewRow(height: Dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            .height(height)
            .clip(Gomob.shapes.r3),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        PreviewPane(kind = "彩色", isDepth = false, modifier = Modifier.weight(1f))
        PreviewPane(kind = "深度", isDepth = true, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PreviewPane(
    kind: String,
    isDepth: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (isDepth) Color(0xFF0A1218) else Color(0xFF0F1117)),
    ) {
        if (isDepth) DepthPreviewCanvas() else RgbPreviewCanvas()
        SegmentationOverlay()
        CrossHair()
        Text(
            kind,
            modifier = Modifier
                .padding(8.dp)
                .clip(Gomob.shapes.r1)
                .background(Color.Black.copy(alpha = 0.45f))
                .border(BorderStroke(1.dp, if (isDepth) Gomob.colors.okLine else Gomob.colors.accentLine), Gomob.shapes.r1)
                .padding(horizontal = 5.dp, vertical = 2.dp),
            style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.14.em),
            color = if (isDepth) Gomob.colors.ok else Gomob.colors.accent,
        )
    }
}

@Composable
private fun RgbPreviewCanvas() {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF1A2230), Color(0xFF0F1219), Color(0xFF070A10))), size = size)
        drawLine(Color(0xFF7890AA).copy(alpha = 0.15f), Offset(0f, size.height * 0.78f), Offset(size.width, size.height * 0.78f), strokeWidth = 0.5.dp.toPx())
        val car = vehicleSidePath(size)
        drawPath(car, Brush.verticalGradient(listOf(Color(0xFF5A6677), Color(0xFF262D39))))
        drawPath(car, Color(0xFFB4C8DC).copy(alpha = 0.25f), style = Stroke(width = 0.6.dp.toPx()))
        val glass = Path().apply {
            moveTo(size.width * 0.28f, size.height * 0.53f)
            lineTo(size.width * 0.36f, size.height * 0.49f)
            lineTo(size.width * 0.52f, size.height * 0.43f)
            quadraticBezierTo(size.width * 0.64f, size.height * 0.43f, size.width * 0.74f, size.height * 0.49f)
            lineTo(size.width * 0.78f, size.height * 0.52f)
            close()
        }
        drawPath(glass, Color(0xFF8CB4DC).copy(alpha = 0.18f))
        listOf(0.28f, 0.76f).forEach { x ->
            drawCircle(Color(0xFF0A0C12), radius = size.minDimension * 0.06f, center = Offset(size.width * x, size.height * 0.71f))
            drawCircle(Color(0xFF96AAC8).copy(alpha = 0.40f), radius = size.minDimension * 0.06f, center = Offset(size.width * x, size.height * 0.71f), style = Stroke(width = 0.6.dp.toPx()))
        }
    }
}

@Composable
private fun DepthPreviewCanvas() {
    val accentStrong = Gomob.colors.accentStrong
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF0A1018), Color(0xFF06080D))), size = size)
        repeat(10) { i ->
            val y = size.height * i / 10f
            drawLine(Color(0xFF50B4DC).copy(alpha = 0.05f), Offset(0f, y), Offset(size.width, y), strokeWidth = 0.3.dp.toPx())
        }
        val car = vehicleSidePath(size)
        drawPath(car, Brush.radialGradient(listOf(accentStrong, Color(0xFF5177D8), Color(0xFF352659)), center = Offset(size.width * 0.5f, size.height * 0.55f), radius = size.minDimension * 0.7f))
        drawRect(Color(0xFF140A28).copy(alpha = 0.4f), size = Size(size.width, size.height * 0.40f))
        repeat(60) { i ->
            drawCircle(
                Color(0xFFB4DCFF).copy(alpha = 0.35f),
                radius = 0.5.dp.toPx(),
                center = Offset(size.width * ((i * 17) % 100) / 100f, size.height * ((i * 11) % 100) / 100f),
            )
        }
    }
}

private fun vehicleSidePath(size: Size): Path = Path().apply {
    moveTo(size.width * 0.10f, size.height * 0.70f)
    lineTo(size.width * 0.14f, size.height * 0.56f)
    quadraticBezierTo(size.width * 0.22f, size.height * 0.48f, size.width * 0.36f, size.height * 0.47f)
    lineTo(size.width * 0.52f, size.height * 0.39f)
    quadraticBezierTo(size.width * 0.66f, size.height * 0.38f, size.width * 0.78f, size.height * 0.47f)
    lineTo(size.width * 0.86f, size.height * 0.50f)
    quadraticBezierTo(size.width * 0.92f, size.height * 0.52f, size.width * 0.92f, size.height * 0.60f)
    lineTo(size.width * 0.92f, size.height * 0.70f)
    close()
}

@Composable
private fun SegmentationOverlay() {
    val acc = Gomob.colors.accent
    Canvas(Modifier.fillMaxSize()) {
        drawPath(
            vehicleSidePath(size),
            acc.copy(alpha = 0.85f),
            style = Stroke(width = 0.8.dp.toPx()),
        )
    }
}

@Composable
private fun CrossHair() {
    Canvas(Modifier.fillMaxSize()) {
        drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), strokeWidth = 1f)
        drawLine(Color.White.copy(alpha = 0.06f), Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), strokeWidth = 1f)
    }
}

@Composable
private fun AngleRing(
    active: Int,
    shots: List<Int>,
    height: Dp,
    ringSize: Dp,
    onSelect: (Int) -> Unit,
) {
    val ringSizePx = ringSize.value
    val center = ringSizePx / 2f
    val radius = ringSizePx * 60f / 156f
    val dotSize = if (ringSize < 140.dp) 24.dp else 28.dp
    val dotHalf = dotSize.value / 2f
    val angleFontSize = if (ringSize < 140.dp) 8.sp else 9.sp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .padding(horizontal = 20.dp),
    ) {
        Box(
            modifier = Modifier
                .size(ringSize)
                .align(Alignment.Center),
        ) {
            AngleRingCanvas(active = active)
            VehicleAngles.forEachIndexed { i, angle ->
                val rad = angle.deg * PI.toFloat() / 180f
                val x = center + radius * sin(rad) - dotHalf
                val y = center - radius * cos(rad) - dotHalf
                val done = shots[i] > 0
                val selected = i == active
                Box(
                    modifier = Modifier
                        .offset(x.dp, y.dp)
                        .size(dotSize)
                        .clip(CircleShape)
                        .background(
                            when {
                                selected -> Gomob.colors.accentSoft
                                done -> Gomob.colors.okSoft
                                else -> Gomob.colors.bg2
                            },
                        )
                        .border(
                            BorderStroke(
                                1.dp,
                                when {
                                    selected -> Gomob.colors.accent
                                    done -> Gomob.colors.ok
                                    else -> Gomob.colors.line2
                                },
                            ),
                            CircleShape,
                        )
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        angle.label,
                        fontSize = if (angle.label.length > 1) {
                            angleFontSize
                        } else if (ringSize < 140.dp) {
                            10.sp
                        } else {
                            11.sp
                        },
                        fontWeight = FontWeight.Medium,
                        color = when {
                            selected -> Gomob.colors.accent
                            done -> Gomob.colors.ok
                            else -> Gomob.colors.fg2
                        },
                        maxLines = 1,
                    )
                    if (done) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Gomob.colors.ok)
                                .border(BorderStroke(1.dp, Gomob.colors.bg1), CircleShape),
                        )
                    }
                }
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("${(active + 1).toString().padStart(2, '0')} / 08", style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.06.em), color = Gomob.colors.fg3)
            Text(VehicleAngles[active].name, style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.06.em), color = Gomob.colors.accent)
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("已拍 ${shots[active]} 次", style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.06.em), color = Gomob.colors.fg3)
            Text("方位 ${VehicleAngles[active].deg.toInt()}°", style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.06.em), color = Gomob.colors.fg3)
        }
    }
}

@Composable
private fun AngleRingCanvas(active: Int) {
    val line1 = Gomob.colors.line1
    val line2 = Gomob.colors.line2
    val accent = Gomob.colors.accent
    val accentSoft = Gomob.colors.accentSoft
    val bg3 = Gomob.colors.bg3
    val fg3 = Gomob.colors.fg3
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension * 60f / 156f
        drawCircle(Color.Transparent, radius = r, center = c, style = Stroke(width = 1.dp.toPx()))
        drawCircle(line1, radius = r, center = c, style = Stroke(width = 1.dp.toPx()))
        drawCircle(line1, radius = r - 30.dp.toPx(), center = c, style = Stroke(width = 1.dp.toPx()))
        VehicleAngles.forEach { angle ->
            val rad = angle.deg * PI.toFloat() / 180f
            drawLine(
                line1,
                c,
                Offset(c.x + (r - 30.dp.toPx()) * sin(rad), c.y - (r - 30.dp.toPx()) * cos(rad)),
                strokeWidth = 0.5.dp.toPx(),
            )
        }
        val activeAngle = VehicleAngles[active]
        val activeRad = activeAngle.deg * PI.toFloat() / 180f
        val activePoint = Offset(c.x + r * sin(activeRad), c.y - r * cos(activeRad))
        drawLine(accent.copy(alpha = 0.65f), activePoint, c, strokeWidth = 1.dp.toPx())
        val dir = Offset((c.x - activePoint.x) * 0.18f, (c.y - activePoint.y) * 0.18f)
        val base = Offset(activePoint.x + dir.x, activePoint.y + dir.y)
        val perp = Offset(-dir.y * 0.3f, dir.x * 0.3f)
        drawPath(
            Path().apply {
                moveTo(activePoint.x, activePoint.y)
                lineTo(base.x + perp.x, base.y + perp.y)
                lineTo(base.x - perp.x, base.y - perp.y)
                close()
            },
            accentSoft,
        )
        drawPath(
            Path().apply {
                val left = c.x - 14.dp.toPx()
                val top = c.y - 24.dp.toPx()
                addRoundRect(androidx.compose.ui.geometry.RoundRect(left, top, left + 28.dp.toPx(), top + 48.dp.toPx(), 7.dp.toPx(), 7.dp.toPx()))
            },
            bg3,
        )
        drawRoundRect(
            color = line2,
            topLeft = Offset(c.x - 14.dp.toPx(), c.y - 24.dp.toPx()),
            size = Size(28.dp.toPx(), 48.dp.toPx()),
            cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx()),
            style = Stroke(width = 0.8.dp.toPx()),
        )
        drawLine(accent.copy(alpha = 0.7f), Offset(c.x - 12.dp.toPx(), c.y - 15.dp.toPx()), Offset(c.x, c.y - 21.dp.toPx()), strokeWidth = 0.8.dp.toPx())
        drawLine(accent.copy(alpha = 0.7f), Offset(c.x, c.y - 21.dp.toPx()), Offset(c.x + 12.dp.toPx(), c.y - 15.dp.toPx()), strokeWidth = 0.8.dp.toPx())
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("F", style = Gomob.type.numInline.copy(fontSize = 8.sp, letterSpacing = 0.18.em), color = fg3)
    }
}

@Composable
private fun VehicleCaptureBar(
    modifier: Modifier = Modifier,
    active: Int,
    shots: List<Int>,
    captured: Int,
    onNext: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Gomob.colors.bg0), startY = 0f, endY = 90f))
            .padding(start = 24.dp, top = 10.dp, end = 24.dp, bottom = 22.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            RoundSideButton(icon = GomobIcons.Refresh, label = "撤销", disabled = shots[active] == 0)
            ShutterButton()
            RoundSideButton(icon = GomobIcons.Check, label = if (captured == 8) "完成" else "下一步", primary = true, onClick = onNext)
        }
    }
}

@Composable
private fun RoundSideButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean = false,
    disabled: Boolean = false,
    onClick: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (primary) Gomob.colors.accentSoft else Gomob.colors.bg1)
            .border(BorderStroke(1.dp, if (primary) Gomob.colors.accent else Gomob.colors.line2), CircleShape)
            .clickable(enabled = !disabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = when {
                disabled -> Gomob.colors.fg3
                primary -> Gomob.colors.accent
                else -> Gomob.colors.fg1
            },
            modifier = Modifier.size(14.dp),
        )
        Text(
            label,
            fontSize = 12.sp,
            color = when {
                disabled -> Gomob.colors.fg3
                primary -> Gomob.colors.accent
                else -> Gomob.colors.fg1
            },
        )
    }
}

@Composable
private fun ShutterButton() {
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(Gomob.colors.bg0)
            .border(BorderStroke(2.dp, Gomob.colors.accent), CircleShape)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .border(BorderStroke(2.dp, Gomob.colors.accent), CircleShape)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(Gomob.colors.accent))
        }
    }
}
