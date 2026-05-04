package io.gomob.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.gomob.designsystem.theme.Gomob

/**
 * 屏幕顶部条：左 eyebrow（小标签）+ 标题，右 trailing 槽位。
 *
 * 显式组件而非隐式 padding — 每屏从这里挂能保证横向对齐和留白一致。
 */
@Composable
fun ScreenHeader(
    title: String,
    eyebrow: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Gomob.colors.bg0)
            .padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
            if (eyebrow != null) {
                Text(text = eyebrow, style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
            }
            Text(text = title, style = Gomob.type.display, color = Gomob.colors.fg0)
        }
        if (trailing != null) trailing()
    }
}
