package io.gomob.designsystem.decoration

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.theme.Gomob

/**
 * jsx tokens.css `.gm .ticks` — 在面板的 4 个对角内绘制 L 形 1dp 角标。
 * 给 HairlineCard 之类的 Box 加上 `.ticks()` 即可，与 hl-top 高光共存。
 *
 * 视觉：左上画 ┐ 形，右下画 └ 形（jsx 实际是左上 ┌ + 右下 ┘，这里对齐）。
 *
 * @param length 角标边长（jsx 默认 8dp）
 * @param color 描边颜色（默认 lineStrong = 16% 白）
 */
fun Modifier.ticks(
    length: Dp = 8.dp,
    color: Color? = null,
): Modifier = composed {
    val stroke = with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }
    val len = with(androidx.compose.ui.platform.LocalDensity.current) { length.toPx() }
    val tick = color ?: Gomob.colors.lineStrong
    drawWithContent {
        drawContent()
        // 左上角 ┌
        drawLine(tick, Offset(0f, 0f), Offset(len, 0f), strokeWidth = stroke)
        drawLine(tick, Offset(0f, 0f), Offset(0f, len), strokeWidth = stroke)
        // 右下角 ┘
        drawLine(tick, Offset(size.width, size.height), Offset(size.width - len, size.height), strokeWidth = stroke)
        drawLine(tick, Offset(size.width, size.height), Offset(size.width, size.height - len), strokeWidth = stroke)
    }
}

/**
 * jsx tokens.css `.gm .grid-bg` — 32×32dp 蓝图网格底纹。
 * 用于在大面积空白（相机预览框/资产网格背景）上铺一层细网格。
 */
fun Modifier.gridBg(
    cell: Dp = 32.dp,
    color: Color? = null,
): Modifier = composed {
    val cellPx = with(androidx.compose.ui.platform.LocalDensity.current) { cell.toPx() }
    val stroke = with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }
    val grid = color ?: Gomob.colors.line1
    drawBehind {
        var x = 0f
        while (x <= size.width) {
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
            x += cellPx
        }
        var y = 0f
        while (y <= size.height) {
            drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
            y += cellPx
        }
    }
}

/**
 * jsx home.jsx AssistantBubble — 流式扫描线（顶部 1px 渐变水平横扫）。
 * 配合 LLM 流式生成场景使用，`streaming = false` 时扫描线消失。
 *
 * 视觉：顶部 1dp 高度的左右扫动线，颜色从透明 → accent → 透明。
 */
@Composable
fun Modifier.scanline(streaming: Boolean): Modifier {
    if (!streaming) return this
    val transition = rememberInfiniteTransition(label = "scanline")
    val phase by transition.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "scanline-phase",
    )
    val accent = Gomob.colors.accent
    val stroke = with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }
    return this.drawWithContent {
        drawContent()
        val w = size.width
        val cx = w * (phase + 1f) / 2f          // -1..1 → 0..w
        val band = w * 0.6f                      // 渐变带宽 60% 容器宽
        val brush = Brush.horizontalGradient(
            colors = listOf(Color.Transparent, accent, Color.Transparent),
            startX = cx - band / 2f,
            endX = cx + band / 2f,
        )
        drawRect(brush = brush, topLeft = Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(w, stroke))
    }
}
