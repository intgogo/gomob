package io.gomob.data.message

import android.net.Uri
import android.util.Log
import io.gomob.database.message.ConversationDao
import io.gomob.database.message.ConversationEntity
import io.gomob.database.message.MessageDao
import io.gomob.database.message.MessageEntity
import io.gomob.model.message.ConversationSummary
import io.gomob.model.message.HelpExpert
import io.gomob.model.message.HelpExpertCase
import io.gomob.model.message.InspectionShareCard
import io.gomob.model.message.MessageRecord
import io.gomob.model.message.MessageStatus
import io.gomob.network.ApiException
import io.gomob.network.MessageApi
import io.gomob.network.dto.ConversationDto
import io.gomob.network.dto.CallInviteResponse
import io.gomob.network.dto.CreateCallInviteRequest
import io.gomob.network.dto.CreateMessageRequest
import io.gomob.network.dto.HelpExpertCaseDto
import io.gomob.network.dto.HelpExpertDto
import io.gomob.network.dto.MessageDto
import io.gomob.network.dto.OpenDirectConversationRequest
import io.gomob.network.dto.TranscribeDraftVoiceRequest
import io.gomob.realtime.RealtimeConnectionState
import io.gomob.realtime.RealtimeEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val DEFAULT_PREWARM_CONVERSATION_LIMIT = 12
private const val DEFAULT_PREWARM_MESSAGE_LIMIT = 100
private const val MESSAGE_REPOSITORY_TAG = "MessageRepository"
private const val STALE_CONVERSATION_MESSAGE = "会话已失效，请返回消息中心重新打开"

private object NoopRealtimeMessageTransport : RealtimeMessageTransport {
    override val state = MutableStateFlow(RealtimeConnectionState.Disconnected)
    override val events = MutableSharedFlow<RealtimeEvent>()

    override fun connect() = Unit

    override fun disconnect() = Unit

    override fun sendMessage(
        conversationId: Long,
        kind: String,
        content: JsonElement,
        clientMsgId: String,
    ): Boolean = false
}

private fun logInfo(message: String) {
    runCatching { Log.i(MESSAGE_REPOSITORY_TAG, message) }
}

private fun logWarn(message: String) {
    runCatching { Log.w(MESSAGE_REPOSITORY_TAG, message) }
}

@Singleton
class MessageRepository @Inject constructor(
    private val api: MessageApi,
    private val mediaAssetUploader: MediaAssetUploader,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val json: Json,
    private val realtimeRepository: RealtimeMessageTransport = NoopRealtimeMessageTransport,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val realtimeStarted = AtomicBoolean(false)
    private val conversationSnapshots = ConcurrentHashMap<Long, ConversationSummary>()
    private val messageSnapshots = ConcurrentHashMap<Long, List<MessageRecord>>()
    private val hydratedConversationIds = ConcurrentHashMap.newKeySet<Long>()

    fun startRealtimeSync() {
        if (realtimeStarted.compareAndSet(false, true)) {
            scope.launch {
                realtimeRepository.events.collect { event ->
                    runCatching { applyRealtimeEvent(event) }
                        .onFailure { logWarn("实时消息落库失败: ${it.message}") }
                }
            }
            scope.launch {
                realtimeRepository.state.collectLatest { state ->
                    logInfo("实时通道状态=$state")
                }
            }
        }
        realtimeRepository.connect()
    }

    fun stopRealtimeSync() {
        realtimeRepository.disconnect()
    }

    fun observeConversations(): Flow<List<ConversationSummary>> =
        conversationDao.observeConversations().map { items ->
            items.map { it.conversation.toDomain(it.lastMessage) }
        }.onEach { items ->
            items.forEach(::rememberConversation)
        }

    fun observeMessages(conversationId: Long): Flow<List<MessageRecord>> =
        messageDao.observeMessages(conversationId).map { items ->
            items.map { it.toDomain() }
        }.onEach { items ->
            messageSnapshots[conversationId] = items
        }

    fun observeConversation(conversationId: Long): Flow<ConversationSummary?> =
        conversationDao.observeConversation(conversationId).map { item ->
            item?.conversation?.toDomain(item.lastMessage)
        }.onEach { item ->
            item?.let(::rememberConversation)
        }

    fun observeHelpRoomConversation(): Flow<ConversationSummary?> =
        conversationDao.observeLatestBySubjectKind("online_help").map { item ->
            item?.conversation?.toDomain(item.lastMessage)
        }.onEach { item ->
            item?.let(::rememberConversation)
        }

    fun cachedConversation(conversationId: Long): ConversationSummary? =
        conversationSnapshots[conversationId]

    fun cachedMessages(conversationId: Long): List<MessageRecord> =
        messageSnapshots[conversationId].orEmpty()

    fun shouldHydrateConversationHistory(conversationId: Long): Boolean =
        !hydratedConversationIds.contains(conversationId)

    fun markConversationHistoryHydrated(conversationId: Long) {
        hydratedConversationIds += conversationId
    }

    suspend fun prewarmRecentConversationHistories(
        conversationLimit: Int = DEFAULT_PREWARM_CONVERSATION_LIMIT,
        messageLimit: Int = DEFAULT_PREWARM_MESSAGE_LIMIT,
    ) {
        val conversations = conversationDao.recentConversations(conversationLimit)
            .map { it.conversation.toDomain(it.lastMessage) }
            .filterNot { it.isOnlineHelpConversation() }
        conversations.forEach(::rememberConversation)
        conversations.forEach { warmConversationSnapshot(it.id, messageLimit) }
        conversations.forEach { conversation ->
            if (shouldHydrateConversationHistory(conversation.id)) {
                runCatching {
                    refreshMessages(
                        conversationId = conversation.id,
                        limit = messageLimit,
                        fullSync = true,
                    )
                }
            }
        }
    }

    suspend fun prewarmConversationHistory(
        conversationId: Long,
        messageLimit: Int = DEFAULT_PREWARM_MESSAGE_LIMIT,
    ) {
        warmConversationSnapshot(conversationId, messageLimit)
        if (shouldHydrateConversationHistory(conversationId)) {
            refreshMessages(
                conversationId = conversationId,
                limit = messageLimit,
                fullSync = true,
            )
        }
    }

    suspend fun warmConversationSnapshot(
        conversationId: Long,
        messageLimit: Int = DEFAULT_PREWARM_MESSAGE_LIMIT,
    ) {
        conversationDao.findById(conversationId)?.toDomain(lastMessage = null)?.let(::rememberConversation)
        val localMessages = messageDao.recentMessages(conversationId, messageLimit)
            .asReversed()
            .map { it.toDomain() }
        if (localMessages.isNotEmpty()) {
            messageSnapshots[conversationId] = localMessages
        }
    }

    suspend fun refreshConversations(limit: Int = 20) {
        val resp = api.conversations(limit = limit)
        val data = resp.data ?: throw ApiException(50001, 500, "会话列表响应缺数据")
        val localConversations = data.items.mapNotNull { dto ->
            dto.id.toLongOrNull()?.let { id -> id to conversationDao.findById(id) }
        }.toMap()
        val messageEntities = data.items.mapNotNull { dto ->
            val conversationId = dto.id.toLongOrNull() ?: return@mapNotNull null
            val clearedBeforeSeq = localConversations[conversationId]?.clearedBeforeSeq ?: 0L
            dto.lastMessage
                ?.takeIf { it.serverSeq > clearedBeforeSeq }
                ?.toEntity(
                    conversationId = conversationId,
                    json = json,
                )
        }
        if (messageEntities.isNotEmpty()) {
            messageDao.upsertServerMessages(messageEntities)
        }
        val entities = data.items.map { dto ->
            val id = dto.id.toLongOrNull()
            dto.toEntity(local = id?.let(localConversations::get))
        }
        val messagesByLocalKey = messageEntities.associateBy { it.localKey }
        conversationDao.upsertConversations(entities)
        entities.forEach { entity ->
            rememberConversation(entity.toDomain(messagesByLocalKey[entity.lastMessageLocalKey]))
        }
    }

    suspend fun clearConversationMessages(conversationId: Long) {
        val clearedBeforeSeq = messageDao.maxServerSeq(conversationId) ?: 0L
        conversationDao.markCleared(conversationId, clearedBeforeSeq)
        messageDao.deleteClearedMessages(conversationId, clearedBeforeSeq)
    }

    suspend fun setConversationPinned(conversationId: Long, pinned: Boolean) {
        conversationDao.setPinned(conversationId, pinned)
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
        val conversationId = dto.id.toLong()
        val local = conversationDao.findById(conversationId)
        val lastMessage = dto.lastMessage
            ?.takeIf { it.serverSeq > (local?.clearedBeforeSeq ?: 0L) }
            ?.toEntity(
                conversationId = conversationId,
                json = json,
            )
        if (lastMessage != null) {
            messageDao.upsertServerMessages(listOf(lastMessage))
        }
        val entity = dto.toEntity(local = local)
        conversationDao.upsertConversation(entity)
        return entity.toDomain(lastMessage).also(::rememberConversation)
    }

    suspend fun openHelpRoom(): ConversationSummary {
        val resp = api.openHelpRoom()
        val dto = resp.data ?: throw ApiException(50001, 500, "在线求助会话响应缺数据")
        val conversationId = dto.id.toLong()
        val local = conversationDao.findById(conversationId)
        val lastMessage = dto.lastMessage
            ?.takeIf { it.serverSeq > (local?.clearedBeforeSeq ?: 0L) }
            ?.toEntity(
                conversationId = conversationId,
                json = json,
            )
        if (lastMessage != null) {
            messageDao.upsertServerMessages(listOf(lastMessage))
        }
        val entity = dto.toEntity(local = local)
        conversationDao.upsertConversation(entity)
        return entity.toDomain(lastMessage).also(::rememberConversation)
    }

    suspend fun refreshMessages(conversationId: Long, limit: Int = 100, fullSync: Boolean = false) {
        val since = if (fullSync) 0L else messageDao.maxServerSeq(conversationId) ?: 0L
        val resp = try {
            api.messages(conversationId.toString(), sinceSeq = since, limit = limit)
        } catch (error: ApiException) {
            if (error.isPermissionDenied) {
                forgetInaccessibleConversation(conversationId)
                throw staleConversationError(error)
            }
            throw error
        }
        val data = resp.data ?: throw ApiException(50001, 500, "消息历史响应缺数据")
        val clearedBeforeSeq = conversationDao.findById(conversationId)?.clearedBeforeSeq ?: 0L
        val entities = data.items
            .filter { it.serverSeq > clearedBeforeSeq }
            .map { it.toEntity(conversationId, json) }
        messageDao.upsertServerMessages(entities)
        rememberMessages(conversationId, entities.map { it.toDomain() })
        if (fullSync) {
            markConversationHistoryHydrated(conversationId)
        }
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

    suspend fun transcribeVoiceDraft(uri: Uri, language: String = "zh"): String {
        val asset = mediaAssetUploader.upload(uri, MediaAssetKind.Voice)
        return transcribeUploadedVoiceDraft(asset, language)
    }

    internal suspend fun transcribeUploadedVoiceDraft(
        asset: UploadedMediaAsset,
        language: String = "zh",
    ): String {
        val resp = api.transcribeDraftVoice(
            TranscribeDraftVoiceRequest(
                assetId = asset.assetId,
                language = language,
            ),
        )
        val data = resp.data ?: throw ApiException(50001, 500, "语音转文字响应缺数据")
        return data.normalizedText.ifBlank { data.text }.trim()
            .ifBlank { throw ApiException(50001, 500, "未识别到有效文字") }
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

    suspend fun sendInspectionCard(conversationId: Long, card: InspectionShareCard): String {
        return sendClientMessage(
            conversationId = conversationId,
            kind = "inspection_card",
            payload = inspectionCardPayload(card),
            preview = "[流水] ${card.vin}",
        )
    }

    suspend fun createVideoCallInvite(conversationId: Long, title: String): VideoCallInviteResult {
        val clientMsgId = UUID.randomUUID().toString()
        val resp = try {
            api.createCallInvite(
                conversationId = conversationId.toString(),
                request = CreateCallInviteRequest(
                    clientMsgId = clientMsgId,
                    title = title.ifBlank { "视频通话" },
                ),
            )
        } catch (error: ApiException) {
            if (error.isPermissionDenied) {
                forgetInaccessibleConversation(conversationId)
                throw staleConversationError(error)
            }
            throw error
        }
        val data = resp.data ?: throw ApiException(50001, 500, "视频通话邀请响应缺数据")
        messageDao.upsertServerMessages(listOf(data.message.toEntity(conversationId, json)))
        return data.toVideoCallInviteResult()
    }

    suspend fun retryText(clientMsgId: String) {
        retryMessage(clientMsgId)
    }

    suspend fun retryVoiceTranscript(messageId: Long) {
        val resp = api.retryMessageTranscript(messageId.toString())
        val dto = resp.data ?: throw ApiException(50001, 500, "语音转写重试响应缺数据")
        val entity = dto.toEntity(dto.conversationId?.toLongOrNull() ?: 0L, json)
        messageDao.upsertServerMessages(listOf(entity))
        rememberMessage(entity.conversationId, entity.toDomain())
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
        if (realtimeRepository.state.value == RealtimeConnectionState.Connected &&
            realtimeRepository.sendMessage(
                conversationId = conversationId,
                kind = kind,
                content = payload,
                clientMsgId = clientMsgId,
            )
        ) {
            logInfo(
                "消息已走实时通道 conversation_id=$conversationId client_msg_id=$clientMsgId kind=$kind",
            )
            return
        }
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
            val delivered = dto.toEntity(conversationId, json)
            messageDao.markDelivered(
                clientMsgId = clientMsgId,
                serverId = delivered.serverId ?: dto.id.toLong(),
                serverSeq = delivered.serverSeq ?: dto.serverSeq,
                createdAt = delivered.createdAt,
            )
            conversationDao.recordLastMessage(
                conversationId = conversationId,
                localKey = delivered.localKey,
                serverSeq = delivered.serverSeq ?: dto.serverSeq,
                updatedAt = delivered.createdAt,
                incrementUnread = false,
            )
            rememberMessage(conversationId, delivered.toDomain())
        } catch (t: Throwable) {
            if (t is ApiException && t.isPermissionDenied) {
                forgetInaccessibleConversation(conversationId)
                throw staleConversationError(t)
            }
            messageDao.markFailed(clientMsgId)
            markCachedMessageFailed(conversationId, clientMsgId)
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
        val pending = pendingMessageEntity(
            conversationId = conversationId,
            kind = kind,
            payload = payload,
            preview = preview,
            clientMsgId = clientMsgId,
            now = Instant.now().toString(),
        )
        messageDao.upsertMessage(pending)
        rememberMessage(conversationId, pending.toDomain())
        sendExistingMessage(conversationId, clientMsgId, kind, payload)
        return clientMsgId
    }

    internal suspend fun applyRealtimeEvent(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.Hello -> {
                logInfo("实时 hello user_id=${event.userId} role=${event.role}")
                runCatching { refreshConversations() }
                runCatching { prewarmRecentConversationHistories() }
            }
            is RealtimeEvent.MessageDelivered -> applyRealtimeDelivered(event)
            is RealtimeEvent.MessageReceived -> applyRealtimeReceived(event)
            is RealtimeEvent.TranscriptUpdated -> applyTranscriptUpdated(event)
            is RealtimeEvent.Error -> logWarn(
                "实时通道业务错误 code=${event.code} message=${event.message}",
            )
            is RealtimeEvent.Unknown -> logWarn("未知实时事件 type=${event.envelope.type}")
        }
    }

    private suspend fun applyRealtimeDelivered(event: RealtimeEvent.MessageDelivered) {
        val clientMsgId = event.clientMsgId
        if (clientMsgId.isNullOrBlank()) {
            refreshMessages(conversationId = event.conversationId)
            return
        }
        messageDao.markDelivered(
            clientMsgId = clientMsgId,
            serverId = event.messageId,
            serverSeq = event.serverSeq,
            createdAt = event.createdAt,
        )
        val delivered = messageDao.findByClientMsgId(clientMsgId)
        conversationDao.recordLastMessage(
            conversationId = event.conversationId,
            localKey = "s:${event.messageId}",
            serverSeq = event.serverSeq,
            updatedAt = event.createdAt,
            incrementUnread = false,
        )
        delivered?.toDomain()?.let { rememberMessage(event.conversationId, it) }
        logInfo(
            "实时回执已落库 conversation_id=${event.conversationId} server_seq=${event.serverSeq} client_msg_id=$clientMsgId",
        )
    }

    private suspend fun applyRealtimeReceived(event: RealtimeEvent.MessageReceived) {
        val conversationKnown = conversationDao.findById(event.conversationId) != null
        val entity = event.toEntity(json)
        messageDao.upsertServerMessages(listOf(entity))
        conversationDao.recordLastMessage(
            conversationId = event.conversationId,
            localKey = entity.localKey,
            serverSeq = event.serverSeq,
            updatedAt = event.createdAt,
            incrementUnread = true,
        )
        rememberMessage(event.conversationId, entity.toDomain())
        logInfo(
            "实时消息已落库 conversation_id=${event.conversationId} server_seq=${event.serverSeq} message_id=${event.messageId}",
        )
        if (!conversationKnown) {
            runCatching { refreshConversations() }
        }
    }

    private suspend fun applyTranscriptUpdated(event: RealtimeEvent.TranscriptUpdated) {
        if (event.kind != "voice") return
        val payloadJson = event.content?.let { json.encodeToString(JsonElement.serializer(), it) } ?: "{}"
        val preview = realtimePreview(event.kind, event.content)
        messageDao.updateServerMessagePayload(
            serverId = event.messageId,
            payloadJson = payloadJson,
            preview = preview,
            updatedAt = event.updatedAt,
        )
        val updated = messageDao.findByServerId(event.messageId)
        updated?.toDomain()?.let { rememberMessage(event.conversationId, it) }
        if ((messageDao.maxServerSeq(event.conversationId) ?: 0L) == event.serverSeq) {
            conversationDao.recordLastMessage(
                conversationId = event.conversationId,
                localKey = "s:${event.messageId}",
                serverSeq = event.serverSeq,
                updatedAt = event.updatedAt,
                incrementUnread = false,
            )
        }
        logInfo(
            "语音转写实时更新已落库 conversation_id=${event.conversationId} server_seq=${event.serverSeq} message_id=${event.messageId}",
        )
    }

    private fun rememberConversation(summary: ConversationSummary) {
        conversationSnapshots[summary.id] = summary
    }

    private fun rememberMessages(conversationId: Long, records: List<MessageRecord>) {
        records.forEach { rememberMessage(conversationId, it) }
    }

    private fun rememberMessage(conversationId: Long, record: MessageRecord) {
        val current = messageSnapshots[conversationId].orEmpty()
        val next = current
            .filterNot { old ->
                old.localKey == record.localKey ||
                    (record.clientMsgId != null && old.clientMsgId == record.clientMsgId) ||
                    (record.serverId != null && old.serverId == record.serverId)
            }
            .plus(record)
            .sortedWith(compareBy<MessageRecord> { it.serverSeq ?: Long.MAX_VALUE }.thenBy { it.createdAt })
        messageSnapshots[conversationId] = next
    }

    private fun markCachedMessageFailed(conversationId: Long, clientMsgId: String) {
        messageSnapshots[conversationId] = messageSnapshots[conversationId].orEmpty().map { record ->
            if (record.clientMsgId == clientMsgId && record.status == MessageStatus.Pending) {
                record.copy(status = MessageStatus.Failed)
            } else {
                record
            }
        }
    }

    private suspend fun forgetInaccessibleConversation(conversationId: Long) {
        messageDao.deleteByConversationId(conversationId)
        conversationDao.deleteById(conversationId)
        conversationSnapshots.remove(conversationId)
        messageSnapshots.remove(conversationId)
        hydratedConversationIds.remove(conversationId)
        logWarn("本地会话已移除: 服务端拒绝访问 conversation_id=$conversationId")
    }
}

private val ApiException.isPermissionDenied: Boolean
    get() = code == 40103 || httpStatus == 403

private fun staleConversationError(cause: ApiException): ApiException =
    ApiException(cause.code, cause.httpStatus, STALE_CONVERSATION_MESSAGE, cause.traceId)

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
    kind == "text" || kind == "image" || kind == "voice" || kind == "video_clip" || kind == "inspection_card"

private fun ConversationSummary.isOnlineHelpConversation(): Boolean =
    subjectKind == "online_help" || (kind == "group" && title == "在线求助")

data class VideoCallInviteResult(
    val roomId: String,
    val providerRoom: String,
    val title: String,
    val liveKitConfigured: Boolean,
)

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

private fun inspectionCardPayload(card: InspectionShareCard): JsonElement = buildJsonObject {
    put("inspection_id", card.inspectionId)
    put("vin", card.vin)
    put("vehicle_line", card.vehicleLine)
    put("time_label", card.timeLabel)
    put("status", card.status)
    putJsonArray("tags") {
        card.tags.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
    }
}

private fun formatDuration(sec: Int): String {
    val normalized = sec.coerceAtLeast(0)
    val m = normalized / 60
    val s = normalized % 60
    return "$m:" + s.toString().padStart(2, '0')
}

private fun ConversationDto.toEntity(local: ConversationEntity? = null): ConversationEntity {
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
        pinned = local?.pinned ?: false,
        clearedBeforeSeq = local?.clearedBeforeSeq ?: 0L,
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

private fun RealtimeEvent.MessageReceived.toEntity(json: Json): MessageEntity {
    val serverId = messageId
    val payloadJson = content?.let { json.encodeToString(JsonElement.serializer(), it) } ?: "{}"
    return MessageEntity(
        localKey = serverId?.let { "s:$it" } ?: "r:$conversationId:$serverSeq",
        serverId = serverId,
        conversationId = conversationId,
        serverSeq = serverSeq,
        senderId = senderId,
        kind = kind,
        payloadJson = payloadJson,
        preview = realtimePreview(kind, content),
        clientMsgId = clientMsgId,
        status = MessageStatus.Sent.name,
        createdAt = createdAt,
        editedAt = null,
    )
}

private fun CallInviteResponse.toVideoCallInviteResult(): VideoCallInviteResult {
    val title = runCatching {
        message.payload?.jsonObject?.get("title")?.jsonPrimitive?.content.orEmpty()
    }.getOrDefault(message.preview?.removePrefix("[视频通话]")?.trim().orEmpty())
    return VideoCallInviteResult(
        roomId = room.id,
        providerRoom = room.providerRoom,
        title = title.ifBlank { "视频通话" },
        liveKitConfigured = room.liveKitConfigured,
    )
}

private fun realtimePreview(kind: String, payload: JsonElement?): String? = when (kind) {
    "text" -> payload.textContent().takeIf { it.isNotBlank() }
    "image" -> "[图片]"
    "voice" -> payload.voiceTranscriptPreview() ?: "[语音消息]"
    "video_clip" -> "[视频消息]"
    "inspection_card" -> payload.jsonField("vin")?.let { "[流水] $it" } ?: "[业务流水]"
    "call_invite" -> payload.jsonField("title")?.let { "[视频通话] $it" } ?: "[视频通话邀请]"
    "video_call" -> "[视频通话]"
    "system" -> payload.textContent().takeIf { it.isNotBlank() } ?: "[系统消息]"
    else -> null
}

private fun JsonElement?.textContent(): String {
    val element = this ?: return ""
    return when (element) {
        is JsonObject -> element["text"]?.let {
            (it as? JsonPrimitive)?.contentOrNull
        }.orEmpty()
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        else -> ""
    }
}

private fun JsonElement?.jsonField(name: String): String? =
    (this as? JsonObject)?.get(name)?.let { (it as? JsonPrimitive)?.contentOrNull }

private fun JsonElement?.voiceTranscriptPreview(): String? {
    val obj = this as? JsonObject ?: return null
    return when (obj["transcript_status"]?.let { (it as? JsonPrimitive)?.contentOrNull }) {
        "done" -> obj["transcript_normalized_text"].asPrimitiveString()
            ?.ifBlank { obj["transcript_text"].asPrimitiveString().orEmpty() }
            ?.takeIf { it.isNotBlank() }
            ?.let { "[语音转文字] $it" }
        "pending", "processing" -> "[语音转写中]"
        "failed" -> "[语音转写失败]"
        else -> null
    }
}

private fun JsonElement?.asPrimitiveString(): String? =
    (this as? JsonPrimitive)?.contentOrNull
