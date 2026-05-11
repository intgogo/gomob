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
import io.gomob.model.message.InspectionShareCard
import io.gomob.model.message.MessageRecord
import io.gomob.model.message.MessageQuote
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private const val INITIAL_MESSAGE_PAGE_LIMIT = 30

@HiltViewModel
class MessageListViewModel @Inject constructor(
    private val repository: MessageRepository,
    private val tokenStore: TokenStore,
    private val json: Json,
) : ViewModel() {
    private val refreshState = MutableStateFlow<RefreshState>(RefreshState.Loading)
    private val helpExperts = MutableStateFlow<List<HelpExpertRowUi>>(emptyList())
    private val helpRefreshState = MutableStateFlow<RefreshState>(RefreshState.Loading)
    private val helpRoomRefreshState = MutableStateFlow<RefreshState>(RefreshState.Ready)
    private val helpConversationId = MutableStateFlow<Long?>(null)
    private val _contactActionError = MutableStateFlow<String?>(null)
    private val _openConversationEvents = MutableSharedFlow<Long>()
    private val _forwardResultEvents = MutableSharedFlow<String>()
    private val currentUserId = tokenStore.currentUserIdFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = tokenStore.currentUserId(),
    )
    val contactActionError: StateFlow<String?> = _contactActionError.asStateFlow()
    val openConversationEvents = _openConversationEvents.asSharedFlow()
    val forwardResultEvents = _forwardResultEvents.asSharedFlow()

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

    val forwardTargets: StateFlow<List<MessageForwardTargetUi>> =
        combine(repository.observeConversations(), helpExperts, helpConversationId) { conversations, experts, helpId ->
            val conversationTargets = visibleMessageConversations(conversations)
                .filterNot { it.id == helpId }
                .map { it.toForwardTargetUi() }
            val conversationPeerIds = conversations.mapNotNull { it.peer?.id }.toSet()
            val expertTargets = experts
                .filterNot { it.userId in conversationPeerIds }
                .map { it.toForwardTargetUi() }
            (conversationTargets + expertTargets).dedupeForwardTargets()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
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
            HelpRoomUiState.Content(
                conversationId = conversationId ?: 0L,
                title = conversation?.displayTitle() ?: "专家连线",
                experts = experts,
                messages = messages.map { it.toBubbleUi(json, experts, currentUserId.value) },
                unreadCount = conversation?.unreadCount ?: 0L,
                loading = false,
                offlineCached = messages.isNotEmpty() && refresh is RefreshState.Error,
                errorMessage = (refresh as? RefreshState.Error)?.message,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HelpRoomUiState.Content(
                conversationId = 0L,
                title = "专家连线",
                experts = emptyList(),
                messages = emptyList(),
                unreadCount = 0L,
                loading = false,
                offlineCached = false,
                errorMessage = null,
            ),
        )

    init {
        viewModelScope.launch {
            repository.observeHelpRoomConversation().collect { room ->
                if (room != null && helpConversationId.value == null) {
                    helpConversationId.value = room.id
                }
            }
        }
        refresh()
        refreshHelpExperts()
        refreshHelpRoom()
    }

    fun refresh() {
        viewModelScope.launch {
            refreshState.value = RefreshState.Loading
            runCatching {
                repository.refreshConversations()
                RefreshState.Ready
            }.onSuccess { state ->
                refreshState.value = state
                viewModelScope.launch {
                    runCatching { repository.prewarmRecentConversationHistories() }
                }
            }.onFailure { error ->
                refreshState.value = RefreshState.Error(error.readableMessage())
                viewModelScope.launch {
                    runCatching { repository.prewarmRecentConversationHistories() }
                }
            }
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
            helpRoomRefreshState.value = runCatching {
                val room = repository.openHelpRoom()
                helpConversationId.value = room.id
                repository.refreshMessages(
                    conversationId = room.id,
                    limit = INITIAL_MESSAGE_PAGE_LIMIT,
                    fullSync = repository.shouldHydrateConversationHistory(room.id),
                )
                RefreshState.Ready
            }.getOrElse { RefreshState.Error(it.readableMessage()) }
        }
    }

    fun sendHelpRoomMessage(text: String, quote: MessageQuote? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val conversationId = ensureHelpConversationId()
                repository.sendText(conversationId, trimmed, quote)
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

    fun transcribeHelpRoomVoice(uri: Uri, durationSec: Int) {
        if (durationSec <= 0) {
            helpRoomRefreshState.value = RefreshState.Error("录音太短，未发送消息")
            return
        }
        viewModelScope.launch {
            runCatching {
                val text = repository.transcribeVoiceDraft(uri)
                repository.sendText(ensureHelpConversationId(), text)
                helpRoomRefreshState.value = RefreshState.Ready
            }.onFailure { error ->
                helpRoomRefreshState.value = RefreshState.Error("语音转文字失败，未发送消息：${error.readableMessage()}")
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

    fun sendHelpRoomInspectionCard(card: InspectionShareCard) {
        viewModelScope.launch {
            runCatching {
                repository.sendInspectionCard(ensureHelpConversationId(), card)
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

    fun retryHelpRoomVoiceTranscript(messageId: Long?) {
        if (messageId == null || messageId <= 0) return
        viewModelScope.launch {
            runCatching {
                repository.retryVoiceTranscript(messageId)
                helpRoomRefreshState.value = RefreshState.Ready
            }.onFailure { helpRoomRefreshState.value = RefreshState.Error(it.readableMessage()) }
        }
    }

    fun showHelpRoomError(message: String) {
        helpRoomRefreshState.value = RefreshState.Error(message)
    }

    fun openConversation(conversationId: Long) {
        if (conversationId <= 0) return
        viewModelScope.launch {
            runCatching {
                repository.prewarmConversationHistory(
                    conversationId = conversationId,
                    messageLimit = INITIAL_MESSAGE_PAGE_LIMIT,
                )
            }.onFailure {
                runCatching { repository.warmConversationSnapshot(conversationId, INITIAL_MESSAGE_PAGE_LIMIT) }
            }
            _openConversationEvents.emit(conversationId)
        }
    }

    fun openDirectConversation(peerUserId: Long?) {
        if (peerUserId == null || peerUserId <= 0) {
            _contactActionError.value = "该联系人暂未同步到服务端"
            return
        }
        viewModelScope.launch {
            _contactActionError.value = null
            val conversation = runCatching { repository.openDirectConversation(peerUserId) }
                .getOrElse { error ->
                    _contactActionError.value = error.readableMessage()
                    return@launch
                }
            runCatching {
                repository.prewarmConversationHistory(
                    conversationId = conversation.id,
                    messageLimit = INITIAL_MESSAGE_PAGE_LIMIT,
                )
            }.onFailure {
                runCatching { repository.warmConversationSnapshot(conversation.id, INITIAL_MESSAGE_PAGE_LIMIT) }
            }
            repository.refreshConversations()
            _openConversationEvents.emit(conversation.id)
        }
    }

    fun clearContactActionError() {
        _contactActionError.value = null
    }

    fun forwardHelpRoomMessages(target: MessageForwardTargetUi, sourceLocalKeys: List<String>) {
        if (sourceLocalKeys.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val targetConversationId = resolveForwardTargetConversationId(target)
                val count = repository.forwardMessages(targetConversationId, sourceLocalKeys)
                repository.refreshConversations()
                _forwardResultEvents.emit("已转发给 ${target.title}${if (count > 1) " · $count 条" else ""}")
                helpRoomRefreshState.value = RefreshState.Ready
            }.onFailure { error ->
                helpRoomRefreshState.value = RefreshState.Error(error.readableMessage())
            }
        }
    }

    private suspend fun resolveForwardTargetConversationId(target: MessageForwardTargetUi): Long =
        target.conversationId
            ?: target.peerUserId?.let { repository.openDirectConversation(it).id }
            ?: throw IllegalArgumentException("该联系人暂未同步到服务端")

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
            val conversation = runCatching { repository.openDirectConversation(expertUserId) }
                .getOrElse { error ->
                    _state.value = (_state.value as? ExpertDetailUiState.Content)
                        ?.copy(openingMessage = false, messageError = error.readableMessage())
                        ?: ExpertDetailUiState.Error(error.readableMessage())
                    return@launch
                }
            runCatching {
                repository.prewarmConversationHistory(
                    conversationId = conversation.id,
                    messageLimit = INITIAL_MESSAGE_PAGE_LIMIT,
                )
            }.onFailure {
                runCatching { repository.warmConversationSnapshot(conversation.id, INITIAL_MESSAGE_PAGE_LIMIT) }
            }
            _state.value = (_state.value as? ExpertDetailUiState.Content)
                ?.copy(openingMessage = false)
                ?: _state.value
            _openConversationEvents.emit(conversation.id)
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
    private val refreshState = MutableStateFlow<RefreshState>(RefreshState.Ready)
    private val forwardExperts = MutableStateFlow<List<HelpExpertRowUi>>(emptyList())
    private val _videoCallEvents = MutableSharedFlow<VideoCallOpenEvent>()
    private val _forwardResultEvents = MutableSharedFlow<String>()
    private var markedReadSeq = 0L
    val videoCallEvents = _videoCallEvents.asSharedFlow()
    val forwardResultEvents = _forwardResultEvents.asSharedFlow()

    val uiState: StateFlow<ConversationUiState> =
        combine(
            repository.observeConversation(conversationId),
            repository.observeMessages(conversationId),
            refreshState,
            tokenStore.currentUserIdFlow,
        ) { conversation, messages, refresh, currentUserId ->
            conversationUiState(
                conversationId = conversationId,
                conversation = conversation,
                messages = messages,
                refresh = refresh,
                currentUserId = currentUserId,
                json = json,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = conversationUiState(
                conversationId = conversationId,
                conversation = repository.cachedConversation(conversationId),
                messages = repository.cachedMessages(conversationId),
                refresh = RefreshState.Ready,
                currentUserId = tokenStore.currentUserId(),
                json = json,
            ),
        )

    val forwardTargets: StateFlow<List<MessageForwardTargetUi>> =
        combine(repository.observeConversations(), forwardExperts) { conversations, experts ->
            val conversationTargets = visibleMessageConversations(conversations)
                .filterNot { it.id == conversationId }
                .map { it.toForwardTargetUi() }
            val conversationPeerIds = conversations.mapNotNull { it.peer?.id }.toSet()
            val expertTargets = experts
                .filterNot { it.userId in conversationPeerIds }
                .map { it.toForwardTargetUi() }
            (conversationTargets + expertTargets).dedupeForwardTargets()
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    init {
        refresh()
        refreshForwardExperts()
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

    private fun refreshForwardExperts() {
        viewModelScope.launch {
            runCatching { repository.helpExperts().map { it.toRowUi() } }
                .onSuccess { forwardExperts.value = it }
        }
    }

    fun refresh() {
        if (conversationId <= 0) {
            refreshState.value = RefreshState.Error("会话参数无效")
            return
        }
        viewModelScope.launch {
            refreshState.value = runCatching {
                repository.refreshMessages(
                    conversationId = conversationId,
                    limit = INITIAL_MESSAGE_PAGE_LIMIT,
                    fullSync = repository.shouldHydrateConversationHistory(conversationId),
                )
                RefreshState.Ready
            }.getOrElse { RefreshState.Error(it.readableMessage()) }
        }
    }

    fun send(text: String, quote: MessageQuote? = null) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || conversationId <= 0) return
        viewModelScope.launch {
            runCatching {
                repository.sendText(conversationId, trimmed, quote)
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

    fun transcribeVoiceToText(uri: Uri, durationSec: Int) {
        if (conversationId <= 0) return
        if (durationSec <= 0) {
            refreshState.value = RefreshState.Error("录音太短，未发送消息")
            return
        }
        viewModelScope.launch {
            runCatching {
                val text = repository.transcribeVoiceDraft(uri)
                repository.sendText(conversationId, text)
                refreshState.value = RefreshState.Ready
            }.onFailure { error ->
                refreshState.value = RefreshState.Error("语音转文字失败，未发送消息：${error.readableMessage()}")
            }
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

    fun sendInspectionCard(card: InspectionShareCard) {
        if (conversationId <= 0) return
        viewModelScope.launch {
            runCatching {
                repository.sendInspectionCard(conversationId, card)
                refreshState.value = RefreshState.Ready
            }
                .onFailure { refreshState.value = RefreshState.Error(it.readableMessage()) }
        }
    }

    fun startVideoCall() {
        if (conversationId <= 0) return
        viewModelScope.launch {
            runCatching {
                repository.createVideoCallInvite(conversationId, uiState.value.title)
            }.onSuccess { invite ->
                refreshState.value = RefreshState.Ready
                _videoCallEvents.emit(
                    VideoCallOpenEvent(
                        roomId = invite.roomId,
                        title = invite.title,
                        mode = VideoCallMode.Caller,
                    ),
                )
            }.onFailure { error ->
                refreshState.value = RefreshState.Error(error.readableMessage())
            }
        }
    }

    fun acceptVideoCall(call: CallInviteUi) {
        if (call.roomId.isBlank()) return
        viewModelScope.launch {
            _videoCallEvents.emit(
                VideoCallOpenEvent(
                    roomId = call.roomId,
                    title = call.title.ifBlank { uiState.value.title },
                    mode = VideoCallMode.Callee,
                ),
            )
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

    fun retryVoiceTranscript(messageId: Long?) {
        if (messageId == null || messageId <= 0) return
        viewModelScope.launch {
            runCatching {
                repository.retryVoiceTranscript(messageId)
                refreshState.value = RefreshState.Ready
            }.onFailure { refreshState.value = RefreshState.Error(it.readableMessage()) }
        }
    }

    fun clearMessages() {
        if (conversationId <= 0) return
        viewModelScope.launch {
            runCatching {
                repository.clearConversationMessages(conversationId)
                refreshState.value = RefreshState.Ready
            }
                .onFailure { refreshState.value = RefreshState.Error(it.readableMessage()) }
        }
    }

    fun togglePinned() {
        if (conversationId <= 0) return
        val pinned = !uiState.value.pinned
        viewModelScope.launch {
            runCatching {
                repository.setConversationPinned(conversationId, pinned)
                refreshState.value = RefreshState.Ready
            }
                .onFailure { refreshState.value = RefreshState.Error(it.readableMessage()) }
        }
    }

    fun forwardMessages(target: MessageForwardTargetUi, sourceLocalKeys: List<String>) {
        if (sourceLocalKeys.isEmpty()) return
        viewModelScope.launch {
            runCatching {
                val targetConversationId = resolveForwardTargetConversationId(target)
                val count = repository.forwardMessages(targetConversationId, sourceLocalKeys)
                repository.refreshConversations()
                _forwardResultEvents.emit("已转发给 ${target.title}${if (count > 1) " · $count 条" else ""}")
                refreshState.value = RefreshState.Ready
            }.onFailure { error ->
                refreshState.value = RefreshState.Error(error.readableMessage())
            }
        }
    }

    private suspend fun resolveForwardTargetConversationId(target: MessageForwardTargetUi): Long =
        target.conversationId
            ?: target.peerUserId?.let { repository.openDirectConversation(it).id }
            ?: throw IllegalArgumentException("该联系人暂未同步到服务端")

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
        val unreadCount: Long,
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
    val loading: Boolean = false,
    val offlineCached: Boolean = false,
    val errorMessage: String? = null,
    val pinned: Boolean = false,
) {
    val empty: Boolean get() = !loading && messages.isEmpty() && errorMessage == null
}

private fun conversationUiState(
    conversationId: Long,
    conversation: ConversationSummary?,
    messages: List<MessageRecord>,
    refresh: RefreshState,
    currentUserId: Long?,
    json: Json,
): ConversationUiState = ConversationUiState(
    conversationId = conversationId,
    title = conversation?.displayTitle() ?: if (conversationId > 0) "会话 #$conversationId" else "会话",
    eyebrow = if (conversationId > 0) "会话 · #$conversationId" else "会话",
    messages = messages.map { it.toBubbleUi(json, currentUserId) },
    loading = false,
    offlineCached = messages.isNotEmpty() && refresh is RefreshState.Error,
    errorMessage = (refresh as? RefreshState.Error)?.message,
    pinned = conversation?.pinned ?: false,
)

data class MessageBubbleUi(
    val localKey: String,
    val serverId: Long?,
    val kind: String,
    val text: String,
    val mine: Boolean,
    val senderLabel: String?,
    val avatarKey: String,
    val time: String,
    val timeDividerLabel: String,
    val createdAtEpochMillis: Long?,
    val status: MessageStatus,
    val clientMsgId: String?,
    val isVoice: Boolean = false,
    val inspectionCard: InspectionCardUi? = null,
    val callInvite: CallInviteUi? = null,
    val voiceTranscript: VoiceTranscriptUi? = null,
    val quote: QuoteReferenceUi? = null,
)

data class QuoteReferenceUi(
    val localKey: String,
    val serverId: Long?,
    val senderLabel: String,
    val text: String,
)

data class MessageForwardTargetUi(
    val stableKey: String,
    val title: String,
    val subtitle: String,
    val initials: String,
    val conversationId: Long?,
    val peerUserId: Long?,
)

data class VoiceTranscriptUi(
    val status: String,
    val text: String?,
    val error: String?,
)

data class InspectionCardUi(
    val inspectionId: String,
    val vin: String,
    val vehicleLine: String,
    val timeLabel: String,
    val status: String,
    val tags: List<String>,
) {
    val searchText: String get() = listOf(inspectionId, vin, vehicleLine, timeLabel, status, tags.joinToString(" "))
        .joinToString(" ")
}

data class CallInviteUi(
    val roomId: String,
    val providerRoom: String,
    val title: String,
    val status: String,
    val liveKitConfigured: Boolean,
    val message: String?,
) {
    val searchText: String get() = listOf(roomId, providerRoom, title, status, message.orEmpty())
        .joinToString(" ")
}

data class VideoCallOpenEvent(
    val roomId: String,
    val title: String,
    val mode: VideoCallMode,
)

enum class VideoCallMode(val routeValue: String) {
    Caller("caller"),
    Callee("callee"),
}

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
        preview = last?.previewText(json).orEmpty(),
        time = last?.createdAt?.formatMessageTime().orEmpty(),
        unreadCount = unreadCount,
        avatarKind = last?.avatarKind() ?: AvatarKind.Neutral,
        unreadTone = if (unreadCount > 0) WatchTone.Accent else WatchTone.Neutral,
    )
}

private fun ConversationSummary.toForwardTargetUi(): MessageForwardTargetUi {
    val title = displayTitle()
    val subtitle = peer?.employeeId?.takeIf { it.isNotBlank() }
        ?: when (kind) {
            "group" -> subjectKind?.takeIf { it.isNotBlank() } ?: "群聊"
            else -> "最近会话"
        }
    return MessageForwardTargetUi(
        stableKey = "conversation-$id",
        title = title,
        subtitle = subtitle,
        initials = initialsFor(title),
        conversationId = id,
        peerUserId = peer?.id,
    )
}

private fun HelpExpertRowUi.toForwardTargetUi(): MessageForwardTargetUi = MessageForwardTargetUi(
    stableKey = "expert-$userId",
    title = name,
    subtitle = "$roleTitle · $employeeId",
    initials = initials,
    conversationId = null,
    peerUserId = userId,
)

private fun List<MessageForwardTargetUi>.dedupeForwardTargets(): List<MessageForwardTargetUi> {
    val seen = linkedSetOf<String>()
    return filter { target ->
        val key = target.peerUserId?.let { "peer-$it" } ?: target.stableKey
        seen.add(key)
    }
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
    val card = inspectionCardPayload(json)
    val call = callInvitePayload(json)
    val transcript = voiceTranscriptPayload(json)
    val quote = quoteReferencePayload(json)
    return MessageBubbleUi(
        localKey = localKey,
        serverId = serverId,
        kind = kind,
        text = card?.searchText ?: call?.searchText ?: if (kind == "voice") voiceBaseText(json) else previewText(json),
        mine = mine,
        senderLabel = null,
        avatarKey = if (mine) "me" else "peer-${senderId ?: localKey}",
        time = createdAt.formatMessageTime(),
        timeDividerLabel = createdAt.formatChatDividerTime(),
        createdAtEpochMillis = createdAt.toEpochMillisOrNull(),
        status = status,
        clientMsgId = clientMsgId,
        isVoice = kind == "voice",
        inspectionCard = card,
        callInvite = call,
        voiceTranscript = transcript,
        quote = quote,
    )
}

private fun MessageRecord.toBubbleUi(
    json: Json,
    experts: List<HelpExpertRowUi>,
    currentUserId: Long?,
): MessageBubbleUi {
    val mine = mineBySender(currentUserId)
    val expert = experts.firstOrNull { it.userId == senderId }
    val card = inspectionCardPayload(json)
    val call = callInvitePayload(json)
    val transcript = voiceTranscriptPayload(json)
    val quote = quoteReferencePayload(json)
    return MessageBubbleUi(
        localKey = localKey,
        serverId = serverId,
        kind = kind,
        text = card?.searchText ?: call?.searchText ?: if (kind == "voice") voiceBaseText(json) else previewText(json),
        mine = mine,
        senderLabel = if (mine) null else expert?.name ?: "成员 #$senderId",
        avatarKey = if (mine) {
            "me"
        } else {
            expert?.let { "expert-${it.userId}-${it.name}" } ?: "member-${senderId ?: localKey}"
        },
        time = createdAt.formatMessageTime(),
        timeDividerLabel = createdAt.formatChatDividerTime(),
        createdAtEpochMillis = createdAt.toEpochMillisOrNull(),
        status = status,
        clientMsgId = clientMsgId,
        isVoice = kind == "voice",
        inspectionCard = card,
        callInvite = call,
        voiceTranscript = transcript,
        quote = quote,
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
    "inspection_card" -> preview?.takeIf { it.isNotBlank() } ?: inspectionCardPayload(json)?.let { "[流水] ${it.vin}" } ?: "[业务流水]"
    "call_invite" -> preview?.takeIf { it.isNotBlank() } ?: callInvitePayload(json)?.let { "[视频通话] ${it.title}" } ?: "[视频通话邀请]"
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

private fun MessageRecord.quoteReferencePayload(json: Json): QuoteReferenceUi? {
    if (kind != "text") return null
    val obj = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return null
    val quote = runCatching { obj["quote"]?.jsonObject }.getOrNull() ?: return null
    val text = quote["text"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
    if (text.isBlank()) return null
    val senderLabel = quote["sender_label"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "引用消息" }
    return QuoteReferenceUi(
        localKey = quote["local_key"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        serverId = quote["server_id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
        senderLabel = senderLabel,
        text = text,
    )
}

private fun MessageRecord.mediaPreview(json: Json, awaiting: String, ready: String): String {
    val obj = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return ready
    val state = obj["media_state"]?.jsonPrimitive?.contentOrNull.orEmpty()
    return if (state == "awaiting_asset_upload") awaiting else ready
}

private fun MessageRecord.voiceBaseText(json: Json): String {
    val obj = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull()
        ?: return preview?.takeIf { it.isNotBlank() } ?: "[语音消息]"
    if (obj["media_state"]?.jsonPrimitive?.contentOrNull == "awaiting_asset_upload") {
        return "[语音待上传]"
    }
    val duration = obj["duration_sec"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
    return if (duration != null && duration > 0) "[语音 ${formatVoiceDuration(duration)}]" else "[语音消息]"
}

private fun MessageRecord.voiceTranscriptPayload(json: Json): VoiceTranscriptUi? {
    if (kind != "voice") return null
    val obj = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return null
    val status = obj["transcript_status"]?.jsonPrimitive?.contentOrNull ?: return null
    val normalized = obj["transcript_normalized_text"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val text = normalized.ifBlank { obj["transcript_text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
    val error = obj["transcript_error"]?.jsonPrimitive?.contentOrNull
    return VoiceTranscriptUi(
        status = status,
        text = text.takeIf { it.isNotBlank() },
        error = error,
    )
}

private fun MessageRecord.inspectionCardPayload(json: Json): InspectionCardUi? {
    if (kind != "inspection_card") return null
    val obj = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return null
    val inspectionId = obj["inspection_id"]?.jsonPrimitive?.contentOrNull
        ?: obj["id"]?.jsonPrimitive?.contentOrNull
        ?: return null
    val vin = obj["vin"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { inspectionId }
    val vehicleLine = obj["vehicle_line"]?.jsonPrimitive?.contentOrNull
        ?: obj["model"]?.jsonPrimitive?.contentOrNull
        ?: "业务流水"
    val tags = obj["tags"]?.let { element ->
        runCatching {
            element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }
        }.getOrDefault(emptyList())
    }.orEmpty()
    return InspectionCardUi(
        inspectionId = inspectionId,
        vin = vin,
        vehicleLine = vehicleLine,
        timeLabel = obj["time_label"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        status = obj["status"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "warn" },
        tags = tags,
    )
}

private fun MessageRecord.callInvitePayload(json: Json): CallInviteUi? {
    if (kind != "call_invite") return null
    val obj = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return null
    val roomId = obj["room_id"]?.jsonPrimitive?.contentOrNull
        ?: obj["call_id"]?.jsonPrimitive?.contentOrNull
        ?: return null
    return CallInviteUi(
        roomId = roomId,
        providerRoom = obj["provider_room"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "视频通话" },
        status = obj["status"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "ringing" },
        liveKitConfigured = obj["livekit_configured"]?.jsonPrimitive?.contentOrNull == "true",
        message = obj["message"]?.jsonPrimitive?.contentOrNull,
    )
}

private fun MessageRecord.avatarKind(): AvatarKind = when (kind) {
    "image" -> AvatarKind.Image
    "voice" -> AvatarKind.Voice
    "video_clip" -> AvatarKind.Video
    "inspection_card" -> AvatarKind.System
    "call_invite" -> AvatarKind.Call
    "video_call" -> AvatarKind.Call
    "system" -> AvatarKind.System
    else -> AvatarKind.Neutral
}

private fun initialsFor(title: String): String =
    title.trim().firstOrNull()?.toString()?.uppercase().orEmpty().ifBlank { "#" }

private fun formatVoiceDuration(sec: Int): String {
    val normalized = sec.coerceAtLeast(0)
    val m = normalized / 60
    val s = normalized % 60
    return "$m:" + s.toString().padStart(2, '0')
}

private fun String.formatMessageTime(): String =
    runCatching {
        Instant.parse(this).atZone(ZoneId.systemDefault()).format(messageTimeFormatter)
    }.getOrDefault("")

private fun String.formatChatDividerTime(): String =
    runCatching {
        val zoned = Instant.parse(this).atZone(ZoneId.systemDefault())
        val today = Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()
        val date = zoned.toLocalDate()
        when {
            date == today -> zoned.format(messageTimeFormatter)
            date == today.minusDays(1) -> "昨天 ${zoned.format(messageTimeFormatter)}"
            date.year == today.year -> zoned.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
            else -> zoned.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        }
    }.getOrDefault("")

private fun String.toEpochMillisOrNull(): Long? =
    runCatching { Instant.parse(this).toEpochMilli() }.getOrNull()

private fun String.formatCaseTime(): String =
    runCatching {
        Instant.parse(this).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    }.getOrDefault("")

private fun Throwable.readableMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "消息服务暂不可用"
