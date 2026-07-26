package io.gomob.network

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

interface FeedbackApi {
    @POST("v1/feedback")
    suspend fun submit(@Body req: FeedbackSubmitRequest): Envelope<FeedbackSubmitResponse>
}

@Serializable
data class FeedbackSubmitRequest(
    val title: String,
    val severity: String,
    val category: String,
    val pageUrl: String,
    val userAgent: String,
    val imageDataUrl: String,
    val annotatedDataUrl: String,
    val boxes: List<FeedbackBoxDto>,
)

@Serializable
data class FeedbackBoxDto(
    val x: Double,
    val y: Double,
    val w: Double,
    val h: Double,
    val note: String,
    val points: List<FeedbackPointDto> = emptyList(),
)

@Serializable
data class FeedbackPointDto(
    val x: Double,
    val y: Double,
)

@Serializable
data class FeedbackSubmitResponse(
    val ok: Boolean,
    val id: String,
)
