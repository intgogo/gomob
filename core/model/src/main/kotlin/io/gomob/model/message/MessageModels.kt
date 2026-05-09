package io.gomob.model.message

data class ConversationSummary(
    val id: Long,
    val kind: String,
    val title: String?,
    val peer: ConversationPeer?,
    val subjectKind: String?,
    val subjectId: Long?,
    val lastMessage: MessageRecord?,
    val lastReadSeq: Long,
    val unreadCount: Long,
    val createdAt: String,
    val updatedAt: String,
)

data class ConversationPeer(
    val id: Long,
    val name: String,
    val employeeId: String?,
)

data class HelpExpert(
    val userId: Long,
    val name: String,
    val employeeId: String,
    val roleTitle: String,
    val specialty: String,
    val availability: String,
)

data class HelpExpertCase(
    val id: Long,
    val authorId: Long,
    val title: String,
    val summary: String,
    val category: String,
    val publishedAt: String,
)

data class MessageRecord(
    val localKey: String,
    val serverId: Long?,
    val conversationId: Long,
    val serverSeq: Long?,
    val senderId: Long?,
    val kind: String,
    val payloadJson: String,
    val preview: String? = null,
    val clientMsgId: String?,
    val status: MessageStatus,
    val createdAt: String,
    val editedAt: String? = null,
)

enum class MessageStatus {
    Pending,
    Sent,
    Failed,
}

data class LiveSessionSummary(
    val id: Long,
    val mediaRoomId: Long,
    val publisherId: Long,
    val title: String,
    val status: String,
    val startedAt: String?,
    val updatedAt: String,
)
