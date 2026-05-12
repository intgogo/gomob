package io.gomob.feature.message

import com.google.common.truth.Truth.assertThat
import io.gomob.model.message.ConversationSummary
import io.gomob.model.message.MessageRecord
import io.gomob.model.message.MessageStatus
import kotlinx.serialization.json.Json
import org.junit.Test

class MessageViewModelsTest {
    @Test
    fun visibleMessageConversationsFiltersOnlineHelpRoom() {
        val p2p = conversationSummary(id = 1, subjectKind = null, title = null)
        val help = conversationSummary(id = 2, subjectKind = "online_help", title = "在线求助")
        val review = conversationSummary(id = 3, subjectKind = "review", title = "复核会话")
        val legacyHelp = conversationSummary(id = 4, subjectKind = null, title = "在线求助")

        val visible = visibleMessageConversations(listOf(p2p, help, review, legacyHelp))

        assertThat(visible.map { it.id }).containsExactly(1L, 3L).inOrder()
    }

    @Test
    fun conversationStartsBlankInsteadOfLoading() {
        val state = ConversationUiState(conversationId = 9)

        assertThat(state.loading).isFalse()
        assertThat(state.empty).isTrue()
    }

    @Test
    fun videoCallPreviewPrefersPayloadDurationOverGenericPreview() {
        val message = messageRecord(
            kind = "video_call",
            payloadJson = """{"status":"completed","duration_sec":75}""",
            preview = "[视频通话]",
        )

        assertThat(message.previewText(testJson)).isEqualTo("[视频通话 1:15]")
        assertThat(message.callResultPayload(testJson)?.durationText).isEqualTo("1:15")
    }

    @Test
    fun videoCallFailurePreviewShowsReason() {
        val message = messageRecord(
            kind = "video_call",
            payloadJson = """{"status":"failed","reason":"LiveKit token 过期"}""",
            preview = "[视频通话]",
        )

        val result = message.callResultPayload(testJson)

        assertThat(message.previewText(testJson)).isEqualTo("[视频通话失败] LiveKit token 过期")
        assertThat(result?.succeeded).isFalse()
        assertThat(result?.failureReason).isEqualTo("LiveKit token 过期")
    }

    @Test
    fun voiceTranscriptDisplayShowsUnrecognizedForEmptyText() {
        assertThat(
            VoiceTranscriptUi(status = "done", text = null, error = null)
                .voiceTranscriptDisplayText(messageId = 401),
        ).isEqualTo("未识别到文字")
        assertThat(
            VoiceTranscriptUi(status = "failed", text = null, error = "ASR 未识别出有效文本")
                .voiceTranscriptDisplayText(messageId = 401),
        ).isEqualTo("未识别到文字")
    }

    @Test
    fun callResultMessageMergesIntoOriginalInvite() {
        val invite = messageRecord(
            kind = "call_invite",
            payloadJson = """{"room_id":"701","title":"和陈若愚的视频通话","status":"ringing"}""",
            preview = "[视频通话] 和陈若愚的视频通话",
        ).copy(localKey = "s:201", serverId = 201, serverSeq = 9)
        val result = messageRecord(
            kind = "video_call",
            payloadJson = """{"room_id":"701","status":"completed","duration_sec":75}""",
            preview = "[视频通话]",
        ).copy(localKey = "s:202", serverId = 202, serverSeq = 10)

        val merged = listOf(invite, result).mergeCallResultMessages(testJson)

        assertThat(merged).hasSize(1)
        assertThat(merged.single().kind).isEqualTo("call_invite")
        assertThat(merged.single().payloadJson).contains("\"status\":\"completed\"")
        assertThat(merged.single().payloadJson).contains("\"duration_sec\":75")
        assertThat(merged.single().previewText(testJson)).isEqualTo("[视频通话 1:15]")
    }
}

private val testJson = Json { ignoreUnknownKeys = true }

private fun conversationSummary(
    id: Long,
    subjectKind: String?,
    title: String?,
): ConversationSummary = ConversationSummary(
    id = id,
    kind = "group",
    title = title,
    peer = null,
    subjectKind = subjectKind,
    subjectId = null,
    lastMessage = null,
    lastReadSeq = 0,
    unreadCount = 0,
    createdAt = "2026-05-08T11:00:00Z",
    updatedAt = "2026-05-08T12:00:00Z",
)

private fun messageRecord(
    kind: String,
    payloadJson: String,
    preview: String?,
): MessageRecord = MessageRecord(
    localKey = "s:101",
    serverId = 101,
    conversationId = 9,
    serverSeq = 5,
    senderId = 31,
    kind = kind,
    payloadJson = payloadJson,
    preview = preview,
    clientMsgId = null,
    status = MessageStatus.Sent,
    createdAt = "2026-05-08T12:00:00Z",
    editedAt = null,
)
