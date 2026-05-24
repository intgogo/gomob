package io.gomob.data.message

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.gomob.database.message.ConversationDao
import io.gomob.database.message.ConversationEntity
import io.gomob.database.message.ConversationWithLastMessage
import io.gomob.database.message.MessageDao
import io.gomob.database.message.MessageEntity
import io.gomob.model.message.MessageStatus
import io.gomob.network.Envelope
import io.gomob.network.ApiException
import io.gomob.model.message.InspectionShareCard
import io.gomob.network.MessageApi
import io.gomob.network.dto.ContactListResponse
import io.gomob.network.dto.ConversationDto
import io.gomob.network.dto.ConversationListResponse
import io.gomob.network.dto.CallInviteResponse
import io.gomob.network.dto.CreateCallInviteRequest
import io.gomob.network.dto.CreateMessageRequest
import io.gomob.network.dto.HelpExpertCaseDto
import io.gomob.network.dto.HelpExpertCaseListResponse
import io.gomob.network.dto.HelpExpertDto
import io.gomob.network.dto.HelpExpertListResponse
import io.gomob.network.dto.LeaveConversationResponse
import io.gomob.network.dto.MarkReadRequest
import io.gomob.network.dto.MarkReadResponse
import io.gomob.network.dto.MessageDto
import io.gomob.network.dto.MessageListResponse
import io.gomob.network.dto.MediaRoomResponse
import io.gomob.network.dto.OpenDirectConversationRequest
import io.gomob.network.dto.TranscribeDraftVoiceRequest
import io.gomob.network.dto.TranscribeDraftVoiceResponse
import io.gomob.realtime.RealtimeEvent
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
            mediaAssetUploader = FakeMediaAssetUploader(),
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
    fun sendImageShowsLocalUriImmediatelyButSendsOnlyAssetPayload() = runTest {
        val api = FakeMessageApi()
        val messageDao = FakeMessageDao()
        val uploader = FakeMediaAssetUploader()
        val repository = MessageRepository(
            api = api,
            mediaAssetUploader = uploader,
            conversationDao = FakeConversationDao(),
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )
        val localUri = "content://io.gomob.scan.debug.message.fileprovider/message_captures/capture.jpg"

        val clientMsgId = repository.sendLocalImage(
            conversationId = 9,
            localUri = localUri,
            uploadAsset = {
                uploader.uploadedKinds += MediaAssetKind.Image
                fakeUploadedAsset().copy(
                    objectKey = "orphan/${MediaAssetKind.Image.serverKind}/901.bin",
                    mime = MediaAssetKind.Image.fallbackMime,
                )
            },
        )

        assertThat(uploader.uploadedKinds).containsExactly(MediaAssetKind.Image)
        val request = api.sentRequests.single()
        assertThat(request.kind).isEqualTo("image")
        assertThat(request.payload.toString()).contains("\"media_state\":\"ready\"")
        assertThat(request.payload.toString()).contains("\"asset_id\":\"901\"")
        assertThat(request.payload.toString()).doesNotContain("local_uri")
        val saved = messageDao.items.single()
        assertThat(saved.clientMsgId).isEqualTo(clientMsgId)
        assertThat(saved.localKey).isEqualTo("s:101")
        assertThat(saved.status).isEqualTo(MessageStatus.Sent.name)
        assertThat(saved.payloadJson).contains("\"local_uri\":\"$localUri\"")
        assertThat(saved.payloadJson).contains("\"download_url\":\"http://example.test/901\"")
    }

    @Test
    fun sendVoiceUploadsAssetBeforeSendingReadyPayload() = runTest {
        val api = FakeMessageApi()
        val repository = MessageRepository(
            api = api,
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = FakeConversationDao(),
            messageDao = FakeMessageDao(),
            json = Json { ignoreUnknownKeys = true },
        )

        repository.sendUploadedVoice(conversationId = 9, asset = fakeUploadedAsset(), durationSec = 6)

        val request = api.sentRequests.single()
        assertThat(request.kind).isEqualTo("voice")
        assertThat(request.payload.toString()).contains("\"media_state\":\"ready\"")
        assertThat(request.payload.toString()).contains("\"asset_id\":\"901\"")
        assertThat(request.payload.toString()).contains("\"duration_sec\":6")
    }

    @Test
    fun transcribeUploadedVoiceDraftReturnsNormalizedTextWithoutSendingMessage() = runTest {
        val api = FakeMessageApi(draftTranscriptText = "  你好世界。  ")
        val repository = MessageRepository(
            api = api,
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = FakeConversationDao(),
            messageDao = FakeMessageDao(),
            json = Json { ignoreUnknownKeys = true },
        )

        val text = repository.transcribeUploadedVoiceDraft(fakeUploadedAsset())

        assertThat(text).isEqualTo("你好世界。")
        assertThat(api.draftTranscribeRequests.single().assetId).isEqualTo("901")
        assertThat(api.sentRequests).isEmpty()
    }

    @Test
    fun sendVideoClipUploadsAssetBeforeSendingReadyPayload() = runTest {
        val api = FakeMessageApi()
        val repository = MessageRepository(
            api = api,
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = FakeConversationDao(),
            messageDao = FakeMessageDao(),
            json = Json { ignoreUnknownKeys = true },
        )

        repository.sendUploadedVideoClip(conversationId = 9, asset = fakeUploadedAsset())

        val request = api.sentRequests.single()
        assertThat(request.kind).isEqualTo("video_clip")
        assertThat(request.payload.toString()).contains("\"media_state\":\"ready\"")
    }

    @Test
    fun sendInspectionCardSendsStructuredPayload() = runTest {
        val api = FakeMessageApi()
        val repository = MessageRepository(
            api = api,
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = FakeConversationDao(),
            messageDao = FakeMessageDao(),
            json = Json { ignoreUnknownKeys = true },
        )

        repository.sendInspectionCard(
            conversationId = 9,
            card = InspectionShareCard(
                inspectionId = "LSVHM133022221761",
                vin = "LSVHM133022221761",
                vehicleLine = "大众系列 · 小型汽车 · 沪A12345",
                timeLabel = "11:45",
                status = "danger",
                tags = listOf("OBD检验", "外廓尺寸"),
            ),
        )

        val request = api.sentRequests.single()
        assertThat(request.kind).isEqualTo("inspection_card")
        assertThat(request.payload.toString()).contains("\"inspection_id\":\"LSVHM133022221761\"")
        assertThat(request.payload.toString()).contains("\"vin\":\"LSVHM133022221761\"")
        assertThat(request.payload.toString()).contains("\"tags\":[\"OBD检验\",\"外廓尺寸\"]")
    }

    @Test
    fun createVideoCallInviteStoresServerMessage() = runTest {
        val messageDao = FakeMessageDao()
        val api = FakeMessageApi()
        val repository = MessageRepository(
            api = api,
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = FakeConversationDao(),
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        val invite = repository.createVideoCallInvite(conversationId = 9, title = "和陈若愚的视频通话")

        assertThat(invite.roomId).isEqualTo("701")
        assertThat(invite.providerRoom).isEqualTo("gomob_call_test")
        assertThat(api.callInviteRequests.single().title).isEqualTo("和陈若愚的视频通话")
        assertThat(messageDao.items.single().kind).isEqualTo("call_invite")
        assertThat(messageDao.items.single().payloadJson).contains("\"room_id\":\"701\"")
    }

    @Test
    fun createVideoCallInviteRemovesStaleConversationOnForbidden() = runTest {
        val conversationDao = FakeConversationDao(
            initialConversations = listOf(
                ConversationWithLastMessage(
                    conversation = conversationEntity(9),
                    lastMessage = null,
                ),
            ),
        )
        val messageDao = FakeMessageDao()
        messageDao.upsertMessage(
            pendingTextEntity(
                conversationId = 9,
                text = "旧消息",
                clientMsgId = "old-1",
                now = "2026-05-08T12:00:00Z",
            ),
        )
        val repository = MessageRepository(
            api = FakeMessageApi(callInviteError = ApiException(40103, 403, "权限不足")),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = conversationDao,
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        val error = runCatching {
            repository.createVideoCallInvite(conversationId = 9, title = "视频通话")
        }.exceptionOrNull()

        assertThat(error?.message).isEqualTo("会话已失效，请返回消息中心重新打开")
        assertThat(conversationDao.findById(9)).isNull()
        assertThat(messageDao.items).isEmpty()
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
            mediaAssetUploader = FakeMediaAssetUploader(),
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
            mediaAssetUploader = FakeMediaAssetUploader(),
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
    fun fullRefreshMessagesRequestsLatestPageEvenWhenSummaryCachedLastMessage() = runTest {
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
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = FakeConversationDao(),
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.refreshMessages(conversationId = 9, fullSync = true)

        assertThat(api.messageSinceSeqRequests).containsExactly(0L)
        assertThat(api.messageLatestRequests).containsExactly(true)
        assertThat(messageDao.items.map { it.serverSeq }).containsExactly(1L, 5L)
    }

    @Test
    fun refreshMessagesKeepsLocalImageDisplayFieldsWhenServerPayloadIsSparse() = runTest {
        val localUri = "content://io.gomob.scan.debug.message.fileprovider/message_captures/capture.jpg"
        val messageDao = FakeMessageDao()
        messageDao.upsertMessage(
            MessageEntity(
                localKey = "s:303",
                serverId = 303,
                conversationId = 9,
                serverSeq = 7,
                senderId = 1,
                kind = "image",
                payloadJson = buildJsonObject {
                    put("media_state", "ready")
                    put("asset_id", "901")
                    put("download_url", "http://example.test/901")
                    put("local_uri", localUri)
                    put("mime", "image/jpeg")
                }.toString(),
                preview = "[图片]",
                clientMsgId = "img-1",
                status = MessageStatus.Sent.name,
                createdAt = "2026-05-08T12:00:00Z",
                editedAt = null,
            ),
        )
        val repository = MessageRepository(
            api = FakeMessageApi(
                messages = listOf(
                    MessageDto(
                        id = "303",
                        conversationId = "9",
                        serverSeq = 7,
                        senderId = "1",
                        kind = "image",
                        payload = buildJsonObject {
                            put("media_state", "ready")
                            put("asset_id", "901")
                        },
                        clientMsgId = "img-1",
                        createdAt = "2026-05-08T12:00:01Z",
                    ),
                ),
            ),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = FakeConversationDao(),
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.refreshMessages(conversationId = 9)

        val saved = messageDao.items.single()
        assertThat(saved.localKey).isEqualTo("s:303")
        assertThat(saved.payloadJson).contains("\"local_uri\":\"$localUri\"")
        assertThat(saved.payloadJson).contains("\"download_url\":\"http://example.test/901\"")
        assertThat(saved.payloadJson).contains("\"mime\":\"image/jpeg\"")
    }

    @Test
    fun fullRefreshMessagesPrunesCachedServerMessagesBeforeLatestWindow() = runTest {
        val messageDao = FakeMessageDao()
        messageDao.upsertMessage(
            MessageEntity(
                localKey = "s:1",
                serverId = 1,
                conversationId = 9,
                serverSeq = 1,
                senderId = 2,
                kind = "text",
                payloadJson = "{}",
                preview = "旧缓存第一条",
                clientMsgId = null,
                status = MessageStatus.Sent.name,
                createdAt = "2026-05-08T11:00:00Z",
                editedAt = null,
            ),
        )
        messageDao.upsertMessage(
            pendingMessageEntity(
                conversationId = 9,
                kind = "text",
                payload = buildJsonObject { put("text", "本地待发送") },
                preview = "本地待发送",
                clientMsgId = "pending-1",
                now = "2026-05-08T12:00:00Z",
            ),
        )
        val repository = MessageRepository(
            api = FakeMessageApi(
                messages = listOf(
                    MessageDto(
                        id = "170",
                        conversationId = "9",
                        serverSeq = 70,
                        senderId = "2",
                        kind = "text",
                        payload = buildJsonObject { put("text", "最新窗口第一条") },
                        createdAt = "2026-05-08T12:30:00Z",
                    ),
                    MessageDto(
                        id = "199",
                        conversationId = "9",
                        serverSeq = 99,
                        senderId = "1",
                        kind = "text",
                        payload = buildJsonObject { put("text", "最新窗口最后一条") },
                        createdAt = "2026-05-08T12:59:00Z",
                    ),
                ),
            ),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = FakeConversationDao(),
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.warmConversationSnapshot(conversationId = 9)
        repository.refreshMessages(conversationId = 9, fullSync = true)

        assertThat(messageDao.items.map { it.localKey }).containsExactly("c:pending-1", "s:170", "s:199")
        assertThat(repository.cachedMessages(9).map { it.serverSeq }).containsExactly(70L, 99L, null).inOrder()
    }

    @Test
    fun fullRefreshMessagesReplacesPendingEchoByClientMsgId() = runTest {
        val messageDao = FakeMessageDao()
        messageDao.upsertMessage(
            pendingMessageEntity(
                conversationId = 9,
                kind = "text",
                payload = buildJsonObject { put("text", "详情页打开前发送") },
                preview = "详情页打开前发送",
                clientMsgId = "echo-1",
                now = "2026-05-08T12:00:00Z",
            ),
        )
        val repository = MessageRepository(
            api = FakeMessageApi(
                messages = listOf(
                    MessageDto(
                        id = "101",
                        conversationId = "9",
                        serverSeq = 5,
                        senderId = "2",
                        kind = "text",
                        payload = buildJsonObject { put("text", "详情页打开前发送") },
                        clientMsgId = "echo-1",
                        createdAt = "2026-05-08T12:00:01Z",
                    ),
                ),
            ),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = FakeConversationDao(),
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.refreshMessages(conversationId = 9, fullSync = true)

        assertThat(messageDao.items).hasSize(1)
        val saved = messageDao.items.single()
        assertThat(saved.localKey).isEqualTo("s:101")
        assertThat(saved.serverId).isEqualTo(101)
        assertThat(saved.clientMsgId).isEqualTo("echo-1")
        assertThat(saved.status).isEqualTo(MessageStatus.Sent.name)
        assertThat(saved.payloadJson).contains("详情页打开前发送")
    }

    @Test
    fun fullRefreshMarksConversationHistoryHydrated() = runTest {
        val repository = MessageRepository(
            api = FakeMessageApi(
                messages = listOf(
                    MessageDto(
                        id = "101",
                        conversationId = "9",
                        serverSeq = 5,
                        senderId = "2",
                        kind = "text",
                        payload = buildJsonObject { put("text", "本地历史铺底后同步的新消息") },
                        createdAt = "2026-05-08T12:00:00Z",
                    ),
                ),
            ),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = FakeConversationDao(),
            messageDao = FakeMessageDao(),
            json = Json { ignoreUnknownKeys = true },
        )

        assertThat(repository.shouldHydrateConversationHistory(9)).isTrue()

        repository.refreshMessages(conversationId = 9, fullSync = true)

        assertThat(repository.shouldHydrateConversationHistory(9)).isFalse()
    }

    @Test
    fun warmConversationSnapshotCachesLocalMessagesBeforeNavigation() = runTest {
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
                preview = "离线聊天记录先显示",
                clientMsgId = null,
                status = MessageStatus.Sent.name,
                createdAt = "2026-05-08T12:00:00Z",
                editedAt = null,
            ),
        )
        val repository = MessageRepository(
            api = FakeMessageApi(),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = FakeConversationDao(
                initialConversations = listOf(
                    ConversationWithLastMessage(
                        conversation = conversationEntity(id = 9),
                        lastMessage = null,
                    ),
                ),
            ),
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.warmConversationSnapshot(9)

        assertThat(repository.cachedMessages(9).map { it.preview }).containsExactly("离线聊天记录先显示")
    }

    @Test
    fun warmRecentConversationSnapshotsCachesLocalListBeforeNetwork() = runTest {
        val lastMessage = MessageEntity(
            localKey = "s:101",
            serverId = 101,
            conversationId = 9,
            serverSeq = 5,
            senderId = 2,
            kind = "text",
            payloadJson = "{}",
            preview = "本地会话列表先显示",
            clientMsgId = null,
            status = MessageStatus.Sent.name,
            createdAt = "2026-05-08T12:00:00Z",
            editedAt = null,
        )
        val messageDao = FakeMessageDao()
        messageDao.upsertMessage(lastMessage)
        val repository = MessageRepository(
            api = FakeMessageApi(),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = FakeConversationDao(
                initialConversations = listOf(
                    ConversationWithLastMessage(
                        conversation = conversationEntity(id = 9).copy(lastMessageLocalKey = lastMessage.localKey),
                        lastMessage = lastMessage,
                    ),
                ),
            ),
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.warmRecentConversationSnapshots()

        assertThat(repository.cachedConversations().map { it.id }).containsExactly(9L)
        assertThat(repository.cachedConversations().single().lastMessage?.preview).isEqualTo("本地会话列表先显示")
        assertThat(repository.cachedMessages(9).map { it.preview }).containsExactly("本地会话列表先显示")
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
            mediaAssetUploader = FakeMediaAssetUploader(),
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
    fun helpExpertCasesMapServerPublishedCases() = runTest {
        val repository = MessageRepository(
            api = FakeMessageApi(
                expertCases = listOf(
                    HelpExpertCaseDto(
                        id = "801",
                        authorId = "31",
                        title = "新能源 VIN 浅刻复核",
                        summary = "现场补光后复拍铭牌与拓印图",
                        category = "VIN",
                        publishedAt = "2026-05-08T12:00:00Z",
                    ),
                ),
            ),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = FakeConversationDao(),
            messageDao = FakeMessageDao(),
            json = Json { ignoreUnknownKeys = true },
        )

        val cases = repository.helpExpertCases(expertUserId = 31)

        assertThat(cases).hasSize(1)
        assertThat(cases.single().id).isEqualTo(801)
        assertThat(cases.single().authorId).isEqualTo(31)
        assertThat(cases.single().title).contains("VIN")
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
            mediaAssetUploader = FakeMediaAssetUploader(),
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
            mediaAssetUploader = FakeMediaAssetUploader(),
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

    @Test
    fun realtimeDeliveredMarksPendingRowAndUpdatesConversationSummary() = runTest {
        val conversationDao = FakeConversationDao(
            initialConversations = listOf(
                ConversationWithLastMessage(
                    conversation = conversationEntity(id = 9),
                    lastMessage = null,
                ),
            ),
        )
        val messageDao = FakeMessageDao()
        messageDao.upsertMessage(
            pendingMessageEntity(
                conversationId = 9,
                kind = "text",
                payload = buildJsonObject { put("text", "实时发送") },
                preview = "实时发送",
                clientMsgId = "rt-1",
                now = "2026-05-08T12:00:00Z",
            ),
        )
        val repository = MessageRepository(
            api = FakeMessageApi(),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = conversationDao,
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.applyRealtimeEvent(
            RealtimeEvent.MessageDelivered(
                clientMsgId = "rt-1",
                conversationId = 9,
                serverSeq = 6,
                messageId = 301,
                createdAt = "2026-05-08T12:00:01Z",
            ),
        )

        val saved = messageDao.items.single()
        assertThat(saved.localKey).isEqualTo("s:301")
        assertThat(saved.status).isEqualTo(MessageStatus.Sent.name)
        assertThat(saved.serverSeq).isEqualTo(6)
        assertThat(conversationDao.lastRecorded?.localKey).isEqualTo("s:301")
        assertThat(conversationDao.lastRecorded?.incrementUnread).isFalse()
    }

    @Test
    fun realtimeDeliveredReplacesServerSummaryRowWhenItArrivedFirst() = runTest {
        val conversationDao = FakeConversationDao(
            initialConversations = listOf(
                ConversationWithLastMessage(
                    conversation = conversationEntity(id = 9),
                    lastMessage = null,
                ),
            ),
        )
        val messageDao = FakeMessageDao()
        messageDao.upsertMessage(
            pendingMessageEntity(
                conversationId = 9,
                kind = "text",
                payload = buildJsonObject { put("text", "摘要先到") },
                preview = "摘要先到",
                clientMsgId = "rt-echo",
                now = "2026-05-08T12:00:00Z",
            ),
        )
        messageDao.upsertServerMessages(
            listOf(
                MessageEntity(
                    localKey = "s:301",
                    serverId = 301,
                    conversationId = 9,
                    serverSeq = 6,
                    senderId = 2,
                    kind = "text",
                    payloadJson = "{}",
                    preview = "摘要先到",
                    clientMsgId = null,
                    status = MessageStatus.Sent.name,
                    createdAt = "2026-05-08T12:00:01Z",
                    editedAt = null,
                ),
            ),
        )
        val repository = MessageRepository(
            api = FakeMessageApi(),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = conversationDao,
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.applyRealtimeEvent(
            RealtimeEvent.MessageDelivered(
                clientMsgId = "rt-echo",
                conversationId = 9,
                serverSeq = 6,
                messageId = 301,
                createdAt = "2026-05-08T12:00:01Z",
            ),
        )

        assertThat(messageDao.items).hasSize(1)
        val saved = messageDao.items.single()
        assertThat(saved.localKey).isEqualTo("s:301")
        assertThat(saved.clientMsgId).isEqualTo("rt-echo")
        assertThat(saved.status).isEqualTo(MessageStatus.Sent.name)
    }

    @Test
    fun realtimeReceivedPersistsServerMessageAndIncrementsUnread() = runTest {
        val conversationDao = FakeConversationDao(
            initialConversations = listOf(
                ConversationWithLastMessage(
                    conversation = conversationEntity(id = 9),
                    lastMessage = null,
                ),
            ),
        )
        val messageDao = FakeMessageDao()
        val repository = MessageRepository(
            api = FakeMessageApi(),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = conversationDao,
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.applyRealtimeEvent(
            RealtimeEvent.MessageReceived(
                messageId = 302,
                conversationId = 9,
                serverSeq = 7,
                senderId = 31,
                kind = "text",
                content = buildJsonObject { put("text", "真机收到的实时消息") },
                clientMsgId = "peer-1",
                createdAt = "2026-05-08T12:00:02Z",
            ),
        )

        val saved = messageDao.items.single()
        assertThat(saved.localKey).isEqualTo("s:302")
        assertThat(saved.serverId).isEqualTo(302)
        assertThat(saved.preview).isEqualTo("真机收到的实时消息")
        assertThat(saved.payloadJson).contains("真机收到的实时消息")
        assertThat(conversationDao.lastRecorded?.localKey).isEqualTo("s:302")
        assertThat(conversationDao.lastRecorded?.incrementUnread).isTrue()
    }

    @Test
    fun realtimeReceivedForLocalImageKeepsLocalDisplayPayload() = runTest {
        val localUri = "content://io.gomob.scan.debug.message.fileprovider/message_captures/capture.jpg"
        val conversationDao = FakeConversationDao(
            initialConversations = listOf(
                ConversationWithLastMessage(
                    conversation = conversationEntity(id = 9),
                    lastMessage = null,
                ),
            ),
        )
        val messageDao = FakeMessageDao()
        messageDao.upsertMessage(
            pendingMessageEntity(
                conversationId = 9,
                kind = "image",
                payload = buildJsonObject {
                    put("media_state", "ready")
                    put("asset_id", "901")
                    put("download_url", "http://example.test/901")
                    put("local_uri", localUri)
                    put("mime", "image/jpeg")
                },
                preview = "[图片]",
                clientMsgId = "img-1",
                now = "2026-05-08T12:00:00Z",
            ),
        )
        val repository = MessageRepository(
            api = FakeMessageApi(),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = conversationDao,
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.applyRealtimeEvent(
            RealtimeEvent.MessageReceived(
                messageId = 303,
                conversationId = 9,
                serverSeq = 8,
                senderId = 1,
                kind = "image",
                content = buildJsonObject {
                    put("media_state", "ready")
                    put("asset_id", "901")
                },
                clientMsgId = "img-1",
                createdAt = "2026-05-08T12:00:01Z",
            ),
        )

        val saved = messageDao.items.single()
        assertThat(saved.localKey).isEqualTo("s:303")
        assertThat(saved.status).isEqualTo(MessageStatus.Sent.name)
        assertThat(saved.payloadJson).contains("\"local_uri\":\"$localUri\"")
        assertThat(saved.payloadJson).contains("\"download_url\":\"http://example.test/901\"")
        assertThat(saved.payloadJson).contains("\"mime\":\"image/jpeg\"")
        assertThat(conversationDao.lastRecorded?.incrementUnread).isFalse()
    }

    @Test
    fun realtimeReceivedKnownServerMessageDoesNotIncrementUnreadAgain() = runTest {
        val conversationDao = FakeConversationDao(
            initialConversations = listOf(
                ConversationWithLastMessage(
                    conversation = conversationEntity(id = 9),
                    lastMessage = null,
                ),
            ),
        )
        val messageDao = FakeMessageDao()
        messageDao.upsertServerMessages(
            listOf(
                MessageEntity(
                    localKey = "s:302",
                    serverId = 302,
                    conversationId = 9,
                    serverSeq = 7,
                    senderId = 31,
                    kind = "text",
                    payloadJson = """{"text":"重复实时消息"}""",
                    preview = "重复实时消息",
                    clientMsgId = "peer-1",
                    status = MessageStatus.Sent.name,
                    createdAt = "2026-05-08T12:00:02Z",
                    editedAt = null,
                ),
            ),
        )
        val repository = MessageRepository(
            api = FakeMessageApi(),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = conversationDao,
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.applyRealtimeEvent(
            RealtimeEvent.MessageReceived(
                messageId = 302,
                conversationId = 9,
                serverSeq = 7,
                senderId = 31,
                kind = "text",
                content = buildJsonObject { put("text", "重复实时消息") },
                clientMsgId = "peer-1",
                createdAt = "2026-05-08T12:00:02Z",
            ),
        )

        assertThat(messageDao.items).hasSize(1)
        assertThat(conversationDao.lastRecorded?.incrementUnread).isFalse()
        assertThat(conversationDao.findById(9)?.unreadCount).isEqualTo(0)
    }

    @Test
    fun realtimeReceivedOlderThanReadSeqDoesNotReopenUnread() = runTest {
        val conversationDao = FakeConversationDao(
            initialConversations = listOf(
                ConversationWithLastMessage(
                    conversation = conversationEntity(id = 9).copy(lastReadSeq = 10),
                    lastMessage = null,
                ),
            ),
        )
        val messageDao = FakeMessageDao()
        val repository = MessageRepository(
            api = FakeMessageApi(),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = conversationDao,
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.applyRealtimeEvent(
            RealtimeEvent.MessageReceived(
                messageId = 302,
                conversationId = 9,
                serverSeq = 7,
                senderId = 31,
                kind = "text",
                content = buildJsonObject { put("text", "已读水位内消息") },
                clientMsgId = "peer-1",
                createdAt = "2026-05-08T12:00:02Z",
            ),
        )

        assertThat(conversationDao.lastRecorded?.incrementUnread).isFalse()
        assertThat(conversationDao.findById(9)?.unreadCount).isEqualTo(0)
    }

    @Test
    fun transcriptUpdatedRefreshesVoicePayloadPreviewAndConversationSummary() = runTest {
        val conversationDao = FakeConversationDao(
            initialConversations = listOf(
                ConversationWithLastMessage(
                    conversation = conversationEntity(id = 9),
                    lastMessage = null,
                ),
            ),
        )
        val messageDao = FakeMessageDao()
        messageDao.upsertMessage(
            MessageEntity(
                localKey = "s:401",
                serverId = 401,
                conversationId = 9,
                serverSeq = 8,
                senderId = 31,
                kind = "voice",
                payloadJson = """{"asset_id":"901","duration_sec":6}""",
                preview = "[语音 0:06]",
                clientMsgId = null,
                status = MessageStatus.Sent.name,
                createdAt = "2026-05-08T12:00:00Z",
                editedAt = null,
            ),
        )
        val repository = MessageRepository(
            api = FakeMessageApi(),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = conversationDao,
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.applyRealtimeEvent(
            RealtimeEvent.TranscriptUpdated(
                messageId = 401,
                conversationId = 9,
                serverSeq = 8,
                kind = "voice",
                content = buildJsonObject {
                    put("asset_id", "901")
                    put("duration_sec", 6)
                    put("transcript_status", "done")
                    put("transcript_normalized_text", "请复核第三工位")
                },
                updatedAt = "2026-05-08T12:00:03Z",
            ),
        )

        val saved = messageDao.items.single()
        assertThat(saved.payloadJson).contains("请复核第三工位")
        assertThat(saved.preview).isEqualTo("[语音转文字] 请复核第三工位")
        assertThat(saved.editedAt).isEqualTo("2026-05-08T12:00:03Z")
        assertThat(repository.cachedMessages(9).single().preview).isEqualTo("[语音转文字] 请复核第三工位")
        assertThat(conversationDao.lastRecorded?.localKey).isEqualTo("s:401")
        assertThat(conversationDao.lastRecorded?.incrementUnread).isFalse()
    }

    @Test
    fun transcriptUpdatedShowsUnrecognizedWhenTextIsBlank() = runTest {
        val messageDao = FakeMessageDao()
        messageDao.upsertMessage(
            MessageEntity(
                localKey = "s:401",
                serverId = 401,
                conversationId = 9,
                serverSeq = 8,
                senderId = 31,
                kind = "voice",
                payloadJson = """{"asset_id":"901","duration_sec":6}""",
                preview = "[语音 0:06]",
                clientMsgId = null,
                status = MessageStatus.Sent.name,
                createdAt = "2026-05-08T12:00:00Z",
                editedAt = null,
            ),
        )
        val repository = MessageRepository(
            api = FakeMessageApi(),
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = FakeConversationDao(
                initialConversations = listOf(
                    ConversationWithLastMessage(
                        conversation = conversationEntity(id = 9),
                        lastMessage = null,
                    ),
                ),
            ),
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.applyRealtimeEvent(
            RealtimeEvent.TranscriptUpdated(
                messageId = 401,
                conversationId = 9,
                serverSeq = 8,
                kind = "voice",
                content = buildJsonObject {
                    put("asset_id", "901")
                    put("duration_sec", 6)
                    put("transcript_status", "done")
                    put("transcript_normalized_text", "")
                },
                updatedAt = "2026-05-08T12:00:03Z",
            ),
        )

        assertThat(messageDao.items.single().preview).isEqualTo("[语音转文字] 未识别到文字")
    }

    @Test
    fun retryVoiceTranscriptUpdatesOriginalMessageWithoutSendingNewMessage() = runTest {
        val api = FakeMessageApi()
        val messageDao = FakeMessageDao()
        messageDao.upsertMessage(
            MessageEntity(
                localKey = "s:401",
                serverId = 401,
                conversationId = 9,
                serverSeq = 8,
                senderId = 31,
                kind = "voice",
                payloadJson = """{"asset_id":"901","duration_sec":6,"transcript_status":"failed"}""",
                preview = "[语音转写失败]",
                clientMsgId = null,
                status = MessageStatus.Sent.name,
                createdAt = "2026-05-08T12:00:00Z",
                editedAt = null,
            ),
        )
        val repository = MessageRepository(
            api = api,
            mediaAssetUploader = FakeMediaAssetUploader(),
            conversationDao = FakeConversationDao(),
            messageDao = messageDao,
            json = Json { ignoreUnknownKeys = true },
        )

        repository.retryVoiceTranscript(401)

        assertThat(api.transcriptRetryRequests).containsExactly("401")
        assertThat(api.sentRequests).isEmpty()
        assertThat(messageDao.items).hasSize(1)
        assertThat(messageDao.items.single().localKey).isEqualTo("s:401")
        assertThat(messageDao.items.single().payloadJson).contains("\"transcript_status\":\"pending\"")
    }
}

private class FakeMediaAssetUploader : MediaAssetUploader {
    val uploadedKinds = mutableListOf<MediaAssetKind>()

    override suspend fun upload(uri: Uri, kind: MediaAssetKind): UploadedMediaAsset {
        uploadedKinds += kind
        return UploadedMediaAsset(
            assetId = "901",
            objectKey = "orphan/${kind.serverKind}/901.bin",
            downloadUrl = "http://example.test/901",
            mime = kind.fallbackMime,
            sizeBytes = 1234,
            sha256 = "a".repeat(64),
        )
    }

    override suspend fun refreshDownloadUrl(assetId: String): String? =
        "http://example.test/$assetId?refreshed=1"
}

private fun fakeUploadedAsset(): UploadedMediaAsset =
    UploadedMediaAsset(
        assetId = "901",
        objectKey = "orphan/message/901.bin",
        downloadUrl = "http://example.test/901",
        mime = "application/octet-stream",
        sizeBytes = 1234,
        sha256 = "a".repeat(64),
    )

private fun conversationEntity(id: Long): ConversationEntity = ConversationEntity(
    id = id,
    kind = "p2p",
    title = null,
    peerId = 31,
    peerName = "陈若愚",
    peerEmployeeId = "EXP-VIN-0001",
    subjectKind = null,
    subjectId = null,
    lastMessageLocalKey = null,
    lastReadSeq = 0,
    unreadCount = 0,
    createdAt = "2026-05-08T11:00:00Z",
    updatedAt = "2026-05-08T12:00:00Z",
)

private class FakeMessageApi(
    private val conversations: List<ConversationDto> = emptyList(),
    private val messages: List<MessageDto> = emptyList(),
    private val experts: List<HelpExpertDto> = emptyList(),
    private val expertCases: List<HelpExpertCaseDto> = emptyList(),
    private val directConversation: ConversationDto? = null,
    private val helpRoom: ConversationDto? = null,
    private val messagesError: ApiException? = null,
    private val callInviteError: ApiException? = null,
    private val draftTranscriptText: String = "你好世界。",
) : MessageApi {
    val messageSinceSeqRequests = mutableListOf<Long>()
    val messageLatestRequests = mutableListOf<Boolean>()
    val sentRequests = mutableListOf<CreateMessageRequest>()
    val callInviteRequests = mutableListOf<CreateCallInviteRequest>()
    val transcriptRetryRequests = mutableListOf<String>()
    val draftTranscribeRequests = mutableListOf<TranscribeDraftVoiceRequest>()
    val leaveConversationRequests = mutableListOf<String>()

    override suspend fun conversations(cursor: String?, limit: Int): Envelope<ConversationListResponse> =
        Envelope(code = 0, data = ConversationListResponse(items = conversations))

    override suspend fun helpExperts(): Envelope<HelpExpertListResponse> =
        Envelope(code = 0, data = HelpExpertListResponse(items = experts))

    override suspend fun helpExpertCases(expertUserId: String): Envelope<HelpExpertCaseListResponse> =
        Envelope(code = 0, data = HelpExpertCaseListResponse(items = expertCases))

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

    override suspend fun openAdHocGroup(
        request: io.gomob.network.dto.OpenAdHocGroupRequest,
    ): Envelope<ConversationDto> = Envelope(
        code = 0,
        data = ConversationDto(
            id = "999",
            kind = "group",
            title = request.title ?: "多人连线",
            subjectKind = "ad_hoc_group",
            createdAt = "2026-05-24T06:00:00Z",
            updatedAt = "2026-05-24T06:00:00Z",
        ),
    )

    override suspend fun messages(
        conversationId: String,
        sinceSeq: Long,
        limit: Int,
        latest: Boolean,
    ): Envelope<MessageListResponse> {
        messagesError?.let { throw it }
        messageSinceSeqRequests += sinceSeq
        messageLatestRequests += latest
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

    override suspend fun recallMessage(
        conversationId: String,
        messageId: String,
    ): Envelope<MessageDto> = Envelope(
        code = 0,
        data = MessageDto(
            id = messageId,
            conversationId = conversationId,
            serverSeq = 0,
            senderId = null,
            kind = "text",
            createdAt = "2026-05-08T12:00:00Z",
            deletedAt = "2026-05-08T12:00:00Z",
        ),
    )

    override suspend fun contacts(query: String?, role: String?): Envelope<ContactListResponse> =
        Envelope(code = 0, data = ContactListResponse(items = emptyList()))

    override suspend fun createCallInvite(
        conversationId: String,
        request: CreateCallInviteRequest,
    ): Envelope<CallInviteResponse> {
        callInviteError?.let { throw it }
        callInviteRequests += request
        val payload = buildJsonObject {
            put("room_id", "701")
            put("provider_room", "gomob_call_test")
            put("status", "ringing")
            put("title", request.title ?: "视频通话")
            put("livekit_configured", true)
        }
        return Envelope(
            code = 0,
            data = CallInviteResponse(
                room = MediaRoomResponse(
                    id = "701",
                    provider = "livekit",
                    providerRoom = "gomob_call_test",
                    kind = "call",
                    status = "active",
                    liveKitConfigured = true,
                    createdAt = "2026-05-08T12:00:00Z",
                ),
                message = MessageDto(
                    id = "201",
                    conversationId = conversationId,
                    serverSeq = 9,
                    senderId = "1",
                    kind = "call_invite",
                    payload = payload,
                    clientMsgId = request.clientMsgId,
                    createdAt = "2026-05-08T12:00:00Z",
                ),
            ),
        )
    }

    override suspend fun retryMessageTranscript(messageId: String): Envelope<MessageDto> {
        transcriptRetryRequests += messageId
        return Envelope(
            code = 0,
            data = MessageDto(
                id = messageId,
                conversationId = "9",
                serverSeq = 8,
                senderId = "31",
                kind = "voice",
                payload = buildJsonObject {
                    put("asset_id", "901")
                    put("duration_sec", 6)
                    put("transcript_status", "pending")
                },
                createdAt = "2026-05-08T12:00:00Z",
            ),
        )
    }

    override suspend fun transcribeDraftVoice(
        request: TranscribeDraftVoiceRequest,
    ): Envelope<TranscribeDraftVoiceResponse> {
        draftTranscribeRequests += request
        return Envelope(
            code = 0,
            data = TranscribeDraftVoiceResponse(
                text = draftTranscriptText.trim(),
                normalizedText = draftTranscriptText,
                engine = "fireredasr2",
                model = "FireRedASR2-AED",
                language = "zh",
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

    override suspend fun leaveConversation(conversationId: String): Envelope<LeaveConversationResponse> {
        leaveConversationRequests += conversationId
        return Envelope(
            code = 0,
            data = LeaveConversationResponse(
                conversationId = conversationId,
                left = true,
            ),
        )
    }
}

private class FakeConversationDao(
    initialConversations: List<ConversationWithLastMessage> = emptyList(),
) : ConversationDao {
    private val stored = initialConversations.toMutableList()
    private val empty = MutableStateFlow(stored.toList())
    private val current = MutableStateFlow<ConversationWithLastMessage?>(null)
    var upsertedSingle: ConversationEntity? = null
    var lastRecorded: RecordedLastMessage? = null

    override fun observeConversations(): Flow<List<ConversationWithLastMessage>> = empty

    override fun observeConversation(conversationId: Long): Flow<ConversationWithLastMessage?> = current

    override fun observeLatestBySubjectKind(subjectKind: String): Flow<ConversationWithLastMessage?> = current

    override suspend fun recentConversations(limit: Int): List<ConversationWithLastMessage> = stored.take(limit)

    override suspend fun findById(conversationId: Long): ConversationEntity? =
        stored.firstOrNull { it.conversation.id == conversationId }?.conversation
            ?: upsertedSingle?.takeIf { it.id == conversationId }

    override suspend fun upsertConversations(items: List<ConversationEntity>) {
        items.forEach { item ->
            val index = stored.indexOfFirst { it.conversation.id == item.id }
            val next = ConversationWithLastMessage(conversation = item, lastMessage = null)
            if (index >= 0) {
                stored[index] = next
            } else {
                stored += next
            }
        }
        empty.value = stored.toList()
    }

    override suspend fun upsertConversation(item: ConversationEntity) {
        upsertedSingle = item
        upsertConversations(listOf(item))
    }

    override suspend fun setPinned(conversationId: Long, pinned: Boolean) = Unit

    override suspend fun deleteById(conversationId: Long) {
        stored.removeAll { it.conversation.id == conversationId }
        if (upsertedSingle?.id == conversationId) {
            upsertedSingle = null
        }
        empty.value = stored.toList()
        if (current.value?.conversation?.id == conversationId) {
            current.value = null
        }
    }

    override suspend fun markCleared(conversationId: Long, clearedBeforeSeq: Long) = Unit

    override suspend fun markRead(conversationId: Long, lastReadSeq: Long, unreadCount: Long) = Unit

    override suspend fun recordLastMessage(
        conversationId: Long,
        localKey: String,
        serverSeq: Long,
        updatedAt: String,
        incrementUnread: Boolean,
    ) {
        lastRecorded = RecordedLastMessage(conversationId, localKey, serverSeq, updatedAt, incrementUnread)
        val index = stored.indexOfFirst { it.conversation.id == conversationId }
        if (index >= 0) {
            val old = stored[index]
            stored[index] = old.copy(
                conversation = old.conversation.copy(
                    lastMessageLocalKey = localKey,
                    updatedAt = updatedAt,
                    unreadCount = if (incrementUnread) old.conversation.unreadCount + 1 else old.conversation.unreadCount,
                ),
            )
            empty.value = stored.toList()
        }
    }
}

private data class RecordedLastMessage(
    val conversationId: Long,
    val localKey: String,
    val serverSeq: Long,
    val updatedAt: String,
    val incrementUnread: Boolean,
)

private class FakeMessageDao : MessageDao {
    val items = mutableListOf<MessageEntity>()
    private val messagesFlow = MutableStateFlow<List<MessageEntity>>(emptyList())

    override fun observeMessages(conversationId: Long): Flow<List<MessageEntity>> = messagesFlow

    override fun observeRecentSearchMessages(limit: Int): Flow<List<MessageEntity>> = messagesFlow

    override suspend fun recentMessages(conversationId: Long, limit: Int): List<MessageEntity> =
        items
            .filter { it.conversationId == conversationId }
            .sortedWith(
                compareByDescending<MessageEntity> { it.serverSeq ?: Long.MAX_VALUE }
                    .thenByDescending { it.createdAt },
            )
            .take(limit)

    override suspend fun maxServerSeq(conversationId: Long): Long? =
        items.filter { it.conversationId == conversationId }.mapNotNull { it.serverSeq }.maxOrNull()

    override suspend fun findByClientMsgId(clientMsgId: String): MessageEntity? =
        items.firstOrNull { it.clientMsgId == clientMsgId }

    override suspend fun findByLocalKey(localKey: String): MessageEntity? =
        items.firstOrNull { it.localKey == localKey }

    override suspend fun findByServerId(serverId: Long): MessageEntity? =
        items.firstOrNull { it.serverId == serverId }

    override suspend fun upsertServerMessages(items: List<MessageEntity>) {
        items.forEach { item ->
            this.items.removeAll { existing ->
                existing.localKey == item.localKey ||
                    (item.serverId != null && existing.serverId == item.serverId) ||
                    (item.serverSeq != null &&
                        existing.conversationId == item.conversationId &&
                        existing.serverSeq == item.serverSeq) ||
                    (item.clientMsgId != null && existing.clientMsgId == item.clientMsgId)
            }
            this.items += item
        }
        messagesFlow.value = this.items.toList()
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
        val delivered = old.copy(
            localKey = "s:$serverId",
            serverId = serverId,
            serverSeq = serverSeq,
            status = MessageStatus.Sent.name,
            createdAt = createdAt,
        )
        items.removeAt(index)
        items.removeAll { existing ->
            existing.localKey == delivered.localKey ||
                existing.serverId == serverId ||
                (existing.conversationId == delivered.conversationId && existing.serverSeq == serverSeq)
        }
        items += delivered
        messagesFlow.value = items.toList()
    }

    override suspend fun markFailed(clientMsgId: String) {
        updateStatus(clientMsgId, MessageStatus.Failed)
    }

    override suspend fun markPending(clientMsgId: String) {
        updateStatus(clientMsgId, MessageStatus.Pending)
    }

    override suspend fun updateServerMessagePayload(
        serverId: Long,
        payloadJson: String,
        preview: String?,
        updatedAt: String,
    ) {
        val index = items.indexOfFirst { it.serverId == serverId }
        if (index < 0) return
        items[index] = items[index].copy(
            payloadJson = payloadJson,
            preview = preview,
            editedAt = updatedAt,
        )
        messagesFlow.value = items.toList()
    }

    override suspend fun deleteClearedMessages(conversationId: Long, clearedBeforeSeq: Long) {
        items.removeAll {
            val serverSeq = it.serverSeq
            it.conversationId == conversationId && (serverSeq == null || serverSeq <= clearedBeforeSeq)
        }
        messagesFlow.value = items.toList()
    }

    override suspend fun deleteServerMessagesBefore(conversationId: Long, minServerSeq: Long) {
        items.removeAll {
            val serverSeq = it.serverSeq
            it.conversationId == conversationId && serverSeq != null && serverSeq < minServerSeq
        }
        messagesFlow.value = items.toList()
    }

    override suspend fun deleteByConversationId(conversationId: Long) {
        items.removeAll { it.conversationId == conversationId }
        messagesFlow.value = items.toList()
    }

    override suspend fun deleteByLocalKey(localKey: String) {
        items.removeAll { it.localKey == localKey }
        messagesFlow.value = items.toList()
    }

    override suspend fun markRecalledByServerId(serverId: Long, recalledAt: String) {
        val index = items.indexOfFirst { it.serverId == serverId }
        if (index >= 0) {
            items[index] = items[index].copy(
                recalledAt = recalledAt,
                payloadJson = "{}",
                preview = "[消息已撤回]",
            )
            messagesFlow.value = items.toList()
        }
    }

    private fun updateStatus(clientMsgId: String, status: MessageStatus) {
        val index = items.indexOfFirst { it.clientMsgId == clientMsgId }
        if (index >= 0) {
            items[index] = items[index].copy(status = status.name)
            messagesFlow.value = items.toList()
        }
    }
}
