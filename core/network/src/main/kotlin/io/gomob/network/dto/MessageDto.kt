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
)

@Serializable
data class CreateMessageRequest(
    @SerialName("client_msg_id") val clientMsgId: String,
    val kind: String,
    val payload: JsonElement,
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
