package io.gomob.designsystem.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.theme.BorderSubtle

/**
 * 玻璃拟态卡片 — App 内任何带框的内容默认走它。
 *
 * - 背景：渐变（surface → surface_high）
 * - 描边：1px BorderSubtle 默认 / focus 时切到 BorderGlow（外部传 [borderColor]）
 * - 圆角：默认 large(16dp)，可覆盖
 * - 阴影：依赖 surface 颜色差呈现层级，不用 Material elevation（暗色下阴影看不到）
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    cornerRadius: Dp = 16.dp,
    borderColor: Color = BorderSubtle,
    background: Brush = Brush.verticalGradient(
        listOf(
            MaterialTheme.colorScheme.surface,
            MaterialTheme.colorScheme.surfaceVariant,
        ),
    ),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val borderStroke = BorderStroke(1.dp, borderColor)

    Box(
        modifier = modifier
            .clip(shape)
            .background(background, shape)
            .border(borderStroke, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        content()
    }
}
