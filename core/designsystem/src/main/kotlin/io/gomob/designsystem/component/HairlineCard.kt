package io.gomob.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import io.gomob.designsystem.theme.Gomob

/**
 * 工业仪表风的标准卡片：实底面板 + 1dp hairline 边 + 顶部 1dp 内高光。
 *
 * 比 Material elevation 更"硬"。padding 默认 14dp，可以传 0 给全出血卡片。
 */
@Composable
fun HairlineCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = Gomob.shapes.r3,
    padding: Dp = Gomob.spacing.cardPadding,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(shape)
            .background(Gomob.colors.bg1)
            .border(Gomob.spacing.hairline, Gomob.colors.line1, shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        // 顶部 1dp 内高光
        Box(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.hairline)
                .align(Alignment.TopCenter)
                .background(Gomob.colors.hlTop),
        )
        Box(Modifier.padding(padding)) { content() }
    }
}
