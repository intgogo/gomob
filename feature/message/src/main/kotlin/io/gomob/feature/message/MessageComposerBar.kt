package io.gomob.feature.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

@Composable
internal fun MessageComposerBar(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSendText: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "发消息...",
    onPickImage: (() -> Unit)? = null,
    onTakePhoto: (() -> Unit)? = null,
    onSendVoice: (() -> Unit)? = null,
    onSendVideoClip: (() -> Unit)? = null,
    onOpenLocalVideo: (() -> Unit)? = null,
) {
    val canSendText = enabled && draft.isNotBlank()

    Column(modifier.fillMaxWidth().background(Gomob.colors.bg1)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.hairline)
                .background(Gomob.colors.line1),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8)
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg2)
                .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r3),
        ) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = Gomob.spacing.touchMin, max = 120.dp)
                        .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    BasicTextField(
                        value = draft,
                        onValueChange = onDraftChange,
                        enabled = enabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 24.dp, max = 96.dp),
                        singleLine = false,
                        minLines = 1,
                        maxLines = 5,
                        textStyle = Gomob.type.bodySm.copy(color = Gomob.colors.fg0),
                        cursorBrush = SolidColor(Gomob.colors.accent),
                    )
                    if (draft.isEmpty()) {
                        Text(placeholder, style = Gomob.type.bodySm, color = Gomob.colors.fg3)
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(Gomob.spacing.hairline)
                        .background(Gomob.colors.line1),
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = Gomob.spacing.s8),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
                ) {
                    onPickImage?.let {
                        ComposerToolIcon(Icons.Filled.Image, "图片", enabled = enabled, onClick = it)
                    }
                    onTakePhoto?.let {
                        ComposerToolIcon(Icons.Filled.PhotoCamera, "拍摄", enabled = enabled, onClick = it)
                    }
                    onSendVoice?.let {
                        ComposerToolIcon(GomobIcons.Mic, "发语音", enabled = enabled, onClick = it)
                    }
                    onSendVideoClip?.let {
                        ComposerToolIcon(Icons.Filled.Videocam, "发视频消息", enabled = enabled, onClick = it)
                    }
                    onOpenLocalVideo?.let {
                        ComposerToolIcon(Icons.Filled.VideoCall, "开启第一视角视频", enabled = enabled, onClick = it)
                    }
                    Spacer(Modifier.weight(1f))
                    ComposerSendButton(
                        enabled = canSendText,
                        onClick = onSendText,
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerToolIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(Gomob.shapes.r2)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) Gomob.colors.fg2 else Gomob.colors.fg3.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun ComposerSendButton(
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .width(38.dp)
            .height(34.dp)
            .clip(Gomob.shapes.r2)
            .background(if (enabled) Gomob.colors.accentSoft else Gomob.colors.bg3)
            .border(
                Gomob.spacing.hairline,
                if (enabled) Gomob.colors.accentLine else Gomob.colors.line2,
                Gomob.shapes.r2,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            GomobIcons.Send,
            contentDescription = "发送",
            tint = if (enabled) Gomob.colors.accent else Gomob.colors.fg3,
            modifier = Modifier.size(16.dp),
        )
    }
}
