package io.gomob.database.message

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LiveSessionDao {
    @Query("SELECT * FROM live_sessions WHERE status = :status ORDER BY updatedAt DESC")
    fun observeByStatus(status: String): Flow<List<LiveSessionEntity>>

    @Upsert
    suspend fun upsert(items: List<LiveSessionEntity>)
}
