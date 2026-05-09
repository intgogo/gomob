package io.gomob.feature.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.gomob.designsystem.theme.Gomob
import io.gomob.model.message.MessageStatus

@Composable
internal fun ChatMessageRow(
    bubble: MessageBubbleUi,
    onRetry: () -> Unit,
) {
    val hasSenderLabel = !bubble.mine && !bubble.senderLabel.isNullOrBlank()
    val statusText = when (bubble.status) {
        MessageStatus.Pending -> "发送中"
        MessageStatus.Failed -> "发送失败"
        MessageStatus.Sent -> bubble.time
    }
    val statusColor = when (bubble.status) {
        MessageStatus.Failed -> Gomob.colors.danger
        MessageStatus.Pending -> Gomob.colors.warn
        MessageStatus.Sent -> Gomob.colors.fg3
    }

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val avatarSize = 36.dp
        val avatarGap = Gomob.spacing.s8
        val bubbleMaxWidth = minOf(maxWidth * 0.72f, maxWidth - avatarSize - avatarGap)
            .coerceAtLeast(120.dp)
        val avatarTopPadding = if (hasSenderLabel) 18.dp else 0.dp

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (bubble.mine) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            if (!bubble.mine) {
                ChatAvatar(
                    initials = bubble.avatarInitials,
                    mine = false,
                    modifier = Modifier.padding(top = avatarTopPadding),
                )
                Spacer(Modifier.width(avatarGap))
            } else {
                Spacer(Modifier.weight(1f))
            }

            Column(
                horizontalAlignment = if (bubble.mine) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
            ) {
                if (hasSenderLabel) {
                    Text(
                        bubble.senderLabel.orEmpty(),
                        style = Gomob.type.numInline.copy(fontSize = 10.sp),
                        color = Gomob.colors.fg3,
                        modifier = Modifier.padding(start = Gomob.spacing.s2),
                    )
                }
                ChatBubble(
                    bubble = bubble,
                    maxWidth = bubbleMaxWidth,
                    onRetry = onRetry,
                )
                Text(
                    statusText,
                    style = Gomob.type.numInline,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = Gomob.spacing.s2),
                )
            }

            if (bubble.mine) {
                Spacer(Modifier.width(avatarGap))
                ChatAvatar(
                    initials = bubble.avatarInitials,
                    mine = true,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ChatBubble(
    bubble: MessageBubbleUi,
    maxWidth: Dp,
    onRetry: () -> Unit,
) {
    val bubbleBg = if (bubble.mine) WechatMineBubble else Gomob.colors.bg1
    val bubbleLine = when (bubble.status) {
        MessageStatus.Failed -> Gomob.colors.dangerLine
        MessageStatus.Pending -> Gomob.colors.warnLine
        MessageStatus.Sent -> if (bubble.mine) WechatMineBubbleLine else Gomob.colors.line1
    }
    val textColor = if (bubble.mine) Color(0xF5000000) else Gomob.colors.fg0

    Box(
        Modifier
            .widthIn(max = maxWidth)
            .clip(Gomob.shapes.r2)
            .background(bubbleBg)
            .border(Gomob.spacing.hairline, bubbleLine, Gomob.shapes.r2)
            .let {
                if (bubble.status == MessageStatus.Failed) {
                    it.clickable(onClick = onRetry)
                } else {
                    it
                }
            }
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
    ) {
        Text(bubble.text, style = Gomob.type.bodySm, color = textColor)
    }
}

private val WechatMineBubble = Color(0xFF95EC69)
private val WechatMineBubbleLine = Color(0xFF7DD354)

@Composable
private fun ChatAvatar(
    initials: String,
    mine: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(36.dp)
            .clip(Gomob.shapes.r2)
            .semantics { contentDescription = "头像 $initials" }
            .background(if (mine) Gomob.colors.accentSoft else Gomob.colors.bg2)
            .border(
                Gomob.spacing.hairline,
                if (mine) Gomob.colors.accentLine else Gomob.colors.line2,
                Gomob.shapes.r2,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials.take(2),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (mine) Gomob.colors.accentStrong else Gomob.colors.fg1,
        )
    }
}
