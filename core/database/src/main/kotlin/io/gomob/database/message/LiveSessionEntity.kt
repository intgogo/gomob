package io.gomob.database.message

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.gomob.model.message.LiveSessionSummary

@Entity(
    tableName = "live_sessions",
    indices = [Index(value = ["status", "updatedAt"])],
)
data class LiveSessionEntity(
    @PrimaryKey val id: Long,
    val mediaRoomId: Long,
    val publisherId: Long,
    val title: String,
    val status: String,
    val startedAt: String?,
    val updatedAt: String,
) {
    fun toDomain(): LiveSessionSummary = LiveSessionSummary(
        id = id,
        mediaRoomId = mediaRoomId,
        publisherId = publisherId,
        title = title,
        status = status,
        startedAt = startedAt,
        updatedAt = updatedAt,
    )
}
