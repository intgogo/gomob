package io.gomob.designsystem.motion

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 按压缩放反馈：按下弹性缩到 [pressedScale]，松开回弹。
 * 与 ripple 叠加或替代 ripple 均可；工业风克制取值（卡片 0.985，小件 0.94）。
 *
 * 用法：与产生按压事件的 clickable 共享同一个 [MutableInteractionSource]。
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.985f,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "press-scale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}
