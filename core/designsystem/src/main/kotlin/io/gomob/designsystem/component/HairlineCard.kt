package io.gomob.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import io.gomob.designsystem.theme.Gomob

/**
 * 标准卡片：实底面板 + 稳定留白，不画外框线。
 *
 * padding 默认 14dp，可以传 0 给全出血卡片。
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
            .let { if (onClick != null) it.clickable(onClick = onClick) else it },
    ) {
        Box(Modifier.padding(padding)) { content() }
    }
}
