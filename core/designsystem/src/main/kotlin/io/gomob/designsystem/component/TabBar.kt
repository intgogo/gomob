package io.gomob.designsystem.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.motion.fixedDuringPageDrag
import io.gomob.designsystem.theme.Gomob

/** Tab 项。`icon` 用 Painter（[androidx.compose.ui.graphics.vector.rememberVectorPainter] 也行）。 */
data class TabItem(
    val key: String,
    val label: String,
    val icon: Painter,
)

/** Tab 项简化版：直接用 ImageVector，组件内部 vector→painter。 */
data class TabItemVector(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

/**
 * 底部 tab 条。56dp 高。选中色 = accent，未选中 = fg2。
 *
 * 不画"指示器条" — 选中态完全靠图标 + 文字颜色变化。
 */
@Composable
fun TabBar(
    items: List<TabItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fixedDuringPageDrag().fillMaxWidth().background(Gomob.colors.bg0)) {
        Row(
            Modifier.fillMaxWidth().height(Gomob.spacing.tabBarHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEach { item ->
                val active = item.key == selectedKey
                val tint = if (active) Gomob.colors.accent else Gomob.colors.fg2
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(item.key) }
                        .padding(vertical = Gomob.spacing.s8),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
                ) {
                    Image(
                        painter = item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.size(20.dp),
                        colorFilter = ColorFilter.tint(tint),
                    )
                    Text(text = item.label, style = Gomob.type.micro, color = tint)
                }
            }
        }
    }
}

/** 用 ImageVector 的便捷重载（避免 caller 自己 wrap painter）。 */
@Composable
fun TabBarVector(
    items: List<TabItemVector>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fixedDuringPageDrag().fillMaxWidth().background(Gomob.colors.bg0)) {
        Row(
            Modifier.fillMaxWidth().height(Gomob.spacing.tabBarHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            items.forEach { item ->
                val active = item.key == selectedKey
                val tint = if (active) Gomob.colors.accent else Gomob.colors.fg2
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onSelect(item.key) }
                        .padding(vertical = Gomob.spacing.s8),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.size(20.dp),
                        tint = tint,
                    )
                    Text(text = item.label, style = Gomob.type.micro, color = tint)
                }
            }
        }
    }
}
