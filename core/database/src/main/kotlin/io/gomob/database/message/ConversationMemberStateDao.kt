package io.gomob.database.message

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationMemberStateDao {
    @Query(
        """
        SELECT * FROM conversation_member_states
        WHERE conversationId = :conversationId AND userId = :userId
        LIMIT 1
        """,
    )
    fun observeState(conversationId: Long, userId: Long): Flow<ConversationMemberStateEntity?>

    @Query(
        """
        SELECT * FROM conversation_member_states
        WHERE conversationId = :conversationId AND userId = :userId
        LIMIT 1
        """,
    )
    suspend fun findState(conversationId: Long, userId: Long): ConversationMemberStateEntity?

    @Upsert
    suspend fun upsertState(state: ConversationMemberStateEntity)
}
