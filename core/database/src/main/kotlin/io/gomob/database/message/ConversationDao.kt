package io.gomob.database.message

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class ConversationWithLastMessage(
    @androidx.room.Embedded val conversation: ConversationEntity,
    @androidx.room.Relation(
        parentColumn = "lastMessageLocalKey",
        entityColumn = "localKey",
    )
    val lastMessage: MessageEntity?,
)

@Dao
interface ConversationDao {
    @Transaction
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC, id DESC")
    fun observeConversations(): Flow<List<ConversationWithLastMessage>>

    @Transaction
    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    fun observeConversation(conversationId: Long): Flow<ConversationWithLastMessage?>

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun findById(conversationId: Long): ConversationEntity?

    @Upsert
    suspend fun upsertConversations(items: List<ConversationEntity>)

    @Upsert
    suspend fun upsertConversation(item: ConversationEntity)

    @Query(
        """
        UPDATE conversations
        SET lastReadSeq = max(lastReadSeq, :lastReadSeq),
            unreadCount = :unreadCount
        WHERE id = :conversationId
        """,
    )
    suspend fun markRead(conversationId: Long, lastReadSeq: Long, unreadCount: Long)
}
