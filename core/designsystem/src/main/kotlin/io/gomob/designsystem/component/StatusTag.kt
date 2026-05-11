package io.gomob.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import io.gomob.designsystem.theme.Gomob

enum class StatusTone { Neutral, Accent, Warn, Danger, Ok }

/**
 * 短标签 / 状态徽。20dp 高，r1 圆角，可选状态点。
 *
 * 不要拿来做"按钮" — 它是只读语义标签。要可点的胶囊请用 [Chip]。
 */
@Composable
fun StatusTag(
    text: String,
    tone: StatusTone = StatusTone.Neutral,
    showDot: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val (fg, bg) = colorsFor(tone)
    Row(
        modifier
            .height(Gomob.spacing.chipHeight)
            .clip(Gomob.shapes.r1)
            .background(bg)
            .padding(horizontal = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
    ) {
        if (showDot) {
            Box(Modifier.size(Gomob.spacing.dot6).clip(CircleShape).background(fg))
        }
        Text(text = text, style = Gomob.type.caption, color = fg)
    }
}

@Composable
private fun colorsFor(tone: StatusTone): Pair<Color, Color> = when (tone) {
    StatusTone.Neutral -> Gomob.colors.fg1 to Gomob.colors.bg2
    StatusTone.Accent -> Gomob.colors.accent to Gomob.colors.accentSoft
    StatusTone.Warn -> Gomob.colors.warn to Gomob.colors.warnSoft
    StatusTone.Danger -> Gomob.colors.danger to Gomob.colors.dangerSoft
    StatusTone.Ok -> Gomob.colors.ok to Gomob.colors.okSoft
}
