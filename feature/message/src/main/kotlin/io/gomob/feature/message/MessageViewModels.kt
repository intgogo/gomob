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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
    private val multiLineRoomsRefreshState = MutableStateFlow<RefreshState>(RefreshState.Loading)
    private val _contactActionError = MutableStateFlow<String?>(null)
    private val _openConversationEvents = MutableSharedFlow<Long>()
    private val _openSearchMessageEvents = MutableSharedFlow<MessageSearchOpenEvent>()
    val contactActionError: StateFlow<String?> = _contactActionError.asStateFlow()
    val openConversationEvents = _openConversationEvents.asSharedFlow()
    val openSearchMessageEvents = _openSearchMessageEvents.asSharedFlow()

    val uiState: StateFlow<MessageListUiState> =
        combine(repository.observeConversations(), refreshState) { conversations, refresh ->
            conversations.toMessageListUiState(refresh)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = repository.cachedConversations().toMessageListUiState(RefreshState.Ready),
        )

    val searchUiState: StateFlow<MessageListSearchUiState> =
        combine(
            repository.observeConversations(),
            repository.observeRecentSearchMessages(),
            tokenStore.currentUserIdFlow,
        ) { conversations, messages, currentUserId ->
            val visibleConversations = visibleMessageConversations(conversations).associateBy { it.id }
            MessageListSearchUiState(
                messages = messages.mapNotNull { message ->
                    visibleConversations[message.conversationId]?.let { conversation ->
                        message.toMessageListSearchUi(conversation, json, currentUserId)
                    }
                },
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = MessageListSearchUiState(),
        )

    val multiLineRoomsUiState: StateFlow<MultiLineRoomsUiState> =
        combine(repository.observeConversations(), helpExperts, multiLineRoomsRefreshState) { conversations, experts, refresh ->
            conversations.toMultiLineRoomsUiState(experts, refresh)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = repository.cachedConversations().toMultiLineRoomsUiState(
                experts = emptyList(),
                refresh = RefreshState.Ready,
            ),
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

    init {
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
            multiLineRoomsRefreshState.value = RefreshState.Loading
            val result = runCatching {
                val room = repository.openHelpRoom()
                repository.refreshConversations()
                runCatching {
                    repository.refreshMessages(
                        conversationId = room.id,
                        limit = INITIAL_MESSAGE_PAGE_LIMIT,
                        fullSync = repository.shouldHydrateConversationHistory(room.id),
                    )
                }
                RefreshState.Ready
            }.getOrElse { RefreshState.Error(it.readableMessage()) }
            multiLineRoomsRefreshState.value = result
        }
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

    fun openSearchMessage(message: MessageListSearchMessageUi) {
        if (message.conversationId <= 0 || message.localKey.isBlank()) return
        viewModelScope.launch {
            runCatching {
                repository.warmConversationSnapshot(
                    conversationId = message.conversationId,
                    messageLimit = INITIAL_MESSAGE_PAGE_LIMIT,
                )
            }
            _openSearchMessageEvents.emit(
                MessageSearchOpenEvent(
                    conversationId = message.conversationId,
                    localKey = message.localKey,
                ),
            )
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

    private fun List<ConversationSummary>.toMessageListUiState(refresh: RefreshState): MessageListUiState {
        val rows = visibleMessageConversations(this).map { it.toRowUi(json) }
        return when {
            rows.isNotEmpty() -> MessageListUiState.Content(
                conversations = rows,
                offlineCached = refresh is RefreshState.Error,
                errorMessage = (refresh as? RefreshState.Error)?.message,
            )
            refresh is RefreshState.Loading -> MessageListUiState.Loading
            refresh is RefreshState.Error -> MessageListUiState.Error(refresh.message)
            else -> MessageListUiState.Empty
        }
    }

    private fun List<ConversationSummary>.toMultiLineRoomsUiState(
        experts: List<HelpExpertRowUi>,
        refresh: RefreshState,
    ): MultiLineRoomsUiState {
        val rows = multiLineConversations(this).map { it.toMultiLineRoomRowUi(json, experts) }
        return when {
            rows.isNotEmpty() -> MultiLineRoomsUiState.Content(
                rooms = rows,
                offlineCached = refresh is RefreshState.Error,
                errorMessage = (refresh as? RefreshState.Error)?.message,
            )
            refresh is RefreshState.Loading -> MultiLineRoomsUiState.Loading
            refresh is RefreshState.Error -> MultiLineRoomsUiState.Error(refresh.message)
            else -> MultiLineRoomsUiState.Empty
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
class ContactDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MessageRepository,
) : ViewModel() {
    private val contactId = savedStateHandle.get<String>("id").orEmpty()
    private val _state = MutableStateFlow<ContactDetailUiState>(ContactDetailUiState.Loading)
    val state: StateFlow<ContactDetailUiState> = _state.asStateFlow()
    private val _openConversationEvents = MutableSharedFlow<Long>()
    val openConversationEvents = _openConversationEvents.asSharedFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = ContactDetailUiState.Loading
            _state.value = runCatching {
                val local = localContactProfiles().firstOrNull { it.id == contactId }
                if (local != null) {
                    ContactDetailUiState.Content(contact = local, cases = emptyList())
                } else {
                    val expertUserId = contactId.contactUserId()
                        ?: throw IllegalArgumentException("联系人不存在")
                    val expert = repository.helpExperts()
                        .map { it.toRowUi() }
                        .firstOrNull { it.userId == expertUserId }
                    if (expert == null) {
                        ContactDetailUiState.Content(contact = fallbackContactProfile(expertUserId), cases = emptyList())
                    } else {
                        ContactDetailUiState.Content(
                            contact = expert.toContactProfileUi(),
                            cases = repository.helpExpertCases(expertUserId).map { it.toRowUi() },
                        )
                    }
                }
            }.getOrElse { ContactDetailUiState.Error(it.readableMessage()) }
        }
    }

    fun openDirectConversation() {
        val content = _state.value as? ContactDetailUiState.Content ?: return
        if (content.openingMessage) return
        val peerUserId = content.contact.peerUserId
        val peerEmployeeId = content.contact.employeeId.takeIf { it.isNotBlank() }
        if ((peerUserId == null || peerUserId <= 0) && peerEmployeeId == null) {
            _state.value = content.copy(messageError = "该联系人暂未同步到服务端")
            return
        }
        viewModelScope.launch {
            _state.value = content.copy(openingMessage = true, messageError = null)
            val conversation = runCatching {
                repository.openDirectConversation(
                    peerUserId = peerUserId?.takeIf { it > 0 },
                    peerEmployeeId = peerEmployeeId,
                )
            }
                .getOrElse { error ->
                    _state.value = (_state.value as? ContactDetailUiState.Content)
                        ?.copy(openingMessage = false, messageError = error.readableMessage())
                        ?: ContactDetailUiState.Error(error.readableMessage())
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
            _state.value = (_state.value as? ContactDetailUiState.Content)
                ?.copy(openingMessage = false)
                ?: _state.value
            _openConversationEvents.emit(conversation.id)
        }
    }
}

private fun String.contactUserId(): Long? =
    removePrefix("expert-")
        .removePrefix("user-")
        .toLongOrNull()

private fun fallbackContactProfile(userId: Long): ContactProfileUi = ContactProfileUi(
    id = "user-$userId",
    name = "用户 #$userId",
    initials = "#",
    roleTitle = "消息联系人",
    specialty = "聊天成员",
    employeeId = "#$userId",
    availabilityText = "可联系",
    organization = "消息中心",
    online = false,
    peerUserId = userId,
)

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
        combine(repository.observeConversations(), forwardExperts, tokenStore.currentUserIdFlow) { conversations, experts, currentUserId ->
            val directConversations = visibleMessageConversations(conversations)
                .filterNot { it.id == conversationId }
            val conversationsByPeerId = directConversations
                .mapNotNull { conversation -> conversation.peer?.id?.let { it to conversation } }
                .toMap()
            val conversationsByEmployeeId = directConversations
                .mapNotNull { conversation ->
                    conversation.peer?.employeeId
                        ?.takeIf { it.isNotBlank() }
                        ?.let { it to conversation }
                }
                .toMap()
            val contactTargets = forwardContactProfiles(experts, currentUserId)
                .map { contact ->
                    val existingConversation = contact.peerUserId?.let(conversationsByPeerId::get)
                        ?: conversationsByEmployeeId[contact.employeeId]
                    contact.toForwardTargetUi(existingConversationId = existingConversation?.id)
                }
            val recentTargets = directConversations.map { it.toForwardTargetUi() }
            (contactTargets + recentTargets).dedupeForwardTargets()
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
                    runCatching { repository.markRead(conversationId, lastSeq) }
                        .onSuccess { markedReadSeq = maxOf(markedReadSeq, lastSeq) }
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

    suspend fun transcribeVoiceDraftText(uri: Uri, durationSec: Int): String {
        if (conversationId <= 0 || durationSec <= 0) throw IllegalArgumentException("未识别到文字")
        return repository.transcribeVoiceDraft(uri).ifBlank { throw IllegalArgumentException("未识别到文字") }
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

    fun startVideoCall(titleOverride: String? = null) {
        if (conversationId <= 0) return
        viewModelScope.launch {
            runCatching {
                repository.createVideoCallInvite(
                    conversationId = conversationId,
                    title = titleOverride?.takeIf { it.isNotBlank() } ?: uiState.value.title,
                )
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
            ?: target.peerEmployeeId?.let { repository.openDirectConversation(peerEmployeeId = it).id }
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

sealed interface MultiLineRoomsUiState {
    data object Loading : MultiLineRoomsUiState
    data object Empty : MultiLineRoomsUiState
    data class Error(val message: String) : MultiLineRoomsUiState
    data class Content(
        val rooms: List<MultiLineRoomRowUi>,
        val offlineCached: Boolean,
        val errorMessage: String?,
    ) : MultiLineRoomsUiState
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

data class MultiLineRoomRowUi(
    val id: Long,
    val title: String,
    val subtitle: String,
    val preview: String,
    val time: String,
    val unreadCount: Long,
    val memberCountLabel: String,
    val avatarSeeds: List<String>,
    val unreadTone: WatchTone,
)

data class MessageListSearchUiState(
    val messages: List<MessageListSearchMessageUi> = emptyList(),
)

data class MessageListSearchMessageUi(
    val localKey: String,
    val conversationId: Long,
    val conversationTitle: String,
    val senderLabel: String,
    val preview: String,
    val time: String,
    val kind: String,
    val searchableText: String,
)

data class MessageSearchOpenEvent(
    val conversationId: Long,
    val localKey: String,
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

sealed interface ContactDetailUiState {
    data object Loading : ContactDetailUiState
    data class Error(val message: String) : ContactDetailUiState
    data class Content(
        val contact: ContactProfileUi,
        val cases: List<ExpertCaseRowUi>,
        val openingMessage: Boolean = false,
        val messageError: String? = null,
    ) : ContactDetailUiState
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
    val group: Boolean = false,
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
    title = conversation?.displayMultiLineTitle() ?: if (conversationId > 0) "会话 #$conversationId" else "会话",
    eyebrow = if (conversationId > 0) "会话 · #$conversationId" else "会话",
    messages = messages.mergeCallResultMessages(json).map { it.toBubbleUi(json, currentUserId) },
    loading = false,
    offlineCached = messages.isNotEmpty() && refresh is RefreshState.Error,
    errorMessage = (refresh as? RefreshState.Error)?.message,
    pinned = conversation?.pinned ?: false,
    group = conversation?.kind == "group" || conversation?.isOnlineHelpRoom() == true,
)

data class MessageBubbleUi(
    val localKey: String,
    val serverId: Long?,
    val kind: String,
    val text: String,
    val mine: Boolean,
    val senderUserId: Long?,
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
    val callResult: CallResultUi? = null,
    val voiceTranscript: VoiceTranscriptUi? = null,
    val quote: QuoteReferenceUi? = null,
    val media: MediaAttachmentUi? = null,
)

data class MediaAttachmentUi(
    val mediaState: String,
    val localUri: String?,
    val downloadUrl: String?,
    val mime: String?,
) {
    val imageSource: String?
        get() = localUri?.takeIf { it.isNotBlank() }
            ?: downloadUrl?.takeIf { it.isNotBlank() }
}

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
    val peerEmployeeId: String? = null,
    val roleTitle: String = "",
    val employeeId: String = "",
    val organization: String = "",
    val online: Boolean = true,
    val sectionId: String = "contacts",
    val sectionTitle: String = "联系人",
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
    val durationSec: Int? = null,
    val failureReason: String? = null,
) {
    val ringing: Boolean get() = status == "ringing"
    val succeeded: Boolean get() = status.isSuccessfulCallStatus()
    val statusText: String get() = status.callStatusText()
    val durationText: String? get() = durationSec?.takeIf { it > 0 }?.let(::formatVoiceDuration)
    val searchText: String get() = listOf(
        roomId,
        providerRoom,
        title,
        status,
        statusText,
        message.orEmpty(),
        durationText.orEmpty(),
        failureReason.orEmpty(),
    )
        .joinToString(" ")
}

data class CallResultUi(
    val kind: String,
    val title: String,
    val status: String,
    val statusText: String,
    val durationSec: Int?,
    val failureReason: String?,
) {
    val succeeded: Boolean get() = status.isSuccessfulCallStatus()
    val durationText: String? get() = durationSec?.takeIf { it > 0 }?.let(::formatVoiceDuration)
    val previewText: String
        get() = when {
            succeeded && durationText != null -> "[$title $durationText]"
            succeeded -> "[$title]"
            !failureReason.isNullOrBlank() -> "[$title$statusText] $failureReason"
            statusText.isNotBlank() -> "[$title$statusText]"
            else -> "[$title]"
        }
    val searchText: String
        get() = listOf(title, status, statusText, durationText.orEmpty(), failureReason.orEmpty())
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

private fun ConversationSummary.toMultiLineRoomRowUi(
    json: Json,
    experts: List<HelpExpertRowUi>,
): MultiLineRoomRowUi {
    val title = displayMultiLineTitle()
    val onlineHelp = isOnlineHelpRoom()
    val memberNames = if (onlineHelp) experts.map { it.name } else emptyList()
    val memberCountLabel = when {
        memberNames.isNotEmpty() -> "${memberNames.size + 1}人"
        onlineHelp -> "多人群"
        kind == "group" -> "群聊"
        else -> "多人"
    }
    val subtitle = when {
        memberNames.isNotEmpty() -> memberNames.take(4).joinToString("、")
        onlineHelp -> "协作成员、查验员"
        !subjectKind.isNullOrBlank() -> subjectKind.orEmpty()
        else -> "多人聊天"
    }
    val seeds = if (memberNames.isNotEmpty()) {
        experts.take(4).map { "expert-${it.userId}-${it.name}" }
    } else {
        listOf(
            "group-$id-$title-a",
            "group-$id-$title-b",
            "group-$id-$title-c",
            "group-$id-$title-d",
        )
    }
    val last = lastMessage
    return MultiLineRoomRowUi(
        id = id,
        title = title,
        subtitle = subtitle,
        preview = last?.previewText(json).orEmpty(),
        time = last?.createdAt?.formatMessageTime().orEmpty(),
        unreadCount = unreadCount,
        memberCountLabel = memberCountLabel,
        avatarSeeds = seeds,
        unreadTone = if (unreadCount > 0) WatchTone.Accent else WatchTone.Neutral,
    )
}

private fun MessageRecord.toMessageListSearchUi(
    conversation: ConversationSummary,
    json: Json,
    currentUserId: Long?,
): MessageListSearchMessageUi {
    val conversationTitle = conversation.displayTitle()
    val peer = conversation.peer
    val sender = when {
        senderId != null && senderId == currentUserId -> "我"
        senderId != null && peer != null && senderId == peer.id -> peer.name.ifBlank { "成员 #$senderId" }
        senderId != null -> "成员 #$senderId"
        else -> "系统"
    }
    val preview = previewText(json)
    return MessageListSearchMessageUi(
        localKey = localKey,
        conversationId = conversationId,
        conversationTitle = conversationTitle,
        senderLabel = sender,
        preview = preview,
        time = createdAt.formatChatDividerTime().ifBlank { createdAt.formatMessageTime() },
        kind = kind,
        searchableText = listOf(conversationTitle, sender, messageListSearchText(json))
            .joinToString(" "),
    )
}

private fun ConversationSummary.toForwardTargetUi(): MessageForwardTargetUi {
    val title = displayTitle()
    val employeeId = peer?.employeeId?.takeIf { it.isNotBlank() } ?: "会话 #$id"
    val roleTitle = when (kind) {
        "group" -> subjectKind?.takeIf { it.isNotBlank() } ?: "群聊"
        else -> "最近联系人"
    }
    return MessageForwardTargetUi(
        stableKey = "conversation-$id",
        title = title,
        subtitle = "$roleTitle · $employeeId",
        initials = initialsFor(title),
        conversationId = id,
        peerUserId = peer?.id,
        peerEmployeeId = peer?.employeeId,
        roleTitle = roleTitle,
        employeeId = employeeId,
        organization = "近期会话",
        online = peer != null,
        sectionId = "recent",
        sectionTitle = "近期联系人",
    )
}

private fun ContactProfileUi.toForwardTargetUi(existingConversationId: Long?): MessageForwardTargetUi =
    MessageForwardTargetUi(
        stableKey = "contact-${peerUserId ?: employeeId}",
        title = name,
        subtitle = "$roleTitle · $employeeId",
        initials = initials,
        conversationId = existingConversationId,
        peerUserId = peerUserId,
        peerEmployeeId = employeeId,
        roleTitle = roleTitle,
        employeeId = employeeId,
        organization = organization,
        online = online,
        sectionId = forwardSectionId(),
        sectionTitle = forwardSectionTitle(),
    )

private fun forwardContactProfiles(
    experts: List<HelpExpertRowUi>,
    currentUserId: Long?,
): List<ContactProfileUi> {
    val localContacts = localContactProfiles()
        .filterNot { it.id == "station-shen" }
        .filterNot { it.peerUserId != null && it.peerUserId == currentUserId }
    val expertContacts = experts
        .filterNot { it.userId == currentUserId }
        .map { it.toContactProfileUi() }
    return (localContacts + expertContacts).distinctBy { contact ->
        contact.peerUserId?.let { "peer-$it" } ?: "employee-${contact.employeeId.lowercase()}"
    }
}

private fun ContactProfileUi.forwardSectionId(): String = when {
    id.startsWith("station-") -> "station"
    id.startsWith("supervision-") -> "supervision"
    id.startsWith("expert-") -> "experts"
    else -> "contacts"
}

private fun ContactProfileUi.forwardSectionTitle(): String = when (forwardSectionId()) {
    "station" -> "本站 · 杭州西湖检测站"
    "supervision" -> "监管中心 · 浙江省车管所"
    "experts" -> "外部专家 · 协作池"
    else -> "联系人"
}

private fun List<MessageForwardTargetUi>.dedupeForwardTargets(): List<MessageForwardTargetUi> {
    val seen = linkedSetOf<String>()
    return filter { target ->
        val key = target.peerEmployeeId?.let { "employee-${it.lowercase()}" }
            ?: target.employeeId.takeIf { it.isNotBlank() && !it.startsWith("会话 #") }?.let { "employee-${it.lowercase()}" }
            ?: target.peerUserId?.let { "peer-$it" }
            ?: target.stableKey
        seen.add(key)
    }
}

internal fun visibleMessageConversations(conversations: List<ConversationSummary>): List<ConversationSummary> =
    conversations.filter { it.kind != "group" && !it.isOnlineHelpRoom() }

internal fun multiLineConversations(conversations: List<ConversationSummary>): List<ConversationSummary> {
    val expertLine = conversations.firstOrNull { it.subjectKind == "online_help" }
        ?: conversations.firstOrNull { it.isOnlineHelpRoom() }
    val groupRooms = conversations.filter { it.kind == "group" && !it.isOnlineHelpRoom() }
    return listOfNotNull(expertLine) + groupRooms
}

private fun ConversationSummary.isOnlineHelpRoom(): Boolean =
    subjectKind == "online_help" || (kind == "group" && title == "在线求助")

private fun ConversationSummary.displayMultiLineTitle(): String =
    if (isOnlineHelpRoom()) {
        "专家连线"
    } else {
        displayTitle()
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
    val callResult = callResultPayload(json)
    val transcript = voiceTranscriptPayload(json)
    val quote = quoteReferencePayload(json)
    val media = mediaAttachmentPayload(json)
    return MessageBubbleUi(
        localKey = localKey,
        serverId = serverId,
        kind = kind,
        text = card?.searchText ?: call?.searchText ?: callResult?.searchText ?: if (kind == "voice") voiceBaseText(json) else previewText(json),
        mine = mine,
        senderUserId = senderId ?: if (mine) currentUserId else null,
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
        callResult = callResult,
        voiceTranscript = transcript,
        quote = quote,
        media = media,
    )
}

internal fun List<MessageRecord>.mergeCallResultMessages(json: Json): List<MessageRecord> {
    val inviteRooms = filter { it.kind == "call_invite" }
        .mapNotNull { it.callRoomKey(json) }
        .toSet()
    if (inviteRooms.isEmpty()) return this
    val resultsByRoom = filter { it.kind.isCallResultKind() }
        .mapNotNull { result ->
            result.callRoomKey(json)
                ?.takeIf { it in inviteRooms }
                ?.let { roomKey -> roomKey to result }
        }
        .toMap()
    if (resultsByRoom.isEmpty()) return this
    return mapNotNull { record ->
        val roomKey = record.callRoomKey(json)
        when {
            record.kind.isCallResultKind() && roomKey in inviteRooms -> null
            record.kind == "call_invite" && roomKey != null && resultsByRoom[roomKey] != null ->
                record.mergeCallResult(resultsByRoom.getValue(roomKey), json)
            else -> record
        }
    }
}

private fun MessageRecord.mergeCallResult(result: MessageRecord, json: Json): MessageRecord {
    val base = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return this
    val patch = runCatching { json.parseToJsonElement(result.payloadJson).jsonObject }.getOrNull() ?: return this
    val mergedPayload = JsonObject(base + patch)
    return copy(
        payloadJson = mergedPayload.toString(),
        preview = result.callResultPayload(json)?.previewText ?: result.previewText(json),
        editedAt = result.editedAt ?: result.createdAt,
    )
}

private fun MessageRecord.callRoomKey(json: Json): String? {
    if (kind != "call_invite" && !kind.isCallResultKind()) return null
    val obj = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return null
    return obj["room_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        ?: obj["call_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
}

private fun MessageRecord.mineBySender(currentUserId: Long?): Boolean =
    (senderId != null && senderId == currentUserId) ||
        (senderId == null && clientMsgId != null)

private fun ConversationSummary.displayTitle(): String =
    title?.takeIf { it.isNotBlank() }
        ?: peer?.name?.takeIf { it.isNotBlank() }
        ?: "会话 #$id"

internal fun MessageRecord.previewText(json: Json): String = when (kind) {
    "text" -> preview?.takeIf { it.isNotBlank() } ?: textPayload(json).ifBlank { "[文本]" }
    "image" -> preview?.takeIf { it.isNotBlank() } ?: "[图片]"
    "voice" -> preview?.takeIf { it.isNotBlank() } ?: mediaPreview(json, awaiting = "[语音待上传]", ready = "[语音消息]")
    "video_clip" -> preview?.takeIf { it.isNotBlank() } ?: mediaPreview(json, awaiting = "[视频待上传]", ready = "[视频]")
    "inspection_card" -> preview?.takeIf { it.isNotBlank() } ?: inspectionCardPayload(json)?.let { "[流水] ${it.vin}" } ?: "[业务流水]"
    "call_invite" -> preview?.takeIf { it.isNotBlank() } ?: callInvitePayload(json)?.let { "[视频通话] ${it.title}" } ?: "[视频通话邀请]"
    "video_call", "audio_call" -> callResultPayload(json)?.previewText ?: preview?.takeIf { it.isNotBlank() } ?: "[${kind.callTitle()}]"
    "system" -> preview?.takeIf { it.isNotBlank() } ?: "[系统消息]"
    else -> "[$kind]"
}

internal fun MessageRecord.messageListSearchText(json: Json): String {
    val payload = runCatching { json.parseToJsonElement(payloadJson) }.getOrNull()
    return listOf(kind.messageListKindLabel(), previewText(json), payload.collectMessageSearchText())
        .joinToString(" ")
}

private fun String.messageListKindLabel(): String = when (this) {
    "text" -> "文字"
    "image" -> "图片 照片"
    "voice" -> "语音"
    "video_clip" -> "视频"
    "inspection_card" -> "业务流水 查验 车辆 VIN 车架号"
    "call_invite" -> "视频通话 邀请"
    "video_call" -> "视频通话"
    "audio_call" -> "语音通话"
    "system" -> "系统消息"
    "file", "document" -> "文件"
    else -> this
}

private fun JsonElement?.collectMessageSearchText(): String {
    val element = this ?: return ""
    return when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonArray -> element.joinToString(" ") { it.collectMessageSearchText() }
        is JsonObject -> element.values.joinToString(" ") { it.collectMessageSearchText() }
    }
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

internal fun MessageRecord.mediaAttachmentPayload(json: Json): MediaAttachmentUi? {
    if (kind != "image" && kind != "video_clip") return null
    val obj = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return null
    val localUri = obj["local_uri"]?.jsonPrimitive?.contentOrNull
        ?: obj["localUri"]?.jsonPrimitive?.contentOrNull
    val downloadUrl = obj["download_url"]?.jsonPrimitive?.contentOrNull
        ?: obj["downloadUrl"]?.jsonPrimitive?.contentOrNull
        ?: obj["url"]?.jsonPrimitive?.contentOrNull
    val mediaState = obj["media_state"]?.jsonPrimitive?.contentOrNull
        ?: if (!downloadUrl.isNullOrBlank() || obj["asset_id"] != null) "ready" else ""
    val mime = obj["mime"]?.jsonPrimitive?.contentOrNull
    return MediaAttachmentUi(
        mediaState = mediaState,
        localUri = localUri?.takeIf { it.isNotBlank() },
        downloadUrl = downloadUrl?.takeIf { it.isNotBlank() },
        mime = mime?.takeIf { it.isNotBlank() },
    )
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
    val status = obj["status"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "ringing" }
    val reason = listOf("reason", "failure_reason", "error", "end_error", "message")
        .firstNotNullOfOrNull { key ->
            obj[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        }
        ?: status.defaultCallFailureReason()
    return CallInviteUi(
        roomId = roomId,
        providerRoom = obj["provider_room"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "视频通话" },
        status = status,
        liveKitConfigured = obj["livekit_configured"]?.jsonPrimitive?.contentOrNull == "true",
        message = obj["message"]?.jsonPrimitive?.contentOrNull,
        durationSec = obj["duration_sec"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
        failureReason = reason.takeUnless { status.isSuccessfulCallStatus() || status == "ringing" },
    )
}

internal fun MessageRecord.callResultPayload(json: Json): CallResultUi? {
    if (!kind.isCallResultKind()) return null
    val obj = runCatching { json.parseToJsonElement(payloadJson).jsonObject }.getOrNull() ?: return null
    val durationSec = obj["duration_sec"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
        ?: obj["duration_ms"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { (it / 1000).toInt() }
    val status = obj["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
        .ifBlank { if ((durationSec ?: 0) > 0) "completed" else "failed" }
    val reason = listOf("reason", "failure_reason", "error", "end_error", "message")
        .firstNotNullOfOrNull { key ->
            obj[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
        }
        ?: status.defaultCallFailureReason()
    return CallResultUi(
        kind = kind,
        title = kind.callTitle(),
        status = status,
        statusText = status.callStatusText(),
        durationSec = durationSec?.coerceAtLeast(0),
        failureReason = reason.takeUnless { status.isSuccessfulCallStatus() },
    )
}

private fun MessageRecord.avatarKind(): AvatarKind = when (kind) {
    "image" -> AvatarKind.Image
    "voice" -> AvatarKind.Voice
    "video_clip" -> AvatarKind.Video
    "inspection_card" -> AvatarKind.System
    "call_invite" -> AvatarKind.Call
    "video_call" -> AvatarKind.Call
    "audio_call" -> AvatarKind.Call
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

private fun String.isCallResultKind(): Boolean =
    this == "video_call" || this == "audio_call"

private fun String.callTitle(): String = when (this) {
    "audio_call" -> "语音通话"
    else -> "视频通话"
}

private fun String.isSuccessfulCallStatus(): Boolean =
    this == "completed" || this == "success" || this == "ended"

private fun String.callStatusText(): String = when (this) {
    "completed", "success", "ended" -> "已结束"
    "active" -> "通话中"
    "ringing" -> "等待接听"
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

private fun Throwable.readableMessage(): String {
    val text = message?.takeIf { it.isNotBlank() } ?: return "消息服务暂不可用"
    val normalized = text.lowercase()
    return when {
        "unexpected end of stream" in normalized -> "消息服务异常，请稍后重试"
        "failed to connect" in normalized || "timeout" in normalized || "timed out" in normalized -> {
            "消息服务暂不可用，请稍后重试"
        }
        else -> text
    }
}
