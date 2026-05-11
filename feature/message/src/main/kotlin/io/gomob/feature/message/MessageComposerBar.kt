package io.gomob.feature.message

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.motion.fixedDuringPageDrag
import io.gomob.designsystem.theme.Gomob

@Composable
internal fun MessageComposerBar(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSendText: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "发消息...",
    onShareInspection: (() -> Unit)? = null,
    onPickImage: (() -> Unit)? = null,
    onTakePhoto: (() -> Unit)? = null,
    onStartVideoCall: (() -> Unit)? = null,
    onStartVoice: (() -> Unit)? = null,
    onSendVoice: (() -> Unit)? = null,
    onCancelVoice: (() -> Unit)? = null,
    onTranscribeVoice: (() -> Unit)? = null,
    onSendVideoClip: (() -> Unit)? = null,
    onOpenLocalVideo: (() -> Unit)? = null,
    voiceRecording: Boolean = false,
    quoteDraft: QuoteDraftUi? = null,
    onClearQuote: () -> Unit = {},
    onInputFocusChanged: (Boolean) -> Unit = {},
) {
    var voiceInputMode by rememberSaveable { mutableStateOf(false) }
    var voicePressTarget by remember { mutableStateOf<VoicePressTarget?>(null) }
    val focusManager = LocalFocusManager.current
    val canSendText = enabled && draft.isNotBlank() && !voiceInputMode

    LaunchedEffect(onStartVoice) {
        if (onStartVoice == null) {
            voiceInputMode = false
            voicePressTarget = null
        }
    }

    Column(modifier.fixedDuringPageDrag().fillMaxWidth().background(Gomob.colors.bg1)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
        ) {
            if (voiceInputMode && voicePressTarget != null) {
                VoiceRecordLiftPanel(target = voicePressTarget ?: VoicePressTarget.Send)
            }
            quoteDraft?.let {
                ComposerQuotePreview(quoteDraft = it, onClear = onClearQuote)
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                onStartVoice?.let {
                    ComposerVoiceToggle(
                        voiceMode = voiceInputMode,
                        enabled = enabled,
                        onClick = {
                            voiceInputMode = !voiceInputMode
                            voicePressTarget = null
                            if (voiceInputMode) {
                                focusManager.clearFocus()
                                onInputFocusChanged(false)
                            }
                        },
                    )
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(Gomob.shapes.r3)
                        .background(Gomob.colors.bg2),
                ) {
                    if (voiceInputMode && onStartVoice != null) {
                        VoiceHoldToTalkButton(
                            enabled = enabled,
                            recording = voiceRecording,
                            target = voicePressTarget,
                            onPressStart = {
                                focusManager.clearFocus()
                                onInputFocusChanged(false)
                                voicePressTarget = VoicePressTarget.Send
                                onStartVoice()
                            },
                            onTargetChange = { voicePressTarget = it },
                            onPressEnd = { target ->
                                voicePressTarget = null
                                when (target) {
                                    VoicePressTarget.Send -> onSendVoice?.invoke()
                                    VoicePressTarget.Cancel -> onCancelVoice?.invoke()
                                    VoicePressTarget.Transcribe -> onTranscribeVoice?.invoke()
                                }
                            },
                        )
                    } else {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 42.dp, max = 122.dp)
                                .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            BasicTextField(
                                value = draft,
                                onValueChange = onDraftChange,
                                enabled = enabled,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 24.dp, max = 98.dp)
                                    .onFocusChanged { onInputFocusChanged(it.isFocused) },
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
                    }
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .padding(start = if (onStartVoice != null) 4.dp else 0.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
            ) {
                onShareInspection?.let {
                    ComposerToolIcon(Icons.Filled.AddCircle, "分享业务流水", enabled = enabled, onClick = it)
                }
                onPickImage?.let {
                    ComposerToolIcon(Icons.Filled.Image, "图片", enabled = enabled, onClick = it)
                }
                onTakePhoto?.let {
                    ComposerToolIcon(Icons.Filled.PhotoCamera, "拍摄", enabled = enabled, onClick = it)
                }
                onStartVideoCall?.let {
                    ComposerToolIcon(Icons.Filled.Videocam, "视频通话", enabled = enabled, onClick = it)
                }
                onOpenLocalVideo?.let {
                    ComposerToolIcon(Icons.Filled.Videocam, "开启第一视角视频", enabled = enabled, onClick = it)
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

@Composable
private fun ComposerQuotePreview(
    quoteDraft: QuoteDraftUi,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r1)
            .background(Gomob.colors.bg2)
            .padding(start = Gomob.spacing.s8, end = Gomob.spacing.s6, top = Gomob.spacing.s6, bottom = Gomob.spacing.s6),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(28.dp)
                .background(Gomob.colors.fg3.copy(alpha = 0.32f)),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                "${quoteDraft.quote.senderLabel}: ${quoteDraft.quote.text}",
                style = Gomob.type.caption,
                color = Gomob.colors.fg2,
                maxLines = 2,
            )
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Close, contentDescription = "取消引用", tint = Gomob.colors.fg2, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ComposerVoiceToggle(
    voiceMode: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(42.dp)
            .clip(Gomob.shapes.r3)
            .background(if (voiceMode) Gomob.colors.accentSoft else Gomob.colors.bg2)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (voiceMode) Icons.Filled.Keyboard else GomobIcons.VoiceCircle,
            contentDescription = if (voiceMode) "切换文字输入" else "切换语音输入",
            tint = when {
                !enabled -> Gomob.colors.fg3.copy(alpha = 0.45f)
                voiceMode -> Gomob.colors.accent
                else -> Gomob.colors.fg2
            },
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun VoiceHoldToTalkButton(
    enabled: Boolean,
    recording: Boolean,
    target: VoicePressTarget?,
    onPressStart: () -> Unit,
    onTargetChange: (VoicePressTarget) -> Unit,
    onPressEnd: (VoicePressTarget) -> Unit,
) {
    val actionLiftPx = with(LocalDensity.current) { 46.dp.toPx() }
    var lastTarget by remember { mutableStateOf(VoicePressTarget.Send) }
    val label = when (target) {
        VoicePressTarget.Cancel -> "松手取消"
        VoicePressTarget.Transcribe -> "松手转文字"
        VoicePressTarget.Send -> "松开发送"
        null -> "按住说话"
    }
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .background(if (recording) Gomob.colors.accentSoft else Gomob.colors.bg2)
            .pointerInput(enabled, actionLiftPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!enabled) return@awaitEachGesture
                    lastTarget = VoicePressTarget.Send
                    onPressStart()
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            onPressEnd(VoicePressTarget.Cancel)
                            break
                        }
                        if (!change.pressed) {
                            onPressEnd(lastTarget)
                            change.consume()
                            break
                        }
                        val nextTarget = voicePressTargetForPosition(
                            position = change.position,
                            width = size.width.toFloat(),
                            actionLiftPx = actionLiftPx,
                        )
                        if (nextTarget != lastTarget) {
                            lastTarget = nextTarget
                            onTargetChange(nextTarget)
                        }
                        change.consume()
                    }
                }
            }
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = Gomob.type.bodySm,
            color = when {
                !enabled -> Gomob.colors.fg3.copy(alpha = 0.45f)
                target == VoicePressTarget.Cancel -> Gomob.colors.danger
                target == VoicePressTarget.Transcribe -> Gomob.colors.accent
                recording -> Gomob.colors.accent
                else -> Gomob.colors.fg1
            },
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private enum class VoicePressTarget {
    Send,
    Cancel,
    Transcribe,
}

private fun voicePressTargetForPosition(
    position: Offset,
    width: Float,
    actionLiftPx: Float,
): VoicePressTarget {
    if (position.y > -actionLiftPx) return VoicePressTarget.Send
    return if (position.x < width / 2f) VoicePressTarget.Cancel else VoicePressTarget.Transcribe
}

@Composable
private fun VoiceRecordLiftPanel(target: VoicePressTarget) {
    val fillColor = Gomob.colors.bg2
    Box(
        Modifier
            .fillMaxWidth()
            .height(100.dp),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val radius = size.width * 0.72f
            val center = Offset(size.width / 2f, radius + 20.dp.toPx())
            drawCircle(color = fillColor, radius = radius, center = center)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp)
                .offset(y = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            VoiceArcAction(
                label = "取消",
                active = target == VoicePressTarget.Cancel,
                danger = true,
            )
            VoiceArcAction(
                label = "转文字",
                active = target == VoicePressTarget.Transcribe,
                danger = false,
            )
        }
        Text(
            text = when (target) {
                VoicePressTarget.Send -> "松开发送，上滑选择操作"
                VoicePressTarget.Cancel -> "松手取消本次录音"
                VoicePressTarget.Transcribe -> "松手转成文字"
            },
            style = Gomob.type.caption,
            color = when (target) {
                VoicePressTarget.Cancel -> Gomob.colors.danger
                VoicePressTarget.Transcribe -> Gomob.colors.accent
                VoicePressTarget.Send -> Gomob.colors.fg2
            },
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 12.dp)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun VoiceArcAction(
    label: String,
    active: Boolean,
    danger: Boolean,
) {
    val activeColor = if (danger) Gomob.colors.dangerSoft else Gomob.colors.accentSoft
    val textColor = when {
        active && danger -> Gomob.colors.danger
        active -> Gomob.colors.accent
        else -> Gomob.colors.fg2
    }
    Box(
        Modifier
            .size(62.dp)
            .clip(CircleShape)
            .background(if (active) activeColor else Gomob.colors.bg1),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = Gomob.type.caption,
            color = textColor,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ComposerToolIcon(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(34.dp)
            .clip(Gomob.shapes.r2)
            .background(if (active) Gomob.colors.dangerSoft else Gomob.colors.bg1)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = when {
                !enabled -> Gomob.colors.fg3.copy(alpha = 0.45f)
                active -> Gomob.colors.danger
                else -> Gomob.colors.fg2
            },
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
            .width(40.dp)
            .height(34.dp)
            .clip(Gomob.shapes.r2)
            .background(if (enabled) Gomob.colors.accent else Gomob.colors.bg2)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            GomobIcons.Send,
            contentDescription = "发送",
            tint = if (enabled) Gomob.colors.bg0 else Gomob.colors.fg3,
            modifier = Modifier.size(16.dp),
        )
    }
}
