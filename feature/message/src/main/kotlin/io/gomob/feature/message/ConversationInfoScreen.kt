package io.gomob.feature.message

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ManageAccounts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.auth.TokenStore
import io.gomob.data.message.ConversationInfoRepository
import io.gomob.data.message.ConversationInfoSettings
import io.gomob.data.message.ConversationInfoStoredMember
import io.gomob.data.message.MessageRepository
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.glass.glassPanelBg
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.model.message.ConversationSummary
import io.gomob.model.message.MessageRecord
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.URLEncoder
import javax.inject.Inject

@Composable
fun ConversationInfoRoute(
    onBack: () -> Unit,
    onOpenSearch: (String) -> Unit,
    onOpenUserDetail: (String) -> Unit,
    onLeaveCompleted: () -> Unit,
    viewModel: ConversationInfoViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var clearConfirmOpen by rememberSaveable { mutableStateOf(false) }
    var leaveConfirmOpen by rememberSaveable { mutableStateOf(false) }
    var editTarget by rememberSaveable { mutableStateOf<ConversationInfoEditTarget?>(null) }
    var qrOpen by rememberSaveable { mutableStateOf(false) }
    var memberDialog by rememberSaveable { mutableStateOf<ConversationInfoMemberDialog?>(null) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                ConversationInfoEvent.LeftConversation -> onLeaveCompleted()
                is ConversationInfoEvent.Toast -> context.showMessageActionToast(event.text)
            }
        }
    }

    if (clearConfirmOpen) {
        AlertDialog(
            onDismissRequest = { clearConfirmOpen = false },
            containerColor = Gomob.colors.bg2.copy(alpha = 0.97f),
            shape = Gomob.shapes.r3,
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

    if (leaveConfirmOpen) {
        AlertDialog(
            onDismissRequest = { leaveConfirmOpen = false },
            containerColor = Gomob.colors.bg2.copy(alpha = 0.97f),
            shape = Gomob.shapes.r3,
            title = { Text("退出群聊", style = Gomob.type.title, color = Gomob.colors.fg0) },
            text = { Text("退出后将不再接收该群聊消息。", style = Gomob.type.bodySm, color = Gomob.colors.fg2) },
            confirmButton = {
                TextButton(
                    onClick = {
                        leaveConfirmOpen = false
                        viewModel.leaveConversation()
                    },
                ) {
                    Text("退出", color = Gomob.colors.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { leaveConfirmOpen = false }) {
                    Text("取消", color = Gomob.colors.fg2)
                }
            },
        )
    }

    editTarget?.let { target ->
        ConversationInfoEditDialog(
            target = target,
            state = state,
            onDismiss = { editTarget = null },
            onSave = { value ->
                editTarget = null
                when (target) {
                    ConversationInfoEditTarget.Name -> viewModel.updateDisplayName(value)
                    ConversationInfoEditTarget.Announcement -> viewModel.updateAnnouncement(value)
                    ConversationInfoEditTarget.Remark -> viewModel.updateRemark(value)
                }
            },
        )
    }

    if (qrOpen) {
        ConversationQrDialog(
            state = state,
            onDismiss = { qrOpen = false },
            onCopy = {
                clipboard.setText(AnnotatedString(state.qrPayload))
                context.showMessageActionToast("已复制群聊邀请信息")
            },
        )
    }

    memberDialog?.let { dialog ->
        ConversationMemberDialog(
            dialog = dialog,
            state = state,
            onDismiss = { memberDialog = null },
            onOpenUserDetail = { id ->
                memberDialog = null
                onOpenUserDetail(id)
            },
            onAddMember = { member ->
                memberDialog = null
                viewModel.addMember(member)
            },
            onRemoveMember = { member ->
                memberDialog = null
                viewModel.removeMember(member)
            },
        )
    }

    val scrollState = rememberScrollState()
    // 页面骨架:GlassHeaderScaffold 自带 bg0 + 氛围光 + 玻璃 header;卡片全部拟玻璃
    GlassHeaderScaffold(
        scrollState = scrollState,
        header = {
            BackHeader(
                title = "聊天设置",
                eyebrow = state.groupName,
                onBack = onBack,
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = Gomob.spacing.pageGutter),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.cardGap),
        ) {
            // verticalScroll 无 contentPadding → 首尾 Spacer 承接 scaffold 避让区
            Spacer(Modifier.height(padding.calculateTopPadding()))
            if (state.group) {
                ConversationInfoCard {
                    ConversationMemberGrid(
                        members = state.members,
                        onOpenUserDetail = onOpenUserDetail,
                        onAddMember = { memberDialog = ConversationInfoMemberDialog.Add },
                        onRemoveMember = { memberDialog = ConversationInfoMemberDialog.Remove },
                    )
                }
            } else {
                ConversationDirectMemberCard(
                    peer = state.members.firstOrNull { !it.self },
                    fallbackName = state.groupName,
                    onOpenUserDetail = onOpenUserDetail,
                    // TODO(终态): 服务端建群 API 就绪后改为真正拉群;当前复用添加成员链路
                    onStartGroup = { memberDialog = ConversationInfoMemberDialog.Add },
                )
            }
            ConversationInfoCard {
                ConversationInfoRow(
                    title = if (state.group) "群聊名称" else "聊天名称",
                    value = state.groupName,
                    onClick = { editTarget = ConversationInfoEditTarget.Name },
                    showDivider = state.group,
                )
                if (state.group) {
                    ConversationInfoRow(
                        title = "群二维码",
                        trailingIcon = GomobIcons.ID,
                        onClick = { qrOpen = true },
                    )
                    ConversationInfoRow(
                        title = "群公告",
                        value = state.announcement.ifBlank { "未设置" },
                        onClick = { editTarget = ConversationInfoEditTarget.Announcement },
                    )
                    ConversationInfoRow(
                        title = "群管理",
                        value = "${state.members.size} 人",
                        onClick = { memberDialog = ConversationInfoMemberDialog.Management },
                    )
                    ConversationInfoRow(
                        title = "备注",
                        value = state.remark.ifBlank { "未设置" },
                        onClick = { editTarget = ConversationInfoEditTarget.Remark },
                        showDivider = false,
                    )
                }
            }
            // 功能行组:查找 / 置顶 / 免打扰(群聊追加折叠与强提醒说明)
            ConversationInfoCard {
                ConversationInfoRow(
                    title = "查找聊天记录",
                    onClick = { onOpenSearch(state.conversationId.toString()) },
                )
                ConversationInfoSwitchRow(
                    title = "置顶聊天",
                    checked = state.pinned,
                    onToggle = viewModel::togglePinned,
                )
                ConversationInfoSwitchRow(
                    title = "消息免打扰",
                    checked = state.muted,
                    onToggle = viewModel::toggleMuted,
                    showDivider = state.group,
                )
                if (state.group) {
                    ConversationInfoSwitchRow(
                        title = "折叠该聊天",
                        checked = state.folded,
                        onToggle = viewModel::toggleFolded,
                    )
                    ConversationInfoNoticeRow(
                        title = "以下消息仍通知",
                        subtitle = "@我、@所有人和群公告",
                        onClick = { context.showMessageActionToast("已开启默认强提醒规则") },
                    )
                }
            }
            ConversationDangerCard(
                title = "清空聊天记录",
                onClick = { clearConfirmOpen = true },
            )
            if (state.group) {
                ConversationDangerCard(
                    title = "退出群聊",
                    onClick = { leaveConfirmOpen = true },
                )
            }
            state.errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
                Text(
                    text = message,
                    style = Gomob.type.caption,
                    color = Gomob.colors.danger,
                    modifier = Modifier.padding(vertical = Gomob.spacing.s4),
                )
            }
            Spacer(Modifier.height(padding.calculateBottomPadding() + Gomob.spacing.s16))
        }
    }
}

private enum class ConversationInfoEditTarget {
    Name,
    Announcement,
    Remark,
}

private enum class ConversationInfoMemberDialog {
    Management,
    Add,
    Remove,
}

@Composable
private fun ConversationInfoEditDialog(
    target: ConversationInfoEditTarget,
    state: ConversationInfoUiState,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    val initial = when (target) {
        ConversationInfoEditTarget.Name -> state.groupName
        ConversationInfoEditTarget.Announcement -> state.announcement
        ConversationInfoEditTarget.Remark -> state.remark
    }
    var draft by rememberSaveable(target, state.conversationId) { mutableStateOf(initial) }
    val title = when (target) {
        ConversationInfoEditTarget.Name -> if (state.group) "群聊名称" else "聊天名称"
        ConversationInfoEditTarget.Announcement -> "群公告"
        ConversationInfoEditTarget.Remark -> "备注"
    }
    val placeholder = when (target) {
        ConversationInfoEditTarget.Name -> state.fallbackName
        ConversationInfoEditTarget.Announcement -> "填写群公告"
        ConversationInfoEditTarget.Remark -> "填写备注"
    }
    val maxLines = if (target == ConversationInfoEditTarget.Announcement) 5 else 2
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Gomob.colors.bg2.copy(alpha = 0.97f),
        shape = Gomob.shapes.r3,
        title = { Text(title, style = Gomob.type.title, color = Gomob.colors.fg0) },
        text = {
            ConversationInfoTextInput(
                value = draft,
                onValueChange = { draft = it.take(180) },
                placeholder = placeholder,
                maxLines = maxLines,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) {
                Text("保存", color = Gomob.colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消", color = Gomob.colors.fg2)
            }
        },
    )
}

@Composable
private fun ConversationInfoTextInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    maxLines: Int,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp, max = 130.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg2)
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = Gomob.colors.fg0),
        cursorBrush = SolidColor(Gomob.colors.accent),
        maxLines = maxLines,
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                if (value.isBlank()) {
                    Text(placeholder, style = Gomob.type.bodySm, color = Gomob.colors.fg3)
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun ConversationQrDialog(
    state: ConversationInfoUiState,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Gomob.colors.bg2.copy(alpha = 0.97f),
        shape = Gomob.shapes.r3,
        title = { Text("群二维码", style = Gomob.type.title, color = Gomob.colors.fg0) },
        text = {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
            ) {
                ConversationQrCode(payload = state.qrPayload)
                Text(
                    text = state.groupName,
                    style = Gomob.type.body.copy(fontWeight = FontWeight.Medium),
                    color = Gomob.colors.fg0,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = state.qrPayload,
                    style = Gomob.type.caption,
                    color = Gomob.colors.fg3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(Gomob.spacing.s4))
                Text("复制", color = Gomob.colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭", color = Gomob.colors.fg2)
            }
        },
    )
}

@Composable
private fun ConversationQrCode(payload: String) {
    val matrix = remember(payload) { payload.toQrMatrix() }
    Canvas(
        Modifier
            .size(220.dp)
            .aspectRatio(1f)
            .clip(Gomob.shapes.r2)
            .background(Color.White)
            .padding(12.dp),
    ) {
        val size = matrix.size
        if (size == 0) return@Canvas
        val cell = this.size.minDimension / size
        matrix.forEachIndexed { y, row ->
            row.forEachIndexed { x, filled ->
                if (filled) {
                    drawRect(
                        color = Color.Black,
                        topLeft = androidx.compose.ui.geometry.Offset(x * cell, y * cell),
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                    )
                }
            }
        }
    }
}

@Composable
private fun ConversationMemberDialog(
    dialog: ConversationInfoMemberDialog,
    state: ConversationInfoUiState,
    onDismiss: () -> Unit,
    onOpenUserDetail: (String) -> Unit,
    onAddMember: (ConversationInfoMemberUi) -> Unit,
    onRemoveMember: (ConversationInfoMemberUi) -> Unit,
) {
    val title = when (dialog) {
        ConversationInfoMemberDialog.Management -> "群管理"
        ConversationInfoMemberDialog.Add -> "添加成员"
        ConversationInfoMemberDialog.Remove -> "移除成员"
    }
    val items = when (dialog) {
        ConversationInfoMemberDialog.Management -> state.members
        ConversationInfoMemberDialog.Add -> state.addableMembers
        ConversationInfoMemberDialog.Remove -> state.members.filter { !it.self && it.userId != null }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Gomob.colors.bg2.copy(alpha = 0.97f),
        shape = Gomob.shapes.r3,
        title = { Text(title, style = Gomob.type.title, color = Gomob.colors.fg0) },
        text = {
            if (items.isEmpty()) {
                Text(
                    text = when (dialog) {
                        ConversationInfoMemberDialog.Management -> "暂无成员"
                        ConversationInfoMemberDialog.Add -> "暂无可添加成员"
                        ConversationInfoMemberDialog.Remove -> "暂无可移除成员"
                    },
                    style = Gomob.type.bodySm,
                    color = Gomob.colors.fg2,
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    contentPadding = PaddingValues(vertical = Gomob.spacing.s4),
                    verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                ) {
                    items(items, key = { it.stableKey }) { member ->
                        ConversationMemberManageRow(
                            member = member,
                            dialog = dialog,
                            onOpenUserDetail = onOpenUserDetail,
                            onAddMember = onAddMember,
                            onRemoveMember = onRemoveMember,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = Gomob.colors.accent)
            }
        },
    )
}

@Composable
private fun ConversationMemberManageRow(
    member: ConversationInfoMemberUi,
    dialog: ConversationInfoMemberDialog,
    onOpenUserDetail: (String) -> Unit,
    onAddMember: (ConversationInfoMemberUi) -> Unit,
    onRemoveMember: (ConversationInfoMemberUi) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg2)
            .clickable(enabled = dialog == ConversationInfoMemberDialog.Management && member.userId != null && !member.self) {
                member.userId?.let { onOpenUserDetail("user-$it") }
            }
            .padding(Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        InitialAvatarTile(
            text = member.name,
            size = 38.dp,
            shape = Gomob.shapes.r2,
            fontSize = 14.sp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
            Text(member.name, style = Gomob.type.bodySm, color = Gomob.colors.fg0, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = if (member.self) "我" else "成员 #${member.userId ?: "-"}",
                style = Gomob.type.caption,
                color = Gomob.colors.fg3,
                maxLines = 1,
            )
        }
        when (dialog) {
            ConversationInfoMemberDialog.Management -> {
                Icon(Icons.Filled.ManageAccounts, contentDescription = null, tint = Gomob.colors.fg3, modifier = Modifier.size(18.dp))
            }
            ConversationInfoMemberDialog.Add -> {
                TextButton(onClick = { onAddMember(member) }) {
                    Text("添加", color = Gomob.colors.accent)
                }
            }
            ConversationInfoMemberDialog.Remove -> {
                TextButton(onClick = { onRemoveMember(member) }) {
                    Text("移除", color = Gomob.colors.danger)
                }
            }
        }
    }
}

@HiltViewModel
class ConversationInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MessageRepository,
    private val infoRepository: ConversationInfoRepository,
    private val tokenStore: TokenStore,
) : ViewModel() {
    private val conversationId = savedStateHandle.get<String>("id")?.toLongOrNull() ?: 0L
    private val actionError = MutableStateFlow<String?>(null)
    private val _events = MutableSharedFlow<ConversationInfoEvent>()
    val events = _events.asSharedFlow()

    private val base = combine(
        repository.observeConversation(conversationId),
        repository.observeMessages(conversationId),
        tokenStore.currentUserIdFlow,
    ) { conversation, messages, currentUserId ->
        ConversationInfoBase(
            conversation = conversation,
            messages = messages,
            currentUserId = currentUserId,
        )
    }

    private val settings = infoRepository.observeSettings(conversationId)

    val uiState: StateFlow<ConversationInfoUiState> =
        combine(base, settings, actionError) { base, settings, error ->
            base.toConversationInfoUiState(
                conversationId = conversationId,
                settings = settings,
                errorMessage = error,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ConversationInfoBase(
                conversation = repository.cachedConversation(conversationId),
                messages = repository.cachedMessages(conversationId),
                currentUserId = tokenStore.currentUserId(),
            ).toConversationInfoUiState(
                conversationId = conversationId,
                settings = ConversationInfoSettings(),
                errorMessage = null,
            ),
        )

    fun toggleMuted() {
        if (conversationId <= 0) return
        val next = !uiState.value.muted
        viewModelScope.launch {
            runCatching {
                infoRepository.setMuted(conversationId, next)
                actionError.value = null
                _events.emit(ConversationInfoEvent.Toast(if (next) "已开启消息免打扰" else "已关闭消息免打扰"))
            }.onFailure { error ->
                actionError.value = error.readableConversationInfoMessage()
            }
        }
    }

    fun toggleFolded() {
        if (conversationId <= 0) return
        val next = !uiState.value.folded
        viewModelScope.launch {
            runCatching {
                infoRepository.setFolded(conversationId, next)
                actionError.value = null
                _events.emit(ConversationInfoEvent.Toast(if (next) "已折叠该聊天" else "已取消折叠"))
            }.onFailure { error ->
                actionError.value = error.readableConversationInfoMessage()
            }
        }
    }

    fun updateDisplayName(name: String) {
        if (conversationId <= 0) return
        viewModelScope.launch {
            runCatching {
                infoRepository.setDisplayName(conversationId, name)
                actionError.value = null
                _events.emit(ConversationInfoEvent.Toast("已保存聊天名称"))
            }.onFailure { error ->
                actionError.value = error.readableConversationInfoMessage()
            }
        }
    }

    fun updateAnnouncement(announcement: String) {
        if (conversationId <= 0) return
        viewModelScope.launch {
            runCatching {
                infoRepository.setAnnouncement(conversationId, announcement)
                actionError.value = null
                _events.emit(ConversationInfoEvent.Toast("已保存群公告"))
            }.onFailure { error ->
                actionError.value = error.readableConversationInfoMessage()
            }
        }
    }

    fun updateRemark(remark: String) {
        if (conversationId <= 0) return
        viewModelScope.launch {
            runCatching {
                infoRepository.setRemark(conversationId, remark)
                actionError.value = null
                _events.emit(ConversationInfoEvent.Toast("已保存备注"))
            }.onFailure { error ->
                actionError.value = error.readableConversationInfoMessage()
            }
        }
    }

    fun addMember(member: ConversationInfoMemberUi) {
        if (conversationId <= 0 || member.userId == null) return
        viewModelScope.launch {
            runCatching {
                infoRepository.addMember(
                    conversationId,
                    ConversationInfoStoredMember(member.userId, member.name),
                )
                actionError.value = null
                _events.emit(ConversationInfoEvent.Toast("已添加 ${member.name}"))
            }.onFailure { error ->
                actionError.value = error.readableConversationInfoMessage()
            }
        }
    }

    fun removeMember(member: ConversationInfoMemberUi) {
        if (conversationId <= 0 || member.userId == null || member.self) return
        viewModelScope.launch {
            runCatching {
                infoRepository.removeMember(
                    conversationId,
                    ConversationInfoStoredMember(member.userId, member.name),
                )
                actionError.value = null
                _events.emit(ConversationInfoEvent.Toast("已移除 ${member.name}"))
            }.onFailure { error ->
                actionError.value = error.readableConversationInfoMessage()
            }
        }
    }

    fun togglePinned() {
        if (conversationId <= 0) return
        val pinned = !uiState.value.pinned
        viewModelScope.launch {
            runCatching {
                repository.setConversationPinned(conversationId, pinned)
                actionError.value = null
            }.onFailure { error ->
                actionError.value = error.readableConversationInfoMessage()
            }
        }
    }

    fun clearMessages() {
        if (conversationId <= 0) return
        viewModelScope.launch {
            runCatching {
                repository.clearConversationMessages(conversationId)
                actionError.value = null
                _events.emit(ConversationInfoEvent.Toast("已清空聊天记录"))
            }.onFailure { error ->
                actionError.value = error.readableConversationInfoMessage()
            }
        }
    }

    fun leaveConversation() {
        if (conversationId <= 0) return
        viewModelScope.launch {
            runCatching {
                repository.leaveConversation(conversationId)
                actionError.value = null
                _events.emit(ConversationInfoEvent.Toast("已退出群聊"))
                _events.emit(ConversationInfoEvent.LeftConversation)
            }.onFailure { error ->
                actionError.value = error.readableConversationInfoMessage()
            }
        }
    }
}

sealed interface ConversationInfoEvent {
    data class Toast(val text: String) : ConversationInfoEvent
    data object LeftConversation : ConversationInfoEvent
}

data class ConversationInfoUiState(
    val conversationId: Long,
    val headerTitle: String,
    val groupName: String,
    val fallbackName: String,
    val announcement: String,
    val remark: String,
    val qrPayload: String,
    val members: List<ConversationInfoMemberUi>,
    val addableMembers: List<ConversationInfoMemberUi>,
    val group: Boolean,
    val pinned: Boolean,
    val muted: Boolean,
    val folded: Boolean,
    val errorMessage: String?,
)

data class ConversationInfoMemberUi(
    val stableKey: String,
    val name: String,
    val avatarSeed: String,
    val userId: Long?,
    val self: Boolean,
)

private data class ConversationInfoBase(
    val conversation: ConversationSummary?,
    val messages: List<MessageRecord>,
    val currentUserId: Long?,
)

private fun ConversationInfoBase.toConversationInfoUiState(
    conversationId: Long,
    settings: ConversationInfoSettings,
    errorMessage: String?,
): ConversationInfoUiState {
    val group = conversation?.kind == "group" || conversation?.subjectKind == "online_help" || conversation?.title == "在线求助"
    val title = conversation?.conversationInfoTitle() ?: if (conversationId > 0) "会话 #$conversationId" else "会话"
    val fallbackName = title
    val groupName = settings.displayName.ifBlank { fallbackName }
    val members = buildConversationInfoMembers(
        conversation = conversation,
        messages = messages,
        currentUserId = currentUserId,
        group = group,
        settings = settings,
    )
    val memberIds = members.mapNotNull { it.userId }.toSet()
    return ConversationInfoUiState(
        conversationId = conversationId,
        headerTitle = if (group) "聊天信息 (${members.size})" else "聊天信息",
        groupName = groupName,
        fallbackName = fallbackName,
        announcement = settings.announcement,
        remark = settings.remark,
        qrPayload = "gomob://conversation/$conversationId?name=${groupName.encodeConversationInfoPayload()}",
        members = members,
        addableMembers = localConversationCandidateMembers()
            .filter { it.userId != null && it.userId !in memberIds && it.userId !in settings.removedMemberIds }
            .sortedBy { it.userId },
        group = group,
        pinned = conversation?.pinned ?: false,
        muted = settings.muted,
        folded = settings.folded,
        errorMessage = errorMessage,
    )
}

private fun buildConversationInfoMembers(
    conversation: ConversationSummary?,
    messages: List<MessageRecord>,
    currentUserId: Long?,
    group: Boolean,
    settings: ConversationInfoSettings,
): List<ConversationInfoMemberUi> {
    val members = mutableListOf<ConversationInfoMemberUi>()
    if (currentUserId != null) {
        members += ConversationInfoMemberUi(
            stableKey = "self-$currentUserId",
            name = "我",
            avatarSeed = "member-self-$currentUserId",
            userId = currentUserId,
            self = true,
        )
    }
    conversation?.peer?.let { peer ->
        if (peer.id != currentUserId) {
            members += ConversationInfoMemberUi(
                stableKey = "peer-${peer.id}",
                name = peer.name.ifBlank { "成员 #${peer.id}" },
                avatarSeed = "member-peer-${peer.id}-${peer.name}",
                userId = peer.id,
                self = false,
            )
        }
    }
    messages.mapNotNull { it.senderId }
        .filter { it != currentUserId }
        .distinct()
        .forEach { senderId ->
            val name = localConversationMemberNames[senderId] ?: "成员 #$senderId"
            members += ConversationInfoMemberUi(
                stableKey = "sender-$senderId",
                name = name,
                avatarSeed = "member-sender-$senderId-$name",
                userId = senderId,
                self = false,
            )
        }
    if (group && members.isEmpty()) {
        members += ConversationInfoMemberUi(
            stableKey = "group-owner",
            name = "我",
            avatarSeed = "member-group-owner",
            userId = currentUserId,
            self = true,
        )
    }
    settings.addedMembers.forEach { member ->
        if (member.userId != currentUserId) {
            members += ConversationInfoMemberUi(
                stableKey = "added-${member.userId}",
                name = member.name,
                avatarSeed = "member-added-${member.userId}-${member.name}",
                userId = member.userId,
                self = false,
            )
        }
    }
    return members
        .filter { member -> member.self || member.userId == null || member.userId !in settings.removedMemberIds }
        .distinctBy { member -> member.userId?.let { "user-$it" } ?: member.stableKey }
}

/** 聊天设置页统一拟玻璃卡容器。 */
@Composable
private fun ConversationInfoCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .glassPanelBg(shape = Gomob.shapes.r3),
        content = content,
    )
}

/** 48dp r3 虚线边框(发起群聊块 / 网格操作块共用)。 */
private fun Modifier.dashedTileBorder(color: Color, cornerRadius: Dp): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        style = Stroke(
            width = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()), 0f),
        ),
        cornerRadius = CornerRadius(cornerRadius.toPx()),
    )
}

/** 1:1 成员区:对话人首字母头像 + 发起群聊虚线块。 */
@Composable
private fun ConversationDirectMemberCard(
    peer: ConversationInfoMemberUi?,
    fallbackName: String,
    onOpenUserDetail: (String) -> Unit,
    onStartGroup: () -> Unit,
) {
    val peerName = peer?.name?.takeIf { it.isNotBlank() } ?: fallbackName
    Row(
        Modifier
            .fillMaxWidth()
            .glassPanelBg(shape = Gomob.shapes.r3)
            .padding(Gomob.spacing.s16),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s16),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier.clickable(enabled = peer?.userId != null) {
                peer?.userId?.let { onOpenUserDetail("user-$it") }
            },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
        ) {
            InitialAvatarTile(
                text = peerName,
                size = Gomob.spacing.avatar48,
            )
            Text(
                text = peerName,
                fontSize = 11.sp,
                color = Gomob.colors.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            Modifier.clickable(onClick = onStartGroup),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
        ) {
            Box(
                Modifier
                    .size(Gomob.spacing.avatar48)
                    .dashedTileBorder(Gomob.colors.lineStrong, 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", fontSize = 18.sp, color = Gomob.colors.fg3)
            }
            Text("发起群聊", fontSize = 11.sp, color = Gomob.colors.fg3, maxLines = 1)
        }
    }
}

@Composable
private fun ConversationMemberGrid(
    members: List<ConversationInfoMemberUi>,
    onOpenUserDetail: (String) -> Unit,
    onAddMember: () -> Unit,
    onRemoveMember: () -> Unit,
) {
    val visibleMembers = remember(members) { members.take(8) }
    val tiles = remember(visibleMembers) {
        visibleMembers.map { ConversationInfoGridTile.Member(it) } +
            ConversationInfoGridTile.Action(GomobIcons.Plus, "添加") +
            ConversationInfoGridTile.Action(GomobIcons.Minus, "移除")
    }
    val columnCount = 5
    val rows = remember(tiles) { tiles.chunked(columnCount) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(Gomob.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s14),
    ) {
        rows.forEach { rowTiles ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
            ) {
                rowTiles.forEach { tile ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                        when (tile) {
                            is ConversationInfoGridTile.Member -> ConversationMemberTile(
                                member = tile.member,
                                onOpenUserDetail = onOpenUserDetail,
                            )
                            is ConversationInfoGridTile.Action -> ConversationActionTile(
                                tile = tile,
                                onClick = if (tile.label == "添加") onAddMember else onRemoveMember,
                            )
                        }
                    }
                }
                repeat(columnCount - rowTiles.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ConversationMemberTile(
    member: ConversationInfoMemberUi,
    onOpenUserDetail: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !member.self && member.userId != null) {
                member.userId?.let { onOpenUserDetail("user-$it") }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        InitialAvatarTile(
            text = member.name,
            size = Gomob.spacing.avatar48,
        )
        Text(
            text = member.name,
            style = Gomob.type.caption.copy(fontSize = 11.sp),
            color = Gomob.colors.fg2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ConversationActionTile(
    tile: ConversationInfoGridTile.Action,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        Box(
            Modifier
                .size(Gomob.spacing.avatar48)
                .dashedTileBorder(Gomob.colors.lineStrong, 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = tile.icon,
                contentDescription = null,
                tint = Gomob.colors.fg3,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(
            text = tile.label,
            style = Gomob.type.caption.copy(fontSize = 11.sp),
            color = Gomob.colors.fg3,
            maxLines = 1,
        )
    }
}

@Composable
private fun ConversationInfoRow(
    title: String,
    value: String? = null,
    trailingIcon: ImageVector? = null,
    onClick: () -> Unit,
    showDivider: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.rowSetting)
                .clickable(onClick = onClick)
                .padding(horizontal = Gomob.spacing.s14),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = Gomob.type.body,
                color = Gomob.colors.fg0,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            value?.let {
                Text(
                    text = it,
                    style = Gomob.type.body,
                    color = Gomob.colors.fg2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.padding(start = Gomob.spacing.s12).weight(1.1f),
                )
            }
            trailingIcon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = Gomob.colors.fg2,
                    modifier = Modifier.padding(start = Gomob.spacing.s12).size(22.dp),
                )
            }
            Icon(
                imageVector = GomobIcons.ChevronRight,
                contentDescription = "进入",
                tint = Gomob.colors.fg3,
                modifier = Modifier.padding(start = Gomob.spacing.s8).size(18.dp),
            )
        }
        if (showDivider) {
            ConversationHairline(startPadding = Gomob.spacing.s14)
        }
    }
}

@Composable
private fun ConversationInfoNoticeRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Gomob.spacing.rowSettingTall)
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s14),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
        ) {
            Text(
                text = title,
                style = Gomob.type.body,
                color = Gomob.colors.fg0,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = Gomob.type.caption,
                color = Gomob.colors.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            imageVector = GomobIcons.ChevronRight,
            contentDescription = "进入",
            tint = Gomob.colors.fg3,
            modifier = Modifier.padding(start = Gomob.spacing.s8).size(18.dp),
        )
    }
}

@Composable
private fun ConversationInfoSwitchRow(
    title: String,
    checked: Boolean,
    onToggle: () -> Unit,
    showDivider: Boolean = true,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.rowSetting)
                .clickable(onClick = onToggle)
                .padding(horizontal = Gomob.spacing.s14),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = Gomob.type.body,
                color = Gomob.colors.fg0,
                modifier = Modifier.weight(1f),
            )
            ConversationSwitch(checked = checked)
        }
        if (showDivider) {
            ConversationHairline(startPadding = Gomob.spacing.s14)
        }
    }
}

@Composable
private fun ConversationSwitch(checked: Boolean) {
    // on 轨道 accent 半透明 / off 轨道 fg0 低透明,thumb 白
    Box(
        Modifier
            .width(Gomob.spacing.switchW)
            .height(Gomob.spacing.switchH)
            .clip(Gomob.shapes.pill)
            .background(
                if (checked) Gomob.colors.accent.copy(alpha = 0.5f)
                else Gomob.colors.fg0.copy(alpha = 0.12f),
            ),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(Gomob.spacing.switchPad)
                .size(Gomob.spacing.switchThumb)
                .clip(Gomob.shapes.pill)
                .background(Color.White),
        )
    }
}

/** 独立危险动作卡:52dp danger 文本居中。 */
@Composable
private fun ConversationDangerCard(
    title: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .glassPanelBg(shape = Gomob.shapes.r3)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title,
            style = Gomob.type.body.copy(fontWeight = FontWeight.Medium),
            color = Gomob.colors.danger,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ConversationHairline(startPadding: Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = startPadding)
            .height(Gomob.spacing.hairline)
            .background(Gomob.colors.line1),
    )
}

private sealed interface ConversationInfoGridTile {
    data class Member(val member: ConversationInfoMemberUi) : ConversationInfoGridTile

    data class Action(
        val icon: ImageVector,
        val label: String,
    ) : ConversationInfoGridTile
}

private val localConversationMemberNames = mapOf(
    2_101L to "周科",
    2_104L to "吴风",
    2_109L to "江庆宇",
    2_201L to "调度员",
    2_203L to "通道员",
    2_207L to "危化复核",
    2_301L to "抽查组",
    2_302L to "现场联络",
    2_303L to "监管复核",
    2_401L to "采集员",
    2_402L to "会审复核",
    2_405L to "重建工程师",
    2_501L to "预约员",
    2_502L to "人工窗口",
    2_503L to "排队调度",
)

private fun localConversationCandidateMembers(): List<ConversationInfoMemberUi> =
    localConversationMemberNames.map { (userId, name) ->
        ConversationInfoMemberUi(
            stableKey = "candidate-$userId",
            name = name,
            avatarSeed = "member-candidate-$userId-$name",
            userId = userId,
            self = false,
        )
    }

private fun String.encodeConversationInfoPayload(): String =
    URLEncoder.encode(this, Charsets.UTF_8.name())

private fun String.toQrMatrix(): List<List<Boolean>> {
    val matrix = QRCodeWriter().encode(this, BarcodeFormat.QR_CODE, 49, 49)
    return List(matrix.height) { y ->
        List(matrix.width) { x -> matrix[x, y] }
    }
}

private fun ConversationSummary.conversationInfoTitle(): String =
    when {
        subjectKind == "online_help" || (kind == "group" && title == "在线求助") -> "专家连线"
        !title.isNullOrBlank() -> title.orEmpty()
        !peer?.name.isNullOrBlank() -> peer?.name.orEmpty()
        else -> "会话 #$id"
    }

private fun Throwable.readableConversationInfoMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "操作失败，请稍后重试"
