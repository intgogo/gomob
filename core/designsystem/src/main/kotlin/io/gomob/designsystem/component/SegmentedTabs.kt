package io.gomob.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.gomob.designsystem.theme.Gomob

/** 分段控制器一段。`badge` 可选，用于"4 / 127"等右侧计数。 */
data class SegmentedTabItem(
    val label: String,
    val badge: String? = null,
)

/**
 * Segmented Control —— iOS 风格的分段切换器。
 *
 * 视觉契约：
 *   - 外壳 r2 圆角 + 1dp line2 描边 + 透明底
 *   - 内部等宽分 N 段，段间 1dp line2 竖分隔
 *   - 选中段 accentSoft 填充 + accent 文字；未选 fg2 文字
 *
 * 用作"页面顶部主分类切换"，多页内 tab 复用 ([MessageScreen]、[CollaborationScreen])。
 */
@Composable
fun SegmentedTabs(
    items: List<SegmentedTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(Gomob.shapes.r2)
            .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2),
    ) {
        items.forEachIndexed { i, item ->
            val active = i == selectedIndex
            val fg = if (active) Gomob.colors.accent else Gomob.colors.fg2
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (active) Gomob.colors.accentSoft else Color.Transparent)
                    .clickable { onSelect(i) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(item.label, fontSize = 12.sp, color = fg)
                if (item.badge != null) {
                    Spacer(Modifier.width(Gomob.spacing.s6))
                    Text(
                        item.badge,
                        style = Gomob.type.numInline.copy(fontSize = 12.sp),
                        color = fg.copy(alpha = 0.7f),
                    )
                }
            }
            if (i != items.lastIndex) {
                Box(
                    Modifier
                        .width(Gomob.spacing.hairline)
                        .fillMaxHeight()
                        .background(Gomob.colors.line2),
                )
            }
        }
    }
}
