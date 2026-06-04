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
import io.gomob.model.message.StationContact
import io.gomob.model.message.InspectionShareCard
import io.gomob.model.message.MessageRecord
import io.gomob.model.message.MessageQuote
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
import io.gomob.network.dto.OpenAdHocGroupRequest
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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
private const val DEFAULT_PREWARM_MESSAGE_LIMIT = 30
private const val DEFAULT_SEARCH_MESSAGE_LIMIT = 200
private const val MESSAGE_REPOSITORY_TAG = "MessageRepository"
private const val STALE_CONVERSATION_MESSAGE = "会话已失效，请返回消息中心重新打开"

/**
 * 一次发送文本消息时携带的 @ 提及记录（落到 payload.mentions[]）。
 * server 当前只是原样存 payload；后续要为"提到我的"做未读 / 通知聚合时会读这个字段。
 */
data class MentionPayload(
    val userId: Long,
    val name: String,
)

/**
 * 全局来电邀请事件 — 通过 [MessageRepository.incomingCallInvites] 暴露给 UI 顶层弹浮窗。
 * 消费方需要自己根据 currentUserId 过滤掉 `senderId == self` 的本端发起项。
 */
data class IncomingCallInvite(
    val conversationId: Long,
    val messageId: Long?,
    val serverSeq: Long,
    val senderId: Long?,
    val clientMsgId: String?,
    val title: String,
    val roomId: String?,
    val providerRoom: String?,
    val livekitUrl: String?,
    val createdAt: String,
    val conversationKind: String = "p2p",
    val conversationTitle: String? = null,
)

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

    /**
     * 来电邀请事件流：ws 收到 kind=call_invite 且 sender 不是自己时 emit 一条。
     * AppRoot 订阅 → 弹全局浮窗，不论用户当前在哪个页面都能接到。
     */
    private val _incomingCallInvites = MutableSharedFlow<IncomingCallInvite>(extraBufferCapacity = 4)
    val incomingCallInvites: SharedFlow<IncomingCallInvite> = _incomingCallInvites.asSharedFlow()

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

    fun observeRecentSearchMessages(limit: Int = DEFAULT_SEARCH_MESSAGE_LIMIT): Flow<List<MessageRecord>> =
        messageDao.observeRecentSearchMessages(limit).map { items ->
            items.map { it.toDomain() }
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

    fun cachedConversations(): List<ConversationSummary> =
        conversationSnapshots.values
            .sortedWith(
                compareByDescending<ConversationSummary> { it.pinned }
                    .thenByDescending { it.updatedAt }
                    .thenByDescending { it.id },
            )

    fun shouldHydrateConversationHistory(conversationId: Long): Boolean =
        !hydratedConversationIds.contains(conversationId)

    fun markConversationHistoryHydrated(conversationId: Long) {
        hydratedConversationIds += conversationId
    }

    suspend fun prewarmRecentConversationHistories(
        conversationLimit: Int = DEFAULT_PREWARM_CONVERSATION_LIMIT,
        messageLimit: Int = DEFAULT_PREWARM_MESSAGE_LIMIT,
    ) {
        warmRecentConversationSnapshots(conversationLimit, messageLimit)
        val conversations = cachedConversations()
            .take(conversationLimit)
            .filterNot { it.isOnlineHelpConversation() }
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

    suspend fun warmRecentConversationSnapshots(
        conversationLimit: Int = DEFAULT_PREWARM_CONVERSATION_LIMIT,
        messageLimit: Int = DEFAULT_PREWARM_MESSAGE_LIMIT,
    ) {
        val conversations = conversationDao.recentConversations(conversationLimit)
            .map { it.conversation.toDomain(it.lastMessage) }
        conversations.forEach(::rememberConversation)
        conversations.forEach { warmConversationSnapshot(it.id, messageLimit) }
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
        val cachedLastMessage = conversationSnapshots[conversationId]?.lastMessage
        conversationDao.findById(conversationId)
            ?.toDomain(lastMessage = null)
            ?.copy(lastMessage = cachedLastMessage)
            ?.let(::rememberConversation)
        val localMessages = messageDao.recentMessages(conversationId, messageLimit)
            .asReversed()
            .map { it.toDomain() }
        if (localMessages.isNotEmpty()) {
            messageSnapshots[conversationId] = localMessages
            conversationSnapshots[conversationId]
                ?.takeIf { it.lastMessage == null }
                ?.copy(lastMessage = localMessages.last())
                ?.let(::rememberConversation)
        }
    }

    suspend fun refreshConversations(limit: Int = 20) {
        val resp = api.conversations(limit = limit)
        val data = resp.data ?: throw ApiException(50001, 500, "会话列表响应缺数据")
        val localConversations = data.items.mapNotNull { dto ->
            dto.id.toLongOrNull()?.let { id -> id to conversationDao.findById(id) }
        }.toMap()
        val messageEntities = mergeKnownLocalMediaFields(data.items.mapNotNull { dto ->
            val conversationId = dto.id.toLongOrNull() ?: return@mapNotNull null
            val clearedBeforeSeq = localConversations[conversationId]?.clearedBeforeSeq ?: 0L
            dto.lastMessage
                ?.takeIf { it.serverSeq > clearedBeforeSeq }
                ?.toEntity(
                    conversationId = conversationId,
                    json = json,
                )
        })
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

    suspend fun leaveConversation(conversationId: Long) {
        try {
            api.leaveConversation(conversationId.toString())
        } catch (error: ApiException) {
            if (error.isConversationGone) {
                forgetConversationLocal(conversationId)
                return
            }
            throw error
        }
        forgetConversationLocal(conversationId)
    }

    suspend fun helpExperts(): List<HelpExpert> {
        val resp = api.helpExperts()
        val data = resp.data ?: throw ApiException(50001, 500, "专家列表响应缺数据")
        return data.items.map { it.toDomain() }
    }

    /**
     * 拉取站内通讯录（GET /v1/contacts）。
     *
     * 当前 server 默认只返回当前用户同 station 下的活跃用户；自己未绑 station 时退化为全站。
     * 这里不缓存到 Room — 通讯录变更频次低、规模小，每次进入"联系人" tab 重新拉，避免缓存
     * 滞后造成"已离职 / 已禁用"用户残留在 picker 里。
     */
    suspend fun stationContacts(query: String? = null, role: String? = null): List<StationContact> {
        val resp = api.contacts(query = query?.takeIf { it.isNotBlank() }, role = role?.takeIf { it.isNotBlank() })
        val data = resp.data ?: throw ApiException(50001, 500, "通讯录响应缺数据")
        return data.items.map { dto ->
            StationContact(
                userId = dto.userId.toLong(),
                name = dto.name,
                employeeId = dto.employeeId,
                username = dto.username,
                role = dto.role,
                stationId = dto.stationId.toLongOrNull(),
                stationName = dto.stationName,
            )
        }
    }

    suspend fun helpExpertCases(expertUserId: Long): List<HelpExpertCase> {
        val resp = api.helpExpertCases(expertUserId.toString())
        val data = resp.data ?: throw ApiException(50001, 500, "专家案例响应缺数据")
        return data.items.map { it.toDomain() }
    }

    suspend fun openDirectConversation(
        peerUserId: Long? = null,
        peerEmployeeId: String? = null,
    ): ConversationSummary {
        val employeeId = peerEmployeeId?.trim().orEmpty()
        require(peerUserId != null || employeeId.isNotBlank()) { "联系人参数无效" }
        val resp = api.openDirectConversation(
            OpenDirectConversationRequest(
                peerUserId = peerUserId?.toString(),
                peerEmployeeId = employeeId.takeIf { it.isNotBlank() },
            ),
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
            ?.let { mergeKnownLocalMediaFields(it) }
        if (lastMessage != null) {
            messageDao.upsertServerMessages(listOf(lastMessage))
        }
        val entity = dto.toEntity(local = local)
        conversationDao.upsertConversation(entity)
        return entity.toDomain(lastMessage).also(::rememberConversation)
    }

    /**
     * 通讯录多选发起多人通话时拿/建临时群会话。
     *
     * server 端按 sorted(member_ids) 哈希作 subject_id，同一帮人多次发起仍是同一 conv
     * （保证通话历史 + 文件 + 流水在一个上下文里连续）。返回的 conv 跟普通群聊一样落库
     * 显示在消息列表，调用方拿到 id 后直接走既有 createCallInvite。
     */
    suspend fun openAdHocGroup(memberUserIds: List<Long>, title: String? = null): ConversationSummary {
        require(memberUserIds.isNotEmpty()) { "成员列表不能为空" }
        val resp = api.openAdHocGroup(
            OpenAdHocGroupRequest(
                memberUserIds = memberUserIds.map { it.toString() },
                title = title?.takeIf { it.isNotBlank() },
            ),
        )
        val dto = resp.data ?: throw ApiException(50001, 500, "多人会话响应缺数据")
        val conversationId = dto.id.toLong()
        val local = conversationDao.findById(conversationId)
        val lastMessage = dto.lastMessage
            ?.takeIf { it.serverSeq > (local?.clearedBeforeSeq ?: 0L) }
            ?.toEntity(conversationId, json)
            ?.let { mergeKnownLocalMediaFields(it) }
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
            ?.let { mergeKnownLocalMediaFields(it) }
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
            api.messages(
                conversationId = conversationId.toString(),
                sinceSeq = since,
                limit = limit,
                latest = fullSync,
            )
        } catch (error: ApiException) {
            if (error.isPermissionDenied) {
                forgetInaccessibleConversation(conversationId)
                throw staleConversationError(error)
            }
            throw error
        }
        val data = resp.data ?: throw ApiException(50001, 500, "消息历史响应缺数据")
        val clearedBeforeSeq = conversationDao.findById(conversationId)?.clearedBeforeSeq ?: 0L
        val entities = mergeKnownLocalMediaFields(
            data.items
                .filter { it.serverSeq > clearedBeforeSeq }
                .map { it.toEntity(conversationId, json) },
        )
        if (fullSync) {
            entities.mapNotNull { it.serverSeq }.minOrNull()?.let { minServerSeq ->
                messageDao.deleteServerMessagesBefore(conversationId, minServerSeq)
                pruneCachedServerMessagesBefore(conversationId, minServerSeq)
            }
        }
        messageDao.upsertServerMessages(entities)
        rememberMessages(conversationId, entities.map { it.toDomain() })
        if (fullSync) {
            markConversationHistoryHydrated(conversationId)
        }
    }

    suspend fun sendText(
        conversationId: Long,
        text: String,
        quote: MessageQuote? = null,
        mentions: List<MentionPayload> = emptyList(),
    ): String {
        val payload = buildJsonObject {
            put("text", text)
            quote?.let { quoted ->
                put(
                    "quote",
                    buildJsonObject {
                        put("local_key", quoted.localKey)
                        quoted.serverId?.let { put("server_id", it) }
                        put("sender_label", quoted.senderLabel)
                        put("text", quoted.text)
                    },
                )
            }
            if (mentions.isNotEmpty()) {
                putJsonArray("mentions") {
                    mentions.forEach { ref ->
                        add(
                            buildJsonObject {
                                put("user_id", ref.userId)
                                put("name", ref.name)
                            },
                        )
                    }
                }
            }
        }
        return sendClientMessage(
            conversationId = conversationId,
            kind = "text",
            payload = payload,
            preview = text,
        )
    }

    suspend fun forwardMessages(targetConversationId: Long, sourceLocalKeys: List<String>): Int {
        if (targetConversationId <= 0) {
            throw IllegalArgumentException("转发目标无效")
        }
        val sourceMessages = sourceLocalKeys
            .distinct()
            .mapNotNull { localKey -> messageDao.findByLocalKey(localKey) }
        if (sourceMessages.isEmpty()) {
            throw IllegalArgumentException("原消息不存在，无法转发")
        }
        sourceMessages.forEach { source ->
            val payload = runCatching { json.parseToJsonElement(source.payloadJson) }.getOrNull()
            if (payload != null && source.canForwardOriginalPayload()) {
                sendClientMessage(
                    conversationId = targetConversationId,
                    kind = source.kind,
                    payload = payload,
                    preview = source.forwardPreviewText(json),
                )
            } else {
                sendText(targetConversationId, source.forwardPreviewText(json))
            }
        }
        return sourceMessages.size
    }

    suspend fun sendImage(conversationId: Long, uri: Uri): String =
        sendLocalImage(
            conversationId = conversationId,
            localUri = uri.toString(),
            uploadAsset = { mediaAssetUploader.upload(uri, MediaAssetKind.Image) },
        )

    internal suspend fun sendLocalImage(
        conversationId: Long,
        localUri: String,
        uploadAsset: suspend () -> UploadedMediaAsset,
    ): String {
        val clientMsgId = UUID.randomUUID().toString()
        val pending = pendingMessageEntity(
            conversationId = conversationId,
            kind = "image",
            payload = pendingImagePayload(localUri),
            preview = "[图片]",
            clientMsgId = clientMsgId,
            now = Instant.now().toString(),
        )
        messageDao.upsertMessage(pending)
        rememberMessage(conversationId, pending.toDomain())

        val networkPayload = try {
            val asset = uploadAsset()
            val localPayload = mediaPayload(asset) {
                put("local_uri", localUri)
            }
            updatePendingMessagePayload(
                conversationId = conversationId,
                clientMsgId = clientMsgId,
                payload = localPayload,
                preview = "[图片]",
            )
            mediaPayload(asset)
        } catch (t: Throwable) {
            messageDao.markFailed(clientMsgId)
            markCachedMessageFailed(conversationId, clientMsgId)
            throw t
        }
        sendExistingMessage(conversationId, clientMsgId, "image", networkPayload)
        return clientMsgId
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
        if (entity.kind == "image" && payload.awaitingAssetUpload()) {
            val localUri = payload.jsonObject["local_uri"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            if (localUri != null) {
                val networkPayload = try {
                    val asset = mediaAssetUploader.upload(Uri.parse(localUri), MediaAssetKind.Image)
                    val localPayload = mediaPayload(asset) {
                        put("local_uri", localUri)
                    }
                    updatePendingMessagePayload(
                        conversationId = entity.conversationId,
                        clientMsgId = clientMsgId,
                        payload = localPayload,
                        preview = "[图片]",
                    )
                    mediaPayload(asset)
                } catch (t: Throwable) {
                    messageDao.markFailed(clientMsgId)
                    markCachedMessageFailed(entity.conversationId, clientMsgId)
                    throw t
                }
                sendExistingMessage(entity.conversationId, clientMsgId, entity.kind, networkPayload)
                return
            }
        }
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
                content = payload.withoutLocalMediaFields(),
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
                    payload = payload.withoutLocalMediaFields(),
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
            rememberMessage(conversationId, messageDao.findByClientMsgId(clientMsgId)?.toDomain() ?: delivered.toDomain())
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

    /**
     * 仅删除本地一条消息（Failed / Pending 场景，server 没有 row）。
     * 已 Sent 到 server 的消息不应该走本地删除路径。
     */
    suspend fun deleteLocalMessage(localKey: String) {
        if (localKey.isBlank()) return
        val entity = messageDao.findByLocalKey(localKey) ?: return
        val status = runCatching { MessageStatus.valueOf(entity.status) }.getOrNull()
        if (status != MessageStatus.Failed && status != MessageStatus.Pending) return
        messageDao.deleteByLocalKey(localKey)
        messageSnapshots[entity.conversationId] = messageSnapshots[entity.conversationId]
            .orEmpty()
            .filterNot { it.localKey == localKey }
    }

    /**
     * 给本地 image / video / voice 消息重签 download URL。
     *
     * 触发场景：UI 加载图片失败（pre-signed URL 5min 过期）。拿 asset_id 调
     * server presign 接口拿新 URL，写回本地 message.payload，下次进入对话 UI 也能用。
     * 返回新 URL（成功）或 null（失败）。
     */
    suspend fun refreshAssetDownloadUrl(localKey: String, assetId: String): String? {
        if (assetId.isBlank() || localKey.isBlank()) return null
        val newUrl = mediaAssetUploader.refreshDownloadUrl(assetId) ?: return null
        val entity = messageDao.findByLocalKey(localKey) ?: return newUrl
        val payload = runCatching { json.parseToJsonElement(entity.payloadJson).jsonObject }.getOrNull()
            ?: return newUrl
        val patched = buildJsonObject {
            payload.forEach { (k, v) -> if (k != "download_url" && k != "downloadUrl") put(k, v) }
            put("download_url", newUrl)
        }
        messageDao.upsertMessage(entity.copy(payloadJson = patched.toString()))
        rememberMessage(entity.conversationId, entity.copy(payloadJson = patched.toString()).toDomain())
        return newUrl
    }

    private suspend fun updatePendingMessagePayload(
        conversationId: Long,
        clientMsgId: String,
        payload: JsonElement,
        preview: String,
    ) {
        val updated = messageDao.findByClientMsgId(clientMsgId)
            ?.copy(
                payloadJson = payload.toString(),
                preview = preview,
                editedAt = Instant.now().toString(),
            )
            ?: return
        messageDao.upsertMessage(updated)
        rememberMessage(conversationId, updated.toDomain())
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
            is RealtimeEvent.TranscriptUpdated -> applyMessageUpdated(event)
            is RealtimeEvent.MessageRecalled -> applyRealtimeRecalled(event)
            is RealtimeEvent.Error -> logWarn(
                "实时通道业务错误 code=${event.code} message=${event.message}",
            )
            // 扫描融合完成事件由 scan3d 侧（ScanFusionRepository）消费，消息仓库忽略。
            is RealtimeEvent.ScanFusionDone -> Unit
            // 激光扫描事件（M8'）由 scan3d 侧（LaserScanRepository）消费，消息仓库忽略。
            is RealtimeEvent.LaserPoints -> Unit
            is RealtimeEvent.LaserStatus -> Unit
            is RealtimeEvent.LaserScanDone -> Unit
            is RealtimeEvent.Unknown -> logWarn("未知实时事件 type=${event.envelope.type}")
        }
    }

    /**
     * 撤回一条已 Sent 的消息（服务端落 deleted_at）。
     *
     * 服务端会同时 ws 推 msg.recall 给所有在线成员（含自己其它端），所以这里不主动
     * markRecalled — 全部由 [applyRealtimeRecalled] 统一落库，避免双写不一致。
     * HTTP 响应只用来确认成功 / 返回 ApiException 让 UI 提示"超过撤回时限"等。
     */
    suspend fun recallMessage(conversationId: Long, messageId: Long) {
        api.recallMessage(conversationId.toString(), messageId.toString())
    }

    private suspend fun applyRealtimeRecalled(event: RealtimeEvent.MessageRecalled) {
        messageDao.markRecalledByServerId(event.messageId, event.deletedAt)
        val updated = messageDao.findByServerId(event.messageId) ?: return
        rememberMessage(event.conversationId, updated.toDomain())
        // last_message 是已撤回消息时也要刷新 conversation 摘要预览
        if ((messageDao.maxServerSeq(event.conversationId) ?: 0L) == event.serverSeq) {
            conversationDao.recordLastMessage(
                conversationId = event.conversationId,
                localKey = "s:${event.messageId}",
                serverSeq = event.serverSeq,
                updatedAt = event.deletedAt,
                incrementUnread = false,
            )
        }
        logInfo(
            "消息已撤回 conversation_id=${event.conversationId} server_seq=${event.serverSeq} recalled_by=${event.recalledBy}",
        )
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
        val conversation = conversationDao.findById(event.conversationId)
        val knownByServerId = event.messageId?.let { messageDao.findByServerId(it) }
        val knownByClientMsgId = event.clientMsgId
            ?.takeIf { it.isNotBlank() }
            ?.let { messageDao.findByClientMsgId(it) }
        val knownByRealtimeKey = if (event.messageId == null) {
            messageDao.findByLocalKey("r:${event.conversationId}:${event.serverSeq}")
        } else {
            null
        }
        val messageAlreadyKnown = knownByServerId != null || knownByClientMsgId != null || knownByRealtimeKey != null
        val localKnown = knownByClientMsgId ?: knownByServerId ?: knownByRealtimeKey
        val entity = event.toEntity(json).withLocalMediaFieldsFrom(localKnown, json)
        messageDao.upsertServerMessages(listOf(entity))
        conversationDao.recordLastMessage(
            conversationId = event.conversationId,
            localKey = entity.localKey,
            serverSeq = event.serverSeq,
            updatedAt = event.createdAt,
            incrementUnread = !messageAlreadyKnown && event.serverSeq > (conversation?.lastReadSeq ?: 0L),
        )
        rememberMessage(event.conversationId, entity.toDomain())
        logInfo(
            "实时消息已落库 conversation_id=${event.conversationId} server_seq=${event.serverSeq} message_id=${event.messageId}",
        )
        if (conversation == null) {
            runCatching { refreshConversations() }
        }
        // 全局来电浮窗：只对新收到的 call_invite emit，避免历史 fetch / 自我 echo 误触发。
        if (event.kind == "call_invite" && !messageAlreadyKnown) {
            val payload = event.content?.let { runCatching { it.jsonObject }.getOrNull() }
            val title = payload?.get("title")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: "视频通话"
            _incomingCallInvites.tryEmit(
                IncomingCallInvite(
                    conversationId = event.conversationId,
                    messageId = event.messageId,
                    serverSeq = event.serverSeq,
                    senderId = event.senderId,
                    clientMsgId = event.clientMsgId,
                    title = title,
                    roomId = payload?.get("room_id")?.jsonPrimitive?.contentOrNull,
                    providerRoom = payload?.get("provider_room")?.jsonPrimitive?.contentOrNull,
                    livekitUrl = payload?.get("livekit_url")?.jsonPrimitive?.contentOrNull,
                    createdAt = event.createdAt,
                    conversationKind = conversation?.kind ?: "p2p",
                    conversationTitle = conversation?.title,
                ),
            )
        }
    }

    private suspend fun mergeKnownLocalMediaFields(entities: List<MessageEntity>): List<MessageEntity> =
        entities.map { mergeKnownLocalMediaFields(it) }

    private suspend fun mergeKnownLocalMediaFields(entity: MessageEntity): MessageEntity {
        if (!entity.canPreserveLocalMediaFields()) return entity
        val known = entity.clientMsgId
            ?.takeIf { it.isNotBlank() }
            ?.let { messageDao.findByClientMsgId(it) }
            ?: entity.serverId?.let { messageDao.findByServerId(it) }
            ?: messageDao.findByLocalKey(entity.localKey)
        return entity.withLocalMediaFieldsFrom(known, json)
    }

    private suspend fun applyMessageUpdated(event: RealtimeEvent.TranscriptUpdated) {
        val payloadJson = event.content?.let { json.encodeToString(JsonElement.serializer(), it) } ?: "{}"
        val preview = realtimePreview(event.kind, event.content)
        messageDao.updateServerMessagePayload(
            serverId = event.messageId,
            payloadJson = payloadJson,
            preview = preview,
            updatedAt = event.updatedAt,
        )
        val updated = messageDao.findByServerId(event.messageId)
        if (updated == null) {
            refreshMessages(conversationId = event.conversationId)
            return
        }
        updated.toDomain().let { rememberMessage(event.conversationId, it) }
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
            "消息实时更新已落库 conversation_id=${event.conversationId} server_seq=${event.serverSeq} message_id=${event.messageId}",
        )
    }

    private fun rememberConversation(summary: ConversationSummary) {
        conversationSnapshots[summary.id] = summary
    }

    private fun rememberMessages(conversationId: Long, records: List<MessageRecord>) {
        records.forEach { rememberMessage(conversationId, it) }
    }

    private fun pruneCachedServerMessagesBefore(conversationId: Long, minServerSeq: Long) {
        messageSnapshots[conversationId] = messageSnapshots[conversationId].orEmpty().filter { record ->
            val serverSeq = record.serverSeq
            serverSeq == null || serverSeq >= minServerSeq
        }
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
        forgetConversationLocal(conversationId)
        logWarn("本地会话已移除: 服务端拒绝访问 conversation_id=$conversationId")
    }

    private suspend fun forgetConversationLocal(conversationId: Long) {
        messageDao.deleteByConversationId(conversationId)
        conversationDao.deleteById(conversationId)
        conversationSnapshots.remove(conversationId)
        messageSnapshots.remove(conversationId)
        hydratedConversationIds.remove(conversationId)
    }
}

private val ApiException.isConversationGone: Boolean
    get() = isPermissionDenied || code == 40301 || httpStatus == 404

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

private fun pendingImagePayload(localUri: String): JsonElement = buildJsonObject {
    put("media_state", "awaiting_asset_upload")
    put("local_uri", localUri)
}

private fun retryableMessageKind(kind: String): Boolean =
    kind == "text" || kind == "image" || kind == "voice" || kind == "video_clip" || kind == "inspection_card"

private fun MessageEntity.canForwardOriginalPayload(): Boolean =
    kind == "text" ||
        (status == MessageStatus.Sent.name && (kind == "image" || kind == "voice" || kind == "video_clip" || kind == "inspection_card"))

private fun MessageEntity.forwardPreviewText(json: Json): String {
    if (kind == "text") {
        val text = runCatching {
            val element = json.parseToJsonElement(payloadJson)
            when (element) {
                is JsonObject -> element["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
                is JsonPrimitive -> element.contentOrNull.orEmpty()
                else -> ""
            }
        }.getOrDefault("")
        if (text.isNotBlank()) return text
    }
    return preview?.takeIf { it.isNotBlank() } ?: when (kind) {
        "image" -> "[图片]"
        "voice" -> "[语音消息]"
        "video_clip" -> "[视频消息]"
        "inspection_card" -> "[业务流水]"
        "call_invite", "video_call" -> "[视频通话]"
        "audio_call" -> "[语音通话]"
        "system" -> "[系统消息]"
        else -> "[$kind]"
    }
}

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

private fun JsonElement.awaitingAssetUpload(): Boolean =
    runCatching {
        jsonObject["media_state"]?.jsonPrimitive?.contentOrNull == "awaiting_asset_upload"
    }.getOrDefault(false)

private fun JsonElement.withoutLocalMediaFields(): JsonElement {
    val obj = this as? JsonObject ?: return this
    if ("local_uri" !in obj && "localUri" !in obj) return this
    return JsonObject(obj.filterKeys { it != "local_uri" && it != "localUri" })
}

private fun MessageEntity.canPreserveLocalMediaFields(): Boolean =
    kind == "image" || kind == "video_clip"

private fun MessageEntity.withLocalMediaFieldsFrom(local: MessageEntity?, json: Json): MessageEntity {
    if (local == null || !canPreserveLocalMediaFields() || !local.canPreserveLocalMediaFields()) return this
    val remotePayload = payloadJson.toJsonObjectOrNull(json) ?: return this
    val localPayload = local.payloadJson.toJsonObjectOrNull(json) ?: return this
    val preserved = mutableMapOf<String, JsonElement>()

    fun JsonObject.hasUsableField(keys: Set<String>): Boolean =
        keys.any { key ->
            this[key]?.let { it.asPrimitiveString()?.isNotBlank() ?: true } == true
        }

    fun preserveIfRemoteMissing(remoteKeys: Set<String>, localKeys: Set<String>, targetKey: String) {
        if (remotePayload.hasUsableField(remoteKeys)) return
        val value = localKeys.firstNotNullOfOrNull { key ->
            localPayload[key]?.takeIf { it.asPrimitiveString()?.isNotBlank() ?: true }
        } ?: return
        preserved[targetKey] = value
    }

    preserveIfRemoteMissing(
        remoteKeys = setOf("local_uri", "localUri"),
        localKeys = setOf("local_uri", "localUri"),
        targetKey = "local_uri",
    )
    preserveIfRemoteMissing(
        remoteKeys = setOf("download_url", "downloadUrl", "url"),
        localKeys = setOf("download_url", "downloadUrl", "url"),
        targetKey = "download_url",
    )
    preserveIfRemoteMissing(
        remoteKeys = setOf("mime"),
        localKeys = setOf("mime"),
        targetKey = "mime",
    )

    if (preserved.isEmpty()) return this
    val merged = JsonObject(remotePayload + preserved)
    return copy(payloadJson = json.encodeToString(JsonElement.serializer(), merged))
}

private fun String.toJsonObjectOrNull(json: Json): JsonObject? =
    runCatching { json.parseToJsonElement(this).jsonObject }.getOrNull()

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
    val recalled = !deletedAt.isNullOrBlank()
    val effectivePreview = if (recalled) "[消息已撤回]" else preview?.takeIf { it.isNotBlank() }
    val effectivePayloadJson = if (recalled) {
        "{}"
    } else {
        payload?.let { json.encodeToString(JsonElement.serializer(), it) } ?: "{}"
    }
    return MessageEntity(
        localKey = "s:$serverId",
        serverId = serverId,
        conversationId = this.conversationId?.toLongOrNull() ?: conversationId,
        serverSeq = serverSeq,
        senderId = senderId?.toLongOrNull(),
        kind = kind,
        payloadJson = effectivePayloadJson,
        preview = effectivePreview,
        clientMsgId = clientMsgId,
        status = MessageStatus.Sent.name,
        createdAt = createdAt,
        editedAt = editedAt,
        recalledAt = deletedAt,
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
    "call_invite" -> payload.callInvitePreview() ?: "[视频通话邀请]"
    "video_call", "audio_call" -> payload.callResultPreview(kind)
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
        "done" -> "[语音转文字] " + obj["transcript_normalized_text"].asPrimitiveString()
            .orEmpty()
            .ifBlank { obj["transcript_text"].asPrimitiveString().orEmpty() }
            .ifBlank { "未识别到文字" }
        "pending", "processing" -> "[语音转写中]"
        "failed" -> if (obj["transcript_error"].asPrimitiveString().isUnrecognizedVoiceError()) {
            "[语音转文字] 未识别到文字"
        } else {
            "[语音转写失败]"
        }
        else -> null
    }
}

private fun JsonElement?.callInvitePreview(): String? {
    val obj = this as? JsonObject ?: return null
    val title = obj["title"].asPrimitiveString()?.takeIf { it.isNotBlank() } ?: "视频通话"
    val status = obj["status"].asPrimitiveString().orEmpty()
    if (status.isBlank() || status == "ringing") {
        return "[视频通话] $title"
    }
    val durationSec = obj["duration_sec"].asPrimitiveString()?.toIntOrNull()
        ?: obj["duration_ms"].asPrimitiveString()?.toLongOrNull()?.let { (it / 1000).toInt() }
    if (status.isSuccessfulCallStatus()) {
        return durationSec?.takeIf { it > 0 }?.let { "[$title ${formatDuration(it)}]" } ?: "[$title]"
    }
    val reason = listOf("reason", "failure_reason", "error", "end_error", "message")
        .firstNotNullOfOrNull { key -> obj[key].asPrimitiveString()?.trim()?.takeIf(String::isNotBlank) }
        ?: status.defaultCallFailureReason()
    return if (reason.isNullOrBlank()) "[$title${status.callStatusText()}]" else "[$title${status.callStatusText()}] $reason"
}

private fun JsonElement?.callResultPreview(kind: String): String {
    val obj = this as? JsonObject ?: return "[${kind.callTitle()}]"
    val durationSec = obj["duration_sec"].asPrimitiveString()?.toIntOrNull()
        ?: obj["duration_ms"].asPrimitiveString()?.toLongOrNull()?.let { (it / 1000).toInt() }
    val status = obj["status"].asPrimitiveString().orEmpty()
        .ifBlank { if ((durationSec ?: 0) > 0) "completed" else "failed" }
    val title = kind.callTitle()
    if (status.isSuccessfulCallStatus()) {
        return durationSec?.takeIf { it > 0 }?.let { "[$title ${formatDuration(it)}]" } ?: "[$title]"
    }
    val reason = listOf("reason", "failure_reason", "error", "end_error", "message")
        .firstNotNullOfOrNull { key -> obj[key].asPrimitiveString()?.trim()?.takeIf(String::isNotBlank) }
        ?: status.defaultCallFailureReason()
    return if (reason.isNullOrBlank()) "[$title${status.callStatusText()}]" else "[$title${status.callStatusText()}] $reason"
}

private fun String.callTitle(): String = when (this) {
    "audio_call" -> "语音通话"
    else -> "视频通话"
}

private fun String.isSuccessfulCallStatus(): Boolean =
    this == "completed" || this == "success" || this == "ended"

private fun String.callStatusText(): String = when (this) {
    "completed", "success", "ended" -> "已结束"
    "missed" -> "未接通"
    "rejected" -> "已拒绝"
    "dropped" -> "异常断开"
    "cancelled", "canceled" -> "已取消"
    "failed" -> "失败"
    else -> if (isBlank()) "失败" else this
}

private fun String.defaultCallFailureReason(): String? = when (this) {
    "missed" -> "对方未接听"
    "rejected" -> "对方已拒绝"
    "dropped" -> "媒体连接异常断开"
    "cancelled", "canceled" -> "通话已取消"
    "failed" -> "媒体房间未建立或连接失败"
    else -> null
}

private fun JsonElement?.asPrimitiveString(): String? =
    (this as? JsonPrimitive)?.contentOrNull

private fun String?.isUnrecognizedVoiceError(): Boolean {
    val value = this?.trim().orEmpty()
    return value.isBlank() || value.contains("未识别") || value.contains("有效文本")
}
