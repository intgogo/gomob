package io.gomob.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import io.gomob.designsystem.glass.glassPanelBg
import io.gomob.designsystem.motion.pressScale
import io.gomob.designsystem.theme.Gomob

/**
 * 标准卡片：拟玻璃面板（半透明底 + 顶缘高光 + 细边），稳定留白。
 * 半透明底透出屏幕氛围光晕（AmbientGlow）形成玻璃质感；可点卡片带按压缩放反馈。
 *
 * padding 默认 14dp，可以传 0 给全出血卡片。
 */
@Composable
fun HairlineCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = Gomob.shapes.r3,
    padding: Dp = Gomob.spacing.cardPadding,
    borderColor: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .then(if (onClick != null) Modifier.pressScale(interaction) else Modifier)
            .glassPanelBg(shape = shape, borderColor = borderColor)
            .let {
                if (onClick != null) {
                    it.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    it
                }
            },
    ) {
        Box(Modifier.padding(padding)) { content() }
    }
}
