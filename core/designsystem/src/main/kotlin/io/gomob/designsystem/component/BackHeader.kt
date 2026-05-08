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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

/**
 * 二级页顶栏:返回箭头 + 标题(可选 eyebrow)+ trailing 槽位 + 底部 hairline。
 *
 * 与 [ScreenHeader] 区分:
 * - ScreenHeader 是 root tab 的大字号 display 标题(无返回)
 * - BackHeader 是二级页面的紧凑标题(有返回)
 */
@Composable
fun BackHeader(
    title: String,
    onBack: () -> Unit,
    eyebrow: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().background(Gomob.colors.bg0)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.headerHeight)
                .padding(start = 0.dp, end = Gomob.spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(Gomob.spacing.touchMin)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = GomobIcons.ChevronLeft,
                        contentDescription = "返回",
                        modifier = Modifier.size(26.dp),
                        tint = Gomob.colors.fg1,
                    )
                }
                Column(
                    Modifier.padding(start = Gomob.spacing.s4),
                    verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
                ) {
                    if (eyebrow != null) {
                        Text(eyebrow, style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    }
                    Text(title, style = Gomob.type.title, color = Gomob.colors.fg0)
                }
            }
            if (trailing != null) trailing()
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.hairline)
                .background(Gomob.colors.line1),
        )
    }
}
