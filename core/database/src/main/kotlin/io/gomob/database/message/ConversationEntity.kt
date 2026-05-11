package io.gomob.database.message

import androidx.room.Entity
import androidx.room.Index
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey
import io.gomob.model.message.ConversationPeer
import io.gomob.model.message.ConversationSummary

@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["updatedAt"]),
        Index(value = ["pinned", "updatedAt"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey val id: Long,
    val kind: String,
    val title: String?,
    val peerId: Long?,
    val peerName: String?,
    val peerEmployeeId: String?,
    val subjectKind: String?,
    val subjectId: Long?,
    val lastMessageLocalKey: String?,
    val lastReadSeq: Long,
    val unreadCount: Long,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val clearedBeforeSeq: Long = 0,
    val createdAt: String,
    val updatedAt: String,
) {
    fun toDomain(lastMessage: MessageEntity?): ConversationSummary = ConversationSummary(
        id = id,
        kind = kind,
        title = title,
        peer = peerId?.let {
            ConversationPeer(
                id = it,
                name = peerName.orEmpty(),
                employeeId = peerEmployeeId,
            )
        },
        subjectKind = subjectKind,
        subjectId = subjectId,
        lastMessage = lastMessage?.toDomain(),
        lastReadSeq = lastReadSeq,
        unreadCount = unreadCount,
        pinned = pinned,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
