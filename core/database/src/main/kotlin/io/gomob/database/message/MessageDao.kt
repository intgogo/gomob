package io.gomob.database.message

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query(
        """
        SELECT m.* FROM messages m
        JOIN conversations c ON c.id = m.conversationId
        WHERE m.conversationId = :conversationId
          AND (m.serverSeq IS NULL OR m.serverSeq > c.clearedBeforeSeq)
        ORDER BY COALESCE(m.serverSeq, 9223372036854775807), m.createdAt ASC
        """,
    )
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query(
        """
        SELECT m.* FROM messages m
        JOIN conversations c ON c.id = m.conversationId
        WHERE m.conversationId = :conversationId
          AND (m.serverSeq IS NULL OR m.serverSeq > c.clearedBeforeSeq)
        ORDER BY COALESCE(m.serverSeq, 9223372036854775807) DESC, m.createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun recentMessages(conversationId: Long, limit: Int): List<MessageEntity>

    @Query("SELECT max(COALESCE(serverSeq, 0)) FROM messages WHERE conversationId = :conversationId")
    suspend fun maxServerSeq(conversationId: Long): Long?

    @Query("SELECT * FROM messages WHERE clientMsgId = :clientMsgId LIMIT 1")
    suspend fun findByClientMsgId(clientMsgId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE serverId = :serverId LIMIT 1")
    suspend fun findByServerId(serverId: Long): MessageEntity?

    @Upsert
    suspend fun upsertServerMessages(items: List<MessageEntity>)

    @Upsert
    suspend fun upsertMessage(item: MessageEntity)

    @Query(
        """
        UPDATE messages
        SET localKey = 's:' || :serverId,
            serverId = :serverId,
            serverSeq = :serverSeq,
            status = 'Sent',
            createdAt = :createdAt
        WHERE clientMsgId = :clientMsgId
        """,
    )
    suspend fun markDelivered(clientMsgId: String, serverId: Long, serverSeq: Long, createdAt: String)

    @Query("UPDATE messages SET status = 'Failed' WHERE clientMsgId = :clientMsgId AND status = 'Pending'")
    suspend fun markFailed(clientMsgId: String)

    @Query("UPDATE messages SET status = 'Pending' WHERE clientMsgId = :clientMsgId AND status = 'Failed'")
    suspend fun markPending(clientMsgId: String)

    @Query(
        """
        UPDATE messages
        SET payloadJson = :payloadJson,
            preview = :preview,
            editedAt = :updatedAt
        WHERE serverId = :serverId
        """,
    )
    suspend fun updateServerMessagePayload(
        serverId: Long,
        payloadJson: String,
        preview: String?,
        updatedAt: String,
    )

    @Query(
        """
        DELETE FROM messages
        WHERE conversationId = :conversationId
          AND (serverSeq IS NULL OR serverSeq <= :clearedBeforeSeq)
        """,
    )
    suspend fun deleteClearedMessages(conversationId: Long, clearedBeforeSeq: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: Long)
}
