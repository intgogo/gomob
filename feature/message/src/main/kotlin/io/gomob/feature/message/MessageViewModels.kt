package io.gomob.feature.message

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.auth.TokenStore
import io.gomob.data.message.MessageRepository
import io.gomob.model.message.ConversationSummary
import io.gomob.model.message.HelpExpert
import io.gomob.model.message.HelpExpertCase
import io.gomob.model.message.MessageRecord
import io.gomob.model.message.MessageStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@HiltViewModel
class MessageListViewModel @Inject constructor(
    private val repository: MessageRepository,
    private val tokenStore: TokenStore,
    private val json: Json,
) : ViewModel() {
    private val refreshState = MutableStateFlow<RefreshState>(RefreshState.Loading)
    private val helpExperts = MutableStateFlow<List<HelpExpertRowUi>>(emptyList())
    private val helpRefreshState = MutableStateFlow<RefreshState>(RefreshState.Loading)
    private val helpRoomRefreshState = MutableStateFlow<RefreshState>(RefreshState.Loading)
    private val helpConversationId = MutableStateFlow<Long?>(null)
    private val currentUserId = tokenStore.currentUserIdFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = tokenStore.currentUserId(),
    )

    val uiState: StateFlow<MessageListUiState> =
        combine(repository.observeConversations(), refreshState) { conversations, refresh ->
            val rows = visibleMessageConversations(conversations).map { it.toRowUi(json) }
            when {
                rows.isNotEmpty() -> MessageListUiState.Content(
                    conversations = rows,
                    offlineCached = refresh is RefreshState.Error,
                    errorMessage = (refresh as? RefreshState.Error)?.message,
                )
                refresh is RefreshState.Loading -> MessageListUiState.Loading
                refresh is RefreshState.Error -> MessageListUiState.Error(refresh.message)
                else -> MessageListUiState.Empty
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MessageListUiState.Loading,
        )

    val helpUiState: StateFlow<HelpExpertsUiState> =
        combine(helpExperts, helpRefreshState) { experts, refresh ->
            when {
                experts.isNotEmpty() -> HelpExpertsUiState.Content(
                    experts = experts,
                    offlineCached = refresh is RefreshState.Error,
                    errorMessage = (refresh as? RefreshState.Error)?.message,
                )
                refresh is RefreshState.Loading -> HelpExpertsUiState.Loading
                refresh is RefreshState.Error -> HelpExpertsUiState.Error(refresh.message)
                else -> HelpExpertsUiState.Empty
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HelpExpertsUiState.Loading,
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    val helpRoomUiState: StateFlow<HelpRoomUiState> =
        combine(
            helpConversationId,
            helpConversationId.flatMapLatest { id ->
                id?.let { repository.observeConversation(it) } ?: flowOf(null)
            },
            helpConversationId.flatMapLatest { id ->
                id?.let { repository.observeMessages(it) } ?: flowOf(emptyList<MessageRecord>())
            },
            helpExperts,
            helpRoomRefreshState,
        ) { conversationId, conversation, messages, experts, refresh ->
            when {
                conversationId == null && refresh is RefreshState.Loading -> HelpRoomUiState.Loading
                conversationId == null && refresh is RefreshState.Error -> HelpRoomUiState.Error(refresh.message)
                conversationId == null -> HelpRoomUiState.Loading
                else -> HelpRoomUiState.Content(
                    conversationId = conversationId,
                    title = conversation?.displayTitle() ?: "在线求助",
                    experts = experts,
                    messages = messages.map { it.toBubbleUi(json, experts, currentUserId.value) },
                    loading = messages.isEmpty() && refresh is RefreshState.Loading,
                    offlineCached = messages.isNotEmpty() && refresh is RefreshState.Error,
                    errorMessage = (refresh as? RefreshState.Error)?.message,
                )
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HelpRoomUiState.Loading,
        )

    init {
        refresh()
        refreshHelpExperts()
        refreshHelpRoom()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshState.value = RefreshState.Loading
            refreshState.value = runCatching {
                repository.refreshConversations()
                RefreshState.Ready
            }.getOrElse { RefreshState.Error(it.readableMessage()) }
        }
    }

    fun refreshHelpExperts() {
        viewModelScope.launch {
            helpRefreshState.value = RefreshState.Loading
            helpRefreshState.value = runCatching {
                helpExperts.value = repository.helpExperts().map { it.toRowUi() }
                RefreshState.Ready
            }.getOrElse { RefreshState.Error(it.readableMessage()) }
        }
    }

    fun refreshHelpRoom() {
        viewModelScope.launch {
            helpRoomRefreshState.value = RefreshState.Loading
            helpRoomRefreshState.value = runCatching {
                val room = repository.openHelpRoom()
                helpConversationId.value = room.id
                repository.refreshMessages(room.id, fullSync = true)
                RefreshState.Ready
            }.getOrElse { RefreshState.Error(it.readableMessage()) }
        }
    }

    fun sendHelpRoomMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val conversationId = ensureHelpConversationId()
                repository.sendText(conversationId, trimmed)
                helpRoomRefreshState.value = RefreshState.Ready
            }.onFailure { error ->
                helpRoomRefreshState.value = RefreshState.Error(error.readableMessage())
            }
        }
    }

    fun sendHelpRoomVoice() {
        helpRoomRefreshState.value = RefreshState.Error("语音文件缺失，请重新录制")
    }

    fun sendHelpRoomImage(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                repository.sendImage(ensureHelpConversationId(), uri)
                helpRoomRefreshState.value = RefreshState.Ready
            }.onFailure { error ->
                helpRoomRefreshState.value = RefreshState.Error(error.readableMessage())
            }
        }
    }

    fun sendHelpRoomVoice(uri: Uri, durationSec: Int) {
        viewModelScope.launch {
            runCatching {
                repository.sendVoice(ensureHelpConversationId(), uri, durationSec)
                helpRoomRefreshState.value = RefreshState.Ready
            }.onFailure { error ->
                helpRoomRefreshState.value = RefreshState.Error(error.readableMessage())
            }
        }
    }

    fun sendHelpRoomVideoClip(uri: Uri) {
        viewModelScope.launch {
            runCatching {
                repository.sendVideoClip(ensureHelpConversationId(), uri)
                helpRoomRefreshState.value = RefreshState.Ready
            }.onFailure { error ->
                helpRoomRefreshState.value = RefreshState.Error(error.readableMessage())
            }
        }
    }

    fun retryHelpRoomMessage(clientMsgId: String?) {
        if (clientMsgId.isNullOrBlank()) return
        viewModelScope.launch {
            runCatching {
                repository.retryMessage(clientMsgId)
                helpRoomRefreshState.value = RefreshState.Ready
            }
                .onFailure { helpRoomRefreshState.value = RefreshState.Error(it.readableMessage()) }
        }
    }

    fun showHelpRoomError(message: String) {
        helpRoomRefreshState.value = RefreshState.Error(message)
    }

    private suspend fun ensureHelpConversationId(): Long =
        helpConversationId.value ?: repository.openHelpRoom().also {
            helpConversationId.value = it.id
        }.id

}

@HiltViewModel
class ExpertDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MessageRepository,
) : ViewModel() {
    private val expertUserId = savedStateHandle.get<String>("id")?.toLongOrNull() ?: 0L
    private val _state = MutableStateFlow<ExpertDetailUiState>(ExpertDetailUiState.Loading)
    val state: StateFlow<ExpertDetailUiState> = _state.asStateFlow()
    private val _openConversationEvents = MutableSharedFlow<Long>()
    val openConversationEvents = _openConversationEvents.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = ExpertDetailUiState.Loading
            _state.value = runCatching {
                val expert = repository.helpExperts()
                    .map { it.toRowUi() }
                    .firstOrNull { it.userId == expertUserId }
                    ?: throw IllegalArgumentException("专家不存在")
                val cases = repository.helpExpertCases(expertUserId).map { it.toRowUi() }
                ExpertDetailUiState.Content(expert = expert, cases = cases)
            }.getOrElse { ExpertDetailUiState.Error(it.readableMessage()) }
        }
    }

    fun openDirectConversation() {
        val content = _state.value as? ExpertDetailUiState.Content ?: return
        if (content.openingMessage) return
        viewModelScope.launch {
            _state.value = content.copy(openingMessage = true, messageError = null)
            runCatching {
                repository.openDirectConversation(expertUserId)
            }.onSuccess { conversation ->
                _state.value = (_state.value as? ExpertDetailUiState.Content)
                    ?.copy(openingMessage = false)
                    ?: _state.value
                _openConversationEvents.emit(conversation.id)
            }.onFailure { error ->
                _state.value = (_state.value as? ExpertDetailUiState.Content)
                    ?.copy(openingMessage = false, messageError = error.readableMessage())
                    ?: ExpertDetailUiState.Error(error.readableMessage())
            }
        }
    }
}

@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MessageRepository,
    private val tokenStore: TokenStore,
    private val json: Json,
) : ViewModel() {
    private val conversationId = savedStateHandle.get<String>("id")?.toLongOrNull() ?: 0L
    private val refreshState = MutableStateFlow<RefreshState>(RefreshState.Loading)
    private var markedReadSeq = 0L

    val uiState: StateFlow<ConversationUiState> =
        combine(
            repository.observeConversation(conversationId),
            repository.observeMessages(conversationId),
            refreshState,
            tokenStore.currentUserIdFlow,
        ) { conversation, messages, refresh, currentUserId ->
            ConversationUiState(
                conversationId = conversationId,
                title = conversation?.displayTitle() ?: "会话 #$conversationId",
                eyebrow = if (conversationId > 0) "会话 · #$conversationId" else "会话",
                messages = messages.map { it.toBubbleUi(json, currentUserId) },
                loading = messages.isEmpty() && refresh is RefreshState.Loading,
                offlineCached = messages.isNotEmpty() && refresh is RefreshState.Error,
                errorMessage = (refresh as? RefreshState.Error)?.message,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ConversationUiState(conversationId = conversationId),
        )

    init {
        refresh()
        viewModelScope.launch {
            repository.observeMessages(conversationId).collect { messages ->
                val lastSeq = messages.mapNotNull { it.serverSeq }.maxOrNull() ?: return@collect
                if (lastSeq > markedReadSeq) {
                    markedReadSeq = lastSeq
                    runCatching { repository.markRead(conversationId, lastSeq) }
                }
            }
        }
    }

    fun refresh() {
        if (conversationId <= 0) {
            refreshState.value = RefreshState.Error("会话参数无效")
            return
        }
        viewModelScope.launch {
            refreshState.value = RefreshState.Loading
            refreshState.value = runCatching {
                repository.refreshMessages(conversationId, fullSync = true)
                RefreshState.Ready
            }.getOrElse { RefreshState.Error(it.readableMessage()) }
        }
    }

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || conversationId <= 0) return
        viewModelScope.launch {
            runCatching {
                repository.sendText(conversationId, trimmed)
                refreshState.value = RefreshState.Ready
            }
                .onFailure { refreshState.value = RefreshState.Error(it.readableMessage()) }
        }
    }

    fun sendVoice() {
        refreshState.value = RefreshState.Error("语音文件缺失，请重新录制")
    }

    fun sendImage(uri: Uri) {
        if (conversationId <= 0) return
        viewModelScope.launch {
            runCatching {
                repository.sendImage(conversationId, uri)
                refreshState.value = RefreshState.Ready
            }
                .onFailure { refreshState.value = RefreshState.Error(it.readableMessage()) }
        }
    }

    fun sendVoice(uri: Uri, durationSec: Int) {
        if (conversationId <= 0) return
        viewModelScope.launch {
            runCatching {
                repository.sendVoice(conversationId, uri, durationSec)
                refreshState.value = RefreshState.Ready
            }
                .onFailure { refreshState.value = RefreshState.Error(it.readableMessage()) }
        }
    }

    fun sendVideoClip(uri: Uri) {
        if (conversationId <= 0) return
        viewModelScope.launch {
            runCatching {
                repository.sendVideoClip(conversationId, uri)
                refreshState.value = RefreshState.Ready
            }
                .onFailure { refreshState.value = RefreshState.Error(it.readableMessage()) }
        }
    }

    fun retry(clientMsgId: String?) {
        if (clientMsgId.isNullOrBlank()) return
        viewModelScope.launch {
            runCatching {
                repository.retryMessage(clientMsgId)
                refreshState.value = RefreshState.Ready
            }
                .onFailure { refreshState.value = RefreshState.Error(it.readableMessage()) }
        }
    }

    fun showError(message: String) {
        refreshState.value = RefreshState.Error(message)
    }
}

sealed interface MessageListUiState {
    data object Loading : MessageListUiState
    data object Empty : MessageListUiState
    data class Error(val message: String) : MessageListUiState
    data class Content(
        val conversations: List<ConversationRowUi>,
        val offlineCached: Boolean,
        val errorMessage: String?,
    ) : MessageListUiState
}

data class ConversationRowUi(
    val id: Long,
    val title: String,
    val initials: String,
    val preview: String,
    val time: String,
    val unreadCount: Long,
    val avatarKind: AvatarKind,
    val unreadTone: WatchTone,
)

sealed interface HelpExpertsUiState {
    data object Loading : HelpExpertsUiState
    data object Empty : HelpExpertsUiState
    data class Error(val message: String) : HelpExpertsUiState
    data class Content(
        val experts: List<HelpExpertRowUi>,
        val offlineCached: Boolean,
        val errorMessage: String?,
    ) : HelpExpertsUiState
}

sealed interface HelpRoomUiState {
    data object Loading : HelpRoomUiState
    data class Error(val message: String) : HelpRoomUiState
    data class Content(
        val conversationId: Long,
        val title: String,
        val experts: List<HelpExpertRowUi>,
        val messages: List<MessageBubbleUi>,
        val loading: Boolean,
        val offlineCached: Boolean,
        val errorMessage: String?,
    ) : HelpRoomUiState {
        val empty: Boolean get() = !loading && messages.isEmpty() && errorMessage == null
    }
}

sealed interface ExpertDetailUiState {
    data object Loading : ExpertDetailUiState
    data class Error(val message: String) : ExpertDetailUiState
    data class Content(
        val expert: HelpExpertRowUi,
        val cases: List<ExpertCaseRowUi>,
        val openingMessage: Boolean = false,
        val messageError: String? = null,
    ) : ExpertDetailUiState
}

data class HelpExpertRowUi(
    val userId: Long,
    val name: String,
    val initials: String,
    val roleTitle: String,
    val specialty: String,
    val employeeId: String,
    val availabilityText: String,
)

data class ExpertCaseRowUi(
    val id: Long,
    val title: String,
    val summary: String,
    val category: String,
    val publishedAt: String,
)

data class ConversationUiState(
    val conversationId: Long = 0,
    val title: String = "会话",
    val eyebrow: String = "会话",
    val messages: List<MessageBubbleUi> = emptyList(),
    val loading: Boolean = true,
    val offlineCached: Boolean = false,
    val errorMessage: String? = null,
) {
    val empty: Boolean get() = !loading && messages.isEmpty() && errorMessage == null
}

data class MessageBubbleUi(
    val localKey: String,
    val text: String,
    val mine: Boolean,
    val senderLabel: String?,
    val avatarInitials: String,
    val time: String,
    val status: MessageStatus,
    val clientMsgId: String?,
)

private sealed interface RefreshState {
    data object Loading : RefreshState
    data object Ready : RefreshState
    data class Error(val message: String) : RefreshState
}

private val messageTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

private fun ConversationSummary.toRowUi(json: Json): ConversationRowUi {
    val title = displayTitle()
    val last = lastMessage
    return ConversationRowUi(
        id = id,
        title = title,
        initials = initialsFor(title),
        preview = last?.previewText(json) ?: "暂无消息",
        time = last?.createdAt?.formatMessageTime().orEmpty(),
        unreadCount = unreadCount,
        avatarKind = last?.avatarKind() ?: AvatarKind.Neutral,
        unreadTone = if (unreadCount > 0) WatchTone.Accent else WatchTone.Neutral,
    )
}

internal fun visibleMessageConversations(conversations: List<ConversationSummary>): List<ConversationSummary> =
    conversations.filterNot { it.subjectKind == "online_help" || (it.kind == "group" && it.title == "在线求助") }

private fun HelpExpert.toRowUi(): HelpExpertRowUi = HelpExpertRowUi(
    userId = userId,
    name = name,
    initials = initialsFor(name),
    roleTitle = roleTitle,
    specialty = specialty,
    employeeId = employeeId,
    availabilityText = when (availability) {
        "message_ready" -> "可发消息"
        else -> "可联系"
    },
)

private fun HelpExpertCase.toRowUi(): ExpertCaseRowUi = ExpertCaseRowUi(
    id = id,
    title = title,
    summary = summary,
    category = category,
    publishedAt = publishedAt.formatCaseTime(),
)

private fun MessageRecord.toBubbleUi(json: Json, currentUserId: Long?): MessageBubbleUi {
    val mine = mineBySender(currentUserId)
    return MessageBubbleUi(
        localKey = localKey,
        text = previewText(json),
        mine = mine,
        senderLabel = null,
        avatarInitials = if (mine) "我" else "对",
        time = createdAt.formatMessageTime(),
        status = status,
        clientMsgId = clientMsgId,
    )
}

private fun MessageRecord.toBubbleUi(
    json: Json,
    experts: List<HelpExpertRowUi>,
    currentUserId: Long?,
): MessageBubbleUi {
    val mine = mineBySender(currentUserId)
    val expert = experts.firstOrNull { it.userId == senderId }
    return MessageBubbleUi(
        localKey = localKey,
        text = previewText(json),
        mine = mine,
        senderLabel = if (mine) null else expert?.name ?: "成员 #$senderId",
        avatarInitials = if (mine) "我" else expert?.initials ?: "成",
        time = createdAt.formatMessageTime(),
        status = status,
        clientMsgId = clientMsgId,
    )
}

private fun MessageRecord.mineBySender(currentUserId: Long?): Boolean =
    (senderId != null && senderId == currentUserId) ||
        (senderId == null && clientMsgId != null)

private fun ConversationSummary.displayTitle(): String =
    title?.takeIf { it.isNotBlank() }
        ?: peer?.name?.takeIf { it.isNotBlank() }
        ?: "会话 #$id"

private fun MessageRecord.previewText(json: Json): String = when (kind) {
    "text" -> preview?.takeIf { it.isNotBlank() } ?: textPayload(json).ifBlank { "[文本]" }
    "image" -> preview?.takeIf { it.isNotBlank() } ?: "[图片]"
    "voice" -> preview?.takeIf { it.isNotBlank() } ?: mediaPreview(json, awaiting = "[语音待上传]", ready = "[语音消息]")
    "video_clip" -> preview?.takeIf { it.isNotBlank() } ?: mediaPreview(json, awaiting = "[视频待上传]", ready = "[视频]")
    "video_call" -> preview?.takeIf { it.isNotBlank() } ?: "[视频通话]"
    "system" -> preview?.takeIf { it.isNotBlank() } ?: "[系统消息]"
    else -> "[$kind]"
}

private fun MessageRecord.textPayload(json: Json): String {
    val element = runCatching { json.parseToJsonElement(payloadJson) }.getOrNull() ?: return ""
    return when (element) {
        is JsonObject -> element["text"]?.jsonPrimitive?.contentOrNull.orEmpty()
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        else -> ""
    }
}

private fun MessageRecord.mediaPreview(json: Json, awaiting: String, ready: String): String {
    val obj = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return ready
    val state = obj["media_state"]?.jsonPrimitive?.contentOrNull.orEmpty()
    return if (state == "awaiting_asset_upload") awaiting else ready
}

private fun MessageRecord.avatarKind(): AvatarKind = when (kind) {
    "image" -> AvatarKind.Image
    "voice" -> AvatarKind.Voice
    "video_clip" -> AvatarKind.Video
    "video_call" -> AvatarKind.Call
    "system" -> AvatarKind.System
    else -> AvatarKind.Neutral
}

private fun initialsFor(title: String): String =
    title.trim().firstOrNull()?.toString()?.uppercase().orEmpty().ifBlank { "#" }

private fun String.formatMessageTime(): String =
    runCatching {
        Instant.parse(this).atZone(ZoneId.systemDefault()).format(messageTimeFormatter)
    }.getOrDefault("")

private fun String.formatCaseTime(): String =
    runCatching {
        Instant.parse(this).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrDefault("")

private fun Throwable.readableMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "消息服务暂不可用"
