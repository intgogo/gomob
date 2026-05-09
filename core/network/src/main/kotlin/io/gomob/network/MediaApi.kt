package io.gomob.network

import io.gomob.network.dto.CreateLiveSessionRequest
import io.gomob.network.dto.CreateMediaRoomRequest
import io.gomob.network.dto.LiveSessionListResponse
import io.gomob.network.dto.LiveSessionResponse
import io.gomob.network.dto.MediaRoomResponse
import io.gomob.network.dto.MediaRoomTokenRequest
import io.gomob.network.dto.MediaRoomTokenResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MediaApi {
    @POST("v1/media/rooms")
    suspend fun createRoom(
        @Body request: CreateMediaRoomRequest,
    ): Envelope<MediaRoomResponse>

    @POST("v1/media/rooms/{id}/token")
    suspend fun roomToken(
        @Path("id") roomId: String,
        @Body request: MediaRoomTokenRequest,
    ): Envelope<MediaRoomTokenResponse>

    @POST("v1/media/rooms/{id}/end")
    suspend fun endRoom(
        @Path("id") roomId: String,
    ): Envelope<MediaRoomResponse>

    @POST("v1/live-sessions")
    suspend fun createLiveSession(
        @Body request: CreateLiveSessionRequest,
    ): Envelope<LiveSessionResponse>

    @GET("v1/live-sessions")
    suspend fun liveSessions(
        @Query("status") status: String? = "live",
        @Query("limit") limit: Int = 50,
    ): Envelope<LiveSessionListResponse>
}
