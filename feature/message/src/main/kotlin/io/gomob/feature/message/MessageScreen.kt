package io.gomob.feature.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.decoration.ticks
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.model.message.MessageStatus

const val MESSAGE_ROUTE = "message"

private enum class MsgTab { List, Help }

enum class AvatarKind { System, Call, Video, Image, Voice, Neutral }
enum class WatchTone { Accent, Warn, Danger, Ok, Neutral }

@Composable
fun MessageRoute(
    onOpenConversation: (String) -> Unit = {},
    onOpenLocalVideo: (String) -> Unit = {},
    onOpenExpertDetail: (String) -> Unit = {},
    viewModel: MessageListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val helpState by viewModel.helpUiState.collectAsStateWithLifecycle()
    val helpRoomState by viewModel.helpRoomUiState.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(MsgTab.List) }
    val count = (state as? MessageListUiState.Content)?.conversations?.size ?: 0

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        ScreenHeader(
            title = "消息中心",
            eyebrow = "实时协同 · 监管督查 · 专家会审",
            trailing = { ComposeIconButton() },
        )
        SegmentedTabs(
            tab = tab,
            messageCount = count,
            onChange = { tab = it },
        )
        when (tab) {
            MsgTab.List -> ListPane(
                state = state,
                onRefresh = viewModel::refresh,
                onOpenConversation = { onOpenConversation(it.id.toString()) },
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
                onSendHelpVoice = viewModel::sendHelpRoomVoice,
                onSendHelpVideoClip = viewModel::sendHelpRoomVideoClip,
                onRetry = viewModel::retryHelpRoomMessage,
                onOpenLocalVideo = { onOpenLocalVideo("在线求助 · 第一视角") },
            )
        }
    }
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
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Gomob.spacing.s24),
    ) {
        item { SearchContainer() }
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
}

@Composable
private fun SearchContainer() {
    Box(
        Modifier
            .padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, bottom = 14.dp)
            .fillMaxWidth(),
    ) {
        SearchBar()
    }
}

@Composable
private fun SearchBar() {
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
        Box(Modifier.weight(1f)) {
            if (draft.isEmpty()) {
                Text(
                    "搜索消息 / 联系人 / VIN",
                    fontSize = 12.sp,
                    color = Gomob.colors.fg3,
                )
            }
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = Gomob.colors.fg0),
                cursorBrush = SolidColor(Gomob.colors.accent),
            )
        }
    }
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
    onSendHelpVoice: () -> Unit,
    onSendHelpVideoClip: () -> Unit,
    onRetry: (String?) -> Unit,
    onOpenLocalVideo: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        HelpParticipantStrip(
            state = state,
            onOpenExpertDetail = onOpenExpertDetail,
            onRefresh = onRefresh,
        )
        when (roomState) {
            HelpRoomUiState.Loading -> Box(Modifier.weight(1f)) {
                StateBlock(text = "正在打开在线求助群", tone = StatusTone.Neutral)
            }
            is HelpRoomUiState.Error -> Box(Modifier.weight(1f)) {
                StateBlock(text = roomState.message, tone = StatusTone.Danger, onClick = onRefresh)
            }
            is HelpRoomUiState.Content -> HelpRoomMessageList(
                state = roomState,
                onRefresh = onRefresh,
                onRetry = onRetry,
                modifier = Modifier.weight(1f),
            )
        }
        MessageComposerBar(
            draft = draft,
            enabled = roomState is HelpRoomUiState.Content,
            onDraftChange = { draft = it },
            onSendVoice = onSendHelpVoice,
            onSendVideoClip = onSendHelpVideoClip,
            onOpenLocalVideo = onOpenLocalVideo,
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
private fun HelpParticipantStrip(
    state: HelpExpertsUiState,
    onOpenExpertDetail: (HelpExpertRowUi) -> Unit,
    onRefresh: () -> Unit,
) {
    when (state) {
        HelpExpertsUiState.Loading -> StatusStrip(
            text = "正在加载固定专家",
            tone = StatusTone.Neutral,
            onClick = onRefresh,
        )
        HelpExpertsUiState.Empty -> StatusStrip(
            text = "服务端未配置固定专家",
            tone = StatusTone.Warn,
            onClick = onRefresh,
        )
        is HelpExpertsUiState.Error -> StatusStrip(
            text = state.message,
            tone = StatusTone.Danger,
            onClick = onRefresh,
        )
        is HelpExpertsUiState.Content -> {
            if (state.offlineCached) {
                StatusStrip(
                    text = state.errorMessage ?: "专家列表使用本地缓存",
                    tone = StatusTone.Warn,
                    onClick = onRefresh,
                )
            }
            ExpertParticipantCard(
                experts = state.experts,
                onOpenExpertDetail = onOpenExpertDetail,
                onRefresh = onRefresh,
            )
        }
    }
}

@Composable
private fun HelpRoomMessageList(
    state: HelpRoomUiState.Content,
    onRefresh: () -> Unit,
    onRetry: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = Gomob.spacing.s20,
            vertical = Gomob.spacing.s12,
        ),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
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
                HelpBubbleRow(
                    bubble = bubble,
                    onRetry = { onRetry(bubble.clientMsgId) },
                )
            }
        }
    }
}

@Composable
private fun HelpBubbleRow(
    bubble: MessageBubbleUi,
    onRetry: () -> Unit,
) {
    val alignment = if (bubble.mine) Alignment.End else Alignment.Start
    val bubbleBg = if (bubble.mine) Gomob.colors.accentSoft else Gomob.colors.bg1
    val bubbleLine = when (bubble.status) {
        MessageStatus.Failed -> Gomob.colors.dangerLine
        MessageStatus.Pending -> Gomob.colors.warnLine
        MessageStatus.Sent -> if (bubble.mine) Gomob.colors.accentLine else Gomob.colors.line1
    }
    val textColor = if (bubble.mine) Gomob.colors.accent else Gomob.colors.fg0
    val statusText = when (bubble.status) {
        MessageStatus.Pending -> "发送中"
        MessageStatus.Failed -> "发送失败"
        MessageStatus.Sent -> bubble.time
    }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        if (!bubble.mine && !bubble.senderLabel.isNullOrBlank()) {
            Text(
                bubble.senderLabel,
                style = Gomob.type.numInline.copy(fontSize = 10.sp),
                color = Gomob.colors.fg3,
                modifier = Modifier.padding(start = Gomob.spacing.s4, bottom = Gomob.spacing.s2),
            )
        }
        Box(
            Modifier
                .widthIn(max = 292.dp)
                .clip(Gomob.shapes.r3)
                .background(bubbleBg)
                .border(Gomob.spacing.hairline, bubbleLine, Gomob.shapes.r3)
                .let {
                    if (bubble.status == MessageStatus.Failed) {
                        it.clickable(onClick = onRetry)
                    } else {
                        it
                    }
                }
                .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        ) {
            Text(bubble.text, style = Gomob.type.bodySm, color = textColor)
        }
        Text(
            statusText,
            style = Gomob.type.numInline,
            color = when (bubble.status) {
                MessageStatus.Failed -> Gomob.colors.danger
                MessageStatus.Pending -> Gomob.colors.warn
                MessageStatus.Sent -> Gomob.colors.fg3
            },
            modifier = Modifier.padding(top = Gomob.spacing.s2, start = Gomob.spacing.s4, end = Gomob.spacing.s4),
        )
    }
}

@Composable
private fun ExpertParticipantCard(
    experts: List<HelpExpertRowUi>,
    onOpenExpertDetail: (HelpExpertRowUi) -> Unit,
    onRefresh: () -> Unit,
) {
    Column(
        Modifier.padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20),
    ) {
        Text(
            "固定多人群 · ${experts.size} 位专家",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = Gomob.colors.fg3,
            modifier = Modifier.padding(bottom = Gomob.spacing.s8),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
                .ticks()
                .padding(Gomob.spacing.s14),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                experts.forEach { expert ->
                    ExpertChip(
                        expert = expert,
                        onAvatarClick = { onOpenExpertDetail(expert) },
                    )
                }
                RefreshExpertsBox(onClick = onRefresh)
            }
        }
    }
}

@Composable
private fun ExpertChip(
    expert: HelpExpertRowUi,
    onAvatarClick: () -> Unit,
) {
    Column(
        Modifier.width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clickable(onClick = onAvatarClick),
            contentAlignment = Alignment.BottomEnd,
        ) {
            Box(
                Modifier
                    .size(44.dp)
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
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Gomob.colors.accentStrong,
                )
            }
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (expert.availabilityText == "可发消息") Gomob.colors.ok else Gomob.colors.fg3)
                    .border(2.dp, Gomob.colors.bg1, CircleShape),
            )
        }
        Text(expert.name, fontSize = 11.sp, color = Gomob.colors.fg0, maxLines = 1)
        Text(
            expert.roleTitle,
            fontSize = 9.sp,
            color = Gomob.colors.fg3,
            maxLines = 1,
        )
    }
}

@Composable
private fun RefreshExpertsBox(onClick: () -> Unit) {
    Box(
        Modifier
            .size(44.dp)
            .clip(Gomob.shapes.r2)
            .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            GomobIcons.Refresh,
            contentDescription = "刷新专家",
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(Gomob.spacing.icon16),
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
