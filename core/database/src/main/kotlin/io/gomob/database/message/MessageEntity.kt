package io.gomob.database.message

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.gomob.model.message.MessageRecord
import io.gomob.model.message.MessageStatus

@Entity(
    tableName = "messages",
    indices = [
        Index(value = ["serverId"], unique = true),
        Index(value = ["conversationId", "serverSeq"], unique = true),
        Index(value = ["clientMsgId"], unique = true),
        Index(value = ["conversationId", "createdAt"]),
    ],
)
data class MessageEntity(
    @PrimaryKey val localKey: String,
    val serverId: Long?,
    val conversationId: Long,
    val serverSeq: Long?,
    val senderId: Long?,
    val kind: String,
    val payloadJson: String,
    val preview: String?,
    val clientMsgId: String?,
    val status: String,
    val createdAt: String,
    val editedAt: String?,
    val recalledAt: String? = null,
) {
    fun toDomain(): MessageRecord = MessageRecord(
        localKey = localKey,
        serverId = serverId,
        conversationId = conversationId,
        serverSeq = serverSeq,
        senderId = senderId,
        kind = kind,
        payloadJson = payloadJson,
        preview = preview,
        clientMsgId = clientMsgId,
        status = runCatching { MessageStatus.valueOf(status) }.getOrDefault(MessageStatus.Sent),
        createdAt = createdAt,
        editedAt = editedAt,
        recalledAt = recalledAt,
    )
}
