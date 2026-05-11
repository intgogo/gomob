package io.gomob.feature.message

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.ui.window.Dialog
import io.gomob.designsystem.theme.Gomob
import io.gomob.model.message.MessageQuote

internal data class QuoteDraftUi(
    val quote: MessageQuote,
)

internal fun MessageBubbleUi.toMessageQuote(): MessageQuote = MessageQuote(
    localKey = localKey,
    serverId = serverId,
    senderLabel = if (mine) "我" else senderLabel?.takeIf { it.isNotBlank() } ?: "对方",
    text = text,
)

internal fun messageShareText(messages: List<MessageBubbleUi>): String =
    messages.joinToString(separator = "\n") { bubble ->
        val sender = if (bubble.mine) "我" else bubble.senderLabel?.takeIf { it.isNotBlank() } ?: "对方"
        "$sender：${bubble.text}"
    }

internal fun Context.showMessageActionToast(text: String) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

@Composable
internal fun MessageMultiSelectBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Gomob.colors.bg1)
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Text(
            "已选择 $selectedCount 条",
            style = Gomob.type.bodySm,
            color = Gomob.colors.fg0,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        MessageSelectActionButton(
            label = "复制",
            icon = Icons.Filled.ContentCopy,
            enabled = selectedCount > 0,
            onClick = onCopy,
        )
        MessageSelectActionButton(
            label = "转发",
            icon = MessageForwardActionIcon,
            enabled = selectedCount > 0,
            onClick = onForward,
        )
        MessageSelectActionButton(
            label = "取消",
            icon = Icons.Filled.Close,
            enabled = true,
            onClick = onCancel,
        )
    }
}

@Composable
internal fun MessageForwardTargetDialog(
    visible: Boolean,
    targets: List<MessageForwardTargetUi>,
    messageCount: Int,
    onDismiss: () -> Unit,
    onSelectTarget: (MessageForwardTargetUi) -> Unit,
) {
    if (!visible) return
    Dialog(
        onDismissRequest = onDismiss,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 360.dp)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.bg1)
                .padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("转发给", style = Gomob.type.title, color = Gomob.colors.fg0)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    if (messageCount > 1) "共 $messageCount 条消息" else "1 条消息",
                    style = Gomob.type.caption,
                    color = Gomob.colors.fg3,
                )
                if (targets.isEmpty()) {
                    Text("暂无可转发联系人", style = Gomob.type.bodySm, color = Gomob.colors.fg2)
                } else {
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
                    ) {
                        items(targets, key = { it.stableKey }) { target ->
                            MessageForwardTargetRow(
                                target = target,
                                onClick = { onSelectTarget(target) },
                            )
                        }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(Gomob.shapes.r1)
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("取消", style = Gomob.type.bodySm, color = Gomob.colors.fg2)
            }
        }
    }
}

@Composable
private fun MessageForwardTargetRow(
    target: MessageForwardTargetUi,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r2)
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MessageAvatarImage(
            seed = "forward-${target.stableKey}-${target.title}",
            size = 38.dp,
            shape = Gomob.shapes.r1,
            online = target.peerUserId != null,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                target.title,
                style = Gomob.type.bodySm,
                color = Gomob.colors.fg0,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                target.subtitle,
                style = Gomob.type.caption,
                color = Gomob.colors.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            MessageForwardActionIcon,
            contentDescription = "转发",
            tint = Gomob.colors.accent,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun MessageSelectActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val fg = if (enabled) Gomob.colors.accent else Gomob.colors.fg3.copy(alpha = 0.5f)
    Box(
        Modifier
            .clip(Gomob.shapes.r2)
            .background(if (enabled) Gomob.colors.accentSoft else Gomob.colors.bg2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s6),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = Gomob.type.caption, color = fg)
        }
    }
}

internal val MessageForwardActionIcon: ImageVector
    get() {
        _messageForwardActionIcon?.let { return it }
        return ImageVector.Builder(
            name = "MessageForwardActionIcon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4.8f, 17.2f)
                curveTo(7.1f, 11.2f, 12.0f, 8.2f, 18.1f, 8.2f)
                moveTo(14.8f, 4.6f)
                lineTo(19.2f, 8.2f)
                lineTo(14.8f, 11.8f)
            }
        }.build().also { _messageForwardActionIcon = it }
    }

private var _messageForwardActionIcon: ImageVector? = null
