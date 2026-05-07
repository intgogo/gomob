package io.gomob.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.theme.Gomob

/**
 * 设置/列表行。56dp（无副标题）/ 64dp（带副标题），左标题 + 可选副标题，右尾元素。
 *
 * 多行 [SettingRow] 直接堆 [HairlineCard] 里 — 分割用 [SettingRowDivider]。
 */
@Composable
fun SettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    titleColor: androidx.compose.ui.graphics.Color = Gomob.colors.fg0,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(if (subtitle == null) Gomob.spacing.rowSetting else 64.dp)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = Gomob.spacing.s16),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
            Text(text = title, style = Gomob.type.body, color = titleColor)
            if (subtitle != null) {
                Text(text = subtitle, style = Gomob.type.caption, color = Gomob.colors.fg2)
            }
        }
        if (trailing != null) {
            Box(Modifier.padding(start = Gomob.spacing.s12)) { trailing() }
        }
    }
}

/** 行间分割线。两端 16dp 内缩，颜色用最浅的 line1。 */
@Composable
fun SettingRowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s16)
            .height(Gomob.spacing.hairline)
            .background(Gomob.colors.line1),
    )
}
