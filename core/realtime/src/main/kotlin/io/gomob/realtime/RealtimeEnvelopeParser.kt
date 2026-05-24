package io.gomob.realtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealtimeEnvelopeParser @Inject constructor(
    private val json: Json,
) {
    fun parse(text: String): RealtimeEnvelope = json.decodeFromString(RealtimeEnvelope.serializer(), text)

    fun encode(envelope: RealtimeEnvelope): String = json.encodeToString(RealtimeEnvelope.serializer(), envelope)

    fun toEvent(envelope: RealtimeEnvelope): RealtimeEvent = when (envelope.type) {
        "hello" -> envelope.payload?.decode<HelloPayload>()?.let {
            RealtimeEvent.Hello(
                userId = it.userId,
                role = it.role,
                serverTs = it.serverTs,
            )
        } ?: RealtimeEvent.Unknown(envelope)
        "msg.delivered" -> envelope.payload?.decode<DeliveredPayload>()?.let {
            RealtimeEvent.MessageDelivered(
                clientMsgId = it.clientMsgId,
                conversationId = it.conversationId,
                serverSeq = it.serverSeq,
                messageId = it.messageId,
                createdAt = it.createdAt,
            )
        } ?: RealtimeEvent.Unknown(envelope)
        "msg.recv" -> envelope.payload?.decode<RecvPayload>()?.let {
            RealtimeEvent.MessageReceived(
                messageId = it.messageId,
                conversationId = it.conversationId,
                serverSeq = it.serverSeq,
                senderId = it.senderId,
                kind = it.kind,
                content = it.content,
                clientMsgId = it.clientMsgId,
                createdAt = it.createdAt,
            )
        } ?: RealtimeEvent.Unknown(envelope)
        "msg.transcript.updated" -> envelope.payload?.decode<TranscriptUpdatedPayload>()?.let {
            RealtimeEvent.TranscriptUpdated(
                messageId = it.messageId,
                conversationId = it.conversationId,
                serverSeq = it.serverSeq,
                kind = it.kind,
                content = it.content,
                updatedAt = it.updatedAt,
            )
        } ?: RealtimeEvent.Unknown(envelope)
        "msg.recall" -> envelope.payload?.decode<RecallPayload>()?.let {
            RealtimeEvent.MessageRecalled(
                messageId = it.messageId,
                conversationId = it.conversationId,
                serverSeq = it.serverSeq,
                recalledBy = it.recalledBy,
                deletedAt = it.deletedAt,
            )
        } ?: RealtimeEvent.Unknown(envelope)
        "error" -> RealtimeEvent.Error(
            code = envelope.code ?: 50001,
            message = envelope.message ?: "实时通道错误",
            inReplyTo = envelope.payload?.decode<ErrorPayload>()?.inReplyTo,
        )
        else -> RealtimeEvent.Unknown(envelope)
    }

    private inline fun <reified T> JsonElement.decode(): T = json.decodeFromJsonElement(this)
}

@Serializable
private data class HelloPayload(
    @SerialName("user_id") val userId: Long,
    val role: String,
    @SerialName("server_ts") val serverTs: Long,
)

@Serializable
private data class DeliveredPayload(
    @SerialName("client_msg_id") val clientMsgId: String? = null,
    @SerialName("conversation_id") val conversationId: Long,
    @SerialName("server_seq") val serverSeq: Long,
    @SerialName("message_id") val messageId: Long,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class RecvPayload(
    @SerialName("message_id") val messageId: Long? = null,
    @SerialName("conversation_id") val conversationId: Long,
    @SerialName("server_seq") val serverSeq: Long,
    @SerialName("sender_id") val senderId: Long,
    val kind: String,
    val content: JsonElement? = null,
    @SerialName("client_msg_id") val clientMsgId: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class TranscriptUpdatedPayload(
    @SerialName("message_id") val messageId: Long,
    @SerialName("conversation_id") val conversationId: Long,
    @SerialName("server_seq") val serverSeq: Long,
    val kind: String,
    val content: JsonElement? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class RecallPayload(
    @SerialName("message_id") val messageId: Long,
    @SerialName("conversation_id") val conversationId: Long,
    @SerialName("server_seq") val serverSeq: Long,
    @SerialName("recalled_by") val recalledBy: Long,
    @SerialName("deleted_at") val deletedAt: String,
)

@Serializable
private data class ErrorPayload(
    @SerialName("in_reply_to") val inReplyTo: String? = null,
)
