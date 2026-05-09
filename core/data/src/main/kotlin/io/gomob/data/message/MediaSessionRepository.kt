package io.gomob.data.message

import io.gomob.database.message.LiveSessionDao
import io.gomob.database.message.LiveSessionEntity
import io.gomob.model.message.LiveSessionSummary
import io.gomob.network.ApiException
import io.gomob.network.MediaApi
import io.gomob.network.dto.CreateLiveSessionRequest
import io.gomob.network.dto.MediaRoomTokenRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaSessionRepository @Inject constructor(
    private val api: MediaApi,
    private val liveSessionDao: LiveSessionDao,
) {
    suspend fun startFirstPersonLive(title: String, conversationId: Long? = null): LiveSessionStartResult {
        val resp = api.createLiveSession(
            CreateLiveSessionRequest(
                title = title,
                conversationId = conversationId?.toString(),
            ),
        )
        val session = resp.data ?: throw ApiException(50001, 500, "直播会话响应缺数据")
        liveSessionDao.upsert(listOf(session.toEntity()))
        if (session.status != "live") {
            return LiveSessionStartResult.Unavailable(session.title)
        }
        val tokenResp = api.roomToken(
            roomId = session.mediaRoomId,
            request = MediaRoomTokenRequest(role = "publisher"),
        )
        val token = tokenResp.data ?: throw ApiException(50001, 500, "媒体 token 响应缺数据")
        return LiveSessionStartResult.Ready(
            session = session.toEntity().toDomain(),
            url = token.url,
            token = token.token,
            providerRoom = token.providerRoom,
        )
    }

    suspend fun refreshLiveSessions() {
        val resp = api.liveSessions(status = "live")
        val data = resp.data ?: throw ApiException(50001, 500, "直播列表响应缺数据")
        liveSessionDao.upsert(data.items.map { it.toEntity() })
    }

    suspend fun joinLiveSession(liveSessionId: Long): LiveSessionJoinResult {
        val cached = liveSessionDao.findById(liveSessionId)
            ?: run {
                refreshLiveSessions()
                liveSessionDao.findById(liveSessionId)
            }
            ?: throw IllegalArgumentException("直播会话不存在")
        val tokenResp = api.roomToken(
            roomId = cached.mediaRoomId.toString(),
            request = MediaRoomTokenRequest(role = "viewer"),
        )
        val token = tokenResp.data ?: throw ApiException(50001, 500, "媒体 token 响应缺数据")
        return LiveSessionJoinResult(
            session = cached.toDomain(),
            url = token.url,
            token = token.token,
            providerRoom = token.providerRoom,
        )
    }
}

sealed interface LiveSessionStartResult {
    data class Ready(
        val session: LiveSessionSummary,
        val url: String,
        val token: String,
        val providerRoom: String,
    ) : LiveSessionStartResult

    data class Unavailable(val title: String) : LiveSessionStartResult
}

data class LiveSessionJoinResult(
    val session: LiveSessionSummary,
    val url: String,
    val token: String,
    val providerRoom: String,
)

private fun io.gomob.network.dto.LiveSessionResponse.toEntity(): LiveSessionEntity =
    LiveSessionEntity(
        id = id.toLong(),
        mediaRoomId = mediaRoomId.toLong(),
        publisherId = publisherId.toLong(),
        title = title,
        status = status,
        startedAt = startedAt,
        updatedAt = updatedAt,
    )
