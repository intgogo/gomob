package io.gomob.data.message

import com.google.common.truth.Truth.assertThat
import io.gomob.database.message.ConversationDao
import io.gomob.database.message.ConversationEntity
import io.gomob.database.message.ConversationWithLastMessage
import io.gomob.database.message.MessageDao
import io.gomob.database.message.MessageEntity
import io.gomob.model.message.MessageStatus
import io.gomob.network.Envelope
import io.gomob.network.MessageApi
import io.gomob.network.dto.ConversationDto
import io.gomob.network.dto.ConversationListResponse
import io.gomob.network.dto.CreateMessageRequest
import io.gomob.network.dto.HelpExpertDto
import io.gomob.network.dto.HelpExpertListResponse
import io.gomob.network.dto.MarkReadRequest
import io.gomob.network.dto.MarkReadResponse
import io.gomob.network.dto.MessageDto
import io.gomob.network.dto.MessageListResponse
import io.gomob.network.dto.OpenDirectConversationRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class MessageRepositoryTest {
    @Test
    fun pendingTextEntityUsesClientKeyAndPendingStatus() {
        val entity = pendingTextEntity(
            conversationId = 7,
            text = "好的",
            clientMsgId = "c-1",
            now = "2026-05-08T12:00:00Z",
        )

        assertThat(entity.localKey).isEqualTo("c:c-1")
        assertThat(entity.conversationId).isEqualTo(7)
        assertThat(entity.clientMsgId).isEqualTo("c-1")
        assertThat(entity.status).isEqualTo(MessageStatus.Pending.name)
        assertThat(entity.payloadJson).contains("好的")
        assertThat(entity.preview).isEqualTo("好的")
    }

    @Test
    fun pendingMessageEntityStoresMediaKindAndPreview() {
        val payload = buildJsonObject {
            put("media_state", "awaiting_asset_upload")
            put("source", "composer_voice")
        }

        val entity = pendingMessageEntity(
            conversationId = 7,
            kind = "voice",
            payload = payload,
            preview = "[语音待上传]",
            clientMsgId = "voice-1",
            now = "2026-05-08T12:00:00Z",
        )

        assertThat(entity.localKey).isEqualTo("c:voice-1")
        assertThat(entity.kind).isEqualTo("voice")
        assertThat(entity.payloadJson).contains("awaiting_asset_upload")
        assertThat(entity.preview).isEqualTo("[语音待上传]")
        assertThat(entity.status).isEqualTo(MessageStatus.Pending.name)
    }

    @Test
    fun sendTextUpdatesPendingRowToDeliveredWithoutDuplicateInsert() = runTest {
        val messageDao = FakeMessageDao()
        val repository = MessageRepository(
            api = FakeMessageApi(),
            conversationDao = FakeConversationDao(),
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        val clientMsgId = repository.sendText(conversationId = 9, text = "收到")

        assertThat(messageDao.items).hasSize(1)
        val saved = messageDao.items.single()
        assertThat(saved.clientMsgId).isEqualTo(clientMsgId)
        assertThat(saved.localKey).isEqualTo("s:101")
        assertThat(saved.serverId).isEqualTo(101)
        assertThat(saved.serverSeq).isEqualTo(5)
        assertThat(saved.status).isEqualTo(MessageStatus.Sent.name)
    }

    @Test
    fun sendVoiceUsesVoiceKindAndAwaitingUploadPayload() = runTest {
        val api = FakeMessageApi()
        val repository = MessageRepository(
            api = api,
            conversationDao = FakeConversationDao(),
            messageDao = FakeMessageDao(),
            json = Json { ignoreUnknownKeys = true },
        )

        repository.sendVoice(conversationId = 9)

        val request = api.sentRequests.single()
        assertThat(request.kind).isEqualTo("voice")
        assertThat(request.payload.toString()).contains("awaiting_asset_upload")
    }

    @Test
    fun sendVideoClipUsesVideoClipKindAndAwaitingUploadPayload() = runTest {
        val api = FakeMessageApi()
        val repository = MessageRepository(
            api = api,
            conversationDao = FakeConversationDao(),
            messageDao = FakeMessageDao(),
            json = Json { ignoreUnknownKeys = true },
        )

        repository.sendVideoClip(conversationId = 9)

        val request = api.sentRequests.single()
        assertThat(request.kind).isEqualTo("video_clip")
        assertThat(request.payload.toString()).contains("awaiting_asset_upload")
    }

    @Test
    fun retryMessageResendsMediaKind() = runTest {
        val api = FakeMessageApi()
        val messageDao = FakeMessageDao()
        messageDao.upsertMessage(
            pendingMessageEntity(
                conversationId = 9,
                kind = "voice",
                payload = buildJsonObject { put("media_state", "awaiting_asset_upload") },
                preview = "[语音待上传]",
                clientMsgId = "voice-retry",
                now = "2026-05-08T12:00:00Z",
            ).copy(status = MessageStatus.Failed.name),
        )
        val repository = MessageRepository(
            api = api,
            conversationDao = FakeConversationDao(),
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.retryMessage("voice-retry")

        val request = api.sentRequests.single()
        assertThat(request.kind).isEqualTo("voice")
        assertThat(request.clientMsgId).isEqualTo("voice-retry")
        assertThat(messageDao.items.single().status).isEqualTo(MessageStatus.Sent.name)
    }

    @Test
    fun refreshConversationsStoresServerLastMessagePreview() = runTest {
        val messageDao = FakeMessageDao()
        val repository = MessageRepository(
            api = FakeMessageApi(
                conversations = listOf(
                    ConversationDto(
                        id = "9",
                        kind = "p2p",
                        lastMessage = MessageDto(
                            id = "101",
                            conversationId = null,
                            serverSeq = 5,
                            senderId = "2",
                            kind = "text",
                            payload = null,
                            preview = "上线同步：请复核第 3 工位 VIN 拓印",
                            createdAt = "2026-05-08T12:00:00Z",
                        ),
                        createdAt = "2026-05-08T11:00:00Z",
                        updatedAt = "2026-05-08T12:00:00Z",
                    ),
                ),
            ),
            conversationDao = FakeConversationDao(),
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.refreshConversations()

        assertThat(messageDao.items).hasSize(1)
        assertThat(messageDao.items.single().preview).isEqualTo("上线同步：请复核第 3 工位 VIN 拓印")
        assertThat(messageDao.items.single().payloadJson).isEqualTo("{}")
    }

    @Test
    fun fullRefreshMessagesStartsFromZeroEvenWhenSummaryCachedLastMessage() = runTest {
        val messageDao = FakeMessageDao()
        messageDao.upsertMessage(
            MessageEntity(
                localKey = "s:101",
                serverId = 101,
                conversationId = 9,
                serverSeq = 5,
                senderId = 2,
                kind = "text",
                payloadJson = "{}",
                preview = "最后一条摘要",
                clientMsgId = null,
                status = MessageStatus.Sent.name,
                createdAt = "2026-05-08T12:00:00Z",
                editedAt = null,
            ),
        )
        val api = FakeMessageApi(
            messages = listOf(
                MessageDto(
                    id = "99",
                    conversationId = "9",
                    serverSeq = 1,
                    senderId = "2",
                    kind = "text",
                    payload = buildJsonObject { put("text", "第一条历史消息") },
                    createdAt = "2026-05-08T11:58:00Z",
                ),
                MessageDto(
                    id = "101",
                    conversationId = "9",
                    serverSeq = 5,
                    senderId = "2",
                    kind = "text",
                    payload = buildJsonObject { put("text", "最后一条完整消息") },
                    createdAt = "2026-05-08T12:00:00Z",
                ),
            ),
        )
        val repository = MessageRepository(
            api = api,
            conversationDao = FakeConversationDao(),
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.refreshMessages(conversationId = 9, fullSync = true)

        assertThat(api.messageSinceSeqRequests).containsExactly(0L)
        assertThat(messageDao.items.map { it.serverSeq }).containsExactly(1L, 5L)
    }

    @Test
    fun helpExpertsMapServerFixedExperts() = runTest {
        val repository = MessageRepository(
            api = FakeMessageApi(
                experts = listOf(
                    HelpExpertDto(
                        userId = "31",
                        name = "陈若愚",
                        employeeId = "EXP-VIN-0001",
                        roleTitle = "VIN 拓印专家",
                        specialty = "VIN 字符复核",
                        availability = "message_ready",
                    ),
                ),
            ),
            conversationDao = FakeConversationDao(),
            messageDao = FakeMessageDao(),
            json = Json { ignoreUnknownKeys = true },
        )

        val experts = repository.helpExperts()

        assertThat(experts).hasSize(1)
        assertThat(experts.single().userId).isEqualTo(31)
        assertThat(experts.single().roleTitle).isEqualTo("VIN 拓印专家")
    }

    @Test
    fun openDirectConversationStoresReturnedConversation() = runTest {
        val conversationDao = FakeConversationDao()
        val repository = MessageRepository(
            api = FakeMessageApi(
                directConversation = ConversationDto(
                    id = "44",
                    kind = "p2p",
                    peer = io.gomob.network.dto.ConversationPeerDto(
                        id = "31",
                        name = "陈若愚",
                        employeeId = "EXP-VIN-0001",
                    ),
                    createdAt = "2026-05-08T12:00:00Z",
                    updatedAt = "2026-05-08T12:00:00Z",
                ),
            ),
            conversationDao = conversationDao,
            messageDao = FakeMessageDao(),
            json = Json { ignoreUnknownKeys = true },
        )

        val conversation = repository.openDirectConversation(peerUserId = 31)

        assertThat(conversation.id).isEqualTo(44)
        assertThat(conversation.peer?.name).isEqualTo("陈若愚")
        assertThat(conversationDao.upsertedSingle?.id).isEqualTo(44)
    }

    @Test
    fun openHelpRoomStoresReturnedGroupConversation() = runTest {
        val conversationDao = FakeConversationDao()
        val repository = MessageRepository(
            api = FakeMessageApi(
                helpRoom = ConversationDto(
                    id = "77",
                    kind = "group",
                    title = "在线求助",
                    subjectKind = "online_help",
                    subjectId = "3",
                    createdAt = "2026-05-08T12:00:00Z",
                    updatedAt = "2026-05-08T12:00:00Z",
                ),
            ),
            conversationDao = conversationDao,
            messageDao = FakeMessageDao(),
            json = Json { ignoreUnknownKeys = true },
        )

        val conversation = repository.openHelpRoom()

        assertThat(conversation.id).isEqualTo(77)
        assertThat(conversation.kind).isEqualTo("group")
        assertThat(conversation.title).isEqualTo("在线求助")
        assertThat(conversation.subjectKind).isEqualTo("online_help")
        assertThat(conversationDao.upsertedSingle?.id).isEqualTo(77)
    }
}

private class FakeMessageApi(
    private val conversations: List<ConversationDto> = emptyList(),
    private val messages: List<MessageDto> = emptyList(),
    private val experts: List<HelpExpertDto> = emptyList(),
    private val directConversation: ConversationDto? = null,
    private val helpRoom: ConversationDto? = null,
) : MessageApi {
    val messageSinceSeqRequests = mutableListOf<Long>()
    val sentRequests = mutableListOf<CreateMessageRequest>()

    override suspend fun conversations(cursor: String?, limit: Int): Envelope<ConversationListResponse> =
        Envelope(code = 0, data = ConversationListResponse(items = conversations))

    override suspend fun helpExperts(): Envelope<HelpExpertListResponse> =
        Envelope(code = 0, data = HelpExpertListResponse(items = experts))

    override suspend fun openHelpRoom(): Envelope<ConversationDto> =
        Envelope(
            code = 0,
            data = helpRoom ?: ConversationDto(
                id = "77",
                kind = "group",
                title = "在线求助",
                subjectKind = "online_help",
                createdAt = "2026-05-08T11:00:00Z",
                updatedAt = "2026-05-08T11:00:00Z",
            ),
        )

    override suspend fun openDirectConversation(
        request: OpenDirectConversationRequest,
    ): Envelope<ConversationDto> =
        Envelope(
            code = 0,
            data = directConversation ?: ConversationDto(
                id = "9",
                kind = "p2p",
                createdAt = "2026-05-08T11:00:00Z",
                updatedAt = "2026-05-08T11:00:00Z",
            ),
        )

    override suspend fun messages(
        conversationId: String,
        sinceSeq: Long,
        limit: Int,
    ): Envelope<MessageListResponse> {
        messageSinceSeqRequests += sinceSeq
        return Envelope(code = 0, data = MessageListResponse(items = messages))
    }

    override suspend fun sendMessage(
        conversationId: String,
        request: CreateMessageRequest,
    ): Envelope<MessageDto> {
        sentRequests += request
        return Envelope(
            code = 0,
            data = MessageDto(
                id = "101",
                conversationId = conversationId,
                serverSeq = 5,
                senderId = "1",
                kind = request.kind,
                payload = request.payload,
                clientMsgId = request.clientMsgId,
                createdAt = "2026-05-08T12:00:00Z",
            ),
        )
    }

    override suspend fun markRead(
        conversationId: String,
        request: MarkReadRequest,
    ): Envelope<MarkReadResponse> =
        Envelope(
            code = 0,
            data = MarkReadResponse(
                conversationId = conversationId,
                lastReadSeq = request.lastReadSeq,
                unreadCount = 0,
            ),
        )
}

private class FakeConversationDao : ConversationDao {
    private val empty = MutableStateFlow<List<ConversationWithLastMessage>>(emptyList())
    private val current = MutableStateFlow<ConversationWithLastMessage?>(null)
    var upsertedSingle: ConversationEntity? = null

    override fun observeConversations(): Flow<List<ConversationWithLastMessage>> = empty

    override fun observeConversation(conversationId: Long): Flow<ConversationWithLastMessage?> = current

    override suspend fun findById(conversationId: Long): ConversationEntity? = null

    override suspend fun upsertConversations(items: List<ConversationEntity>) = Unit

    override suspend fun upsertConversation(item: ConversationEntity) {
        upsertedSingle = item
    }

    override suspend fun markRead(conversationId: Long, lastReadSeq: Long, unreadCount: Long) = Unit
}

private class FakeMessageDao : MessageDao {
    val items = mutableListOf<MessageEntity>()
    private val messagesFlow = MutableStateFlow<List<MessageEntity>>(emptyList())

    override fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> = messagesFlow

    override suspend fun maxServerSeq(conversationId: Long): Long? =
        items.filter { it.conversationId == conversationId }.mapNotNull { it.serverSeq }.maxOrNull()

    override suspend fun findByClientMsgId(clientMsgId: String): MessageEntity? =
        items.firstOrNull { it.clientMsgId == clientMsgId }

    override suspend fun upsertServerMessages(items: List<MessageEntity>) {
        items.forEach { upsertMessage(it) }
    }

    override suspend fun upsertMessage(item: MessageEntity) {
        val index = items.indexOfFirst { it.localKey == item.localKey }
        if (index >= 0) {
            items[index] = item
        } else {
            items += item
        }
        messagesFlow.value = items.toList()
    }

    override suspend fun markDelivered(clientMsgId: String, serverId: Long, serverSeq: Long, createdAt: String) {
        val index = items.indexOfFirst { it.clientMsgId == clientMsgId }
        if (index < 0) return
        val old = items[index]
        items[index] = old.copy(
            localKey = "s:$serverId",
            serverId = serverId,
            serverSeq = serverSeq,
            status = MessageStatus.Sent.name,
            createdAt = createdAt,
        )
        messagesFlow.value = items.toList()
    }

    override suspend fun markFailed(clientMsgId: String) {
        updateStatus(clientMsgId, MessageStatus.Failed)
    }

    override suspend fun markPending(clientMsgId: String) {
        updateStatus(clientMsgId, MessageStatus.Pending)
    }

    private fun updateStatus(clientMsgId: String, status: MessageStatus) {
        val index = items.indexOfFirst { it.clientMsgId == clientMsgId }
        if (index >= 0) {
            items[index] = items[index].copy(status = status.name)
            messagesFlow.value = items.toList()
        }
    }
}
