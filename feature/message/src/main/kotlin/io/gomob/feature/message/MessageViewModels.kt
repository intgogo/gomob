package io.gomob.feature.message

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.message.MessageRepository
import io.gomob.model.message.ConversationSummary
import io.gomob.model.message.MessageRecord
import io.gomob.model.message.MessageStatus
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    init {
        refresh()
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
