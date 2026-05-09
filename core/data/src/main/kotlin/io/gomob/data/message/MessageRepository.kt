package io.gomob.data.message

import android.net.Uri
import io.gomob.database.message.ConversationDao
import io.gomob.database.message.ConversationEntity
import io.gomob.database.message.MessageDao
import io.gomob.database.message.MessageEntity
import io.gomob.model.message.ConversationSummary
import io.gomob.model.message.HelpExpert
import io.gomob.model.message.HelpExpertCase
import io.gomob.model.message.MessageRecord
import io.gomob.model.message.MessageStatus
import io.gomob.network.ApiException
import io.gomob.network.MessageApi
import io.gomob.network.dto.ConversationDto
import io.gomob.network.dto.CreateMessageRequest
import io.gomob.network.dto.HelpExpertCaseDto
import io.gomob.network.dto.HelpExpertDto
import io.gomob.network.dto.MessageDto
import io.gomob.network.dto.OpenDirectConversationRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val api: MessageApi,
    private val mediaAssetUploader: MediaAssetUploader,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val json: Json,
) {
    fun observeConversations(): Flow<List<ConversationSummary>> =
        conversationDao.observeConversations().map { items ->
            items.map { it.conversation.toDomain(it.lastMessage) }
        }

    fun observeMessages(conversationId: Long): Flow<List<MessageRecord>> =
        messageDao.observeMessages(conversationId).map { items -> items.map { it.toDomain() } }

    fun observeConversation(conversationId: Long): Flow<ConversationSummary?> =
        conversationDao.observeConversation(conversationId).map { item ->
            item?.conversation?.toDomain(item.lastMessage)
        }

    suspend fun refreshConversations(limit: Int = 20) {
        val resp = api.conversations(limit = limit)
        val data = resp.data ?: throw ApiException(50001, 500, "会话列表响应缺数据")
        val messageEntities = data.items.mapNotNull { dto ->
            dto.lastMessage?.toEntity(
                conversationId = dto.id.toLongOrNull() ?: return@mapNotNull null,
                json = json,
            )
        }
        if (messageEntities.isNotEmpty()) {
            messageDao.upsertServerMessages(messageEntities)
        }
        conversationDao.upsertConversations(data.items.map { it.toEntity() })
    }

    suspend fun helpExperts(): List<HelpExpert> {
        val resp = api.helpExperts()
        val data = resp.data ?: throw ApiException(50001, 500, "专家列表响应缺数据")
        return data.items.map { it.toDomain() }
    }

    suspend fun helpExpertCases(expertUserId: Long): List<HelpExpertCase> {
        val resp = api.helpExpertCases(expertUserId.toString())
        val data = resp.data ?: throw ApiException(50001, 500, "专家案例响应缺数据")
        return data.items.map { it.toDomain() }
    }

    suspend fun openDirectConversation(peerUserId: Long): ConversationSummary {
        val resp = api.openDirectConversation(
            OpenDirectConversationRequest(peerUserId = peerUserId.toString()),
        )
        val dto = resp.data ?: throw ApiException(50001, 500, "专家会话响应缺数据")
        val lastMessage = dto.lastMessage?.toEntity(
            conversationId = dto.id.toLong(),
            json = json,
        )
        if (lastMessage != null) {
            messageDao.upsertServerMessages(listOf(lastMessage))
        }
        val entity = dto.toEntity()
        conversationDao.upsertConversation(entity)
        return entity.toDomain(lastMessage)
    }

    suspend fun openHelpRoom(): ConversationSummary {
        val resp = api.openHelpRoom()
        val dto = resp.data ?: throw ApiException(50001, 500, "在线求助会话响应缺数据")
        val lastMessage = dto.lastMessage?.toEntity(
            conversationId = dto.id.toLong(),
            json = json,
        )
        if (lastMessage != null) {
            messageDao.upsertServerMessages(listOf(lastMessage))
        }
        val entity = dto.toEntity()
        conversationDao.upsertConversation(entity)
        return entity.toDomain(lastMessage)
    }

    suspend fun refreshMessages(conversationId: Long, limit: Int = 100, fullSync: Boolean = false) {
        val since = if (fullSync) 0L else messageDao.maxServerSeq(conversationId) ?: 0L
        val resp = api.messages(conversationId.toString(), sinceSeq = since, limit = limit)
        val data = resp.data ?: throw ApiException(50001, 500, "消息历史响应缺数据")
        messageDao.upsertServerMessages(data.items.map { it.toEntity(conversationId, json) })
    }

    suspend fun sendText(conversationId: Long, text: String): String {
        val payload = buildJsonObject { put("text", text) }
        return sendClientMessage(
            conversationId = conversationId,
            kind = "text",
            payload = payload,
            preview = text,
        )
    }

    suspend fun sendImage(conversationId: Long, uri: Uri): String {
        val asset = mediaAssetUploader.upload(uri, MediaAssetKind.Image)
        return sendUploadedImage(conversationId, asset)
    }

    internal suspend fun sendUploadedImage(conversationId: Long, asset: UploadedMediaAsset): String {
        return sendClientMessage(
            conversationId = conversationId,
            kind = "image",
            payload = mediaPayload(asset),
            preview = "[图片]",
        )
    }

    suspend fun sendVoice(conversationId: Long, uri: Uri, durationSec: Int): String {
        val asset = mediaAssetUploader.upload(uri, MediaAssetKind.Voice)
        return sendUploadedVoice(conversationId, asset, durationSec)
    }

    internal suspend fun sendUploadedVoice(
        conversationId: Long,
        asset: UploadedMediaAsset,
        durationSec: Int,
    ): String {
        return sendClientMessage(
            conversationId = conversationId,
            kind = "voice",
            payload = mediaPayload(asset) {
                put("duration_sec", durationSec.coerceAtLeast(0))
                put("source", "composer_voice")
            },
            preview = if (durationSec > 0) "[语音 ${formatDuration(durationSec)}]" else "[语音消息]",
        )
    }

    suspend fun sendVideoClip(conversationId: Long, uri: Uri): String {
        val asset = mediaAssetUploader.upload(uri, MediaAssetKind.VideoClip)
        return sendUploadedVideoClip(conversationId, asset)
    }

    internal suspend fun sendUploadedVideoClip(conversationId: Long, asset: UploadedMediaAsset): String {
        return sendClientMessage(
            conversationId = conversationId,
            kind = "video_clip",
            payload = mediaPayload(asset) {
                put("source", "composer_video")
            },
            preview = "[视频消息]",
        )
    }

    suspend fun retryText(clientMsgId: String) {
        retryMessage(clientMsgId)
    }

    suspend fun retryMessage(clientMsgId: String) {
        val entity = messageDao.findByClientMsgId(clientMsgId) ?: return
        val payload = json.parseToJsonElement(entity.payloadJson)
        if (!retryableMessageKind(entity.kind)) return
        if (entity.kind == "text") {
            val text = payload.jsonObject["text"]?.jsonPrimitive?.content.orEmpty()
            if (text.isBlank()) return
        }
        messageDao.markPending(clientMsgId)
        sendExistingMessage(entity.conversationId, clientMsgId, entity.kind, payload)
    }

    suspend fun markRead(conversationId: Long, lastReadSeq: Long) {
        val resp = api.markRead(conversationId.toString(), io.gomob.network.dto.MarkReadRequest(lastReadSeq))
        val data = resp.data ?: throw ApiException(50001, 500, "标记已读响应缺数据")
        conversationDao.markRead(
            conversationId = conversationId,
            lastReadSeq = data.lastReadSeq,
            unreadCount = data.unreadCount,
        )
    }

    private suspend fun sendExistingMessage(
        conversationId: Long,
        clientMsgId: String,
        kind: String,
        payload: JsonElement,
    ) {
        try {
            val resp = api.sendMessage(
                conversationId = conversationId.toString(),
                request = CreateMessageRequest(
                    clientMsgId = clientMsgId,
                    kind = kind,
                    payload = payload,
                ),
            )
            val dto = resp.data ?: throw ApiException(50001, 500, "发送消息响应缺数据")
            messageDao.markDelivered(
                clientMsgId = clientMsgId,
                serverId = dto.id.toLong(),
                serverSeq = dto.serverSeq,
                createdAt = dto.createdAt,
            )
        } catch (t: Throwable) {
            messageDao.markFailed(clientMsgId)
            throw t
        }
    }

    private suspend fun sendClientMessage(
        conversationId: Long,
        kind: String,
        payload: JsonElement,
        preview: String,
    ): String {
        val clientMsgId = UUID.randomUUID().toString()
        messageDao.upsertMessage(
            pendingMessageEntity(
                conversationId = conversationId,
                kind = kind,
                payload = payload,
                preview = preview,
                clientMsgId = clientMsgId,
                now = Instant.now().toString(),
            ),
        )
        sendExistingMessage(conversationId, clientMsgId, kind, payload)
        return clientMsgId
    }
}

internal fun pendingTextEntity(
    conversationId: Long,
    text: String,
    clientMsgId: String,
    now: String,
): MessageEntity = pendingMessageEntity(
    conversationId = conversationId,
    kind = "text",
    payload = buildJsonObject { put("text", text) },
    preview = text,
    clientMsgId = clientMsgId,
    now = now,
)

internal fun pendingMessageEntity(
    conversationId: Long,
    kind: String,
    payload: JsonElement,
    preview: String,
    clientMsgId: String,
    now: String,
): MessageEntity = MessageEntity(
    localKey = "c:$clientMsgId",
    serverId = null,
    conversationId = conversationId,
    serverSeq = null,
    senderId = null,
    kind = kind,
    payloadJson = payload.toString(),
    preview = preview,
    clientMsgId = clientMsgId,
    status = MessageStatus.Pending.name,
    createdAt = now,
    editedAt = null,
)

private fun retryableMessageKind(kind: String): Boolean =
    kind == "text" || kind == "image" || kind == "voice" || kind == "video_clip"

private fun mediaPayload(
    asset: UploadedMediaAsset,
    extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {},
): JsonElement = buildJsonObject {
    put("media_state", "ready")
    put("asset_id", asset.assetId)
    put("object_key", asset.objectKey)
    put("mime", asset.mime)
    put("size_bytes", asset.sizeBytes)
    put("sha256", asset.sha256)
    asset.downloadUrl?.takeIf { it.isNotBlank() }?.let { put("download_url", it) }
    extra()
}

private fun formatDuration(sec: Int): String {
    val normalized = sec.coerceAtLeast(0)
    val m = normalized / 60
    val s = normalized % 60
    return "$m:" + s.toString().padStart(2, '0')
}

private fun ConversationDto.toEntity(): ConversationEntity {
    val conversationId = id.toLong()
    val lastKey = lastMessage?.id?.let { "s:$it" }
    return ConversationEntity(
        id = conversationId,
        kind = kind,
        title = title,
        peerId = peer?.id?.toLongOrNull(),
        peerName = peer?.name,
        peerEmployeeId = peer?.employeeId,
        subjectKind = subjectKind,
        subjectId = subjectId?.toLongOrNull(),
        lastMessageLocalKey = lastKey,
        lastReadSeq = lastReadSeq,
        unreadCount = unreadCount,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}

private fun HelpExpertDto.toDomain(): HelpExpert = HelpExpert(
    userId = userId.toLong(),
    name = name,
    employeeId = employeeId,
    roleTitle = roleTitle,
    specialty = specialty,
    availability = availability,
)

private fun HelpExpertCaseDto.toDomain(): HelpExpertCase = HelpExpertCase(
    id = id.toLong(),
    authorId = authorId.toLong(),
    title = title,
    summary = summary,
    category = category,
    publishedAt = publishedAt,
)

private fun MessageDto.toEntity(conversationId: Long, json: Json): MessageEntity {
    val serverId = id.toLong()
    return MessageEntity(
        localKey = "s:$serverId",
        serverId = serverId,
        conversationId = this.conversationId?.toLongOrNull() ?: conversationId,
        serverSeq = serverSeq,
        senderId = senderId?.toLongOrNull(),
        kind = kind,
        payloadJson = payload?.let { jsonElement ->
            json.encodeToString(JsonElement.serializer(), jsonElement)
        } ?: "{}",
        preview = preview?.takeIf { it.isNotBlank() },
        clientMsgId = clientMsgId,
        status = MessageStatus.Sent.name,
        createdAt = createdAt,
        editedAt = editedAt,
    )
}
