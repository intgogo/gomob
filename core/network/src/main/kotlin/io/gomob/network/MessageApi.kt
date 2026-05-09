package io.gomob.network

import io.gomob.network.dto.ConversationListResponse
import io.gomob.network.dto.ConversationDto
import io.gomob.network.dto.CreateMessageRequest
import io.gomob.network.dto.HelpExpertListResponse
import io.gomob.network.dto.MarkReadRequest
import io.gomob.network.dto.MarkReadResponse
import io.gomob.network.dto.MessageDto
import io.gomob.network.dto.MessageListResponse
import io.gomob.network.dto.OpenDirectConversationRequest
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MessageApi {
    @GET("v1/conversations")
    suspend fun conversations(
        @Query("cursor") cursor: String? = null,
        @Query("limit") limit: Int = 20,
    ): Envelope<ConversationListResponse>

    @GET("v1/conversations/help-experts")
    suspend fun helpExperts(): Envelope<HelpExpertListResponse>

    @POST("v1/conversations/p2p")
    suspend fun openDirectConversation(
        @Body request: OpenDirectConversationRequest,
    ): Envelope<ConversationDto>

    @GET("v1/conversations/{id}/messages")
    suspend fun messages(
        @Path("id") conversationId: String,
        @Query("since_seq") sinceSeq: Long = 0,
        @Query("limit") limit: Int = 100,
    ): Envelope<MessageListResponse>

    @POST("v1/conversations/{id}/messages")
    suspend fun sendMessage(
        @Path("id") conversationId: String,
        @Body request: CreateMessageRequest,
    ): Envelope<MessageDto>

    @POST("v1/conversations/{id}/read")
    suspend fun markRead(
        @Path("id") conversationId: String,
        @Body request: MarkReadRequest,
    ): Envelope<MarkReadResponse>
}
