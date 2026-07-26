package io.gomob.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.glass.glassChrome
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
 * 底部 tab 条。玻璃底（内容从底下穿过时透出模糊背景），吃导航栏 inset。
 *
 * 不画"指示器条" — 选中态靠图标 / 文字颜色 + 图标弹性放大。
 */
@Composable
fun TabBar(
    items: List<TabItem>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    embedded: Boolean = false,
) {
    TabBarFrame(modifier, embedded = embedded) {
        items.forEach { item ->
            TabBarCell(
                active = item.key == selectedKey,
                label = item.label,
                onClick = { onSelect(item.key) },
                modifier = Modifier.weight(1f),
            ) { tint, iconModifier ->
                Image(
                    painter = item.icon,
                    contentDescription = item.label,
                    modifier = iconModifier.size(20.dp),
                    colorFilter = ColorFilter.tint(tint),
                )
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
    embedded: Boolean = false,
) {
    TabBarFrame(modifier, embedded = embedded) {
        items.forEach { item ->
            TabBarCell(
                active = item.key == selectedKey,
                label = item.label,
                onClick = { onSelect(item.key) },
                modifier = Modifier.weight(1f),
            ) { tint, iconModifier ->
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                    modifier = iconModifier.size(20.dp),
                    tint = tint,
                )
            }
        }
    }
}

/** 玻璃条外框：56dp 行 + 导航栏穿透区，顶缘分隔线常显。 */
@Composable
private fun TabBarFrame(
    modifier: Modifier = Modifier,
    embedded: Boolean,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    val frameModifier = (if (embedded) modifier else modifier.fixedDuringPageDrag())
        .fillMaxWidth()
        .let { base ->
            if (embedded) base else base.glassChrome(topEdge = true).navigationBarsPadding()
        }
    Column(
        frameModifier,
    ) {
        Row(
            Modifier.fillMaxWidth().height(Gomob.spacing.tabBarHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            content = content,
        )
    }
}

/**
 * 单个 tab 格：颜色过渡 + 选中图标弹性放大 + 按压回缩。
 * 去掉方块 ripple —— 反馈全靠缩放与颜色，跟玻璃质感更配。
 */
@Composable
private fun TabBarCell(
    active: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable (tint: Color, modifier: Modifier) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val tint by animateColorAsState(
        targetValue = if (active) Gomob.colors.accent else Gomob.colors.fg2,
        animationSpec = tween(durationMillis = 200),
        label = "tab-tint",
    )
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            pressed -> 0.88f
            active -> 1.08f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "tab-scale",
    )
    Column(
        modifier
            .fillMaxHeight()
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = Gomob.spacing.s8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
    ) {
        icon(
            tint,
            Modifier.graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        )
        Text(text = label, style = Gomob.type.micro, color = tint)
    }
}
