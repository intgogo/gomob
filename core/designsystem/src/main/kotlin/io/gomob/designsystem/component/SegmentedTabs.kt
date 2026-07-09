package io.gomob.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 *   - 外壳 r2 圆角 + bg1 底
 *   - 内部等宽分 N 段，不画分隔线
 *   - 选中段 accentSoft 滑动指示块（切换时平滑滑移）+ accent 文字；未选 fg2 文字
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
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1),
    ) {
        val segmentWidth = maxWidth / items.size.coerceAtLeast(1)
        val thumbOffset by animateDpAsState(
            targetValue = segmentWidth * selectedIndex,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            label = "segmented-thumb",
        )
        // 滑动指示块：内缩 3dp 的 accentSoft 圆角块，切换时滑移
        Box(
            Modifier
                .offset(x = thumbOffset)
                .width(segmentWidth)
                .fillMaxHeight()
                .padding(3.dp)
                .clip(Gomob.shapes.r1)
                .background(Gomob.colors.accentSoft),
        )
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { i, item ->
                val active = i == selectedIndex
                val fg by animateColorAsState(
                    targetValue = if (active) Gomob.colors.accent else Gomob.colors.fg2,
                    animationSpec = tween(durationMillis = 220),
                    label = "segmented-fg",
                )
                val interaction = remember { MutableInteractionSource() }
                Row(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                        ) { onSelect(i) },
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
            }
        }
    }
}
