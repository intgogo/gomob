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
private const val DEFAULT_PREWARM_MESSAGE_LIMIT = 30
private const val DEFAULT_SEARCH_MESSAGE_LIMIT = 200
private const val MESSAGE_REPOSITORY_TAG = "MessageRepository"
private const val STALE_CONVERSATION_MESSAGE = "会话已失效，请返回消息中心重新打开"

private val localMultiLineRoomSeeds = listOf(
    LocalMultiLineRoomSeed(
        id = 9_990_101L,
        title = "查验复核群",
        subjectKind = "查验复核",
        subjectId = 101L,
        unreadCount = 2,
        messages = listOf(
            LocalMultiLineMessageSeed(1, 2_101L, "第 3 工位外廓尺寸复核照片已补齐，右后侧角度可以进复核。", "2026-05-08T12:18:00Z"),
            LocalMultiLineMessageSeed(2, 2_104L, "我把 VIN 拓印和行驶证照片放到群里，等会儿一起核对。", "2026-05-08T12:20:00Z"),
            LocalMultiLineMessageSeed(3, 2_109L, "底盘号遮挡位置已标注，报告备注按这个口径走。", "2026-05-08T12:23:00Z"),
            LocalMultiLineMessageSeed(4, 2_101L, "外廓尺寸复核结论已同步，请值班员确认。", "2026-05-08T12:25:00Z"),
        ),
    ),
    LocalMultiLineRoomSeed(
        id = 9_990_102L,
        title = "杭州西湖检测站",
        subjectKind = "站内协同",
        subjectId = 102L,
        unreadCount = 1,
        messages = listOf(
            LocalMultiLineMessageSeed(1, 2_201L, "下午高峰先保留 2 条查验线，复检车辆统一排到 B 区。", "2026-05-08T12:05:00Z"),
            LocalMultiLineMessageSeed(2, 2_207L, "新来的危化品车需要两人交叉确认，证照我已经上传。", "2026-05-08T12:11:00Z"),
            LocalMultiLineMessageSeed(3, 2_203L, "收到，调度屏已经更新，下一辆进 1 号通道。", "2026-05-08T12:16:00Z"),
        ),
    ),
    LocalMultiLineRoomSeed(
        id = 9_990_103L,
        title = "监管抽查协作群",
        subjectKind = "监管抽查",
        subjectId = 103L,
        unreadCount = 0,
        messages = listOf(
            LocalMultiLineMessageSeed(1, 2_301L, "抽查样本先按新能源轻客、重型货车各 3 台抽取。", "2026-05-08T11:42:00Z"),
            LocalMultiLineMessageSeed(2, 2_303L, "监管端已经看到同步记录，异常项只保留有照片佐证的条目。", "2026-05-08T11:50:00Z"),
            LocalMultiLineMessageSeed(3, 2_302L, "第 7 条的检测员签名缺一笔，我联系现场补签。", "2026-05-08T11:57:00Z"),
        ),
    ),
    LocalMultiLineRoomSeed(
        id = 9_990_104L,
        title = "3D 重建会审群",
        subjectKind = "三维会审",
        subjectId = 104L,
        unreadCount = 3,
        messages = listOf(
            LocalMultiLineMessageSeed(1, 2_401L, "这台车左前翼子板点云有一段反光缺洞，我重新采了一圈。", "2026-05-08T10:58:00Z"),
            LocalMultiLineMessageSeed(2, 2_405L, "第二组 RGBD 帧已经上传，外参残差比上午那版稳定。", "2026-05-08T11:04:00Z"),
            LocalMultiLineMessageSeed(3, 2_402L, "会审结论先写“需人工复核”，不要直接判异常。", "2026-05-08T11:09:00Z"),
            LocalMultiLineMessageSeed(4, 2_405L, "我把重建截图和测量标尺都补到记录里了。", "2026-05-08T11:13:00Z"),
        ),
    ),
    LocalMultiLineRoomSeed(
        id = 9_990_105L,
        title = "今日排队调度群",
        subjectKind = "现场调度",
        subjectId = 105L,
        unreadCount = 0,
        messages = listOf(
            LocalMultiLineMessageSeed(1, 2_501L, "预约 14:00 后的车辆提醒车主提前准备三角警示牌。", "2026-05-08T10:20:00Z"),
            LocalMultiLineMessageSeed(2, 2_503L, "A 区排队 12 辆，B 区复检 4 辆，预计 20 分钟消化。", "2026-05-08T10:27:00Z"),
            LocalMultiLineMessageSeed(3, 2_502L, "临牌车辆请先走人工窗口，资料齐了再进线。", "2026-05-08T10:31:00Z"),
        ),
    ),
)

private val localMultiLineRoomIds = localMultiLineRoomSeeds.map { it.id }.toSet()

private data class LocalMultiLineRoomSeed(
    val id: Long,
    val title: String,
    val subjectKind: String,
    val subjectId: Long,
    val unreadCount: Long,
    val messages: List<LocalMultiLineMessageSeed>,
)

private data class LocalMultiLineMessageSeed(
    val index: Int,
    val senderId: Long,
    val text: String,
    val createdAt: String,
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
    private val exitedLocalMultiLineRoomIds = ConcurrentHashMap.newKeySet<Long>()

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
        if (conversationId in localMultiLineRoomIds) {
            exitedLocalMultiLineRoomIds += conversationId
            forgetConversationLocal(conversationId)
            return
        }
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
        ensureLocalMultiLineRooms()
        return entity.toDomain(lastMessage).also(::rememberConversation)
    }

    suspend fun refreshMessages(conversationId: Long, limit: Int = 100, fullSync: Boolean = false) {
        if (conversationId in localMultiLineRoomIds) {
            ensureLocalMultiLineRooms()
            warmConversationSnapshot(conversationId, limit)
            markConversationHistoryHydrated(conversationId)
            return
        }
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

    suspend fun sendText(conversationId: Long, text: String, quote: MessageQuote? = null): String {
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

    private suspend fun ensureLocalMultiLineRooms() {
        val conversationEntities = mutableListOf<ConversationEntity>()
        val messageEntities = mutableListOf<MessageEntity>()
        localMultiLineRoomSeeds.forEach { seed ->
            if (seed.id in exitedLocalMultiLineRoomIds) return@forEach
            val existing = conversationDao.findById(seed.id)
            val messages = seed.messages.map { it.toEntity(seed.id) }
            val lastMessage = messages.lastOrNull()
            messageEntities += messages
            conversationEntities += ConversationEntity(
                id = seed.id,
                kind = "group",
                title = seed.title,
                peerId = null,
                peerName = null,
                peerEmployeeId = null,
                subjectKind = seed.subjectKind,
                subjectId = seed.subjectId,
                lastMessageLocalKey = lastMessage?.localKey,
                lastReadSeq = existing?.lastReadSeq ?: 0L,
                unreadCount = existing?.unreadCount ?: seed.unreadCount,
                pinned = existing?.pinned ?: false,
                clearedBeforeSeq = existing?.clearedBeforeSeq ?: 0L,
                createdAt = messages.firstOrNull()?.createdAt ?: "2026-05-08T10:00:00Z",
                updatedAt = lastMessage?.createdAt ?: "2026-05-08T10:00:00Z",
            )
            lastMessage?.let { rememberConversation(conversationEntities.last().toDomain(it)) }
            messageSnapshots[seed.id] = messages.map { it.toDomain() }
            markConversationHistoryHydrated(seed.id)
        }
        if (messageEntities.isNotEmpty()) {
            messageDao.upsertServerMessages(messageEntities)
        }
        if (conversationEntities.isNotEmpty()) {
            conversationDao.upsertConversations(conversationEntities)
        }
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

private val ApiException.isPermissionDenied: Boolean
    get() = code == 40103 || httpStatus == 403

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

private fun LocalMultiLineMessageSeed.toEntity(conversationId: Long): MessageEntity = MessageEntity(
    localKey = "ml:$conversationId:$index",
    serverId = null,
    conversationId = conversationId,
    serverSeq = null,
    senderId = senderId,
    kind = "text",
    payloadJson = buildJsonObject { put("text", text) }.toString(),
    preview = text,
    clientMsgId = null,
    status = MessageStatus.Sent.name,
    createdAt = createdAt,
    editedAt = null,
)

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
