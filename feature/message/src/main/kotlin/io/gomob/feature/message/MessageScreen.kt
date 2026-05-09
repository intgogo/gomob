package io.gomob.feature.message

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

const val MESSAGE_ROUTE = "message"

enum class MessageEntryTab { List, Help }

private enum class MsgTab { List, Help }

enum class AvatarKind { System, Call, Video, Image, Voice, Neutral }
enum class WatchTone { Accent, Warn, Danger, Ok, Neutral }

@Composable
fun MessageRoute(
    onOpenConversation: (String) -> Unit = {},
    onOpenLocalVideo: (String) -> Unit = {},
    onOpenExpertDetail: (String) -> Unit = {},
    requestedTab: MessageEntryTab? = null,
    onRequestedTabConsumed: () -> Unit = {},
    viewModel: MessageListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val helpState by viewModel.helpUiState.collectAsStateWithLifecycle()
    val helpRoomState by viewModel.helpRoomUiState.collectAsStateWithLifecycle()
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let(viewModel::sendHelpRoomImage) },
    )
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let(viewModel::sendHelpRoomVideoClip) },
    )
    val focusManager = LocalFocusManager.current
    var tab by rememberSaveable { mutableStateOf((requestedTab ?: MessageEntryTab.List).toMsgTab()) }
    val count = (state as? MessageListUiState.Content)?.conversations?.size ?: 0

    LaunchedEffect(requestedTab) {
        requestedTab?.let {
            tab = it.toMsgTab()
            onRequestedTabConsumed()
        }
    }

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        ScreenHeader(
            title = "消息中心",
            eyebrow = "实时协同 · 监管督查 · 专家会审",
            modifier = Modifier.clearInputFocusOnPointerDown(focusManager),
            trailing = { ComposeIconButton() },
        )
        SegmentedTabs(
            tab = tab,
            messageCount = count,
            onChange = {
                focusManager.clearFocus()
                tab = it
            },
        )
        when (tab) {
            MsgTab.List -> ListPane(
                state = state,
                onRefresh = viewModel::refresh,
                onOpenConversation = { onOpenConversation(it.id.toString()) },
                modifier = Modifier.weight(1f),
            )
            MsgTab.Help -> HelpPane(
                state = helpState,
                roomState = helpRoomState,
                onRefresh = {
                    viewModel.refreshHelpExperts()
                    viewModel.refreshHelpRoom()
                },
                onOpenExpertDetail = { expert -> onOpenExpertDetail(expert.userId.toString()) },
                onSendHelpMessage = viewModel::sendHelpRoomMessage,
                onPickHelpImage = {
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onSendHelpVoice = viewModel::sendHelpRoomVoice,
                onSendHelpVideoClip = {
                    videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                },
                onRetry = viewModel::retryHelpRoomMessage,
                onError = viewModel::showHelpRoomError,
                onOpenLocalVideo = { onOpenLocalVideo("在线求助 · 第一视角") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun MessageEntryTab.toMsgTab(): MsgTab = when (this) {
    MessageEntryTab.List -> MsgTab.List
    MessageEntryTab.Help -> MsgTab.Help
}

@Composable
private fun ComposeIconButton() {
    Box(
        Modifier.size(Gomob.spacing.touchMin).clickable {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            GomobIcons.Compose,
            contentDescription = "新消息",
            tint = Gomob.colors.fg2,
            modifier = Modifier.size(Gomob.spacing.icon20),
        )
    }
}

@Composable
private fun SegmentedTabs(
    tab: MsgTab,
    messageCount: Int,
    onChange: (MsgTab) -> Unit,
) {
    Row(
        Modifier
            .padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, bottom = 14.dp)
            .fillMaxWidth()
            .clip(Gomob.shapes.r2)
            .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2),
    ) {
        SegItem(
            modifier = Modifier.weight(1f),
            active = tab == MsgTab.List,
            onClick = { onChange(MsgTab.List) },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
            ) {
                Text(
                    "消息列表",
                    fontSize = 12.sp,
                    color = if (tab == MsgTab.List) Gomob.colors.accent else Gomob.colors.fg2,
                )
                Text(
                    messageCount.toString(),
                    style = Gomob.type.numInline.copy(fontSize = 12.sp),
                    color = if (tab == MsgTab.List)
                        Gomob.colors.accent.copy(alpha = 0.7f)
                    else
                        Gomob.colors.fg2.copy(alpha = 0.7f),
                )
            }
        }
        SegItem(
            modifier = Modifier.weight(1f),
            active = tab == MsgTab.Help,
            onClick = { onChange(MsgTab.Help) },
        ) {
            Text(
                "在线求助",
                fontSize = 12.sp,
                color = if (tab == MsgTab.Help) Gomob.colors.accent else Gomob.colors.fg2,
            )
        }
    }
}

@Composable
private fun SegItem(
    modifier: Modifier = Modifier,
    active: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier
            .height(36.dp)
            .background(if (active) Gomob.colors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun ListPane(
    state: MessageListUiState,
    onRefresh: () -> Unit,
    onOpenConversation: (ConversationRowUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchActive by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val exitSearch = remember(focusManager, keyboardController) {
        {
            searchActive = false
            focusManager.clearFocus()
            keyboardController?.hide()
            Unit
        }
    }
    BackHandler(enabled = searchActive, onBack = exitSearch)

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Gomob.spacing.s24),
        ) {
            item { SearchContainer(onActiveChange = { searchActive = it }) }
            when (state) {
                MessageListUiState.Loading -> item {
                    StateBlock(text = "正在加载会话", tone = StatusTone.Neutral)
                }
                MessageListUiState.Empty -> item {
                    StateBlock(text = "暂无会话", tone = StatusTone.Neutral)
                }
                is MessageListUiState.Error -> item {
                    StateBlock(text = state.message, tone = StatusTone.Danger, onClick = onRefresh)
                }
                is MessageListUiState.Content -> {
                    if (state.offlineCached) {
                        item {
                            StatusStrip(
                                text = state.errorMessage ?: "未连接实时通道",
                                tone = StatusTone.Warn,
                                onClick = onRefresh,
                            )
                        }
                    }
                    items(state.conversations, key = { it.id }) { item ->
                        Box(
                            Modifier
                                .padding(horizontal = Gomob.spacing.s20)
                                .padding(bottom = Gomob.spacing.s8),
                        ) {
                            MsgRow(item, onClick = { onOpenConversation(item) })
                        }
                    }
                }
            }
        }
        SearchInputScrim(
            visible = searchActive,
            onDismiss = exitSearch,
            modifier = Modifier.padding(top = 50.dp),
        )
    }
}

@Composable
private fun SearchContainer(onActiveChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, bottom = 14.dp)
            .fillMaxWidth(),
    ) {
        SearchBar(onActiveChange = onActiveChange)
    }
}

@Composable
private fun SearchBar(onActiveChange: (Boolean) -> Unit) {
    var draft by remember { mutableStateOf("") }
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg2)
            .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r2)
            .padding(horizontal = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Icon(
            GomobIcons.Search,
            contentDescription = null,
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(14.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onActiveChange(it.isFocused) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, color = Gomob.colors.fg0),
                cursorBrush = SolidColor(Gomob.colors.accent),
                decorationBox = { innerTextField ->
                    Box(
                        Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (draft.isEmpty()) {
                            Text(
                                "搜索消息 / 联系人 / VIN",
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = Gomob.colors.fg3,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

@Composable
private fun SearchInputScrim(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.1f))
            .clickable(onClick = onDismiss),
    )
}

@Composable
private fun MsgRow(item: ConversationRowUi, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        MsgAvatar(initials = item.initials, kind = item.avatarKind)
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Gomob.colors.fg0,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    item.time,
                    style = Gomob.type.numInline.copy(fontSize = 10.sp),
                    color = Gomob.colors.fg3,
                    modifier = Modifier.padding(start = Gomob.spacing.s8),
                )
            }
            Spacer(Modifier.height(Gomob.spacing.s4))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.preview,
                    fontSize = 12.sp,
                    color = Gomob.colors.fg2,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (item.unreadCount > 0) {
                    UnreadBadge(item.unreadCount, item.unreadTone)
                }
            }
        }
    }
}

@Composable
private fun MsgAvatar(initials: String, kind: AvatarKind) {
    val tone = when (kind) {
        AvatarKind.System -> Gomob.colors.accent
        AvatarKind.Call -> Gomob.colors.ok
        AvatarKind.Video -> Gomob.colors.warn
        AvatarKind.Image -> Gomob.colors.danger
        AvatarKind.Voice -> Gomob.colors.accent
        AvatarKind.Neutral -> Gomob.colors.fg1
    }
    val borderTone = if (kind == AvatarKind.Neutral) Gomob.colors.line2 else tone
    Box(
        Modifier
            .size(38.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg3)
            .border(Gomob.spacing.hairline, borderTone, Gomob.shapes.r2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = tone,
        )
    }
}

@Composable
private fun UnreadBadge(unread: Long, tone: WatchTone) {
    val color = when (tone) {
        WatchTone.Danger -> Gomob.colors.danger
        WatchTone.Warn -> Gomob.colors.warn
        WatchTone.Accent -> Gomob.colors.accent
        WatchTone.Ok -> Gomob.colors.ok
        WatchTone.Neutral -> Gomob.colors.fg2
    }
    Box(
        Modifier
            .padding(start = Gomob.spacing.s8)
            .height(22.dp)
            .widthIn(min = 22.dp)
            .clip(Gomob.shapes.pill)
            .background(color)
            .padding(horizontal = Gomob.spacing.s6),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            unread.coerceAtMost(99).toString(),
            fontSize = 11.sp,
            lineHeight = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            color = Color.Black,
        )
    }
}

@Composable
private fun HelpPane(
    state: HelpExpertsUiState,
    roomState: HelpRoomUiState,
    onRefresh: () -> Unit,
    onOpenExpertDetail: (HelpExpertRowUi) -> Unit,
    onSendHelpMessage: (String) -> Unit,
    onPickHelpImage: () -> Unit,
    onSendHelpVoice: (android.net.Uri, Int) -> Unit,
    onSendHelpVideoClip: () -> Unit,
    onRetry: (String?) -> Unit,
    onError: (String) -> Unit,
    onOpenLocalVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    var inputFocused by remember { mutableStateOf(false) }
    var voiceRecording by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val voiceRecorder = rememberVoiceRecorder()
    val voicePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            runCatching {
                voiceRecorder.start()
                voiceRecording = true
            }.onFailure { onError(it.message ?: "录音启动失败") }
        } else {
            onError("未授予录音权限")
        }
    }
    val toggleVoiceRecording: () -> Unit = {
        if (voiceRecording) {
            runCatching { voiceRecorder.stop() }
                .onSuccess { result -> onSendHelpVoice(result.uri, result.durationSec) }
                .onFailure { onError(it.message ?: "录音失败") }
            voiceRecording = false
        } else if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                voiceRecorder.start()
                voiceRecording = true
            }.onFailure { onError(it.message ?: "录音启动失败") }
        } else {
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(modifier.fillMaxSize()) {
        when (roomState) {
            HelpRoomUiState.Loading -> LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clearInputFocusOnPointerDown(focusManager),
                contentPadding = PaddingValues(
                    horizontal = Gomob.spacing.s20,
                    vertical = Gomob.spacing.s8,
                ),
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                item {
                    HelpParticipantCompactHeader(
                        state = state,
                        onOpenExpertDetail = onOpenExpertDetail,
                        onRefresh = onRefresh,
                    )
                }
                item { HelpInlineStatus(text = "正在打开在线求助群", tone = StatusTone.Neutral, onClick = null) }
            }
            is HelpRoomUiState.Error -> LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clearInputFocusOnPointerDown(focusManager),
                contentPadding = PaddingValues(
                    horizontal = Gomob.spacing.s20,
                    vertical = Gomob.spacing.s8,
                ),
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                item {
                    HelpParticipantCompactHeader(
                        state = state,
                        onOpenExpertDetail = onOpenExpertDetail,
                        onRefresh = onRefresh,
                    )
                }
                item { HelpInlineStatus(text = roomState.message, tone = StatusTone.Danger, onClick = onRefresh) }
            }
            is HelpRoomUiState.Content -> HelpRoomMessageList(
                state = roomState,
                expertState = state,
                onRefresh = onRefresh,
                onOpenExpertDetail = onOpenExpertDetail,
                onRetry = onRetry,
                inputFocused = inputFocused,
                modifier = Modifier.weight(1f),
            )
        }
        MessageComposerBar(
            draft = draft,
            enabled = roomState is HelpRoomUiState.Content,
            onDraftChange = { draft = it },
            onPickImage = onPickHelpImage,
            onTakePhoto = onPickHelpImage,
            onSendVoice = toggleVoiceRecording,
            onSendVideoClip = onSendHelpVideoClip,
            onOpenLocalVideo = onOpenLocalVideo,
            voiceRecording = voiceRecording,
            onInputFocusChanged = { inputFocused = it },
            onSendText = {
                val text = draft.trim()
                if (text.isNotEmpty()) {
                    onSendHelpMessage(text)
                    draft = ""
                }
            },
        )
    }
}

@Composable
private fun HelpParticipantCompactHeader(
    state: HelpExpertsUiState,
    onOpenExpertDetail: (HelpExpertRowUi) -> Unit,
    onRefresh: () -> Unit,
) {
    when (state) {
        HelpExpertsUiState.Loading -> HelpInlineStatus(
            text = "正在加载固定专家",
            tone = StatusTone.Neutral,
            onClick = onRefresh,
        )
        HelpExpertsUiState.Empty -> HelpInlineStatus(
            text = "服务端未配置固定专家",
            tone = StatusTone.Warn,
            onClick = onRefresh,
        )
        is HelpExpertsUiState.Error -> HelpInlineStatus(
            text = state.message,
            tone = StatusTone.Danger,
            onClick = onRefresh,
        )
        is HelpExpertsUiState.Content -> {
            Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
                if (state.offlineCached) {
                    HelpInlineStatus(
                        text = state.errorMessage ?: "专家列表使用本地缓存",
                        tone = StatusTone.Warn,
                        onClick = onRefresh,
                    )
                }
                ExpertParticipantCompactBar(
                    experts = state.experts,
                    onOpenExpertDetail = onOpenExpertDetail,
                    onRefresh = onRefresh,
                )
            }
        }
    }
}

@Composable
private fun HelpRoomMessageList(
    state: HelpRoomUiState.Content,
    expertState: HelpExpertsUiState,
    onRefresh: () -> Unit,
    onOpenExpertDetail: (HelpExpertRowUi) -> Unit,
    onRetry: (String?) -> Unit,
    inputFocused: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val targetItemCount = helpRoomListItemCount(state)
    LaunchedEffect(inputFocused, state.messages.size, state.loading, state.errorMessage, state.offlineCached) {
        if (inputFocused && targetItemCount > 0) {
            listState.animateScrollToItem(targetItemCount - 1)
        }
    }

    LazyColumn(
        modifier
            .fillMaxWidth()
            .clearInputFocusOnPointerDown(focusManager),
        state = listState,
        contentPadding = PaddingValues(
            horizontal = Gomob.spacing.s20,
            vertical = Gomob.spacing.s8,
        ),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        item {
            HelpParticipantCompactHeader(
                state = expertState,
                onOpenExpertDetail = onOpenExpertDetail,
                onRefresh = onRefresh,
            )
        }
        if (state.offlineCached) {
            item {
                HelpInlineStatus(
                    text = state.errorMessage ?: "在线求助群使用本地缓存",
                    tone = StatusTone.Warn,
                    onClick = onRefresh,
                )
            }
        }
        when {
            state.loading -> item {
                HelpInlineStatus(text = "正在加载在线求助消息", tone = StatusTone.Neutral, onClick = null)
            }
            state.errorMessage != null && state.messages.isEmpty() -> item {
                HelpInlineStatus(text = state.errorMessage, tone = StatusTone.Danger, onClick = onRefresh)
            }
            state.empty -> item {
                HelpInlineStatus(text = "暂无消息", tone = StatusTone.Neutral, onClick = null)
            }
            else -> items(state.messages, key = { it.localKey }) { bubble ->
                ChatMessageRow(
                    bubble = bubble,
                    onRetry = { onRetry(bubble.clientMsgId) },
                )
            }
        }
    }
}

private fun helpRoomListItemCount(state: HelpRoomUiState.Content): Int {
    val statusCount = if (state.offlineCached) 1 else 0
    val bodyCount = when {
        state.loading -> 1
        state.errorMessage != null && state.messages.isEmpty() -> 1
        state.empty -> 1
        else -> state.messages.size
    }
    return 1 + statusCount + bodyCount
}

@Composable
private fun ExpertParticipantCompactBar(
    experts: List<HelpExpertRowUi>,
    onOpenExpertDetail: (HelpExpertRowUi) -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1)
            .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r2)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
            Text("在线求助", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
            Text("${experts.size} 位固定专家", style = Gomob.type.numInline, color = Gomob.colors.fg3)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
        ) {
            experts.take(4).forEach { expert ->
                ExpertCompactAvatar(
                    expert = expert,
                    onAvatarClick = { onOpenExpertDetail(expert) },
                )
            }
            if (experts.size > 4) {
                Text(
                    "+${experts.size - 4}",
                    style = Gomob.type.numInline,
                    color = Gomob.colors.fg3,
                    modifier = Modifier.padding(horizontal = Gomob.spacing.s2),
                )
            }
            RefreshExpertsBox(onClick = onRefresh)
        }
    }
}

@Composable
private fun ExpertCompactAvatar(
    expert: HelpExpertRowUi,
    onAvatarClick: () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .clickable(onClick = onAvatarClick),
        contentAlignment = Alignment.BottomEnd,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .align(Alignment.Center)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.accentSoft)
                .border(
                    Gomob.spacing.hairline,
                    Gomob.colors.accentLine,
                    Gomob.shapes.r2,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                expert.initials,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Gomob.colors.accentStrong,
            )
        }
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (expert.availabilityText == "可发消息") Gomob.colors.ok else Gomob.colors.fg3)
                .border(1.dp, Gomob.colors.bg1, CircleShape),
        )
    }
}

@Composable
private fun RefreshExpertsBox(onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(Gomob.shapes.r2)
            .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            GomobIcons.Refresh,
            contentDescription = "刷新专家",
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(14.dp),
        )
    }
}

@Composable
private fun StateBlock(
    text: String,
    tone: StatusTone,
    onClick: (() -> Unit)? = null,
) {
    Box(
        Modifier
            .padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s12)
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(Gomob.spacing.s16),
    ) {
        StatusTag(text = text, tone = tone, showDot = tone != StatusTone.Neutral)
    }
}

@Composable
private fun HelpInlineStatus(
    text: String,
    tone: StatusTone,
    onClick: (() -> Unit)?,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(Gomob.spacing.s16),
    ) {
        StatusTag(text = text, tone = tone, showDot = tone != StatusTone.Neutral)
    }
}

@Composable
private fun StatusStrip(
    text: String,
    tone: StatusTone,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .padding(horizontal = Gomob.spacing.s20)
            .padding(bottom = Gomob.spacing.s8)
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        StatusTag(text = text, tone = tone, showDot = true)
    }
}
