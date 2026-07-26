package io.gomob.feature.scan3d

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.data.scan.LaserLatestScan
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import java.text.NumberFormat

const val SCAN3D_ROUTE = "scan3d"

@Composable
fun Scan3dRoute(
    onOpenContourScan: () -> Unit = {},
    onOpenVinRectify: () -> Unit = {},
    vm: Scan3dViewModel = hiltViewModel(),
) {
    val latestScan by vm.latestScan.collectAsStateWithLifecycle()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        vm.refreshLatestScan()
    }

    val listState = rememberLazyListState()
    GlassHeaderScaffold(
        listState = listState,
        header = {
            ScreenHeader(title = "三维扫描")
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + Gomob.spacing.s12,
                bottom = padding.calculateBottomPadding() + Gomob.spacing.s28,
            ),
        ) {
            item {
                ActionTilePair(
                    onOpenContourScan = onOpenContourScan,
                    onOpenVinRectify = onOpenVinRectify,
                )
            }
            item {
                AssetSection(
                    state = latestScan,
                    onRetry = vm::refreshLatestScan,
                )
            }
        }
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
            .padding(horizontal = Gomob.spacing.pageGutter),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.cardGap),
    ) {
        ActionTile(
            modifier = Modifier.fillMaxWidth(),
            title = "VIN 数码拓印",
            desc = "RGBD 采集 · 服务端正射还原",
            detail = "权威还原后可识别 17 位 VIN",
            index = "01",
            onClick = onOpenVinRectify,
            illustration = { VinStampIllustration() },
        )
        ActionTile(
            modifier = Modifier.fillMaxWidth(),
            title = "车辆外廓扫描",
            desc = "3D 工位 · 多镜头云台扫掠",
            detail = "A/B 双单元 · 融合测量预览",
            index = "02",
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
    index: String,
    onClick: () -> Unit,
    illustration: @Composable () -> Unit,
) {
    HairlineCard(
        modifier = modifier,
        padding = Gomob.spacing.cardPadding,
        onClick = onClick,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(84.dp)
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.bg3),
                contentAlignment = Alignment.Center,
            ) {
                illustration()
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Gomob.spacing.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title,
                    style = Gomob.type.title,
                    color = Gomob.colors.fg0,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = GomobIcons.ChevronRight,
                    contentDescription = null,
                    tint = Gomob.colors.fg3,
                    modifier = Modifier.size(Gomob.spacing.icon16),
                )
            }
            Text(
                desc,
                modifier = Modifier.padding(top = Gomob.spacing.s4),
                style = Gomob.type.caption,
                color = Gomob.colors.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .padding(top = Gomob.spacing.s12)
                    .fillMaxWidth()
                    .height(Gomob.spacing.hairline)
                    .background(Gomob.colors.line1),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    detail,
                    style = Gomob.type.caption,
                    color = Gomob.colors.fg3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(index, style = Gomob.type.eyebrow, color = Gomob.colors.accent)
            }
        }
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
private fun AssetSection(
    state: LatestScanUiState,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Gomob.spacing.pageGutter,
                top = Gomob.spacing.sectionGap,
                end = Gomob.spacing.pageGutter,
            ),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.cardGap),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("默认工位最近扫描", style = Gomob.type.sectionTitle, color = Gomob.colors.fg1)
            // TODO(终态): 跳转 3D 资产全列表（路由未接线，先做视觉入口）
            Text("查看全部 ›", fontSize = 12.sp, color = Gomob.colors.accent)
        }
        HairlineCard(
            padding = 0.dp,
            onClick = if (state is LatestScanUiState.Error) onRetry else null,
        ) {
            when (state) {
                LatestScanUiState.Loading -> LatestScanStatusRow(
                    title = "正在读取最近扫描",
                    detail = "从服务端同步默认工位结果",
                    icon = GomobIcons.Refresh,
                )
                LatestScanUiState.Empty -> LatestScanStatusRow(
                    title = "暂无已完成扫描",
                    detail = "完成车辆外廓扫描后将在这里显示",
                    icon = GomobIcons.Cube,
                )
                LatestScanUiState.Error -> LatestScanStatusRow(
                    title = "最近扫描加载失败",
                    detail = "点击重试",
                    icon = GomobIcons.AlertCircle,
                    danger = true,
                    action = "重试",
                )
                is LatestScanUiState.Ready -> LatestScanRow(state.scan)
            }
        }
    }
}

@Composable
private fun LatestScanStatusRow(
    title: String,
    detail: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    danger: Boolean = false,
    action: String? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Gomob.spacing.rowList)
            .padding(horizontal = Gomob.spacing.s14),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(Gomob.shapes.r1)
                .background(if (danger) Gomob.colors.dangerSoft else Gomob.colors.bg2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (danger) Gomob.colors.danger else Gomob.colors.fg3,
                modifier = Modifier.size(Gomob.spacing.icon20),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = Gomob.type.body, color = if (danger) Gomob.colors.danger else Gomob.colors.fg0)
            Text(detail, style = Gomob.type.caption, color = Gomob.colors.fg3)
        }
        if (action != null) {
            Text(
                action,
                style = Gomob.type.caption,
                color = Gomob.colors.accent,
            )
        }
    }
}

@Composable
private fun LatestScanRow(scan: LaserLatestScan) {
    val title = if (scan.backgroundCaptured) "背景采集 #${scan.scanId}" else "扫描 #${scan.scanId}"
    val type = if (scan.backgroundCaptured) "空工位背景" else "车辆外廓"
    val status = if (scan.backgroundCaptured) "采集完成" else "已完成"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Gomob.spacing.rowList)
            .padding(horizontal = Gomob.spacing.s14),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.bg3)
                .border(BorderStroke(Gomob.spacing.hairline, Gomob.colors.line1), Gomob.shapes.r2),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = GomobIcons.Cube,
                contentDescription = null,
                tint = Gomob.colors.accentStrong,
                modifier = Modifier.size(Gomob.spacing.icon20),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    title,
                    style = Gomob.type.numInline.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.04.em),
                    color = Gomob.colors.fg0,
                )
                Text(
                    type,
                    fontSize = 10.sp,
                    color = Gomob.colors.fg2,
                    modifier = Modifier
                        .clip(Gomob.shapes.r1)
                        .border(BorderStroke(1.dp, Gomob.colors.line2), Gomob.shapes.r1)
                        .padding(horizontal = Gomob.spacing.s6, vertical = 1.dp),
                )
            }
            // 模型无时间戳/时长字段，第二行 meta 保留状态文案，字体色对齐 11 mono fg3
            Text(status, style = Gomob.type.numInline.copy(fontSize = 11.sp), color = Gomob.colors.fg3)
        }
        val points = scan.points
        if (points != null) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    NumberFormat.getIntegerInstance().format(points),
                    style = Gomob.type.numInline.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.02.em),
                    color = Gomob.colors.accent,
                )
                Text("POINTS", style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.06.em), color = Gomob.colors.fg3)
            }
        } else {
            Text("点数未返回", style = Gomob.type.caption, color = Gomob.colors.fg3)
        }
        Icon(
            imageVector = GomobIcons.ChevronRight,
            contentDescription = null,
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(Gomob.spacing.icon16),
        )
    }
}
