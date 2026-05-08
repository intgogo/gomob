package io.gomob.database.message

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query(
        """
        SELECT * FROM messages
        WHERE conversationId = :conversationId
        ORDER BY COALESCE(serverSeq, 9223372036854775807), createdAt ASC
        """,
    )
    fun observeMessages(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT max(COALESCE(serverSeq, 0)) FROM messages WHERE conversationId = :conversationId")
    suspend fun maxServerSeq(conversationId: Long): Long?

    @Query("SELECT * FROM messages WHERE clientMsgId = :clientMsgId LIMIT 1")
    suspend fun findByClientMsgId(clientMsgId: String): MessageEntity?

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
}
