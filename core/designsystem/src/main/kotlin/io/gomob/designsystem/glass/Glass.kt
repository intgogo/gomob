package io.gomob.designsystem.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeChild
import io.gomob.designsystem.theme.Gomob
import io.gomob.designsystem.theme.GomobColors

/**
 * gomob · 毛玻璃体系
 *
 * 分两档，按"玻璃下面有没有高频内容穿过"选：
 * 1. **真模糊 chrome**（[glassChrome]）— TabBar / Header / 来电浮窗这类悬浮在滚动内容
 *    之上的条。走 Haze backdrop blur（API 31+ 真模糊，以下自动降级半透明遮罩）。
 * 2. **拟玻璃面板**（[GlassPanel] / [glassPanelBg]）— Dialog / BottomSheet 独立 window
 *    采样不到 Activity 内容，卡片下面只有低频氛围光晕（模糊结果 ≈ 原样）。
 *    这两类用"半透明底 + 顶缘高光 + 细边"拿到同款质感，零模糊开销。
 *
 * HazeState 由外层（Shell / GlassHeaderScaffold）经 [LocalHazeState] 下发；
 * 消费组件不自己 remember，保证 hazeSource / hazeEffect 配对同一个 state。
 */

/** 当前层可用的 haze 采样源；null = 没有真模糊可用（预览 / Dialog window / 未接线）。 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/** chrome 玻璃样式 — 从语义色板派生，不引入新原色。 */
fun glassChromeStyle(colors: GomobColors): HazeStyle {
    return HazeStyle(
        backgroundColor = colors.bg0,
        tints = listOf(HazeTint(colors.bg0.copy(alpha = 0.72f))),
        blurRadius = 20.dp,
        noiseFactor = 0.02f,
        // API < 31 降级：不模糊，只上高不透明度遮罩，保证可读
        fallbackTint = HazeTint(colors.bg0.copy(alpha = 0.94f)),
    )
}

/**
 * 悬浮 chrome 玻璃：真模糊 + 上/下缘细线 + 缘内 1dp 高光。
 *
 * @param topEdge 顶缘画分隔线（底部 TabBar 用）
 * @param bottomEdge 底缘画分隔线（顶部 Header 用）
 * @param edgeAlpha 分隔线透明度 0..1（Header 滚动渐显传动画值；常显传 1f）
 */
@Composable
fun Modifier.glassChrome(
    topEdge: Boolean = false,
    bottomEdge: Boolean = false,
    edgeAlpha: Float = 1f,
): Modifier {
    val colors = Gomob.colors
    val hazeState = LocalHazeState.current
    val base = if (hazeState != null) {
        val style = remember(colors) { glassChromeStyle(colors) }
        this.hazeChild(state = hazeState, style = style)
    } else {
        // 无采样源（@Preview / 独立 window）→ 拟玻璃底
        this.background(colors.bg0.copy(alpha = 0.94f))
    }
    val line = colors.line2
    val highlight = colors.hlTop
    return base.drawWithContent {
        drawContent()
        val hair = 1.dp.toPx()
        if (topEdge && edgeAlpha > 0f) {
            drawLine(line.copy(alpha = line.alpha * edgeAlpha), Offset(0f, 0f), Offset(size.width, 0f), hair)
            drawLine(highlight, Offset(0f, hair), Offset(size.width, hair), hair)
        }
        if (bottomEdge && edgeAlpha > 0f) {
            drawLine(
                line.copy(alpha = line.alpha * edgeAlpha),
                Offset(0f, size.height - hair / 2f),
                Offset(size.width, size.height - hair / 2f),
                hair,
            )
        }
    }
}

/**
 * 拟玻璃面板底：半透明 bg1 + 顶缘 1dp 高光 + line1 细边。
 * 用于卡片 / Dialog / BottomSheet — 不做真模糊（见文件头说明）。
 *
 * @param shape 圆角（与卡片一致默认 r3）
 * @param alpha 底色不透明度；卡片场景默认 0.88，可读性优先
 */
@Composable
fun Modifier.glassPanelBg(
    shape: RoundedCornerShape = Gomob.shapes.r3,
    alpha: Float = 0.88f,
    borderColor: Color? = null,
): Modifier {
    val colors = Gomob.colors
    val resolvedBorderColor = borderColor ?: colors.line1
    return this
        .clip(shape)
        .background(colors.bg1.copy(alpha = alpha))
        .border(Gomob.spacing.hairline, resolvedBorderColor, shape)
        .drawWithContent {
            drawContent()
            // 顶缘 1dp 渐变高光：中间亮两端淡，模拟玻璃上棱受光
            val hair = 1.dp.toPx()
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, colors.hlTop, Color.Transparent),
                ),
                start = Offset(0f, hair / 2f),
                end = Offset(size.width, hair / 2f),
                strokeWidth = hair,
            )
        }
}

/**
 * 拟玻璃面板容器 — Dialog / BottomSheet / 浮层的标准表面。
 * Dialog 独立 window 采样不到主内容，所以统一走拟玻璃（半透明 + 高光 + 细边）。
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = Gomob.shapes.r3,
    alpha: Float = 0.94f,
    content: @Composable () -> Unit,
) {
    Box(modifier.glassPanelBg(shape = shape, alpha = alpha)) {
        content()
    }
}

/**
 * 屏幕氛围底纹：bg0 上两团极淡的 accent 光晕（左上大 + 右下小）。
 * 作用：给半透明卡片"透出点什么"，玻璃质感才成立；纯 bg0 下玻璃与实底无差别。
 * 静态绘制（无动画、无模糊），滚动零开销。
 */
@Composable
fun AmbientGlow(modifier: Modifier = Modifier) {
    val colors = Gomob.colors
    Box(
        modifier.drawWithContent {
            val w = size.width
            val h = size.height
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(colors.accent.copy(alpha = 0.10f), Color.Transparent),
                    center = Offset(w * 0.12f, h * -0.02f),
                    radius = w * 0.95f,
                ),
            )
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(colors.accentStrong.copy(alpha = 0.05f), Color.Transparent),
                    center = Offset(w * 0.96f, h * 0.92f),
                    radius = w * 0.75f,
                ),
            )
            drawContent()
        },
    )
}

/** 供组件在无 token 场景推导玻璃降级底色（如自绘浮层）。 */
fun GomobColors.glassFallback(alpha: Float = 0.94f): Color = bg0.copy(alpha = alpha)
