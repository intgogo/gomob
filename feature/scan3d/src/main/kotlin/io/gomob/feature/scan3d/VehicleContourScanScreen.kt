package io.gomob.feature.scan3d

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.nativebridge.camera.CameraSourceState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

const val SCAN_VEHICLE_ROUTE = "scan3d/vehicle"

@Composable
fun VehicleContourScanRoute(
    onBack: () -> Unit,
    vm: VehicleContourScanViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val shotCounts by vm.shotCounts.collectAsStateWithLifecycle()
    val active by vm.activeAngle.collectAsStateWithLifecycle()
    val colorBmp by vm.colorPreview.collectAsStateWithLifecycle()
    val depthBmp by vm.depthPreview.collectAsStateWithLifecycle()
    val cloud by vm.pointCloudPreview.collectAsStateWithLifecycle()
    val capturing by vm.capturing.collectAsStateWithLifecycle()
    val deviceState by vm.deviceState.collectAsStateWithLifecycle()

    val capturedAngles = shotCounts.count { it > 0 }
    val totalShots = shotCounts.sum()

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = "车辆外廓扫描",
            eyebrow = "三维扫描",
            onBack = onBack,
            trailing = { VehicleHeaderProgress(captured = capturedAngles, totalShots = totalShots) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                is VehicleScanState.Completed -> CompletedPanel(state = s, onRestart = vm::restart)
                VehicleScanState.Uploading, VehicleScanState.Fusing ->
                    ProcessingPanel(uploading = s == VehicleScanState.Uploading)
                is VehicleScanState.Error -> ErrorPanel(msg = s.msg, onRestart = vm::restart)
                VehicleScanState.Capturing -> CaptureBody(
                    shotCounts = shotCounts,
                    active = active,
                    colorBmp = colorBmp,
                    depthBmp = depthBmp,
                    cloud = cloud,
                    capturing = capturing,
                    deviceState = deviceState,
                    onSelect = vm::selectAngle,
                    onCapture = vm::capture,
                    onUndo = vm::undo,
                    onFinish = vm::finishAndUpload,
                )
            }
        }
    }
}

@Composable
private fun CaptureBody(
    shotCounts: List<Int>,
    active: Int,
    colorBmp: android.graphics.Bitmap?,
    depthBmp: android.graphics.Bitmap?,
    cloud: FloatArray,
    capturing: Boolean,
    deviceState: CameraSourceState,
    onSelect: (Int) -> Unit,
    onCapture: () -> Unit,
    onUndo: () -> Unit,
    onFinish: () -> Unit,
) {
    val capturedAngles = shotCounts.count { it > 0 }
    Column(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val contentSize = vehicleContentSize(maxWidth = maxWidth, maxHeight = maxHeight)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                item { LiveCloudPanel(cloud = cloud, totalShots = shotCounts.sum(), height = contentSize.cloudHeight) }
                item { DualPreviewRow(colorBmp = colorBmp, depthBmp = depthBmp, height = contentSize.previewHeight) }
                item {
                    AngleRing(
                        active = active,
                        shots = shotCounts,
                        height = contentSize.ringHeight,
                        ringSize = contentSize.ringSize,
                        onSelect = onSelect,
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
        VehicleCaptureBar(
            active = active,
            shots = shotCounts,
            capturedAngles = capturedAngles,
            capturing = capturing,
            deviceReady = deviceState is CameraSourceState.Streaming,
            onCapture = onCapture,
            onUndo = onUndo,
            onFinish = onFinish,
        )
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

/** 实时点云面板：用当前方位采集的真深度反投影点云（[cloud] 为空时提示对准）。 */
@Composable
private fun LiveCloudPanel(
    cloud: FloatArray,
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
        if (cloud.isNotEmpty()) {
            PointCloud3dView(points = cloud, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "对准车辆按快门采集\n每个方位的真实点云会在此显示",
                    style = Gomob.type.numInline.copy(fontSize = 11.sp),
                    color = Gomob.colors.fg3,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(
            modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("当前方位点云", style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.1.em), color = Gomob.colors.accent)
            Text(
                "已采 $totalShots 帧 · ${cloud.size / 3} 点",
                style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.08.em),
                color = Gomob.colors.fg2,
            )
        }
        AxisBadge(Modifier.align(Alignment.TopEnd).padding(8.dp))
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
private fun DualPreviewRow(
    colorBmp: android.graphics.Bitmap?,
    depthBmp: android.graphics.Bitmap?,
    height: Dp,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            .height(height)
            .clip(Gomob.shapes.r3),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        PreviewPane(kind = "彩色", isDepth = false, bitmap = colorBmp, modifier = Modifier.weight(1f))
        PreviewPane(kind = "深度", isDepth = true, bitmap = depthBmp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PreviewPane(
    kind: String,
    isDepth: Boolean,
    bitmap: android.graphics.Bitmap?,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .background(if (isDepth) Color(0xFF0A1218) else Color(0xFF0F1117)),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = kind,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("等待相机…", style = Gomob.type.numInline.copy(fontSize = 10.sp), color = Gomob.colors.fg3)
            }
        }
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
            VehicleAngleDefs.forEachIndexed { i, angle ->
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
            Text(VehicleAngleDefs[active].name, style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.06.em), color = Gomob.colors.accent)
        }
        Column(
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 12.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text("已拍 ${shots[active]} 次", style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.06.em), color = Gomob.colors.fg3)
            Text("方位 ${VehicleAngleDefs[active].deg.toInt()}°", style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.06.em), color = Gomob.colors.fg3)
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
        VehicleAngleDefs.forEach { angle ->
            val rad = angle.deg * PI.toFloat() / 180f
            drawLine(
                line1,
                c,
                Offset(c.x + (r - 30.dp.toPx()) * sin(rad), c.y - (r - 30.dp.toPx()) * cos(rad)),
                strokeWidth = 0.5.dp.toPx(),
            )
        }
        val activeAngle = VehicleAngleDefs[active]
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
    capturedAngles: Int,
    capturing: Boolean,
    deviceReady: Boolean,
    onCapture: () -> Unit,
    onUndo: () -> Unit,
    onFinish: () -> Unit,
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
            RoundSideButton(icon = GomobIcons.Refresh, label = "撤销", disabled = shots[active] == 0, onClick = onUndo)
            ShutterButton(enabled = deviceReady && !capturing, onClick = onCapture)
            RoundSideButton(
                icon = GomobIcons.Check,
                label = "完成融合",
                primary = true,
                disabled = shots.sum() < 2,
                onClick = onFinish,
            )
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
private fun ShutterButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val ring = if (enabled) Gomob.colors.accent else Gomob.colors.line2
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(Gomob.colors.bg0)
            .border(BorderStroke(2.dp, ring), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .border(BorderStroke(2.dp, ring), CircleShape)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize().clip(CircleShape).background(ring))
        }
    }
}

@Composable
private fun ProcessingPanel(uploading: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator(color = Gomob.colors.accent)
            Text(
                if (uploading) "正在上传多视角 RGBD…" else "云端多视角融合中…\n生成高精度 3D 网格",
                style = Gomob.type.numInline.copy(fontSize = 13.sp),
                color = Gomob.colors.fg1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CompletedPanel(
    state: VehicleScanState.Completed,
    onRestart: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clip(Gomob.shapes.r3)
                .background(Color(0xFF060912)),
        ) {
            GlbModelView(glbFile = state.glbFile, modifier = Modifier.fillMaxSize())
            Column(
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("融合 3D 模型", style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.1.em), color = Gomob.colors.accent)
                Text(
                    "${state.vertices} 顶点 · ${state.triangles} 面 · ${state.frameCount} 视角",
                    style = Gomob.type.numInline.copy(fontSize = 9.sp),
                    color = Gomob.colors.fg2,
                )
            }
        }
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            RoundSideButton(icon = GomobIcons.Refresh, label = "重新扫描", primary = true, onClick = onRestart)
        }
    }
}

@Composable
private fun ErrorPanel(
    msg: String,
    onRestart: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(msg, style = Gomob.type.numInline.copy(fontSize = 13.sp), color = Gomob.colors.danger, textAlign = TextAlign.Center)
            RoundSideButton(icon = GomobIcons.Refresh, label = "重新扫描", primary = true, onClick = onRestart)
        }
    }
}
