package io.gomob.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.theme.Gomob

data class HeaderTabItem(
    val label: String,
    val showDot: Boolean = false,
)

/** Header 内文字页签：44dp 命中区，选中态用 18×3dp 下划线。 */
@Composable
fun HeaderTabs(
    items: List<HeaderTabItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .height(Gomob.spacing.touchMin)
            // 设计：tab 行左右 18、tab 间 22
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            val active = index == selectedIndex
            val color by animateColorAsState(
                targetValue = if (active) Gomob.colors.accent else Gomob.colors.fg2,
                animationSpec = tween(durationMillis = 180),
                label = "header-tab-color",
            )
            val interaction = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .height(Gomob.spacing.touchMin)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { onSelect(index) },
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.label,
                        style = if (active) {
                            Gomob.type.bodySm.copy(fontWeight = FontWeight.SemiBold)
                        } else {
                            Gomob.type.bodySm
                        },
                        color = color,
                    )
                    if (item.showDot) {
                        Spacer(Modifier.width(Gomob.spacing.s6))
                        Box(
                            Modifier
                                .size(Gomob.spacing.dot6)
                                .clip(CircleShape)
                                .background(Gomob.colors.accent),
                        )
                    }
                }
                Spacer(Modifier.height(Gomob.spacing.s4))
                Box(
                    Modifier
                        .width(18.dp)
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (active) Gomob.colors.accent else androidx.compose.ui.graphics.Color.Transparent),
                )
            }
        }
    }
}
