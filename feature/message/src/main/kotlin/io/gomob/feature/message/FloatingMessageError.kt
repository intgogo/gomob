package io.gomob.feature.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

internal enum class FloatingMessageTone { Info, Danger }

@Composable
internal fun FloatingMessageError(
    text: String?,
    modifier: Modifier = Modifier,
    tone: FloatingMessageTone = FloatingMessageTone.Danger,
    onClick: (() -> Unit)? = null,
) {
    val style = floatingMessageStyle(tone)
    val shape = Gomob.shapes.pill
    AnimatedVisibility(
        visible = !text.isNullOrBlank(),
        modifier = modifier,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
    ) {
        Row(
            Modifier
                .padding(horizontal = Gomob.spacing.s20)
                .clip(shape)
                .background(style.container)
                .border(0.5.dp, style.line, shape)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = Gomob.spacing.s12, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            Icon(
                style.icon,
                contentDescription = null,
                tint = style.iconColor,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text.orEmpty(),
                style = Gomob.type.bodySm,
                color = style.textColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onClick != null) {
                Icon(
                    GomobIcons.Refresh,
                    contentDescription = "重试",
                    tint = style.actionColor,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

private data class FloatingMessageStyle(
    val icon: ImageVector,
    val container: Color,
    val line: Color,
    val iconColor: Color,
    val textColor: Color,
    val actionColor: Color,
)

@Composable
private fun floatingMessageStyle(tone: FloatingMessageTone): FloatingMessageStyle = when (tone) {
    FloatingMessageTone.Info -> FloatingMessageStyle(
        icon = GomobIcons.Cache,
        container = if (Gomob.colors.isLight) Color.White.copy(alpha = 0.96f) else Gomob.colors.bg2.copy(alpha = 0.96f),
        line = Gomob.colors.line2,
        iconColor = Gomob.colors.accent,
        textColor = Gomob.colors.fg1,
        actionColor = Gomob.colors.fg2,
    )
    FloatingMessageTone.Danger -> FloatingMessageStyle(
        icon = GomobIcons.AlertCircle,
        container = if (Gomob.colors.isLight) Color.White.copy(alpha = 0.98f) else Gomob.colors.bg2.copy(alpha = 0.98f),
        line = Gomob.colors.dangerLine,
        iconColor = Gomob.colors.danger,
        textColor = Gomob.colors.fg0,
        actionColor = Gomob.colors.danger,
    )
}
