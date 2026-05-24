package io.gomob.network

import io.gomob.network.dto.ContactListResponse
import io.gomob.network.dto.ConversationListResponse
import io.gomob.network.dto.ConversationDto
import io.gomob.network.dto.CreateMessageRequest
import io.gomob.network.dto.CallInviteResponse
import io.gomob.network.dto.CreateCallInviteRequest
import io.gomob.network.dto.HelpExpertCaseListResponse
import io.gomob.network.dto.HelpExpertListResponse
import io.gomob.network.dto.LeaveConversationResponse
import io.gomob.network.dto.MarkReadRequest
import io.gomob.network.dto.MarkReadResponse
import io.gomob.network.dto.MessageDto
import io.gomob.network.dto.MessageListResponse
import io.gomob.network.dto.OpenAdHocGroupRequest
import io.gomob.network.dto.OpenDirectConversationRequest
import io.gomob.network.dto.TranscribeDraftVoiceRequest
import io.gomob.network.dto.TranscribeDraftVoiceResponse
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

    @GET("v1/contacts")
    suspend fun contacts(
        @Query("q") query: String? = null,
        @Query("role") role: String? = null,
    ): Envelope<ContactListResponse>

    @GET("v1/conversations/help-experts/{id}/cases")
    suspend fun helpExpertCases(
        @Path("id") expertUserId: String,
    ): Envelope<HelpExpertCaseListResponse>

    @POST("v1/conversations/help-room")
    suspend fun openHelpRoom(): Envelope<ConversationDto>

    @POST("v1/conversations/p2p")
    suspend fun openDirectConversation(
        @Body request: OpenDirectConversationRequest,
    ): Envelope<ConversationDto>

    @POST("v1/conversations/ad-hoc")
    suspend fun openAdHocGroup(
        @Body request: OpenAdHocGroupRequest,
    ): Envelope<ConversationDto>

    @GET("v1/conversations/{id}/messages")
    suspend fun messages(
        @Path("id") conversationId: String,
        @Query("since_seq") sinceSeq: Long = 0,
        @Query("limit") limit: Int = 100,
        @Query("latest") latest: Boolean = false,
    ): Envelope<MessageListResponse>

    @POST("v1/conversations/{id}/messages")
    suspend fun sendMessage(
        @Path("id") conversationId: String,
        @Body request: CreateMessageRequest,
    ): Envelope<MessageDto>

    @POST("v1/conversations/{id}/messages/{messageId}/recall")
    suspend fun recallMessage(
        @Path("id") conversationId: String,
        @Path("messageId") messageId: String,
    ): Envelope<MessageDto>

    @POST("v1/conversations/{id}/call-invites")
    suspend fun createCallInvite(
        @Path("id") conversationId: String,
        @Body request: CreateCallInviteRequest,
    ): Envelope<CallInviteResponse>

    @POST("v1/messages/{id}/transcript/retry")
    suspend fun retryMessageTranscript(
        @Path("id") messageId: String,
    ): Envelope<MessageDto>

    @POST("v1/messages/transcribe-draft")
    suspend fun transcribeDraftVoice(
        @Body request: TranscribeDraftVoiceRequest,
    ): Envelope<TranscribeDraftVoiceResponse>

    @POST("v1/conversations/{id}/read")
    suspend fun markRead(
        @Path("id") conversationId: String,
        @Body request: MarkReadRequest,
    ): Envelope<MarkReadResponse>

    @POST("v1/conversations/{id}/leave")
    suspend fun leaveConversation(
        @Path("id") conversationId: String,
    ): Envelope<LeaveConversationResponse>
}
