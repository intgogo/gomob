package io.gomob.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.theme.Gomob

/**
 * 可点击筛选片。28dp 高，可选中态。
 *
 * 选中：accentSoft 底 + accent 文字；未选：bg2 底 + fg1 文字。
 */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) Gomob.colors.accentSoft else Gomob.colors.bg2
    val fg = if (selected) Gomob.colors.accent else Gomob.colors.fg1

    Box(
        modifier
            .clip(Gomob.shapes.r2)
            .background(bg)
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s4)) {
            Text(text = text, style = Gomob.type.bodySm, color = fg)
        }
    }
}
