package io.gomob.feature.message

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.message.MessageRepository
import io.gomob.model.message.ConversationSummary
import io.gomob.model.message.HelpExpert
import io.gomob.model.message.MessageRecord
import io.gomob.model.message.MessageStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val json: Json,
) : ViewModel() {
    private val refreshState = MutableStateFlow<RefreshState>(RefreshState.Loading)
    private val helpExperts = MutableStateFlow<List<HelpExpertRowUi>>(emptyList())
    private val helpRefreshState = MutableStateFlow<RefreshState>(RefreshState.Loading)
    private val openingExpertId = MutableStateFlow<Long?>(null)
    private val _helpSendState = MutableStateFlow<HelpSendUiState>(HelpSendUiState.Idle)
    val helpSendState: StateFlow<HelpSendUiState> = _helpSendState.asStateFlow()

    val uiState: StateFlow<MessageListUiState> =
        combine(repository.observeConversations(), refreshState) { conversations, refresh ->
            val rows = conversations.map { it.toRowUi(json) }
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
        combine(helpExperts, helpRefreshState, openingExpertId) { experts, refresh, openingId ->
            when {
                experts.isNotEmpty() -> HelpExpertsUiState.Content(
                    experts = experts.map { it.copy(opening = it.userId == openingId) },
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

    init {
        refresh()
        refreshHelpExperts()
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

    fun openExpertConversation(
        expert: HelpExpertRowUi,
        onOpened: (Long) -> Unit,
    ) {
        if (openingExpertId.value != null) return
        viewModelScope.launch {
            openingExpertId.value = expert.userId
            runCatching { repository.openDirectConversation(expert.userId) }
                .onSuccess {
                    refresh()
                    onOpened(it.id)
                }
                .onFailure { helpRefreshState.value = RefreshState.Error(it.readableMessage()) }
            openingExpertId.value = null
        }
    }

    fun sendHelpMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _helpSendState.value is HelpSendUiState.Sending) return
        viewModelScope.launch {
            _helpSendState.value = HelpSendUiState.Sending
            runCatching {
                val targets = helpExperts.value.ifEmpty {
                    repository.helpExperts().map { it.toRowUi() }.also { helpExperts.value = it }
                }
                require(targets.isNotEmpty()) { "服务端未配置固定专家" }
                targets.forEach { expert ->
                    val conversation = repository.openDirectConversation(expert.userId)
                    repository.sendText(conversation.id, trimmed)
                }
                refresh()
                targets.size
            }.onSuccess { count ->
                _helpSendState.value = HelpSendUiState.Sent("已发送给 $count 位专家")
            }.onFailure { error ->
                _helpSendState.value = HelpSendUiState.Error(error.readableMessage())
            }
        }
    }
}

@HiltViewModel
class ExpertDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MessageRepository,
) : ViewModel() {
    private val expertUserId = savedStateHandle.get<String>("id")?.toLongOrNull() ?: 0L
    private val _state = MutableStateFlow<ExpertDetailUiState>(ExpertDetailUiState.Loading)
    val state: StateFlow<ExpertDetailUiState> = _state.asStateFlow()

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
                ExpertDetailUiState.Content(expert)
            }.getOrElse { ExpertDetailUiState.Error(it.readableMessage()) }
        }
    }
}

@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MessageRepository,
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
        ) { conversation, messages, refresh ->
            ConversationUiState(
                conversationId = conversationId,
                title = conversation?.displayTitle() ?: "会话 #$conversationId",
                eyebrow = if (conversationId > 0) "会话 · #$conversationId" else "会话",
                messages = messages.map { it.toBubbleUi(json) },
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
            runCatching { repository.sendText(conversationId, trimmed) }
                .onFailure { refreshState.value = RefreshState.Error(it.readableMessage()) }
        }
    }

    fun retry(clientMsgId: String?) {
        if (clientMsgId.isNullOrBlank()) return
        viewModelScope.launch {
            runCatching { repository.retryText(clientMsgId) }
                .onFailure { refreshState.value = RefreshState.Error(it.readableMessage()) }
        }
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

sealed interface HelpSendUiState {
    data object Idle : HelpSendUiState
    data object Sending : HelpSendUiState
    data class Sent(val message: String) : HelpSendUiState
    data class Error(val message: String) : HelpSendUiState
}

sealed interface ExpertDetailUiState {
    data object Loading : ExpertDetailUiState
    data class Error(val message: String) : ExpertDetailUiState
    data class Content(val expert: HelpExpertRowUi) : ExpertDetailUiState
}

data class HelpExpertRowUi(
    val userId: Long,
    val name: String,
    val initials: String,
    val roleTitle: String,
    val specialty: String,
    val employeeId: String,
    val availabilityText: String,
    val opening: Boolean = false,
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

private fun MessageRecord.toBubbleUi(json: Json): MessageBubbleUi =
    MessageBubbleUi(
        localKey = localKey,
        text = previewText(json),
        mine = clientMsgId != null || senderId == null,
        time = createdAt.formatMessageTime(),
        status = status,
        clientMsgId = clientMsgId,
    )

private fun ConversationSummary.displayTitle(): String =
    title?.takeIf { it.isNotBlank() }
        ?: peer?.name?.takeIf { it.isNotBlank() }
        ?: "会话 #$id"

private fun MessageRecord.previewText(json: Json): String = when (kind) {
    "text" -> preview?.takeIf { it.isNotBlank() } ?: textPayload(json).ifBlank { "[文本]" }
    "image" -> preview?.takeIf { it.isNotBlank() } ?: "[图片]"
    "video_clip" -> preview?.takeIf { it.isNotBlank() } ?: "[视频]"
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

private fun MessageRecord.avatarKind(): AvatarKind = when (kind) {
    "image" -> AvatarKind.Image
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

private fun Throwable.readableMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "消息服务暂不可用"
