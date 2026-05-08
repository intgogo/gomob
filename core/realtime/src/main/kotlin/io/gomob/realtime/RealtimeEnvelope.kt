package io.gomob.realtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RealtimeEnvelope(
    val type: String,
    val payload: JsonElement? = null,
    @SerialName("frame_seq") val frameSeq: Long? = null,
    val code: Int? = null,
    val message: String? = null,
)

sealed interface RealtimeEvent {
    data class Hello(val userId: Long, val role: String, val serverTs: Long) : RealtimeEvent
    data class MessageDelivered(
        val clientMsgId: String?,
        val conversationId: Long,
        val serverSeq: Long,
        val messageId: Long,
        val createdAt: String,
    ) : RealtimeEvent
    data class MessageReceived(
        val conversationId: Long,
        val serverSeq: Long,
        val senderId: Long,
        val kind: String,
        val content: JsonElement?,
        val createdAt: String,
    ) : RealtimeEvent
    data class Error(val code: Int, val message: String, val inReplyTo: String?) : RealtimeEvent
    data class Unknown(val envelope: RealtimeEnvelope) : RealtimeEvent
}

enum class RealtimeConnectionState {
    Disconnected,
    Connecting,
    Connected,
}
