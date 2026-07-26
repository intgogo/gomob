package io.gomob.feature.message

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.LocalFeedbackTitleTrigger
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.component.feedbackTitleFiveTap
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.glass.glassChrome
import io.gomob.designsystem.icons.GomobIcons
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
            // 用边界匹配（@name 后必须是空白/标点/结尾），避免短名前缀误命中更长的同前缀名。
            val activeMentions = pendingMentions
                .filter { ref -> text.containsMention(ref.name) }
                .distinctBy { it.userId }
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
    val density = LocalDensity.current
    // 吸底栏实测总高（含导航栏 / 输入法 inset），供消息列表 bottom 预留，最后一条不被压住
    var bottomBarHeightPx by remember { mutableIntStateOf(0) }
    GlassHeaderScaffold(
        // 会话列表 reverseLayout 常驻停靠最新消息，星空壁纸始终从玻璃下穿过 → 不接滚动源，分隔线常显
        header = {
            ConversationTopBar(
                title = state.title,
                eyebrow = state.eyebrow,
                onBack = onBack,
                // TODO(终态): 接入 peer presence 后传真实在线态点亮 ok 圆点
                online = null,
                modifier = Modifier.clearInputFocusOnPointerDown(focusManager),
                onOpenInfo = { onOpenInfo(conversationId) },
            )
        },
        overlay = { _ ->
            // 吸底输入栏 / 多选操作栏：玻璃底；导航栏与输入法 inset 由自身处理
            //（原 imePadding 在外层 Column，迁移后收进吸底栏，键盘弹出时随之上移）
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { bottomBarHeightPx = it.height }
                    .glassChrome(topEdge = true)
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
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
                    .navigationBarsPadding()
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
        },
    ) { padding ->
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
            contentPadding = PaddingValues(
                start = Gomob.spacing.s16,
                end = Gomob.spacing.s16,
                top = padding.calculateTopPadding() + Gomob.spacing.s12,
                // 吸底栏未量到前先按导航栏 inset 兜底；量到后随栏高（含键盘弹起）实时避让
                bottom = with(density) { bottomBarHeightPx.toDp() }
                    .coerceAtLeast(padding.calculateBottomPadding()) + Gomob.spacing.s12,
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
    } // CompositionLocalProvider close
}

@Composable
private fun ConversationTopBar(
    title: String,
    eyebrow: String,
    onBack: () -> Unit,
    onOpenInfo: () -> Unit,
    online: Boolean? = null,
    modifier: Modifier = Modifier,
) {
    val feedbackTrigger = LocalFeedbackTitleTrigger.current

    // 顶栏在 GlassHeaderScaffold header 槽内 → 不画实底，由玻璃层负责
    Column(modifier.fillMaxWidth()) {
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
                    tint = Gomob.colors.accent,
                )
            }
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .then(
                        if (feedbackTrigger != null) {
                            Modifier.feedbackTitleFiveTap(title, feedbackTrigger)
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 72.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
            ) {
                Text(
                    title,
                    style = Gomob.type.title,
                    color = Gomob.colors.fg0,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
                if (eyebrow.isNotBlank()) {
                    // 副标题:在线语义存在时点亮 5dp ok 圆点 + micro 文本
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
                    ) {
                        if (online == true) {
                            Box(
                                Modifier
                                    .size(5.dp)
                                    .clip(CircleShape)
                                    .background(Gomob.colors.ok),
                            )
                        }
                        Text(
                            eyebrow,
                            style = Gomob.type.micro,
                            color = Gomob.colors.fg3,
                            maxLines = 1,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            // 1:1 与群聊统一进聊天设置页(查找/置顶/清空 info 页已有)
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .size(Gomob.spacing.touchMin)
                    .clickable(onClick = onOpenInfo),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreHoriz,
                    contentDescription = "聊天设置",
                    tint = Gomob.colors.fg1,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
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
    contentPadding: PaddingValues,
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
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            // 顶/底避让并进 contentPadding（外部算好传入），消息从玻璃顶栏与吸底栏下穿过
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                                peerName = state.title,
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

/**
 * 判断文本里是否含有对 [name] 的 @ 提及。要求 "@name" 整体出现且其后是分界字符
 * （空白 / 标点 / 字符串结尾），避免短名作为更长同前缀名的子串被误匹配
 * （如 "@李" 不应命中 "@李明"）。
 */
private fun String.containsMention(name: String): Boolean {
    if (name.isEmpty()) return false
    val token = "@$name"
    var from = 0
    while (true) {
        val idx = indexOf(token, from)
        if (idx < 0) return false
        val end = idx + token.length
        val next = if (end < length) this[end] else null
        // 下一个字符不是字母/数字/下划线（即名字本体不会继续延展）即视为命中。
        if (next == null || !(next.isLetterOrDigit() || next == '_')) return true
        from = idx + 1
    }
}
