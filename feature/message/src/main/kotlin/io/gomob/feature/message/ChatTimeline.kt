package io.gomob.feature.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.theme.Gomob

private const val CHAT_TIME_GAP_MILLIS = 5 * 60 * 1000L

internal sealed interface ChatTimelineItem {
    val key: String

    data class TimeDivider(
        override val key: String,
        val label: String,
    ) : ChatTimelineItem

    data class Message(
        override val key: String,
        val bubble: MessageBubbleUi,
    ) : ChatTimelineItem
}

internal fun buildChatTimeline(messages: List<MessageBubbleUi>): List<ChatTimelineItem> {
    if (messages.isEmpty()) return emptyList()
    val items = mutableListOf<ChatTimelineItem>()
    var previousMillis: Long? = null
    messages.forEachIndexed { index, bubble ->
        val currentMillis = bubble.createdAtEpochMillis
        val shouldShowTime = bubble.timeDividerLabel.isNotBlank() &&
            (index == 0 || (currentMillis != null && previousMillis != null &&
                currentMillis - previousMillis > CHAT_TIME_GAP_MILLIS))
        if (shouldShowTime) {
            items += ChatTimelineItem.TimeDivider(
                key = "time:${bubble.localKey}",
                label = bubble.timeDividerLabel,
            )
        }
        items += ChatTimelineItem.Message(
            key = "message:${bubble.localKey}",
            bubble = bubble,
        )
        if (currentMillis != null) {
            previousMillis = currentMillis
        }
    }
    return items
}

@Composable
internal fun ChatTimeDivider(label: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Gomob.spacing.s4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = Gomob.type.caption,
            color = Gomob.colors.fg3,
            modifier = Modifier
                .clip(Gomob.shapes.r1)
                .background(Gomob.colors.bg1.copy(alpha = 0.78f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}
