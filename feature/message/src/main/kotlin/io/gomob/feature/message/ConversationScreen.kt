package io.gomob.feature.message

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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

@Composable
fun ConversationRoute(
    conversationId: String,
    onBack: () -> Unit,
    onOpenLocalVideo: (String) -> Unit = {},
    onOpenVideoCall: (roomId: String, title: String, mode: VideoCallMode) -> Unit = { _, _, _ -> },
    onOpenInspection: (String) -> Unit = {},
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var voiceRecording by remember { mutableStateOf(false) }
    var inspectionPickerOpen by rememberSaveable { mutableStateOf(false) }
    var searchActive by rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var inputFocused by remember { mutableStateOf(false) }
    var clearConfirmOpen by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val voiceRecorder = rememberVoiceRecorder()
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let(viewModel::sendImage) },
    )
    val photoCapture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview(),
        onResult = { bitmap ->
            bitmap?.let { viewModel.sendImage(it.writeMessageCapture(context)) }
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
            viewModel.startVideoCall()
        } else {
            viewModel.showError("需要相机和麦克风权限")
        }
    }
    val openImagePicker: () -> Unit = {
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val openVideoPicker: () -> Unit = {
        videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
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
    val startVideoCall: () -> Unit = {
        val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val granted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (granted) {
            viewModel.startVideoCall()
        } else {
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
            .onSuccess { result -> viewModel.transcribeVoiceToText(result.uri, result.durationSec) }
            .onFailure { viewModel.showError(it.message ?: "录音失败") }
        voiceRecording = false
    }
    val sendDraft = {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            viewModel.send(text)
            draft = ""
        }
    }
    val closeSearch = {
        searchActive = false
        searchQuery = ""
        focusManager.clearFocus()
    }

    BackHandler(enabled = searchActive, onBack = closeSearch)

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
                        closeSearch()
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

    Box(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        Column(Modifier.fillMaxSize()) {
            ConversationTopBar(
                title = state.title,
                onBack = onBack,
                pinned = state.pinned,
                modifier = Modifier.clearInputFocusOnPointerDown(focusManager),
                onSearch = { searchActive = true },
                onClearMessages = { clearConfirmOpen = true },
                onTogglePinned = viewModel::togglePinned,
            )
            if (searchActive) {
                ConversationSearchBar(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onClose = closeSearch,
                )
            }

            ConversationBody(
                state = state,
                searchQuery = if (searchActive) searchQuery else "",
                onRefresh = viewModel::refresh,
                onRetry = viewModel::retry,
                onRetryTranscript = viewModel::retryVoiceTranscript,
                onOpenInspection = onOpenInspection,
                onAcceptCall = viewModel::acceptVideoCall,
                inputFocused = inputFocused,
                modifier = Modifier.weight(1f),
            )

            MessageComposerBar(
                draft = draft,
                enabled = true,
                onDraftChange = { draft = it },
                onShareInspection = {
                    focusManager.clearFocus()
                    inspectionPickerOpen = true
                },
                onPickImage = openImagePicker,
                onTakePhoto = { photoCapture.launch(null) },
                onStartVideoCall = startVideoCall,
                onStartVoice = startVoiceRecording,
                onSendVoice = sendVoiceRecording,
                onCancelVoice = cancelVoiceRecording,
                onTranscribeVoice = transcribeVoiceRecording,
                onSendVideoClip = openVideoPicker,
                voiceRecording = voiceRecording,
                onInputFocusChanged = { inputFocused = it },
                onSendText = sendDraft,
            )
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
    }
}

@Composable
private fun ConversationTopBar(
    title: String,
    pinned: Boolean,
    onBack: () -> Unit,
    onSearch: () -> Unit,
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
                        .clickable { menuOpen = true },
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
private fun ConversationSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Row(
        Modifier
            .fixedDuringPageDrag()
            .fillMaxWidth()
            .background(Gomob.colors.bg0)
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(38.dp)
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .padding(horizontal = Gomob.spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
        ) {
            Icon(
                imageVector = GomobIcons.Search,
                contentDescription = "搜索",
                tint = Gomob.colors.fg3,
                modifier = Modifier.size(Gomob.spacing.icon16),
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = Gomob.type.bodySm.copy(color = Gomob.colors.fg0),
                    cursorBrush = SolidColor(Gomob.colors.accent),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                if (query.isEmpty()) {
                    Text("搜索聊天记录", style = Gomob.type.bodySm, color = Gomob.colors.fg3)
                }
            }
        }
        Text(
            "取消",
            style = Gomob.type.bodySm,
            color = Gomob.colors.fg2,
            modifier = Modifier.clickable(onClick = onClose).padding(Gomob.spacing.s6),
        )
    }
}

@Composable
private fun ConversationBody(
    state: ConversationUiState,
    searchQuery: String,
    onRefresh: () -> Unit,
    onRetry: (String?) -> Unit,
    onRetryTranscript: (Long?) -> Unit,
    onOpenInspection: (String) -> Unit,
    onAcceptCall: (CallInviteUi) -> Unit,
    inputFocused: Boolean,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val normalizedQuery = searchQuery.trim()
    val visibleMessages = remember(state.messages, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            state.messages
        } else {
            state.messages.filter { it.text.contains(normalizedQuery, ignoreCase = true) }
        }
    }
    val targetItemCount = conversationListItemCount(state, visibleMessages, normalizedQuery)

    LaunchedEffect(inputFocused, visibleMessages.size, state.loading, state.errorMessage) {
        if (inputFocused && targetItemCount > 0) {
            listState.animateScrollToItem(targetItemCount - 1)
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
        ) {
            when {
                state.empty -> Unit
                state.errorMessage != null && state.messages.isEmpty() -> Unit
                normalizedQuery.isNotBlank() && visibleMessages.isEmpty() -> item {
                    InlineStatus(text = "未找到相关消息", tone = StatusTone.Neutral, onClick = null)
                }
                else -> items(visibleMessages, key = { it.localKey }) { bubble ->
                    ChatMessageRow(
                        bubble = bubble,
                        onOpenInspection = onOpenInspection,
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

private fun conversationListItemCount(
    state: ConversationUiState,
    visibleMessages: List<MessageBubbleUi>,
    normalizedQuery: String,
): Int = when {
    normalizedQuery.isNotBlank() && visibleMessages.isEmpty() -> 1
    state.empty -> 0
    state.errorMessage != null && state.messages.isEmpty() -> 0
    else -> visibleMessages.size
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
