package io.gomob.feature.message

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob

@Composable
fun ConversationRoute(
    conversationId: String,
    onBack: () -> Unit,
    onOpenLocalVideo: (String) -> Unit = {},
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var draft by remember { mutableStateOf("") }
    var voiceRecording by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val voiceRecorder = rememberVoiceRecorder()
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let(viewModel::sendImage) },
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
    val openImagePicker: () -> Unit = {
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val openVideoPicker: () -> Unit = {
        videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
    }
    val toggleVoiceRecording: () -> Unit = {
        if (voiceRecording) {
            runCatching { voiceRecorder.stop() }
                .onSuccess { result -> viewModel.sendVoice(result.uri, result.durationSec) }
                .onFailure { viewModel.showError(it.message ?: "录音失败") }
            voiceRecording = false
        } else if (
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
            modifier = Modifier.clearInputFocusOnPointerDown(focusManager),
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
            onSendVoice = toggleVoiceRecording,
            onSendVideoClip = openVideoPicker,
            voiceRecording = voiceRecording,
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
    val focusManager = LocalFocusManager.current

    LazyColumn(
        modifier
            .fillMaxWidth()
            .clearInputFocusOnPointerDown(focusManager),
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
                ChatMessageRow(
                    bubble = bubble,
                    onRetry = {
                        focusManager.clearFocus()
                        onRetry(bubble.clientMsgId)
                    },
                )
            }
        }
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
