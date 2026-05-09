package io.gomob.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateMediaRoomRequest(
    val kind: String,
    @SerialName("subject_kind") val subjectKind: String? = null,
    @SerialName("subject_id") val subjectId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
    val title: String? = null,
    @SerialName("participant_user_ids") val participantUserIds: List<String> = emptyList(),
)

@Serializable
data class MediaRoomResponse(
    val id: String,
    val provider: String,
    @SerialName("provider_room") val providerRoom: String,
    val kind: String,
    @SerialName("subject_kind") val subjectKind: String? = null,
    @SerialName("subject_id") val subjectId: String? = null,
    val status: String,
    @SerialName("livekit_url") val liveKitUrl: String? = null,
    @SerialName("livekit_configured") val liveKitConfigured: Boolean = false,
    val message: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class MediaRoomTokenRequest(
    val role: String = "viewer",
)

@Serializable
data class MediaRoomTokenResponse(
    @SerialName("room_id") val roomId: String,
    @SerialName("provider_room") val providerRoom: String,
    val url: String,
    val token: String,
    val identity: String,
    val role: String,
    @SerialName("ttl_sec") val ttlSeconds: Long,
)

@Serializable
data class CreateLiveSessionRequest(
    val title: String,
    @SerialName("inspection_id") val inspectionId: String? = null,
    @SerialName("conversation_id") val conversationId: String? = null,
)

@Serializable
data class LiveSessionResponse(
    val id: String,
    @SerialName("media_room_id") val mediaRoomId: String,
    @SerialName("publisher_id") val publisherId: String,
    val title: String,
    val status: String,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class LiveSessionListResponse(
    val items: List<LiveSessionResponse> = emptyList(),
)
