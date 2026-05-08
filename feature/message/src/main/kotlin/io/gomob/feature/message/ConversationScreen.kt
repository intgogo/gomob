package io.gomob.feature.message

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.model.message.MessageStatus

@Composable
fun ConversationRoute(
    conversationId: String,
    onBack: () -> Unit,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = {},
    )
    val openImagePicker = {
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val sendDraft = {
        val text = draft.trim()
        if (text.isNotEmpty()) {
            viewModel.send(text)
            draft = ""
        }
    }

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = state.title,
            onBack = onBack,
            eyebrow = state.eyebrow.ifBlank { "会话 · #$conversationId" },
            trailing = {
                StatusTag(
                    text = if (state.offlineCached) "离线缓存" else "已同步",
                    tone = if (state.offlineCached) StatusTone.Warn else StatusTone.Ok,
                    showDot = true,
                )
            },
        )

        ConversationBody(
            state = state,
            onRefresh = viewModel::refresh,
            onRetry = viewModel::retry,
            modifier = Modifier.weight(1f),
        )

        ComposerBar(
            draft = draft,
            onDraftChange = { draft = it },
            onPickImage = openImagePicker,
            onTakePhoto = openImagePicker,
            onSend = sendDraft,
        )
    }
}

@Composable
private fun ConversationBody(
    state: ConversationUiState,
    onRefresh: () -> Unit,
    onRetry: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = Gomob.spacing.s16,
            vertical = Gomob.spacing.s12,
        ),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        if (state.errorMessage != null && state.messages.isNotEmpty()) {
            item {
                InlineStatus(
                    text = state.errorMessage,
                    tone = StatusTone.Warn,
                    onClick = onRefresh,
                )
            }
        }
        when {
            state.loading -> item {
                InlineStatus(text = "正在加载消息", tone = StatusTone.Neutral, onClick = null)
            }
            state.empty -> item {
                InlineStatus(text = "暂无消息", tone = StatusTone.Neutral, onClick = null)
            }
            state.errorMessage != null && state.messages.isEmpty() -> item {
                InlineStatus(text = state.errorMessage, tone = StatusTone.Danger, onClick = onRefresh)
            }
            else -> items(state.messages, key = { it.localKey }) { bubble ->
                BubbleRow(
                    bubble = bubble,
                    onRetry = { onRetry(bubble.clientMsgId) },
                )
            }
        }
    }
}

@Composable
private fun BubbleRow(
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
private fun ComposerBar(
    draft: String,
    onDraftChange: (String) -> Unit,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onSend: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Gomob.colors.bg1)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.hairline)
                .background(Gomob.colors.line1),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            ToolIcon(Icons.Filled.Image, "图片", onClick = onPickImage)
            ToolIcon(Icons.Filled.PhotoCamera, "拍摄", onClick = onTakePhoto)
            ToolIcon(Icons.Filled.Videocam, "视频通话", enabled = false, onClick = {})
            Box(
                Modifier
                    .weight(1f)
                    .height(Gomob.spacing.touchMin)
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.bg2)
                    .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2)
                    .padding(horizontal = Gomob.spacing.s12),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (draft.isEmpty()) {
                    Text("发消息…", style = Gomob.type.bodySm, color = Gomob.colors.fg3)
                }
                BasicTextField(
                    value = draft,
                    onValueChange = onDraftChange,
                    singleLine = true,
                    textStyle = Gomob.type.bodySm.copy(color = Gomob.colors.fg0),
                    cursorBrush = SolidColor(Gomob.colors.accent),
                )
            }
            Box(
                Modifier
                    .size(Gomob.spacing.touchMin)
                    .clip(Gomob.shapes.r2)
                    .background(if (draft.isBlank()) Gomob.colors.bg2 else Gomob.colors.accentSoft)
                    .border(
                        Gomob.spacing.hairline,
                        if (draft.isBlank()) Gomob.colors.line2 else Gomob.colors.accentLine,
                        Gomob.shapes.r2,
                    )
                    .clickable(enabled = draft.isNotBlank(), onClick = onSend),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    GomobIcons.Send,
                    contentDescription = "发送",
                    tint = if (draft.isBlank()) Gomob.colors.fg3 else Gomob.colors.accent,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(Gomob.shapes.r2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(Gomob.spacing.s8),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) Gomob.colors.fg2 else Gomob.colors.fg3.copy(alpha = 0.45f),
        )
    }
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
            .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(Gomob.spacing.s16),
    ) {
        StatusTag(text = text, tone = tone, showDot = tone != StatusTone.Neutral)
    }
}
