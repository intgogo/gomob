package io.gomob.feature.message

import com.google.common.truth.Truth.assertThat
import io.gomob.model.message.ConversationSummary
import io.gomob.model.message.MessageRecord
import io.gomob.model.message.MessageStatus
import kotlinx.serialization.json.Json
import org.junit.Test

class MessageViewModelsTest {
    @Test
    fun visibleMessageConversationsFiltersGroupRoomsFromMessageList() {
        val p2p = conversationSummary(id = 1, kind = "p2p", subjectKind = null, title = null)
        val help = conversationSummary(id = 2, subjectKind = "online_help", title = "在线求助")
        val review = conversationSummary(id = 3, subjectKind = "review", title = "复核会话")
        val legacyHelp = conversationSummary(id = 4, subjectKind = null, title = "在线求助")
        val directReview = conversationSummary(id = 5, kind = "p2p", subjectKind = "review", title = "复核专员")

        val visible = visibleMessageConversations(listOf(p2p, help, review, legacyHelp, directReview))

        assertThat(visible.map { it.id }).containsExactly(1L, 5L).inOrder()
    }

    @Test
    fun multiLineConversationsKeepsExpertLineFirstThenGroupRooms() {
        val p2p = conversationSummary(id = 1, kind = "p2p", subjectKind = null, title = null)
        val help = conversationSummary(id = 2, subjectKind = "online_help", title = "在线求助")
        val review = conversationSummary(id = 3, subjectKind = "review", title = "复核会话")
        val legacyHelp = conversationSummary(id = 4, subjectKind = null, title = "在线求助")

        val rooms = multiLineConversations(listOf(p2p, review, legacyHelp, help))

        assertThat(rooms.map { it.id }).containsExactly(2L, 3L).inOrder()
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
    fun messageListSearchTextIncludesNestedPayloadFields() {
        val message = messageRecord(
            kind = "inspection_card",
            payloadJson = """
                {
                  "inspection_id":"INSP-42",
                  "vin":"LSVNV2182N0123456",
                  "vehicle_line":"重卡牵引车",
                  "tags":["VIN 复核","外观异常"]
                }
            """.trimIndent(),
            preview = "[流水] LSVNV2182N0123456",
        )

        val text = message.messageListSearchText(testJson)

        assertThat(text).contains("LSVNV2182N0123456")
        assertThat(text).contains("重卡牵引车")
        assertThat(text).contains("外观异常")
    }

    @Test
    fun imageMediaAttachmentPrefersLocalUriForDisplay() {
        val message = messageRecord(
            kind = "image",
            payloadJson = """
                {
                  "media_state":"ready",
                  "local_uri":"content://local/capture.jpg",
                  "download_url":"https://example.test/asset.jpg",
                  "mime":"image/jpeg"
                }
            """.trimIndent(),
            preview = "[图片]",
        )

        val media = message.mediaAttachmentPayload(testJson)

        assertThat(media?.imageSource).isEqualTo("content://local/capture.jpg")
        assertThat(media?.downloadUrl).isEqualTo("https://example.test/asset.jpg")
        assertThat(media?.mime).isEqualTo("image/jpeg")
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

    @Test
    fun completedVideoCallInviteCanRedial() {
        val bubble = messageBubble(
            callInvite = CallInviteUi(
                roomId = "701",
                providerRoom = "gomob_call_701",
                title = "和陈若愚的视频通话",
                status = "completed",
                liveKitConfigured = true,
                message = null,
                durationSec = 75,
            ),
        )

        assertThat(bubble.canRedialVideoCall()).isTrue()
        assertThat(bubble.videoCallRedialTitle()).isEqualTo("和陈若愚的视频通话")
    }

    @Test
    fun ringingVideoCallInviteDoesNotRedial() {
        val bubble = messageBubble(
            callInvite = CallInviteUi(
                roomId = "701",
                providerRoom = "gomob_call_701",
                title = "和陈若愚的视频通话",
                status = "ringing",
                liveKitConfigured = true,
                message = null,
            ),
        )

        assertThat(bubble.canRedialVideoCall()).isFalse()
    }

    @Test
    fun videoCallResultCanRedialButAudioResultCannot() {
        val video = messageBubble(
            callResult = CallResultUi(
                kind = "video_call",
                title = "视频通话",
                status = "missed",
                statusText = "未接通",
                durationSec = null,
                failureReason = "对方未接听",
            ),
        )
        val audio = messageBubble(
            callResult = CallResultUi(
                kind = "audio_call",
                title = "语音通话",
                status = "missed",
                statusText = "未接通",
                durationSec = null,
                failureReason = "对方未接听",
            ),
        )

        assertThat(video.canRedialVideoCall()).isTrue()
        assertThat(audio.canRedialVideoCall()).isFalse()
    }
}

private val testJson = Json { ignoreUnknownKeys = true }

private fun conversationSummary(
    id: Long,
    kind: String = "group",
    subjectKind: String?,
    title: String?,
): ConversationSummary = ConversationSummary(
    id = id,
    kind = kind,
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

private fun messageBubble(
    callInvite: CallInviteUi? = null,
    callResult: CallResultUi? = null,
): MessageBubbleUi = MessageBubbleUi(
    localKey = "s:101",
    serverId = 101,
    kind = callInvite?.let { "call_invite" } ?: callResult?.kind ?: "text",
    text = "消息",
    mine = false,
    senderUserId = 31,
    senderLabel = null,
    avatarKey = "peer-31",
    time = "12:00",
    timeDividerLabel = "12:00",
    createdAtEpochMillis = 1_746_704_000_000,
    status = MessageStatus.Sent,
    clientMsgId = null,
    callInvite = callInvite,
    callResult = callResult,
)
