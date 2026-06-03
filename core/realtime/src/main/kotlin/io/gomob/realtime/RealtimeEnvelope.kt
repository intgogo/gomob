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
        val messageId: Long?,
        val conversationId: Long,
        val serverSeq: Long,
        val senderId: Long,
        val kind: String,
        val content: JsonElement?,
        val clientMsgId: String?,
        val createdAt: String,
    ) : RealtimeEvent
    data class TranscriptUpdated(
        val messageId: Long,
        val conversationId: Long,
        val serverSeq: Long,
        val kind: String,
        val content: JsonElement?,
        val updatedAt: String,
    ) : RealtimeEvent
    data class MessageRecalled(
        val messageId: Long,
        val conversationId: Long,
        val serverSeq: Long,
        val recalledBy: Long,
        val deletedAt: String,
    ) : RealtimeEvent
    /** 云端多视角融合完成（scan.fusion_done）。端侧据 [sessionKey] 关联本次扫描，拉 [resultObjectKey] 的 GLB 回看。 */
    data class ScanFusionDone(
        val jobId: Long?,
        val sessionKey: String,
        val resultObjectKey: String,
        val vertices: Int,
        val triangles: Int,
        val frameCount: Int,
    ) : RealtimeEvent
    data class Error(val code: Int, val message: String, val inReplyTo: String?) : RealtimeEvent
    data class Unknown(val envelope: RealtimeEnvelope) : RealtimeEvent
}

enum class RealtimeConnectionState {
    Disconnected,
    Connecting,
    Connected,
}
