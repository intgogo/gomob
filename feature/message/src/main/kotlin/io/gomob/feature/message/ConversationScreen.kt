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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob
import io.gomob.model.message.MessageStatus

@Composable
fun ConversationRoute(
    conversationId: String,
    onBack: () -> Unit,
    onOpenLocalVideo: (String) -> Unit = {},
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

        MessageComposerBar(
            draft = draft,
            enabled = true,
            onDraftChange = { draft = it },
            onPickImage = openImagePicker,
            onTakePhoto = openImagePicker,
            onOpenLocalVideo = { onOpenLocalVideo(state.title) },
            onSendVoice = viewModel::sendVoice,
            onSendVideoClip = viewModel::sendVideoClip,
            onSendText = sendDraft,
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
