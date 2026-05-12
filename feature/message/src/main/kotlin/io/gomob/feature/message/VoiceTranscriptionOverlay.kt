package io.gomob.feature.message

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.theme.Gomob

internal data class VoiceTranscriptionDraft(
    val uri: Uri,
    val durationSec: Int,
    val text: String = "",
    val loading: Boolean = true,
    val failed: Boolean = false,
) {
    val canSendText: Boolean get() = !loading && text.isNotBlank() && !failed
}

@Composable
internal fun VoiceTranscriptionOverlay(
    draft: VoiceTranscriptionDraft?,
    onCancel: () -> Unit,
    onSendVoice: (VoiceTranscriptionDraft) -> Unit,
    onSendText: (VoiceTranscriptionDraft) -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = draft ?: return
    BackHandler(onBack = onCancel)
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Gray.copy(alpha = 0.62f))
            .padding(Gomob.spacing.s20),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 460.dp)
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .padding(Gomob.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s14),
        ) {
            Text(
                "语音转文字",
                style = Gomob.type.body,
                color = Gomob.colors.fg0,
                fontWeight = FontWeight.Medium,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(128.dp)
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.bg2)
                    .padding(Gomob.spacing.s14),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = when {
                        current.loading -> "正在识别文字"
                        current.failed || current.text.isBlank() -> "未识别到文字"
                        else -> current.text
                    },
                    style = Gomob.type.bodySm,
                    color = if (current.failed || (!current.loading && current.text.isBlank())) {
                        Gomob.colors.fg3
                    } else {
                        Gomob.colors.fg0
                    },
                    textAlign = TextAlign.Center,
                    maxLines = 5,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                VoiceTranscriptionButton(
                    label = "取消",
                    enabled = true,
                    primary = false,
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                )
                VoiceTranscriptionButton(
                    label = "发送原语音",
                    enabled = true,
                    primary = false,
                    onClick = { onSendVoice(current) },
                    modifier = Modifier.weight(1.2f),
                )
                VoiceTranscriptionButton(
                    label = "发送",
                    enabled = current.canSendText,
                    primary = true,
                    onClick = { onSendText(current) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun VoiceTranscriptionButton(
    label: String,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = when {
        !enabled -> Gomob.colors.bg3
        primary -> Gomob.colors.accent
        else -> Gomob.colors.bg2
    }
    val fg = when {
        !enabled -> Gomob.colors.fg3.copy(alpha = 0.5f)
        primary -> Gomob.colors.bg0
        else -> Gomob.colors.fg1
    }
    Box(
        modifier
            .height(42.dp)
            .clip(Gomob.shapes.r2)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Gomob.spacing.s8),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = Gomob.type.caption,
            color = fg,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}
