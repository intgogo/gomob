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
    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC, id DESC")
    fun observeConversations(): Flow<List<ConversationWithLastMessage>>

    @Transaction
    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    fun observeConversation(conversationId: Long): Flow<ConversationWithLastMessage?>

    @Transaction
    @Query("SELECT * FROM conversations WHERE subjectKind = :subjectKind ORDER BY updatedAt DESC, id DESC LIMIT 1")
    fun observeLatestBySubjectKind(subjectKind: String): Flow<ConversationWithLastMessage?>

    @Transaction
    @Query("SELECT * FROM conversations ORDER BY pinned DESC, updatedAt DESC, id DESC LIMIT :limit")
    suspend fun recentConversations(limit: Int): List<ConversationWithLastMessage>

    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun findById(conversationId: Long): ConversationEntity?

    @Upsert
    suspend fun upsertConversations(items: List<ConversationEntity>)

    @Upsert
    suspend fun upsertConversation(item: ConversationEntity)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :conversationId")
    suspend fun setPinned(conversationId: Long, pinned: Boolean)

    @Query("DELETE FROM conversations WHERE id = :conversationId")
    suspend fun deleteById(conversationId: Long)

    @Query(
        """
        UPDATE conversations
        SET clearedBeforeSeq = CASE
                WHEN clearedBeforeSeq > :clearedBeforeSeq THEN clearedBeforeSeq
                ELSE :clearedBeforeSeq
            END,
            lastMessageLocalKey = NULL
        WHERE id = :conversationId
        """,
    )
    suspend fun markCleared(conversationId: Long, clearedBeforeSeq: Long)

    @Query(
        """
        UPDATE conversations
        SET lastReadSeq = max(lastReadSeq, :lastReadSeq),
            unreadCount = :unreadCount
        WHERE id = :conversationId
        """,
    )
    suspend fun markRead(conversationId: Long, lastReadSeq: Long, unreadCount: Long)

    @Query(
        """
        UPDATE conversations
        SET lastMessageLocalKey = :localKey,
            updatedAt = :updatedAt,
            unreadCount = CASE
                WHEN :incrementUnread AND :serverSeq > lastReadSeq THEN unreadCount + 1
                ELSE unreadCount
            END
        WHERE id = :conversationId
          AND (
              lastMessageLocalKey IS NULL
              OR COALESCE((SELECT serverSeq FROM messages WHERE localKey = lastMessageLocalKey), 0) <= :serverSeq
          )
        """,
    )
    suspend fun recordLastMessage(
        conversationId: Long,
        localKey: String,
        serverSeq: Long,
        updatedAt: String,
        incrementUnread: Boolean,
    )
}
