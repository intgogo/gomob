package io.gomob.database.message

import androidx.room.ColumnInfo
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
    @ColumnInfo(defaultValue = "0")
    val folded: Boolean = false,
    @ColumnInfo(defaultValue = "''")
    val displayName: String = "",
    @ColumnInfo(defaultValue = "''")
    val remark: String = "",
    @ColumnInfo(defaultValue = "''")
    val announcement: String = "",
    @ColumnInfo(defaultValue = "'[]'")
    val addedMembersJson: String = "[]",
    @ColumnInfo(defaultValue = "'[]'")
    val removedMemberIdsJson: String = "[]",
    val updatedAt: String,
)
