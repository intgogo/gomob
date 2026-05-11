package io.gomob.feature.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.model.message.MessageStatus

@Composable
internal fun ChatMessageRow(
    bubble: MessageBubbleUi,
    onRetry: () -> Unit,
    onRetryTranscript: () -> Unit = {},
    onOpenInspection: (String) -> Unit = {},
    onAcceptCall: (CallInviteUi) -> Unit = {},
) {
    val hasSenderLabel = !bubble.mine && !bubble.senderLabel.isNullOrBlank()
    val statusText = when (bubble.status) {
        MessageStatus.Pending -> "发送中"
        MessageStatus.Failed -> null
        MessageStatus.Sent -> bubble.time
    }
    val statusColor = when (bubble.status) {
        MessageStatus.Pending -> Gomob.colors.warn
        MessageStatus.Sent -> Gomob.colors.fg3
        MessageStatus.Failed -> Gomob.colors.fg3
    }
    val showRetryIcon = bubble.status == MessageStatus.Failed && bubble.clientMsgId != null

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
                    seed = bubble.avatarKey,
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
                ) {
                    if (bubble.mine && showRetryIcon) {
                        MessageRetryWarning(onClick = onRetry)
                    }
                    ChatBubble(
                        bubble = bubble,
                        maxWidth = bubbleMaxWidth,
                        onOpenInspection = onOpenInspection,
                        onAcceptCall = onAcceptCall,
                        onRetryTranscript = onRetryTranscript,
                    )
                }
                statusText?.let {
                    Text(
                        it,
                        style = Gomob.type.numInline,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = Gomob.spacing.s2),
                    )
                }
            }

            if (bubble.mine) {
                Spacer(Modifier.width(avatarGap))
                ChatAvatar(
                    seed = bubble.avatarKey,
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
    onOpenInspection: (String) -> Unit,
    onAcceptCall: (CallInviteUi) -> Unit,
    onRetryTranscript: () -> Unit,
) {
    val bubbleBg = if (bubble.mine) WechatMineBubble else Gomob.colors.bg1
    val textColor = if (bubble.mine) Color(0xF5000000) else Gomob.colors.fg0

    val card = bubble.inspectionCard
    val call = bubble.callInvite

    if (card != null) {
        InspectionMessageCard(
            card = card,
            maxWidth = maxWidth,
            onOpenInspection = onOpenInspection,
        )
    } else if (call != null) {
        VideoCallInviteCard(
            call = call,
            mine = bubble.mine,
            maxWidth = maxWidth,
            onAcceptCall = onAcceptCall,
        )
    } else if (bubble.isVoice) {
        VoiceMessageBubble(
            bubble = bubble,
            maxWidth = maxWidth,
            bubbleBg = bubbleBg,
            textColor = textColor,
            onRetryTranscript = onRetryTranscript,
        )
    } else {
        Box(
            Modifier
                .widthIn(max = maxWidth)
                .clip(Gomob.shapes.r2)
                .background(bubbleBg)
                .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        ) {
            Text(bubble.text, style = Gomob.type.bodySm, color = textColor)
        }
    }
}

@Composable
private fun VoiceMessageBubble(
    bubble: MessageBubbleUi,
    maxWidth: Dp,
    bubbleBg: Color,
    textColor: Color,
    onRetryTranscript: () -> Unit,
) {
    val transcript = bubble.voiceTranscript
    val actionLabel = transcript.voiceTranscriptActionLabel(bubble.serverId)
    Column(
        Modifier
            .widthIn(max = maxWidth)
            .clip(Gomob.shapes.r2)
            .background(bubbleBg)
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                GomobIcons.VoiceCircle,
                contentDescription = null,
                tint = textColor.copy(alpha = 0.82f),
                modifier = Modifier.size(17.dp),
            )
            Text(bubble.text, style = Gomob.type.bodySm, color = textColor, maxLines = 1)
        }
        Spacer(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(textColor.copy(alpha = 0.10f)),
        )
        Text(
            transcript.displayText(bubble.serverId),
            style = Gomob.type.caption,
            color = when (transcript?.status) {
                "failed" -> Gomob.colors.danger
                "pending", "processing" -> Gomob.colors.fg3
                else -> textColor
            },
        )
        if (actionLabel != null) {
            Row(
                Modifier
                    .clip(Gomob.shapes.r1)
                    .clickable(onClick = onRetryTranscript)
                    .padding(horizontal = Gomob.spacing.s6, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    GomobIcons.Search,
                    contentDescription = actionLabel,
                    tint = if (bubble.mine) Color(0xD9000000) else Gomob.colors.accent,
                    modifier = Modifier.size(13.dp),
                )
                Text(
                    actionLabel,
                    style = Gomob.type.caption,
                    color = if (bubble.mine) Color(0xD9000000) else Gomob.colors.accent,
                    maxLines = 1,
                )
            }
        }
    }
}

private fun VoiceTranscriptUi?.displayText(messageId: Long?): String = when (this?.status) {
    "done" -> text.orEmpty().ifBlank { "转写结果为空" }
    "failed" -> error?.takeIf { it.isNotBlank() }?.let { "转写失败：$it" } ?: "转写失败，可重试"
    "processing" -> "转写中"
    "pending" -> "等待转写"
    else -> if (messageId == null || messageId <= 0) "等待上传完成" else "可转成文字"
}

private fun VoiceTranscriptUi?.voiceTranscriptActionLabel(messageId: Long?): String? {
    if (messageId == null || messageId <= 0) return null
    return when (this?.status) {
        null -> "转文字"
        "failed" -> "重新转文字"
        else -> null
    }
}

@Composable
private fun VideoCallInviteCard(
    call: CallInviteUi,
    mine: Boolean,
    maxWidth: Dp,
    onAcceptCall: (CallInviteUi) -> Unit,
) {
    Column(
        Modifier
            .widthIn(max = maxWidth)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1)
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.VideoCall,
                    contentDescription = null,
                    tint = Gomob.colors.accent,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(call.title, style = Gomob.type.bodySm, color = Gomob.colors.fg0, maxLines = 1)
                Text(
                    if (mine) "已发起，等待对方接受" else "邀请你视频通话",
                    style = Gomob.type.caption,
                    color = Gomob.colors.fg2,
                    maxLines = 1,
                )
            }
        }
        if (!mine && call.status == "ringing") {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .clip(Gomob.shapes.r2)
                        .background(Gomob.colors.accent)
                        .clickable { onAcceptCall(call) }
                        .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s6),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("接受", style = Gomob.type.caption, color = Gomob.colors.bg0)
                }
            }
        }
    }
}

@Composable
private fun MessageRetryWarning(onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(Gomob.shapes.pill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            GomobIcons.AlertCircle,
            contentDescription = "重新发送",
            tint = Gomob.colors.danger,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun InspectionMessageCard(
    card: InspectionCardUi,
    maxWidth: Dp,
    onOpenInspection: (String) -> Unit,
) {
    Column(
        Modifier
            .widthIn(max = maxWidth)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1)
            .clickable { onOpenInspection(card.inspectionId) }
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
            ) {
                Icon(
                    GomobIcons.LinkShare,
                    contentDescription = null,
                    tint = Gomob.colors.accent,
                    modifier = Modifier.size(15.dp),
                )
                Text("业务流水", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
            }
            StatusTag(
                text = card.status.toInspectionStatusText(),
                tone = card.status.toInspectionStatusTone(),
                showDot = true,
            )
        }
        Text(
            card.vin,
            style = Gomob.type.numInline.copy(fontSize = 14.sp),
            color = Gomob.colors.fg0,
            maxLines = 1,
        )
        Text(
            card.vehicleLine,
            style = Gomob.type.caption,
            color = Gomob.colors.fg2,
            maxLines = 2,
        )
        if (card.tags.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                card.tags.take(2).forEach { tag ->
                    Box(
                        Modifier
                            .clip(Gomob.shapes.r1)
                            .background(Gomob.colors.bg2)
                            .padding(horizontal = Gomob.spacing.s6, vertical = 2.dp),
                    ) {
                        Text(tag, fontSize = 10.sp, color = Gomob.colors.fg2, maxLines = 1)
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(card.timeLabel.ifBlank { "查看流水详情" }, style = Gomob.type.numInline, color = Gomob.colors.fg3)
            Icon(
                GomobIcons.ChevronRight,
                contentDescription = "查看流水详情",
                tint = Gomob.colors.fg3,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private fun String.toInspectionStatusText(): String = when (this) {
    "ok", "pass", "normal" -> "正常"
    "danger", "fail", "abnormal" -> "异常"
    else -> "预警"
}

private fun String.toInspectionStatusTone(): StatusTone = when (this) {
    "ok", "pass", "normal" -> StatusTone.Ok
    "danger", "fail", "abnormal" -> StatusTone.Danger
    else -> StatusTone.Warn
}

private val WechatMineBubble = Color(0xFF95EC69)

@Composable
private fun ChatAvatar(
    seed: String,
    mine: Boolean,
    modifier: Modifier = Modifier,
) {
    MessageAvatarImage(
        seed = if (mine) "current-user-$seed" else seed,
        size = 36.dp,
        shape = Gomob.shapes.r2,
        modifier = modifier,
    )
}
