package io.gomob.feature.message

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.model.message.MessageStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URL
import java.util.LinkedHashMap

@Composable
internal fun ChatMessageRow(
    bubble: MessageBubbleUi,
    onRetry: () -> Unit,
    onRetryTranscript: () -> Unit = {},
    onOpenInspection: (String) -> Unit = {},
    onOpenUserDetail: (String) -> Unit = {},
    onAcceptCall: (CallInviteUi) -> Unit = {},
    onStartVideoCall: (String?) -> Unit = {},
    onOpenImage: (MessageBubbleUi) -> Unit = {},
    favorite: Boolean = false,
    selected: Boolean = false,
    multiSelectMode: Boolean = false,
    onToggleSelected: () -> Unit = {},
    onQuickAction: (MessageQuickAction, MessageBubbleUi) -> Unit = { _, _ -> },
) {
    val hasSenderLabel = !bubble.mine && !bubble.senderLabel.isNullOrBlank()
    val statusText = when (bubble.status) {
        MessageStatus.Pending -> "发送中"
        MessageStatus.Failed -> null
        MessageStatus.Sent -> null
    }
    val statusColor = when (bubble.status) {
        MessageStatus.Pending -> Gomob.colors.warn
        MessageStatus.Sent -> Gomob.colors.fg3
        MessageStatus.Failed -> Gomob.colors.fg3
    }
    val showRetryIcon = bubble.status == MessageStatus.Failed && bubble.clientMsgId != null
    var actionPanelOpen by remember(bubble.localKey) { mutableStateOf(false) }

    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .then(if (multiSelectMode) Modifier.clickable(onClick = onToggleSelected) else Modifier),
    ) {
        val avatarSize = 36.dp
        val avatarGap = Gomob.spacing.s8
        val bubbleMaxWidth = minOf(maxWidth * 0.72f, maxWidth - avatarSize - avatarGap)
            .coerceAtLeast(120.dp)
        val avatarTopPadding = if (hasSenderLabel) 18.dp else 0.dp

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (bubble.mine) Arrangement.End else Arrangement.Start,
            verticalAlignment = Alignment.Top,
        ) {
            if (multiSelectMode) {
                MessageSelectionMark(
                    selected = selected,
                    modifier = Modifier.padding(top = 7.dp, end = Gomob.spacing.s8),
                )
            }
            if (!bubble.mine) {
                ChatAvatar(
                    seed = bubble.avatarKey,
                    mine = false,
                    onClick = bubble.senderUserId?.let { id -> { onOpenUserDetail("user-$id") } },
                    modifier = Modifier.padding(top = avatarTopPadding),
                )
                Spacer(Modifier.width(avatarGap))
            } else {
                Spacer(Modifier.weight(1f))
            }

            Column(
                horizontalAlignment = if (bubble.mine) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
            ) {
                if (hasSenderLabel) {
                    Text(
                        bubble.senderLabel.orEmpty(),
                        style = Gomob.type.numInline.copy(fontSize = 10.sp),
                        color = Gomob.colors.fg3,
                        modifier = Modifier.padding(start = Gomob.spacing.s2),
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
                ) {
                    if (bubble.mine && showRetryIcon) {
                        MessageRetryWarning(onClick = onRetry)
                    }
                    Box(
                        Modifier.messageActionTouch(
                            onClick = {
                                when {
                                    multiSelectMode -> onToggleSelected()
                                    bubble.canOpenImagePreview() -> onOpenImage(bubble)
                                    bubble.canRedialVideoCall() -> onStartVideoCall(bubble.videoCallRedialTitle())
                                }
                            },
                            onLongPress = { actionPanelOpen = true },
                        ),
                    ) {
                        ChatBubble(
                            bubble = bubble,
                            maxWidth = bubbleMaxWidth,
                            onOpenInspection = onOpenInspection,
                            onAcceptCall = onAcceptCall,
                        )
                        if (actionPanelOpen) {
                            val nowMillis = System.currentTimeMillis()
                            val actions = bubble.quickActions(nowMillis)
                            if (actions.isNotEmpty()) {
                                MessageActionPopup(
                                    actions = actions,
                                    onDismiss = { actionPanelOpen = false },
                                    onAction = { action ->
                                        actionPanelOpen = false
                                        if (action == MessageQuickAction.TranscribeVoice) {
                                            onRetryTranscript()
                                        } else {
                                            onQuickAction(action, bubble)
                                        }
                                    },
                                )
                            } else {
                                actionPanelOpen = false
                            }
                        }
                    }
                }
                statusText?.let {
                    Text(
                        it,
                        style = Gomob.type.numInline,
                        color = statusColor,
                        modifier = Modifier.padding(horizontal = Gomob.spacing.s2),
                    )
                }
                if (favorite) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "已收藏",
                        tint = Gomob.colors.warn,
                        modifier = Modifier
                            .padding(horizontal = Gomob.spacing.s2)
                            .size(13.dp),
                    )
                }
            }

            if (bubble.mine) {
                Spacer(Modifier.width(avatarGap))
                ChatAvatar(
                    seed = bubble.avatarKey,
                    mine = true,
                    onClick = bubble.senderUserId?.let { id -> { onOpenUserDetail("user-$id") } },
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RecalledMessageBubble(
    bubble: MessageBubbleUi,
    maxWidth: Dp,
) {
    val label = if (bubble.mine) "你撤回了一条消息" else "对方撤回了一条消息"
    Box(
        Modifier
            .widthIn(max = maxWidth)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1.copy(alpha = 0.6f))
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
    ) {
        Text(
            label,
            style = Gomob.type.numInline,
            color = Gomob.colors.fg3,
        )
    }
}

@Composable
private fun ChatBubble(
    bubble: MessageBubbleUi,
    maxWidth: Dp,
    onOpenInspection: (String) -> Unit,
    onAcceptCall: (CallInviteUi) -> Unit,
) {
    val bubbleBg = if (bubble.mine) WechatMineBubble else Gomob.colors.bg1
    val textColor = if (bubble.mine) Color(0xF5000000) else Gomob.colors.fg0

    if (bubble.isRecalled) {
        RecalledMessageBubble(bubble = bubble, maxWidth = maxWidth)
        return
    }

    val card = bubble.inspectionCard
    val call = bubble.callInvite
    val callResult = bubble.callResult

    if (bubble.kind == "image") {
        ImageMessageBubble(
            bubble = bubble,
            maxWidth = maxWidth,
            textColor = textColor,
        )
    } else if (bubble.kind == "video_clip") {
        MediaFileMessageBubble(
            bubble = bubble,
            maxWidth = maxWidth,
            bubbleBg = bubbleBg,
            textColor = textColor,
        )
    } else if (card != null) {
        InspectionMessageCard(
            card = card,
            mine = bubble.mine,
            maxWidth = maxWidth,
            bubbleBg = bubbleBg,
            textColor = textColor,
            onOpenInspection = onOpenInspection,
        )
    } else if (call != null) {
        VideoCallInviteCard(
            call = call,
            mine = bubble.mine,
            maxWidth = maxWidth,
            bubbleBg = bubbleBg,
            textColor = textColor,
            onAcceptCall = onAcceptCall,
        )
    } else if (callResult != null) {
        CallResultCard(
            call = callResult,
            mine = bubble.mine,
            maxWidth = maxWidth,
            bubbleBg = bubbleBg,
            textColor = textColor,
        )
    } else if (bubble.isVoice) {
        VoiceMessageBubble(
            bubble = bubble,
            maxWidth = maxWidth,
            bubbleBg = bubbleBg,
            textColor = textColor,
        )
    } else {
        TextMessageBubble(
            bubble = bubble,
            maxWidth = maxWidth,
            bubbleBg = bubbleBg,
            textColor = textColor,
        )
    }
}

@Composable
private fun ImageMessageBubble(
    bubble: MessageBubbleUi,
    maxWidth: Dp,
    textColor: Color,
) {
    val source = bubble.media?.imageSource
    val refresher = LocalMessageMediaRefresher.current
    val assetId = bubble.media?.assetId
    val onRefresh: (suspend () -> String?)? = if (refresher != null && !assetId.isNullOrBlank() && bubble.localKey.isNotBlank()) {
        { refresher(bubble.localKey, assetId) }
    } else null
    val imageState by rememberMessageImage(source, onRefresh)
    val imageWidth = if (maxWidth < ImageMessageMinWidth) maxWidth else minOf(maxWidth, ImageMessageMaxWidth)
    val imageHeight = imageWidth * 1.28f

    Box(
        Modifier
            .width(imageWidth)
            .height(imageHeight)
            .clip(Gomob.shapes.r2)
            .background(ImageMessageBg),
        contentAlignment = Alignment.Center,
    ) {
        when (val state = imageState) {
            is MessageImageLoadState.Ready -> {
                Image(
                    bitmap = state.bitmap,
                    contentDescription = "照片消息",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            MessageImageLoadState.Empty,
            MessageImageLoadState.Failed,
            MessageImageLoadState.Loading -> ImageMessagePlaceholder(
                text = imagePlaceholderText(bubble, imageState),
                textColor = textColor,
            )
        }
    }
}

@Composable
private fun ImageMessagePlaceholder(
    text: String,
    textColor: Color,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Gomob.colors.bg1),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = Gomob.type.caption,
            color = textColor.copy(alpha = 0.62f),
            maxLines = 1,
        )
    }
}

@Composable
private fun TextMessageBubble(
    bubble: MessageBubbleUi,
    maxWidth: Dp,
    bubbleBg: Color,
    textColor: Color,
) {
    val mentionColor = Gomob.colors.accent
    val annotated = remember(bubble.text, mentionColor) {
        annotateMentions(bubble.text, mentionColor)
    }
    MessageBubbleShell(
        mine = bubble.mine,
        maxWidth = maxWidth,
        bubbleBg = bubbleBg,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
            Text(annotated, style = Gomob.type.bodySm, color = textColor)
            bubble.quote?.let { quote ->
                QuoteReferenceBlock(quote = quote, textColor = textColor, mine = bubble.mine)
            }
        }
    }
}

private val MENTION_REGEX = Regex("@([\\u4e00-\\u9fa5A-Za-z0-9_#\\-]+)")

internal fun annotateMentions(text: String, mentionColor: Color): AnnotatedString {
    if (text.isEmpty() || !text.contains('@')) return AnnotatedString(text)
    val matches = MENTION_REGEX.findAll(text).toList()
    if (matches.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var cursor = 0
        matches.forEach { match ->
            if (match.range.first > cursor) {
                append(text.substring(cursor, match.range.first))
            }
            withStyle(SpanStyle(color = mentionColor, fontWeight = FontWeight.Medium)) {
                append(match.value)
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}

@Composable
private fun QuoteReferenceBlock(
    quote: QuoteReferenceUi,
    textColor: Color,
    mine: Boolean,
) {
    Row(
        Modifier
            .clip(Gomob.shapes.r1)
            .background(Color.Black.copy(alpha = if (mine) 0.08f else 0.045f))
            .padding(horizontal = Gomob.spacing.s8, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(26.dp)
                .background(textColor.copy(alpha = 0.22f)),
        )
        Text(
            "${quote.senderLabel}: ${quote.text}",
            style = Gomob.type.caption,
            color = textColor.copy(alpha = 0.58f),
            maxLines = 2,
        )
    }
}

@Composable
private fun MediaFileMessageBubble(
    bubble: MessageBubbleUi,
    maxWidth: Dp,
    bubbleBg: Color,
    textColor: Color,
) {
    Box(
        Modifier
            .widthIn(max = maxWidth)
            .clip(Gomob.shapes.r2)
            .background(bubbleBg)
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
    ) {
        Text(bubble.text, style = Gomob.type.bodySm, color = textColor)
    }
}

/**
 * 提供给 [rememberMessageImage]：图片加载失败时由 ConversationScreen 注入的重签函数。
 * 入参 (localKey, assetId) → 拿新 download URL；null 表示无 refresher。
 */
internal val LocalMessageMediaRefresher = staticCompositionLocalOf<(suspend (String, String) -> String?)?> { null }

@Composable
internal fun rememberMessageImage(
    source: String?,
    /** 加载失败时调用：返回新 source（重签 URL）则再加载一次，null 则维持 Failed。 */
    onRefreshUrl: (suspend () -> String?)? = null,
): androidx.compose.runtime.State<MessageImageLoadState> {
    val context = LocalContext.current
    val normalized = source?.trim().orEmpty()
    val cached = remember(normalized) {
        normalized.takeIf { it.isNotBlank() }?.let(MessageImageBitmapCache::get)
    }
    return produceState<MessageImageLoadState>(
        initialValue = when {
            normalized.isBlank() -> MessageImageLoadState.Empty
            cached != null -> MessageImageLoadState.Ready(cached)
            else -> MessageImageLoadState.Loading
        },
        normalized,
    ) {
        if (normalized.isBlank()) {
            value = MessageImageLoadState.Empty
            return@produceState
        }
        MessageImageBitmapCache.get(normalized)?.let { bitmap ->
            value = MessageImageLoadState.Ready(bitmap)
            return@produceState
        }
        if (value !is MessageImageLoadState.Ready) {
            value = MessageImageLoadState.Loading
        }
        var bitmap = withContext(Dispatchers.IO) {
            runCatching { loadMessageImageBitmap(context, normalized) }.getOrNull()
        }
        // pre-signed URL 5min 过期时，allow 一次重签重试（仅当 caller 提供了 refresher）
        if (bitmap == null && onRefreshUrl != null) {
            val fresh = runCatching { onRefreshUrl() }.getOrNull()?.takeIf { it.isNotBlank() }
            if (fresh != null && fresh != normalized) {
                bitmap = withContext(Dispatchers.IO) {
                    runCatching { loadMessageImageBitmap(context, fresh) }.getOrNull()
                }
                if (bitmap != null) {
                    MessageImageBitmapCache.put(fresh, bitmap)
                }
            }
        }
        value = if (bitmap != null) {
            MessageImageBitmapCache.put(normalized, bitmap)
            MessageImageLoadState.Ready(bitmap)
        } else {
            MessageImageLoadState.Failed
        }
    }
}

internal sealed interface MessageImageLoadState {
    data object Empty : MessageImageLoadState
    data object Loading : MessageImageLoadState
    data object Failed : MessageImageLoadState
    data class Ready(val bitmap: ImageBitmap) : MessageImageLoadState
}

private fun loadMessageImageBitmap(context: Context, source: String): ImageBitmap? {
    val bitmap = when {
        source.startsWith("http://", ignoreCase = true) ||
            source.startsWith("https://", ignoreCase = true) -> {
            URL(source).openStream().use { BitmapFactory.decodeStream(it) }
        }
        else -> {
            val uri = Uri.parse(source)
            when (uri.scheme?.lowercase()) {
                "file" -> File(uri.path.orEmpty()).inputStream().use { BitmapFactory.decodeStream(it) }
                "content" -> context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                else -> File(source).takeIf { it.exists() }?.inputStream()?.use { BitmapFactory.decodeStream(it) }
                    ?: context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            }
        }
    }
    return bitmap?.asImageBitmap()
}

private fun imagePlaceholderText(
    bubble: MessageBubbleUi,
    imageState: MessageImageLoadState,
): String = when {
    bubble.media?.mediaState == "awaiting_asset_upload" || bubble.status == MessageStatus.Pending -> "照片上传中"
    imageState == MessageImageLoadState.Failed -> "照片暂不可显示"
    imageState == MessageImageLoadState.Loading -> "照片加载中"
    else -> "照片暂不可显示"
}

private val ImageMessageMinWidth = 136.dp
private val ImageMessageMaxWidth = 188.dp
private val ImageMessageBg = Color(0xFF111418)
private const val MessageImageCacheMaxSize = 48

private object MessageImageBitmapCache {
    private val lock = Any()
    private val values = LinkedHashMap<String, ImageBitmap>(MessageImageCacheMaxSize, 0.75f, true)

    fun get(source: String): ImageBitmap? = synchronized(lock) {
        values[source]
    }

    fun put(source: String, bitmap: ImageBitmap) = synchronized(lock) {
        values[source] = bitmap
        while (values.size > MessageImageCacheMaxSize) {
            val firstKey = values.keys.firstOrNull() ?: return@synchronized
            values.remove(firstKey)
        }
    }
}

internal fun MessageBubbleUi.canOpenImagePreview(): Boolean =
    kind == "image" && !media?.imageSource.isNullOrBlank()

private fun MessageBubbleUi.quickActions(nowMillis: Long): List<MessageQuickAction> {
    if (isRecalled) {
        // 撤回后的消息无操作意义
        return emptyList()
    }
    val failed = status == MessageStatus.Failed
    val recallable = canRecall(nowMillis)
    return MessageQuickAction.values().filter { action ->
        when (action) {
            MessageQuickAction.TranscribeVoice -> canRequestVoiceTranscript()
            MessageQuickAction.Retry, MessageQuickAction.Delete -> failed
            MessageQuickAction.Recall -> recallable
            // 发送失败的消息没必要 转发 / 收藏 / 引用 / 多选 — 只能 复制 / 重试 / 删除
            MessageQuickAction.Copy -> true
            else -> !failed
        }
    }
}

private fun MessageBubbleUi.canRequestVoiceTranscript(): Boolean =
    isVoice &&
        serverId != null &&
        serverId > 0 &&
        (voiceTranscript == null || voiceTranscript.status == "failed")

internal fun MessageBubbleUi.canRedialVideoCall(): Boolean {
    val invite = callInvite
    val result = callResult
    return when {
        invite != null -> !invite.ringing && invite.status != "active"
        result != null -> result.kind == "video_call"
        else -> false
    }
}

internal fun MessageBubbleUi.videoCallRedialTitle(): String? =
    callInvite?.title
        ?: callResult?.takeIf { it.kind == "video_call" }?.title

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.messageActionTouch(
    onClick: () -> Unit,
    onLongPress: () -> Unit,
): Modifier =
    combinedClickable(
        onClick = onClick,
        onLongClick = onLongPress,
    )

@Composable
private fun MessageSelectionMark(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val stroke = if (selected) Gomob.colors.accent else Gomob.colors.fg3.copy(alpha = 0.72f)
    Box(
        modifier.size(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            drawCircle(color = if (selected) stroke else Color.Transparent, radius = size.minDimension / 2f)
            drawCircle(color = stroke, radius = size.minDimension / 2f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        }
        if (selected) {
            Icon(
                imageVector = GomobIcons.Check,
                contentDescription = "已选择",
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
private fun MessageActionPopup(
    actions: List<MessageQuickAction>,
    onDismiss: () -> Unit,
    onAction: (MessageQuickAction) -> Unit,
) {
    Popup(
        popupPositionProvider = MessageActionPopupPositionProvider(),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(
                Modifier
                    .clip(Gomob.shapes.r2)
                    .background(MessageActionPanelBg)
                    .padding(horizontal = Gomob.spacing.s12, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s14),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actions.forEach { action ->
                    MessageActionButton(
                        action = action,
                        onClick = { onAction(action) },
                    )
                }
            }
            Canvas(Modifier.size(width = 18.dp, height = 8.dp)) {
                val path = Path().apply {
                    moveTo(size.width * 0.5f, size.height)
                    lineTo(0f, 0f)
                    lineTo(size.width, 0f)
                    close()
                }
                drawPath(path = path, color = MessageActionPanelBg)
            }
        }
    }
}

@Composable
private fun MessageActionButton(
    action: MessageQuickAction,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .width(42.dp)
            .clip(Gomob.shapes.r1)
            .clickable(onClick = onClick)
            .padding(vertical = Gomob.spacing.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = action.icon,
            contentDescription = action.label,
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
        Text(
            action.label,
            style = Gomob.type.caption,
            color = Color.White.copy(alpha = 0.92f),
            maxLines = 1,
        )
    }
}

internal enum class MessageQuickAction(
    val label: String,
    val icon: ImageVector,
) {
    Copy("复制", Icons.Filled.ContentCopy),
    Forward("转发", MessageForwardActionIcon),
    Favorite("收藏", Icons.Filled.StarBorder),
    MultiSelect("多选", Icons.Filled.Checklist),
    Quote("引用", Icons.Filled.FormatQuote),
    TranscribeVoice("转文字", GomobIcons.Compose),
    Retry("重试", Icons.Filled.Refresh),
    Recall("撤回", Icons.AutoMirrored.Filled.Undo),
    Delete("删除", Icons.Filled.Delete),
}

// 撤回时限：5 分钟内自己发的 Sent 消息可撤回（与 server 端 messageRecallWindow 对齐）。
private const val MESSAGE_RECALL_WINDOW_MILLIS = 5 * 60 * 1000L

private fun MessageBubbleUi.canRecall(nowMillis: Long): Boolean {
    if (!mine || isRecalled) return false
    if (status != MessageStatus.Sent) return false
    if (serverId == null || serverId <= 0) return false
    val created = createdAtEpochMillis ?: return false
    return nowMillis - created <= MESSAGE_RECALL_WINDOW_MILLIS
}

private class MessageActionPopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val margin = 8
        val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
            .coerceIn(margin, (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin))
        val y = (anchorBounds.top - popupContentSize.height - margin)
            .coerceAtLeast(margin)
        return IntOffset(x, y)
    }
}

private val MessageActionPanelBg = Color(0xEE4A4A4A)

@Composable
private fun MessageBubbleShell(
    mine: Boolean,
    maxWidth: Dp,
    bubbleBg: Color,
    content: @Composable () -> Unit,
) {
    val contentMaxWidth = (maxWidth - MessageBubbleTailWidth).coerceAtLeast(96.dp)
    Row(verticalAlignment = Alignment.Top) {
        if (!mine) {
            MessageBubbleTail(
                mine = false,
                color = bubbleBg,
                modifier = Modifier.padding(top = MessageBubbleTailTop),
            )
        }
        Box(
            Modifier
                .widthIn(max = contentMaxWidth)
                .clip(Gomob.shapes.r2)
                .background(bubbleBg)
                .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        ) {
            content()
        }
        if (mine) {
            MessageBubbleTail(
                mine = true,
                color = bubbleBg,
                modifier = Modifier.padding(top = MessageBubbleTailTop),
            )
        }
    }
}

@Composable
private fun MessageBubbleTail(
    mine: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.size(width = MessageBubbleTailWidth, height = MessageBubbleTailHeight)) {
        val path = Path().apply {
            if (mine) {
                moveTo(0f, size.height * 0.08f)
                lineTo(0f, size.height * 0.92f)
                lineTo(size.width, size.height * 0.50f)
            } else {
                moveTo(size.width, size.height * 0.08f)
                lineTo(size.width, size.height * 0.92f)
                lineTo(0f, size.height * 0.50f)
            }
            close()
        }
        drawPath(path = path, color = color)
    }
}

@Composable
private fun VoiceMessageBubble(
    bubble: MessageBubbleUi,
    maxWidth: Dp,
    bubbleBg: Color,
    textColor: Color,
) {
    val transcript = bubble.voiceTranscript
    MessageBubbleShell(
        mine = bubble.mine,
        maxWidth = maxWidth,
        bubbleBg = bubbleBg,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    GomobIcons.VoiceCircle,
                    contentDescription = null,
                    tint = textColor.copy(alpha = 0.82f),
                    modifier = Modifier.size(17.dp),
                )
                Text(bubble.text, style = Gomob.type.bodySm, color = textColor, maxLines = 1)
            }
            if (transcript != null) {
                Spacer(
                    Modifier
                        .fillMaxWidth()
                        .height(Gomob.spacing.hairline)
                        .background(textColor.copy(alpha = 0.10f)),
                )
                Text(
                    transcript.voiceTranscriptDisplayText(bubble.serverId),
                    style = Gomob.type.caption,
                    color = when (transcript.status) {
                        "failed" -> Gomob.colors.danger
                        "pending", "processing" -> Gomob.colors.fg3
                        else -> textColor
                    },
                )
            }
        }
    }
}

private val MessageBubbleTailWidth = 5.dp
private val MessageBubbleTailHeight = 11.dp
private val MessageBubbleTailTop = 8.dp

internal fun VoiceTranscriptUi?.voiceTranscriptDisplayText(messageId: Long?): String = when (this?.status) {
    "done" -> text.orEmpty().ifBlank { "未识别到文字" }
    "failed" -> if (error.isUnrecognizedVoiceError()) "未识别到文字" else "转写失败：$error"
    "processing" -> "转写中"
    "pending" -> "等待转写"
    else -> if (messageId == null || messageId <= 0) "等待上传完成" else "可转成文字"
}

private fun String?.isUnrecognizedVoiceError(): Boolean {
    val value = this?.trim().orEmpty()
    return value.isBlank() || value.contains("未识别") || value.contains("有效文本")
}

@Composable
private fun CallResultCard(
    call: CallResultUi,
    mine: Boolean,
    maxWidth: Dp,
    bubbleBg: Color,
    textColor: Color,
) {
    MessageBubbleShell(
        mine = mine,
        maxWidth = maxWidth,
        bubbleBg = bubbleBg,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(Gomob.shapes.r2)
                        .background(textColor.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (call.kind == "audio_call") GomobIcons.VoiceCircle else Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = if (call.succeeded) {
                            if (mine) Color(0xD9000000) else Gomob.colors.accent
                        } else {
                            Gomob.colors.danger
                        },
                        modifier = Modifier.size(22.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(call.title, style = Gomob.type.bodySm, color = textColor, maxLines = 1)
                        StatusTag(
                            text = call.statusText,
                            tone = if (call.succeeded) StatusTone.Ok else StatusTone.Danger,
                            showDot = true,
                        )
                    }
                    Text(
                        call.callResultDetail(),
                        style = Gomob.type.caption,
                        color = if (call.succeeded) textColor.copy(alpha = 0.64f) else Gomob.colors.danger,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

private fun CallResultUi.callResultDetail(): String =
    if (succeeded) {
        durationText?.let { "通话时长 $it" } ?: "通话已结束"
    } else {
        failureReason?.takeIf { it.isNotBlank() } ?: statusText
    }

@Composable
private fun VideoCallInviteCard(
    call: CallInviteUi,
    mine: Boolean,
    maxWidth: Dp,
    bubbleBg: Color,
    textColor: Color,
    onAcceptCall: (CallInviteUi) -> Unit,
) {
    val callDanger = !call.ringing && !call.succeeded && call.status != "active"
    MessageBubbleShell(
        mine = mine,
        maxWidth = maxWidth,
        bubbleBg = bubbleBg,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(Gomob.shapes.r2)
                        .background(textColor.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = if (mine) Color(0xD9000000) else Gomob.colors.accent,
                        modifier = Modifier.size(23.dp),
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(call.title, style = Gomob.type.bodySm, color = textColor, maxLines = 1)
                        if (!call.ringing) {
                            StatusTag(
                                text = call.statusText,
                                tone = if (callDanger) StatusTone.Danger else StatusTone.Ok,
                                showDot = true,
                            )
                        }
                    }
                    Text(
                        call.inviteDetailText(mine),
                        style = Gomob.type.caption,
                        color = if (callDanger) Gomob.colors.danger else textColor.copy(alpha = 0.64f),
                        maxLines = 2,
                    )
                }
            }
            if (!mine && call.ringing) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .clip(Gomob.shapes.r2)
                            .background(Gomob.colors.accent)
                            .clickable { onAcceptCall(call) }
                            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s6),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("接受", style = Gomob.type.caption, color = Gomob.colors.bg0)
                    }
                }
            }
        }
    }
}

private fun CallInviteUi.inviteDetailText(mine: Boolean): String =
    when {
        ringing -> if (mine) "已发起，等待对方接受" else "邀请你视频通话"
        succeeded -> durationText?.let { "通话时长 $it" } ?: "通话已结束"
        !failureReason.isNullOrBlank() -> failureReason
        else -> statusText
    }

@Composable
private fun MessageRetryWarning(onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(Gomob.shapes.pill)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            GomobIcons.AlertCircle,
            contentDescription = "重新发送",
            tint = Gomob.colors.danger,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun InspectionMessageCard(
    card: InspectionCardUi,
    mine: Boolean,
    maxWidth: Dp,
    bubbleBg: Color,
    textColor: Color,
    onOpenInspection: (String) -> Unit,
) {
    MessageBubbleShell(
        mine = mine,
        maxWidth = maxWidth,
        bubbleBg = bubbleBg,
    ) {
        Column(
            Modifier.clickable { onOpenInspection(card.inspectionId) },
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
                ) {
                    Icon(
                        GomobIcons.LinkShare,
                        contentDescription = null,
                        tint = if (mine) Color(0xD9000000) else Gomob.colors.accent,
                        modifier = Modifier.size(15.dp),
                    )
                    Text("业务流水", style = Gomob.type.eyebrow, color = textColor.copy(alpha = 0.64f))
                }
                StatusTag(
                    text = card.status.toInspectionStatusText(),
                    tone = card.status.toInspectionStatusTone(),
                    showDot = true,
                )
            }
            Text(
                card.vin,
                style = Gomob.type.numInline.copy(fontSize = 14.sp),
                color = textColor,
                maxLines = 1,
            )
            Text(
                card.vehicleLine,
                style = Gomob.type.caption,
                color = textColor.copy(alpha = 0.64f),
                maxLines = 2,
            )
            if (card.tags.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    card.tags.take(2).forEach { tag ->
                        Box(
                            Modifier
                                .clip(Gomob.shapes.r1)
                                .background(textColor.copy(alpha = 0.10f))
                                .padding(horizontal = Gomob.spacing.s6, vertical = 2.dp),
                        ) {
                            Text(tag, fontSize = 10.sp, color = textColor.copy(alpha = 0.72f), maxLines = 1)
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    card.timeLabel.ifBlank { "查看流水详情" },
                    style = Gomob.type.numInline,
                    color = textColor.copy(alpha = 0.54f),
                )
                Icon(
                    GomobIcons.ChevronRight,
                    contentDescription = "查看流水详情",
                    tint = textColor.copy(alpha = 0.54f),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}

private fun String.toInspectionStatusText(): String = when (this) {
    "ok", "pass", "normal" -> "正常"
    "danger", "fail", "abnormal" -> "异常"
    else -> "预警"
}

private fun String.toInspectionStatusTone(): StatusTone = when (this) {
    "ok", "pass", "normal" -> StatusTone.Ok
    "danger", "fail", "abnormal" -> StatusTone.Danger
    else -> StatusTone.Warn
}

private val WechatMineBubble = Color(0xFF95EC69)

@Composable
private fun ChatAvatar(
    seed: String,
    mine: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    MessageAvatarImage(
        seed = if (mine) "current-user-$seed" else seed,
        size = 36.dp,
        shape = Gomob.shapes.r2,
        modifier = modifier.let { if (onClick != null) it.clickable(onClick = onClick) else it },
    )
}
