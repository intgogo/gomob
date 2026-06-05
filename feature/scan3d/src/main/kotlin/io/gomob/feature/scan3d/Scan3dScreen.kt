package io.gomob.feature.scan3d

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.nativebridge.berxel.BerxelDeviceState

const val SCAN3D_ROUTE = "scan3d"

@Composable
fun Scan3dRoute(
    cameraSlot: @Composable () -> Unit = {},
    onOpenContourScan: () -> Unit = {},
    onOpenDepthCamera: () -> Unit = {},
    onOpenSonixDebug: () -> Unit = {},
    onOpenVinRectify: () -> Unit = {},
    vm: Scan3dViewModel = hiltViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        Scan3dHeader(
            state = ui,
            onOpenDepthCamera = onOpenDepthCamera,
            onOpenSonixDebug = onOpenSonixDebug,
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = Gomob.spacing.s28),
        ) {
            item {
                ActionTilePair(
                    onOpenContourScan = onOpenContourScan,
                    onOpenVinRectify = onOpenVinRectify,
                )
            }
            item { AssetSection() }
        }
    }
}

@Composable
private fun Scan3dHeader(
    state: Scan3dDeviceUiState,
    onOpenDepthCamera: () -> Unit,
    onOpenSonixDebug: () -> Unit = {},
) {
    val badge = state.toBadgeView()
    ScreenHeader(
        title = "三维扫描",
        eyebrow = "便携手持采集 · 实时点云预览",
        trailing = {
            DepthCameraBadge(
                badge = badge,
                onClick = onOpenDepthCamera,
                onLongClick = onOpenSonixDebug,
            )
        },
    )
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DepthCameraBadge(
    badge: DeviceBadgeView,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = GomobIcons.USB,
            contentDescription = badge.contentDescription,
            tint = badge.iconTint,
            modifier = Modifier.size(15.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 2.dp, end = 2.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(badge.dotColor)
                .border(BorderStroke(1.dp, Gomob.colors.bg1), CircleShape),
        )
    }
}

private data class DeviceBadgeView(
    val contentDescription: String,
    val iconTint: Color,
    val dotColor: Color,
)

@Composable
private fun Scan3dDeviceUiState.toBadgeView(): DeviceBadgeView {
    val serial = lastKnownInfo?.serialNumber?.ifBlank { null } ?: "iHawk"
    return when (state) {
        is BerxelDeviceState.Streaming -> DeviceBadgeView(
            contentDescription = "深度相机已连接：${state.info.serialNumber.ifBlank { serial }}",
            iconTint = Gomob.colors.accent,
            dotColor = Gomob.colors.ok,
        )
        is BerxelDeviceState.Initializing,
        is BerxelDeviceState.Opening,
        is BerxelDeviceState.WaitingPermission -> DeviceBadgeView(
            contentDescription = "深度相机连接中：$serial",
            iconTint = Gomob.colors.fg3,
            dotColor = Gomob.colors.fg3,
        )
        is BerxelDeviceState.Error -> DeviceBadgeView(
            contentDescription = "深度相机异常：$serial",
            iconTint = Gomob.colors.danger,
            dotColor = Gomob.colors.danger,
        )
        else -> DeviceBadgeView(
            contentDescription = "深度相机未连接：$serial",
            iconTint = Gomob.colors.fg3,
            dotColor = Gomob.colors.fg3,
        )
    }
}

@Composable
private fun ActionTilePair(
    onOpenContourScan: () -> Unit,
    onOpenVinRectify: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ActionTile(
            modifier = Modifier.fillMaxWidth(),
            title = "VIN 数码拓印",
            desc = "无墨拓印·一拍即录入档",
            detail = "自动识别 17 位 · 入档归档",
            compact = false,
            onClick = onOpenVinRectify,
            illustration = { VinStampIllustration() },
        )
        ActionTile(
            modifier = Modifier.fillMaxWidth(),
            title = "车辆外廓扫描",
            desc = "手持即扫·免架设快部署",
            detail = "主从合一 · 实时点云预览",
            compact = false,
            onClick = onOpenContourScan,
            illustration = { VehicleScanIllustration() },
        )
    }
}

@Composable
private fun ActionTile(
    modifier: Modifier,
    title: String,
    desc: String,
    detail: String,
    compact: Boolean,
    onClick: () -> Unit,
    illustration: @Composable () -> Unit,
) {
    val bgColor = Gomob.colors.bg1
    val titleColor = Gomob.colors.fg0
    val descColor = Gomob.colors.fg2
    val cardPadding = if (compact) 12.dp else 14.dp
    val illustrationHeight = if (compact) 86.dp else 104.dp
    val tileMinHeight = if (compact) 202.dp else 220.dp

    Column(
        modifier
            .clip(Gomob.shapes.r3)
            .background(bgColor)
            .clickable(onClick = onClick)
            .heightIn(min = tileMinHeight)
            .padding(cardPadding),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(illustrationHeight)
                .clip(Gomob.shapes.r1),
            contentAlignment = Alignment.Center,
        ) {
            illustration()
        }

        Text(
            title,
            modifier = Modifier.padding(top = 12.dp),
            fontSize = 16.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            desc,
            modifier = Modifier.padding(top = 4.dp),
            fontSize = 11.sp,
            lineHeight = 14.sp,
            color = descColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .height(Gomob.spacing.hairline)
                .background(Gomob.colors.line1),
        )
        Text(
            detail,
            modifier = Modifier.padding(top = 8.dp),
            fontSize = 10.sp,
            lineHeight = 15.sp,
            color = Gomob.colors.fg3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VehicleScanIllustration() {
    val acc = Gomob.colors.accent
    val accStrong = Gomob.colors.accentStrong
    val fg0 = Gomob.colors.fg0
    val tileBg = Gomob.colors.bg1
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val stroke = 1.2.dp.toPx()
        val guideLeft = w * 0.04f
        val guideRight = w * 0.96f
        val scanLineLeft = w * 0.13f
        val scanLineRight = w * 0.87f

        for (i in 0..3) {
            val y = h * (0.22f + i * 0.19f)
            drawLine(acc.copy(alpha = 0.18f), Offset(scanLineLeft, y), Offset(scanLineRight, y), strokeWidth = 0.5.dp.toPx())
        }
        drawLine(accStrong.copy(alpha = 0.75f), Offset(scanLineLeft, h * 0.22f), Offset(scanLineRight, h * 0.22f), strokeWidth = 0.8.dp.toPx())

        val car = Path().apply {
            moveTo(w * 0.16f, h * 0.82f)
            lineTo(w * 0.22f, h * 0.82f)
            cubicTo(w * 0.24f, h * 0.71f, w * 0.29f, h * 0.63f, w * 0.36f, h * 0.63f)
            lineTo(w * 0.46f, h * 0.63f)
            lineTo(w * 0.53f, h * 0.34f)
            lineTo(w * 0.66f, h * 0.34f)
            lineTo(w * 0.75f, h * 0.63f)
            lineTo(w * 0.84f, h * 0.63f)
            cubicTo(w * 0.89f, h * 0.63f, w * 0.90f, h * 0.74f, w * 0.90f, h * 0.82f)
            lineTo(w * 0.86f, h * 0.82f)
        }
        drawPath(car, fg0.copy(alpha = 0.92f), style = Stroke(width = 1.6.dp.toPx()))
        drawPath(
            Path().apply {
                moveTo(w * 0.54f, h * 0.63f)
                lineTo(w * 0.57f, h * 0.41f)
                lineTo(w * 0.65f, h * 0.40f)
                lineTo(w * 0.71f, h * 0.63f)
                close()
            },
            fg0.copy(alpha = 0.46f),
            style = Stroke(width = stroke),
        )
        listOf(w * 0.33f to h * 0.82f, w * 0.78f to h * 0.82f).forEach { (x, y) ->
            drawCircle(tileBg, radius = 7.5.dp.toPx(), center = Offset(x, y))
            drawCircle(fg0.copy(alpha = 0.9f), radius = 7.5.dp.toPx(), center = Offset(x, y), style = Stroke(width = 1.3.dp.toPx()))
            drawCircle(acc.copy(alpha = 0.8f), radius = 1.7.dp.toPx(), center = Offset(x, y))
        }

        val tick = 7.dp.toPx()
        val m = 2.dp.toPx()
        listOf(
            Offset(guideLeft, m) to Pair(1f, 1f),
            Offset(guideRight, m) to Pair(-1f, 1f),
            Offset(guideLeft, h - m) to Pair(1f, -1f),
            Offset(guideRight, h - m) to Pair(-1f, -1f),
        ).forEach { (p, dir) ->
            drawLine(acc.copy(alpha = 0.7f), p, Offset(p.x + tick * dir.first, p.y), strokeWidth = 0.7.dp.toPx())
            drawLine(acc.copy(alpha = 0.7f), p, Offset(p.x, p.y + tick * dir.second), strokeWidth = 0.7.dp.toPx())
        }
    }
}

@Composable
private fun VinStampIllustration() {
    val fg0 = Gomob.colors.fg0
    val fg1 = Gomob.colors.fg1
    val fg2 = Gomob.colors.fg2
    val acc = Gomob.colors.accent
    val bg3 = Gomob.colors.bg3
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val left = w * 0.08f
        val top = h * 0.22f
        val plateW = w * 0.84f
        val plateH = h * 0.56f
        drawRoundRect(
            color = bg3,
            topLeft = Offset(left, top),
            size = Size(plateW, plateH),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )
        drawRoundRect(
            color = fg1.copy(alpha = 0.42f),
            topLeft = Offset(left, top),
            size = Size(plateW, plateH),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            style = Stroke(width = 0.8.dp.toPx()),
        )
        listOf(
            Offset(left + 6.dp.toPx(), top + 6.dp.toPx()),
            Offset(left + plateW - 6.dp.toPx(), top + 6.dp.toPx()),
            Offset(left + 6.dp.toPx(), top + plateH - 6.dp.toPx()),
            Offset(left + plateW - 6.dp.toPx(), top + plateH - 6.dp.toPx()),
        ).forEach { drawCircle(fg2.copy(alpha = 0.7f), radius = 1.dp.toPx(), center = it) }

        for (i in 0 until 40) {
            val x = left + 12.dp.toPx() + (i * 13 % 100) / 100f * (plateW - 24.dp.toPx())
            val y = top + 13.dp.toPx() + (i * 7 % 100) / 100f * (plateH - 20.dp.toPx())
            drawCircle(fg1.copy(alpha = 0.18f), radius = 0.6.dp.toPx(), center = Offset(x, y))
        }
        val charTop = top + plateH * 0.52f
        for (i in 0 until 17) {
            val x = left + 11.dp.toPx() + i * ((plateW - 22.dp.toPx()) / 16f)
            drawRect(
                color = if (i % 3 == 0) fg0.copy(alpha = 0.82f) else fg0.copy(alpha = 0.58f),
                topLeft = Offset(x, charTop - 7.dp.toPx()),
                size = Size(2.4.dp.toPx(), 9.dp.toPx()),
            )
        }
        val scaleY = top + plateH * 0.72f
        drawLine(fg2.copy(alpha = 0.5f), Offset(left + 12.dp.toPx(), scaleY), Offset(left + plateW - 12.dp.toPx(), scaleY), strokeWidth = 0.5.dp.toPx())
        for (i in 0..8) {
            val x = left + 12.dp.toPx() + i * ((plateW - 24.dp.toPx()) / 8f)
            drawLine(fg2.copy(alpha = 0.5f), Offset(x, scaleY - 3.dp.toPx()), Offset(x, scaleY + 3.dp.toPx()), strokeWidth = 0.5.dp.toPx())
        }
        val tick = 6.dp.toPx()
        listOf(
            Offset(w * 0.04f, h * 0.10f) to Pair(1f, 1f),
            Offset(w * 0.96f, h * 0.10f) to Pair(-1f, 1f),
            Offset(w * 0.04f, h * 0.90f) to Pair(1f, -1f),
            Offset(w * 0.96f, h * 0.90f) to Pair(-1f, -1f),
        ).forEach { (p, dir) ->
            drawLine(acc.copy(alpha = 0.85f), p, Offset(p.x + tick * dir.first, p.y), strokeWidth = 0.7.dp.toPx())
            drawLine(acc.copy(alpha = 0.85f), p, Offset(p.x, p.y + tick * dir.second), strokeWidth = 0.7.dp.toPx())
        }
    }
}

@Composable
private fun AssetSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 24.dp, end = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("最近 3D 资产", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
            Row(
                modifier = Modifier.clickable {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("查看全部", fontSize = 11.sp, color = Gomob.colors.accent)
                Icon(GomobIcons.ArrowRight, contentDescription = null, tint = Gomob.colors.accent, modifier = Modifier.size(11.dp))
            }
        }
        AssetRow(name = "VIN-1", type = "车辆外廓", pts = "0.7M", time = "2024/05/10 14:22", dur = "00:48")
        AssetRow(name = "VIN-2", type = "VIN 拓印", pts = "1.2M", time = "2024/05/10 11:05", dur = "01:12")
        AssetRow(name = "VIN-3", type = "车辆外廓", pts = "0.9M", time = "2024/05/09 18:32", dur = "00:54")
    }
}

@Composable
private fun AssetRow(
    name: String,
    type: String,
    pts: String,
    time: String,
    dur: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r1)
            .background(Gomob.colors.bg1)
            .clickable {}
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PointCloudThumb()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    name,
                    style = Gomob.type.numInline.copy(fontSize = 13.sp, letterSpacing = 0.04.em),
                    color = Gomob.colors.fg0,
                )
                Text(
                    type,
                    fontSize = 9.sp,
                    color = Gomob.colors.fg2,
                    modifier = Modifier
                        .clip(Gomob.shapes.r1)
                        .border(BorderStroke(1.dp, Gomob.colors.line2), Gomob.shapes.r1)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(time, style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.04.em), color = Gomob.colors.fg3)
                Text("·", fontSize = 10.sp, color = Gomob.colors.line2)
                Text("时长 $dur", style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.04.em), color = Gomob.colors.fg3)
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                pts,
                style = Gomob.type.numInline.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.02.em),
                color = Gomob.colors.accentStrong,
            )
            Text("POINTS", style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.06.em), color = Gomob.colors.fg3)
        }
        Icon(GomobIcons.ChevronRight, contentDescription = null, tint = Gomob.colors.fg3, modifier = Modifier.size(12.dp))
    }
}

@Composable
private fun PointCloudThumb() {
    val acc = Gomob.colors.accent
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(Gomob.shapes.r1)
            .background(Gomob.colors.bg3)
            .border(BorderStroke(1.dp, Gomob.colors.line1), Gomob.shapes.r1),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for (i in 0 until 22) {
                val x = (i * 41 % 100) / 100f * size.width
                val y = (i * 59 % 100) / 100f * size.height
                val r = (0.7f + (i % 3) * 0.5f).dp.toPx()
                drawCircle(acc.copy(alpha = 0.55f), radius = r, center = Offset(x, y))
            }
        }
    }
}
