package io.gomob.database.message

import androidx.room.Entity

@Entity(
    tableName = "conversation_member_states",
    primaryKeys = ["conversationId", "userId"],
)
data class ConversationMemberStateEntity(
    val conversationId: Long,
    val userId: Long,
    val lastReadSeq: Long,
    val muted: Boolean,
    val pinned: Boolean,
    val updatedAt: String,
)
