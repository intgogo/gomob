package io.gomob.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ConversationListResponse(
    val items: List<ConversationDto>,
    @SerialName("next_cursor") val nextCursor: String = "0",
    @SerialName("has_more") val hasMore: Boolean = false,
)

@Serializable
data class HelpExpertListResponse(
    val items: List<HelpExpertDto>,
)

@Serializable
data class HelpExpertCaseListResponse(
    val items: List<HelpExpertCaseDto>,
)

@Serializable
data class HelpExpertDto(
    @SerialName("user_id") val userId: String,
    val name: String,
    @SerialName("employee_id") val employeeId: String,
    @SerialName("role_title") val roleTitle: String,
    val specialty: String,
    val availability: String,
)

@Serializable
data class HelpExpertCaseDto(
    val id: String,
    @SerialName("author_id") val authorId: String,
    val title: String,
    val summary: String,
    val category: String,
    @SerialName("published_at") val publishedAt: String,
)

@Serializable
data class ConversationDto(
    val id: String,
    val kind: String,
    val title: String? = null,
    val peer: ConversationPeerDto? = null,
    @SerialName("subject_kind") val subjectKind: String? = null,
    @SerialName("subject_id") val subjectId: String? = null,
    @SerialName("last_message") val lastMessage: MessageDto? = null,
    @SerialName("last_read_seq") val lastReadSeq: Long = 0,
    @SerialName("unread_count") val unreadCount: Long = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
)

@Serializable
data class ConversationPeerDto(
    val id: String,
    val name: String,
    @SerialName("employee_id") val employeeId: String? = null,
)

@Serializable
data class MessageListResponse(
    val items: List<MessageDto>,
    @SerialName("next_since_seq") val nextSinceSeq: Long = 0,
)

@Serializable
data class MessageDto(
    val id: String,
    @SerialName("conversation_id") val conversationId: String? = null,
    @SerialName("server_seq") val serverSeq: Long,
    @SerialName("sender_id") val senderId: String? = null,
    val kind: String,
    val payload: JsonElement? = null,
    @SerialName("client_msg_id") val clientMsgId: String? = null,
    val preview: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class CreateMessageRequest(
    @SerialName("client_msg_id") val clientMsgId: String,
    val kind: String,
    val payload: JsonElement,
)

@Serializable
data class TranscribeDraftVoiceRequest(
    @SerialName("asset_id") val assetId: String,
    val language: String = "zh",
)

@Serializable
data class TranscribeDraftVoiceResponse(
    val text: String,
    @SerialName("normalized_text") val normalizedText: String = "",
    val segments: JsonElement? = null,
    val confidence: Float? = null,
    val engine: String = "",
    val model: String = "",
    val language: String = "",
)

@Serializable
data class CreateCallInviteRequest(
    @SerialName("client_msg_id") val clientMsgId: String,
    val title: String? = null,
)

@Serializable
data class CallInviteResponse(
    val room: MediaRoomResponse,
    val message: MessageDto,
)

@Serializable
data class OpenDirectConversationRequest(
    @SerialName("peer_user_id") val peerUserId: String? = null,
    @SerialName("peer_employee_id") val peerEmployeeId: String? = null,
)

@Serializable
data class OpenAdHocGroupRequest(
    @SerialName("member_user_ids") val memberUserIds: List<String>,
    val title: String? = null,
)

@Serializable
data class MarkReadRequest(
    @SerialName("last_read_seq") val lastReadSeq: Long,
)

@Serializable
data class MarkReadResponse(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("last_read_seq") val lastReadSeq: Long,
    @SerialName("unread_count") val unreadCount: Long,
)

@Serializable
data class LeaveConversationResponse(
    @SerialName("conversation_id") val conversationId: String,
    val left: Boolean,
)

@Serializable
data class ContactListResponse(
    val items: List<ContactDto>,
)

@Serializable
data class ContactDto(
    @SerialName("user_id") val userId: String,
    val name: String,
    @SerialName("employee_id") val employeeId: String,
    val username: String = "",
    val role: String,
    @SerialName("station_id") val stationId: String = "",
    @SerialName("station_name") val stationName: String = "",
)
