package io.gomob.feature.message

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.motion.fixedDuringPageDrag
import io.gomob.designsystem.theme.Gomob
import kotlinx.coroutines.launch

@Composable
fun ConversationRoute(
    conversationId: String,
    targetLocalKey: String? = null,
    onBack: () -> Unit,
    onOpenSearch: (String) -> Unit = {},
    onOpenInfo: (String) -> Unit = {},
    onOpenLocalVideo: (String) -> Unit = {},
    onOpenVideoCall: (roomId: String, title: String, mode: VideoCallMode) -> Unit = { _, _, _ -> },
    onOpenInspection: (String) -> Unit = {},
    onOpenUserDetail: (String) -> Unit = {},
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val forwardTargets by viewModel.forwardTargets.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var voiceRecording by remember { mutableStateOf(false) }
    var inspectionPickerOpen by rememberSaveable { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    var clearConfirmOpen by remember { mutableStateOf(false) }
    var quoteDraft by remember { mutableStateOf<QuoteDraftUi?>(null) }
    var pendingMentions by remember { mutableStateOf<List<MentionRef>>(emptyList()) }
    val mentionCandidates by viewModel.mentionCandidates.collectAsStateWithLifecycle()
    var favoriteMessageKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var multiSelectMode by rememberSaveable { mutableStateOf(false) }
    var selectedMessageKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var forwardingMessages by remember { mutableStateOf<List<MessageBubbleUi>>(emptyList()) }
    var voiceTranscriptionDraft by remember { mutableStateOf<VoiceTranscriptionDraft?>(null) }
    var pendingVideoCallTitle by remember { mutableStateOf<String?>(null) }
    var pendingPhotoUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var imagePreview by remember { mutableStateOf<ImageMessagePreviewUi?>(null) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    val voiceRecorder = rememberVoiceRecorder()
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let(viewModel::sendImage) },
    )
    val photoCapture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { captured ->
            val uri = pendingPhotoUriText?.let(Uri::parse)
            pendingPhotoUriText = null
            when {
                captured && uri != null -> viewModel.sendImage(uri)
                uri != null -> deleteMessageCapture(context, uri)
            }
        },
    )
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let(viewModel::sendVideoClip) },
    )
    val voicePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            runCatching {
                voiceRecorder.start()
                voiceRecording = true
            }.onFailure { viewModel.showError(it.message ?: "录音启动失败") }
        } else {
            viewModel.showError("未授予录音权限")
        }
    }
    val videoPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val granted = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .all { grants[it] == true }
        if (granted) {
            viewModel.startVideoCall(pendingVideoCallTitle)
        } else {
            viewModel.showError("需要相机和麦克风权限")
        }
        pendingVideoCallTitle = null
    }
    val openImagePicker: () -> Unit = {
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val openVideoPicker: () -> Unit = {
        videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }
    fun launchPhotoCapture() {
        runCatching { createMessageCaptureUri(context) }
            .onSuccess { uri ->
                pendingPhotoUriText = uri.toString()
                photoCapture.launch(uri)
            }
            .onFailure { viewModel.showError(it.message ?: "拍照启动失败") }
    }
    val photoPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchPhotoCapture()
        } else {
            viewModel.showError("未授予相机权限")
        }
    }
    val startPhotoCapture: () -> Unit = {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            launchPhotoCapture()
        } else {
            photoPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val startVoiceRecording: () -> Unit = startVoice@{
        if (voiceRecording) return@startVoice
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                voiceRecorder.start()
                voiceRecording = true
            }.onFailure { viewModel.showError(it.message ?: "录音启动失败") }
        } else {
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val startVideoCall: (String?) -> Unit = { title ->
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val granted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            viewModel.startVideoCall(title)
        } else {
            pendingVideoCallTitle = title
            videoPermissionLauncher.launch(permissions)
        }
    }
    val sendVoiceRecording: () -> Unit = sendVoice@{
        if (!voiceRecording) return@sendVoice
        runCatching { voiceRecorder.stop() }
            .onSuccess { result -> viewModel.sendVoice(result.uri, result.durationSec) }
            .onFailure { viewModel.showError(it.message ?: "录音失败") }
        voiceRecording = false
    }
    val cancelVoiceRecording: () -> Unit = cancelVoice@{
        if (!voiceRecording) return@cancelVoice
        voiceRecorder.cancel()
        voiceRecording = false
    }
    val transcribeVoiceRecording: () -> Unit = transcribeVoice@{
        if (!voiceRecording) return@transcribeVoice
        runCatching { voiceRecorder.stop() }
            .onSuccess { result ->
                voiceTranscriptionDraft = VoiceTranscriptionDraft(
                    uri = result.uri,
                    durationSec = result.durationSec,
                )
                coroutineScope.launch {
                    val text = runCatching {
                        viewModel.transcribeVoiceDraftText(result.uri, result.durationSec)
                    }.getOrDefault("")
                    voiceTranscriptionDraft = voiceTranscriptionDraft
                        ?.takeIf { it.uri == result.uri }
                        ?.copy(
                            text = text,
                            loading = false,
                            failed = text.isBlank(),
                        )
                }
            }
            .onFailure { viewModel.showError(it.message ?: "录音失败") }
        voiceRecording = false
    }
    val sendDraft = {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            // 发送时把仍存在 draft 里的 @name 与 pendingMentions 匹配；其余文本里出现但
            // 未在 pending 集合的 @ 串当普通文字（避免误把 "@xxx" 文字内容当成提及）。
            val activeMentions = pendingMentions.filter { ref -> text.contains("@${ref.name}") }
            viewModel.send(text, quoteDraft?.quote, activeMentions)
            draft = ""
            quoteDraft = null
            pendingMentions = emptyList()
        }
    }
    fun toggleMessageSelection(localKey: String) {
        selectedMessageKeys = if (localKey in selectedMessageKeys) {
            selectedMessageKeys - localKey
        } else {
            selectedMessageKeys + localKey
        }
        if (selectedMessageKeys.isEmpty()) {
            multiSelectMode = false
        }
    }
    fun selectedMessages(): List<MessageBubbleUi> =
        state.messages.filter { it.localKey in selectedMessageKeys }

    fun exitMultiSelect() {
        multiSelectMode = false
        selectedMessageKeys = emptyList()
    }
    fun copyMessages(messages: List<MessageBubbleUi>) {
        val text = messageShareText(messages)
        if (text.isBlank()) return
        clipboard.setText(AnnotatedString(text))
        context.showMessageActionToast("已复制")
    }
    fun handleMessageAction(action: MessageQuickAction, bubble: MessageBubbleUi) {
        when (action) {
            MessageQuickAction.Copy -> copyMessages(listOf(bubble))
            MessageQuickAction.Forward -> forwardingMessages = listOf(bubble)
            MessageQuickAction.Favorite -> {
                val added = bubble.localKey !in favoriteMessageKeys
                favoriteMessageKeys = if (added) favoriteMessageKeys + bubble.localKey else favoriteMessageKeys - bubble.localKey
                context.showMessageActionToast(if (added) "已收藏" else "已取消收藏")
            }
            MessageQuickAction.MultiSelect -> {
                multiSelectMode = true
                selectedMessageKeys = listOf(bubble.localKey)
            }
            MessageQuickAction.Quote -> {
                quoteDraft = QuoteDraftUi(bubble.toMessageQuote())
                context.showMessageActionToast("已引用")
            }
            MessageQuickAction.TranscribeVoice -> viewModel.retryVoiceTranscript(bubble.serverId)
            MessageQuickAction.Retry -> {
                viewModel.retry(bubble.clientMsgId)
                context.showMessageActionToast("正在重试…")
            }
            MessageQuickAction.Delete -> {
                viewModel.deleteLocalMessage(bubble.localKey)
                context.showMessageActionToast("已删除")
            }
            MessageQuickAction.Recall -> {
                bubble.serverId?.let { viewModel.recallMessage(it) }
            }
        }
    }
    LaunchedEffect(Unit) {
        viewModel.forwardResultEvents.collect { message ->
            context.showMessageActionToast(message)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.videoCallEvents.collect { event ->
            onOpenVideoCall(event.roomId, event.title, event.mode)
        }
    }

    if (clearConfirmOpen) {
        AlertDialog(
            onDismissRequest = { clearConfirmOpen = false },
            title = { Text("清空聊天记录", style = Gomob.type.title, color = Gomob.colors.fg0) },
            text = { Text("清空后，本机不会再显示当前已同步的历史消息。", style = Gomob.type.bodySm, color = Gomob.colors.fg2) },
            confirmButton = {
                TextButton(
                    onClick = {
                        clearConfirmOpen = false
                        viewModel.clearMessages()
                    },
                ) {
                    Text("清空", color = Gomob.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { clearConfirmOpen = false }) {
                    Text("取消", color = Gomob.colors.fg2)
                }
            },
        )
    }

    MessageForwardTargetDialog(
        visible = forwardingMessages.isNotEmpty(),
        targets = forwardTargets,
        messageCount = forwardingMessages.size,
        onDismiss = { forwardingMessages = emptyList() },
        onSelectTarget = { target ->
            val sourceLocalKeys = forwardingMessages.map { it.localKey }
            forwardingMessages = emptyList()
            exitMultiSelect()
            viewModel.forwardMessages(target, sourceLocalKeys)
        },
    )

    androidx.compose.runtime.CompositionLocalProvider(
        LocalMessageMediaRefresher provides { localKey: String, assetId: String ->
            viewModel.refreshAssetUrl(localKey, assetId)
        },
    ) {
    Box(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            ConversationTopBar(
                title = state.title,
                onBack = onBack,
                pinned = state.pinned,
                group = state.group,
                modifier = Modifier.clearInputFocusOnPointerDown(focusManager),
                onSearch = { onOpenSearch(conversationId) },
                onOpenInfo = { onOpenInfo(conversationId) },
                onClearMessages = { clearConfirmOpen = true },
                onTogglePinned = viewModel::togglePinned,
            )

            ConversationBody(
                state = state,
                searchQuery = "",
                targetLocalKey = targetLocalKey,
                onRefresh = viewModel::refresh,
                onRetry = viewModel::retry,
                onRetryTranscript = viewModel::retryVoiceTranscript,
                onOpenInspection = onOpenInspection,
                onOpenUserDetail = onOpenUserDetail,
                onAcceptCall = viewModel::acceptVideoCall,
                onStartVideoCall = startVideoCall,
                onOpenImage = { bubble ->
                    imagePreview = bubble.toImageMessagePreview()
                },
                inputFocused = inputFocused,
                favoriteMessageKeys = favoriteMessageKeys,
                selectedMessageKeys = selectedMessageKeys,
                multiSelectMode = multiSelectMode,
                onToggleSelected = ::toggleMessageSelection,
                onQuickAction = ::handleMessageAction,
                modifier = Modifier.weight(1f),
            )

            if (multiSelectMode) {
                MessageMultiSelectBar(
                    selectedCount = selectedMessageKeys.size,
                    onCancel = ::exitMultiSelect,
                    onCopy = {
                        copyMessages(selectedMessages())
                        exitMultiSelect()
                    },
                    onForward = {
                        forwardingMessages = selectedMessages()
                    },
                )
            } else {
                MessageComposerBar(
                    draft = draft,
                    enabled = true,
                    onDraftChange = { draft = it },
                    onShareInspection = {
                        focusManager.clearFocus()
                        inspectionPickerOpen = true
                    },
                    onPickImage = openImagePicker,
                    onTakePhoto = startPhotoCapture,
                    onStartVideoCall = { startVideoCall(null) },
                    onStartVoice = startVoiceRecording,
                    onSendVoice = sendVoiceRecording,
                    onCancelVoice = cancelVoiceRecording,
                    onTranscribeVoice = transcribeVoiceRecording,
                    onSendVideoClip = openVideoPicker,
                    voiceRecording = voiceRecording,
                    quoteDraft = quoteDraft,
                    onClearQuote = { quoteDraft = null },
                    onInputFocusChanged = { inputFocused = it },
                    onSendText = sendDraft,
                    mentionCandidates = mentionCandidates,
                    onPickMention = { candidate ->
                        draft = applyMentionPick(draft, candidate)
                        if (pendingMentions.none { it.userId == candidate.userId }) {
                            pendingMentions = pendingMentions + MentionRef(candidate.userId, candidate.name)
                        }
                    },
                )
            }
        }
        InspectionSharePicker(
            visible = inspectionPickerOpen,
            onDismiss = { inspectionPickerOpen = false },
            onSelect = { card ->
                inspectionPickerOpen = false
                viewModel.sendInspectionCard(card)
            },
        )
        FloatingMessageError(
            text = state.errorMessage.takeUnless { inspectionPickerOpen },
            onClick = viewModel::refresh,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 96.dp),
        )
        VoiceTranscriptionOverlay(
            draft = voiceTranscriptionDraft,
            onCancel = { voiceTranscriptionDraft = null },
            onSendVoice = { draft ->
                voiceTranscriptionDraft = null
                viewModel.sendVoice(draft.uri, draft.durationSec)
            },
            onSendText = { draft ->
                val text = draft.text.trim()
                if (text.isNotEmpty()) {
                    voiceTranscriptionDraft = null
                    viewModel.send(text, quoteDraft?.quote)
                    quoteDraft = null
                }
            },
        )
        ImageMessageViewer(
            preview = imagePreview,
            onDismiss = { imagePreview = null },
        )
    }
    } // CompositionLocalProvider close
}

@Composable
private fun ConversationTopBar(
    title: String,
    pinned: Boolean,
    group: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onOpenInfo: () -> Unit,
    onClearMessages: () -> Unit,
    onTogglePinned: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Column(modifier.fixedDuringPageDrag().fillMaxWidth().background(Gomob.colors.bg0)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.headerHeight),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .size(Gomob.spacing.touchMin)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = GomobIcons.ChevronLeft,
                    contentDescription = "返回",
                    modifier = Modifier.size(26.dp),
                    tint = Gomob.colors.fg1,
                )
            }
            Text(
                title,
                style = Gomob.type.title,
                color = Gomob.colors.fg0,
                maxLines = 1,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = 72.dp),
            )
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(Gomob.spacing.touchMin),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(Gomob.spacing.touchMin)
                        .clickable {
                            if (group) {
                                onOpenInfo()
                            } else {
                                menuOpen = true
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreHoriz,
                        contentDescription = "聊天设置",
                        tint = Gomob.colors.fg1,
                        modifier = Modifier.size(24.dp),
                    )
                }
                ConversationOverflowMenu(
                    expanded = menuOpen,
                    pinned = pinned,
                    onDismiss = { menuOpen = false },
                    onSearch = {
                        menuOpen = false
                        onSearch()
                    },
                    onTogglePinned = {
                        menuOpen = false
                        onTogglePinned()
                    },
                    onClearMessages = {
                        menuOpen = false
                        onClearMessages()
                    },
                )
            }
        }
    }
}

@Composable
private fun ConversationOverflowMenu(
    expanded: Boolean,
    pinned: Boolean,
    onDismiss: () -> Unit,
    onSearch: () -> Unit,
    onTogglePinned: () -> Unit,
    onClearMessages: () -> Unit,
) {
    if (!expanded) return

    val offset = with(LocalDensity.current) {
        IntOffset(
            x = (-10).dp.roundToPx(),
            y = (Gomob.spacing.touchMin - 2.dp).roundToPx(),
        )
    }
    Popup(
        alignment = Alignment.TopEnd,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(
            Modifier
                .width(156.dp)
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .padding(vertical = Gomob.spacing.s4),
        ) {
            ConversationMenuItem(
                icon = GomobIcons.Search,
                label = "查找聊天记录",
                onClick = onSearch,
            )
            ConversationMenuItem(
                icon = GomobIcons.Pin,
                label = if (pinned) "取消置顶" else "置顶聊天",
                active = pinned,
                onClick = onTogglePinned,
            )
            ConversationMenuItem(
                icon = GomobIcons.Trash,
                label = "清空聊天记录",
                danger = true,
                onClick = onClearMessages,
            )
        }
    }
}

@Composable
private fun ConversationMenuItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    active: Boolean = false,
    danger: Boolean = false,
) {
    val tint = when {
        danger -> Gomob.colors.danger
        active -> Gomob.colors.accent
        else -> Gomob.colors.fg1
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = label,
            style = Gomob.type.bodySm,
            color = tint,
            maxLines = 1,
        )
    }
}

@Composable
private fun ConversationBody(
    state: ConversationUiState,
    searchQuery: String,
    targetLocalKey: String?,
    onRefresh: () -> Unit,
    onRetry: (String?) -> Unit,
    onRetryTranscript: (Long?) -> Unit,
    onOpenInspection: (String) -> Unit,
    onOpenUserDetail: (String) -> Unit,
    onAcceptCall: (CallInviteUi) -> Unit,
    onStartVideoCall: (String?) -> Unit,
    onOpenImage: (MessageBubbleUi) -> Unit,
    inputFocused: Boolean,
    favoriteMessageKeys: List<String>,
    selectedMessageKeys: List<String>,
    multiSelectMode: Boolean,
    onToggleSelected: (String) -> Unit,
    onQuickAction: (MessageQuickAction, MessageBubbleUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    val normalizedQuery = searchQuery.trim()
    val visibleMessages = remember(state.messages, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            state.messages
        } else {
            state.messages.filter { it.text.contains(normalizedQuery, ignoreCase = true) }
        }
    }
    val reverseMessages = normalizedQuery.isBlank()
    val timelineItems = remember(visibleMessages) { buildChatTimeline(visibleMessages) }
    val displayItems = remember(timelineItems, reverseMessages) {
        if (reverseMessages) timelineItems.asReversed() else timelineItems
    }
    val targetItemCount = conversationListItemCount(state, visibleMessages, displayItems, normalizedQuery)
    val latestMessage = visibleMessages.lastOrNull()
    var lastObservedLatestMessageKey by remember { mutableStateOf<String?>(null) }
    var hasObservedInitialLatestMessage by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(density)

    LaunchedEffect(
        inputFocused,
        imeBottom,
        latestMessage?.timelineStableKey(),
        latestMessage?.mine,
        state.loading,
        state.errorMessage,
        normalizedQuery,
        targetLocalKey,
    ) {
        if (!targetLocalKey.isNullOrBlank()) {
            return@LaunchedEffect
        }
        val latestKey = latestMessage?.timelineStableKey()
        val latestChanged = latestKey != null && latestKey != lastObservedLatestMessageKey
        val newOwnMessage = latestChanged && hasObservedInitialLatestMessage && latestMessage?.mine == true
        if (normalizedQuery.isBlank()) {
            if (targetItemCount > 0) {
                val visibleIndexes = listState.layoutInfo.visibleItemsInfo.map { it.index }
                val alreadyNearLatest = if (reverseMessages) {
                    visibleIndexes.any { it <= 1 }
                } else {
                    (visibleIndexes.maxOrNull() ?: -1) >= targetItemCount - 2
                }
                if (inputFocused || newOwnMessage || alreadyNearLatest) {
                    listState.animateScrollToItem(if (reverseMessages) 0 else targetItemCount - 1)
                }
            }
            if (latestKey != null) {
                lastObservedLatestMessageKey = latestKey
                hasObservedInitialLatestMessage = true
            }
        }
    }

    LaunchedEffect(targetLocalKey, displayItems, state.loading) {
        val target = targetLocalKey?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val targetIndex = displayItems.indexOfFirst { item ->
            item is ChatTimelineItem.Message && item.bubble.localKey == target
        }
        if (targetIndex >= 0) {
            listState.scrollToItem(targetIndex)
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .clearInputFocusOnPointerDown(focusManager),
    ) {
        StarfieldBackground(Modifier.matchParentSize())
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(
                horizontal = Gomob.spacing.s16,
                vertical = Gomob.spacing.s12,
            ),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            reverseLayout = reverseMessages,
        ) {
            when {
                state.empty -> Unit
                state.errorMessage != null && state.messages.isEmpty() -> Unit
                normalizedQuery.isNotBlank() && visibleMessages.isEmpty() -> item {
                    InlineStatus(text = "未找到相关消息", tone = StatusTone.Neutral, onClick = null)
                }
                else -> items(displayItems, key = { it.key }) { item ->
                    when (item) {
                        is ChatTimelineItem.TimeDivider -> ChatTimeDivider(item.label)
                        is ChatTimelineItem.Message -> {
                            val bubble = item.bubble
                            ChatMessageRow(
                                bubble = bubble,
                                favorite = bubble.localKey in favoriteMessageKeys,
                                selected = bubble.localKey in selectedMessageKeys,
                                multiSelectMode = multiSelectMode,
                                onToggleSelected = { onToggleSelected(bubble.localKey) },
                                onQuickAction = onQuickAction,
                                onOpenInspection = onOpenInspection,
                                onOpenUserDetail = onOpenUserDetail,
                                onStartVideoCall = { title ->
                                    focusManager.clearFocus()
                                    onStartVideoCall(title)
                                },
                                onOpenImage = { imageBubble ->
                                    focusManager.clearFocus()
                                    onOpenImage(imageBubble)
                                },
                                onAcceptCall = {
                                    focusManager.clearFocus()
                                    onAcceptCall(it)
                                },
                                onRetry = {
                                    focusManager.clearFocus()
                                    onRetry(bubble.clientMsgId)
                                },
                                onRetryTranscript = {
                                    focusManager.clearFocus()
                                    onRetryTranscript(bubble.serverId)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun conversationListItemCount(
    state: ConversationUiState,
    visibleMessages: List<MessageBubbleUi>,
    displayItems: List<ChatTimelineItem>,
    normalizedQuery: String,
): Int = when {
    normalizedQuery.isNotBlank() && visibleMessages.isEmpty() -> 1
    state.empty -> 0
    state.errorMessage != null && state.messages.isEmpty() -> 0
    else -> displayItems.size
}

@Composable
internal fun StarfieldBackground(modifier: Modifier = Modifier) {
    val colors = Gomob.colors
    val top = if (colors.isLight) Color(0xFFEFF5FF) else Color(0xFF07101F)
    val bottom = if (colors.isLight) Color(0xFFF8FBFF) else Color(0xFF03070E)
    val haze = if (colors.isLight) Color(0x383AA7D6) else Color(0x2E65C6E4)
    val star = if (colors.isLight) Color(0x8A467EA4) else Color(0xCFEAF8FF)
    val dimStar = if (colors.isLight) Color(0x3A467EA4) else Color(0x62EAF8FF)
    val starPoints = remember {
        listOf(
            StarPoint(0.08f, 0.12f, 1.2f, true),
            StarPoint(0.18f, 0.38f, 0.8f, false),
            StarPoint(0.26f, 0.18f, 1.0f, false),
            StarPoint(0.34f, 0.72f, 1.3f, true),
            StarPoint(0.43f, 0.31f, 0.7f, false),
            StarPoint(0.51f, 0.56f, 1.0f, true),
            StarPoint(0.58f, 0.16f, 0.8f, false),
            StarPoint(0.66f, 0.84f, 1.2f, false),
            StarPoint(0.74f, 0.44f, 1.4f, true),
            StarPoint(0.82f, 0.22f, 0.9f, false),
            StarPoint(0.90f, 0.64f, 1.1f, false),
            StarPoint(0.96f, 0.36f, 0.7f, true),
        )
    }

    Canvas(modifier) {
        drawRect(
            Brush.verticalGradient(
                colors = listOf(top, bottom),
                startY = 0f,
                endY = size.height,
            ),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(haze, Color.Transparent),
                center = Offset(size.width * 0.22f, size.height * 0.18f),
                radius = size.minDimension * 0.75f,
            ),
            radius = size.minDimension * 0.75f,
            center = Offset(size.width * 0.22f, size.height * 0.18f),
        )
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(haze.copy(alpha = haze.alpha * 0.6f), Color.Transparent),
                center = Offset(size.width * 0.82f, size.height * 0.76f),
                radius = size.minDimension * 0.58f,
            ),
            radius = size.minDimension * 0.58f,
            center = Offset(size.width * 0.82f, size.height * 0.76f),
        )
        starPoints.forEach { point ->
            drawCircle(
                color = if (point.bright) star else dimStar,
                radius = point.radius,
                center = Offset(size.width * point.x, size.height * point.y),
            )
        }
    }
}

private data class StarPoint(
    val x: Float,
    val y: Float,
    val radius: Float,
    val bright: Boolean,
)

@Composable
private fun InlineStatus(
    text: String,
    tone: StatusTone,
    onClick: (() -> Unit)?,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(Gomob.spacing.s16),
    ) {
        StatusTag(text = text, tone = tone, showDot = tone != StatusTone.Neutral)
    }
}
