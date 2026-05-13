package io.gomob.feature.message

import com.google.common.truth.Truth.assertThat
import io.gomob.model.message.MessageStatus
import org.junit.Test

class ChatTimelineTest {
    @Test
    fun localSendingMessageKeepsTimelineKeyAfterDelivered() {
        val pending = timelineBubble(
            localKey = "c:img-1",
            serverId = null,
            clientMsgId = "img-1",
        )
        val delivered = timelineBubble(
            localKey = "s:303",
            serverId = 303,
            clientMsgId = "img-1",
        )

        val pendingKeys = buildChatTimeline(listOf(pending)).map { it.key }
        val deliveredKeys = buildChatTimeline(listOf(delivered)).map { it.key }

        assertThat(pendingKeys).containsExactlyElementsIn(deliveredKeys).inOrder()
        assertThat(pendingKeys).containsExactly("time:client:img-1", "message:client:img-1").inOrder()
    }
}

private fun timelineBubble(
    localKey: String,
    serverId: Long?,
    clientMsgId: String?,
): MessageBubbleUi = MessageBubbleUi(
    localKey = localKey,
    serverId = serverId,
    kind = "image",
    text = "[图片]",
    mine = true,
    senderUserId = 1,
    senderLabel = null,
    avatarKey = "me",
    time = "12:00",
    timeDividerLabel = "12:00",
    createdAtEpochMillis = 1_746_704_000_000,
    status = MessageStatus.Sent,
    clientMsgId = clientMsgId,
)
