package io.gomob.database.message

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
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

    @Query(
        """
        SELECT m.* FROM messages m
        JOIN conversations c ON c.id = m.conversationId
        WHERE (m.serverSeq IS NULL OR m.serverSeq > c.clearedBeforeSeq)
          AND c.kind != 'group'
          AND (c.subjectKind IS NULL OR c.subjectKind != 'online_help')
          AND NOT (c.kind = 'group' AND c.title = '在线求助')
        ORDER BY m.createdAt DESC, COALESCE(m.serverSeq, 9223372036854775807) DESC
        LIMIT :limit
        """,
    )
    fun observeRecentSearchMessages(limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT max(COALESCE(serverSeq, 0)) FROM messages WHERE conversationId = :conversationId")
    suspend fun maxServerSeq(conversationId: Long): Long?

    @Query("SELECT * FROM messages WHERE clientMsgId = :clientMsgId LIMIT 1")
    suspend fun findByClientMsgId(clientMsgId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE localKey = :localKey LIMIT 1")
    suspend fun findByLocalKey(localKey: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE serverId = :serverId LIMIT 1")
    suspend fun findByServerId(serverId: Long): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertServerMessages(items: List<MessageEntity>)

    @Upsert
    suspend fun upsertMessage(item: MessageEntity)

    // TODO(db thread/原子性 终态): markDelivered 用 `UPDATE OR REPLACE` 改 PK localKey→'s:serverId'，
    //   若目标 's:serverId' 行已被 server 同步路径写入，OR REPLACE 会静默删掉那条已存在行，而
    //   conversation.lastMessageLocalKey 可能正指向它→摘要悬空；且 repository 里 markDelivered 后
    //   再调 ConversationDao.recordLastMessage 跨 DAO 非事务，半途失败也会摘要不一致。
    //   终态修法：把 MessageDao 改 abstract class 拆出 deleteCollidingServerRow + applyDelivered 用
    //   @Transaction 合并；跨 DAO 部分用 RoomDatabase.withTransaction 在 MessageRepository 包裹
    //   markDelivered+recordLastMessage。本轮不改：abstract class 化会破坏 out-of-scope 测试里的
    //   `class FakeMessageDao : MessageDao`(并行 agent 持有该测试文件)，跨 DAO 事务需 core:data 加
    //   room-ktx 依赖(build.gradle.kts 同样 out-of-scope)。等测试 Fake + 依赖一并改时落地。
    @Query(
        """
        UPDATE OR REPLACE messages
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

    @Query(
        """
        DELETE FROM messages
        WHERE conversationId = :conversationId
          AND serverSeq IS NOT NULL
          AND serverSeq < :minServerSeq
        """,
    )
    suspend fun deleteServerMessagesBefore(conversationId: Long, minServerSeq: Long)

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteByConversationId(conversationId: Long)

    @Query("DELETE FROM messages WHERE localKey = :localKey")
    suspend fun deleteByLocalKey(localKey: String)

    @Query(
        """
        UPDATE messages
        SET recalledAt = :recalledAt,
            payloadJson = '{}',
            preview = '[消息已撤回]'
        WHERE serverId = :serverId
        """,
    )
    suspend fun markRecalledByServerId(serverId: Long, recalledAt: String)
}
