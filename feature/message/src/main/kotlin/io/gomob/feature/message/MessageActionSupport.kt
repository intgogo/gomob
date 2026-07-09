package io.gomob.feature.message

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.model.message.MessageQuote

internal data class QuoteDraftUi(
    val quote: MessageQuote,
)

internal fun MessageBubbleUi.toMessageQuote(): MessageQuote = MessageQuote(
    localKey = localKey,
    serverId = serverId,
    senderLabel = if (mine) "我" else senderLabel?.takeIf { it.isNotBlank() } ?: "对方",
    text = text,
)

internal fun messageShareText(messages: List<MessageBubbleUi>): String =
    messages.joinToString(separator = "\n") { bubble ->
        val sender = if (bubble.mine) "我" else bubble.senderLabel?.takeIf { it.isNotBlank() } ?: "对方"
        "$sender：${bubble.text}"
    }

internal fun Context.showMessageActionToast(text: String) {
    Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
}

@Composable
internal fun MessageMultiSelectBar(
    selectedCount: Int,
    onCancel: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 多选操作栏在 GlassHeaderScaffold overlay 槽内 → 不画实底，由 glassChrome 玻璃负责
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Text(
            "已选择 $selectedCount 条",
            style = Gomob.type.bodySm,
            color = Gomob.colors.fg0,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.weight(1f))
        MessageSelectActionButton(
            label = "复制",
            icon = Icons.Filled.ContentCopy,
            enabled = selectedCount > 0,
            onClick = onCopy,
        )
        MessageSelectActionButton(
            label = "转发",
            icon = MessageForwardActionIcon,
            enabled = selectedCount > 0,
            onClick = onForward,
        )
        MessageSelectActionButton(
            label = "取消",
            icon = Icons.Filled.Close,
            enabled = true,
            onClick = onCancel,
        )
    }
}

@Composable
internal fun MessageForwardTargetDialog(
    visible: Boolean,
    targets: List<MessageForwardTargetUi>,
    messageCount: Int,
    onDismiss: () -> Unit,
    onSelectTarget: (MessageForwardTargetUi) -> Unit,
) {
    if (!visible) return
    Dialog(
        onDismissRequest = onDismiss,
    ) {
        var query by rememberSaveable { mutableStateOf("") }
        val focusManager = LocalFocusManager.current
        val sections = remember(targets, query) {
            targets.toForwardContactSections().filterForwardContacts(query)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .widthIn(max = 384.dp)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.bg1)
                .padding(top = 18.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clearInputFocusOnPointerDown(focusManager)
                    .padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "CONTACTS",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Gomob.colors.fg3,
                    )
                    Text("转发给", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
                    Text(
                        if (messageCount > 1) "已选择 $messageCount 条消息" else "已选择 1 条消息",
                        style = Gomob.type.caption,
                        color = Gomob.colors.fg3,
                    )
                }
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(Gomob.shapes.r2)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "取消", tint = Gomob.colors.fg2, modifier = Modifier.size(18.dp))
                }
            }
            ForwardContactSearchBar(query = query, onQueryChange = { query = it })
            if (targets.isEmpty()) {
                ForwardContactEmptyText("暂无可转发联系人", Modifier.clearInputFocusOnPointerDown(focusManager))
            } else if (sections.isEmpty()) {
                ForwardContactEmptyText("没有找到联系人", Modifier.clearInputFocusOnPointerDown(focusManager))
            } else {
                LazyColumn(
                    Modifier
                        .fillMaxWidth()
                        .clearInputFocusOnPointerDown(focusManager)
                        .heightIn(max = 390.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sections.forEach { section ->
                        item(key = "section-${section.id}") {
                            ForwardContactSectionHeader(section)
                        }
                        items(section.contacts, key = { it.target.stableKey }) { contact ->
                            Column {
                                ForwardContactRow(
                                    contact = contact,
                                    onClick = { onSelectTarget(contact.target) },
                                )
                                if (contact != section.contacts.last()) {
                                    ForwardContactDivider()
                                }
                            }
                        }
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .clip(Gomob.shapes.r1)
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("取消", style = Gomob.type.bodySm, color = Gomob.colors.fg2)
            }
        }
    }
}

@Composable
private fun ForwardContactSearchBar(query: String, onQueryChange: (String) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .height(38.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg2)
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
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, color = Gomob.colors.fg0),
            cursorBrush = SolidColor(Gomob.colors.accent),
            decorationBox = { innerTextField ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("搜索 姓名 / 工号 / 职责", fontSize = 12.sp, color = Gomob.colors.fg3)
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun ForwardContactEmptyText(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg2)
            .padding(Gomob.spacing.s14),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = Gomob.type.bodySm, color = Gomob.colors.fg2)
    }
}

@Composable
private fun ForwardContactSectionHeader(section: ForwardContactSectionUi) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s6, vertical = Gomob.spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        Icon(
            GomobIcons.Folder,
            contentDescription = null,
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(13.dp),
        )
        Text(
            section.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Gomob.colors.fg1,
            modifier = Modifier.weight(1f),
        )
        Text(section.contacts.size.toString(), style = Gomob.type.numInline, color = Gomob.colors.fg3)
    }
}

@Composable
private fun ForwardContactRow(
    contact: ForwardContactUi,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(Gomob.shapes.r2)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MessageAvatarImage(
            seed = "forward-${contact.target.stableKey}-${contact.name}",
            size = 32.dp,
            shape = Gomob.shapes.r1,
            online = contact.online,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                contact.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Gomob.colors.fg0,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${contact.roleTitle} · ${contact.employeeId}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Gomob.colors.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            GomobIcons.ChevronRight,
            contentDescription = null,
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
private fun ForwardContactDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 52.dp, end = 10.dp)
            .height(Gomob.spacing.hairline)
            .background(Gomob.colors.line1.copy(alpha = 0.03f)),
    )
}

private data class ForwardContactSectionUi(
    val id: String,
    val title: String,
    val contacts: List<ForwardContactUi>,
)

private data class ForwardContactUi(
    val target: MessageForwardTargetUi,
    val name: String,
    val initials: String,
    val roleTitle: String,
    val employeeId: String,
    val organization: String,
    val online: Boolean,
)

private fun List<MessageForwardTargetUi>.toForwardContactSections(): List<ForwardContactSectionUi> =
    groupBy { it.sectionId.ifBlank { "contacts" } }
        .map { (sectionId, sectionTargets) ->
            ForwardContactSectionUi(
                id = sectionId,
                title = sectionTargets.firstOrNull()?.sectionTitle?.takeIf { it.isNotBlank() } ?: "联系人",
                contacts = sectionTargets.map { it.toForwardContactUi() },
            )
        }
        .sortedBy { section -> forwardContactSectionOrder.indexOf(section.id).takeIf { it >= 0 } ?: Int.MAX_VALUE }

private fun MessageForwardTargetUi.toForwardContactUi(): ForwardContactUi {
    val parsed = subtitle.split(" · ", limit = 2)
    return ForwardContactUi(
        target = this,
        name = title,
        initials = initials,
        roleTitle = roleTitle.ifBlank { parsed.firstOrNull().orEmpty().ifBlank { "联系人" } },
        employeeId = employeeId.ifBlank { parsed.getOrNull(1).orEmpty().ifBlank { peerEmployeeId ?: "可发消息" } },
        organization = organization,
        online = online,
    )
}

private fun List<ForwardContactSectionUi>.filterForwardContacts(query: String): List<ForwardContactSectionUi> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return this
    val normalizedKeyword = keyword.normalizedForwardContactToken()
    return mapNotNull { section ->
        val filtered = section.contacts.filter { it.matchesForwardContactKeyword(keyword, normalizedKeyword) }
        if (filtered.isEmpty()) null else section.copy(contacts = filtered)
    }
}

private fun ForwardContactUi.matchesForwardContactKeyword(keyword: String, normalizedKeyword: String): Boolean {
    if (name.contains(keyword, ignoreCase = true) ||
        roleTitle.contains(keyword, ignoreCase = true) ||
        employeeId.contains(keyword, ignoreCase = true) ||
        organization.contains(keyword, ignoreCase = true)
    ) {
        return true
    }
    if (normalizedKeyword.isEmpty()) return false
    return listOf(name, roleTitle, employeeId, organization, initials)
        .flatMap { it.forwardContactSearchTokens() }
        .any { it.contains(normalizedKeyword) }
}

private fun String.forwardContactSearchTokens(): List<String> {
    val normalized = normalizedForwardContactToken()
    val syllables = mapNotNull { char ->
        when {
            char.isForwardAsciiLetterOrDigit() -> char.lowercaseChar().toString()
            else -> contactPinyinMap[char]
        }
    }
    val fullPinyin = syllables.joinToString("")
    val initials = syllables.mapNotNull { it.firstOrNull()?.toString() }.joinToString("")
    return listOf(normalized, fullPinyin, initials).filter { it.isNotEmpty() }.distinct()
}

private fun String.normalizedForwardContactToken(): String =
    lowercase().filter { it.isForwardAsciiLetterOrDigit() }

private fun Char.isForwardAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

private val forwardContactSectionOrder = listOf("recent", "station", "supervision", "experts", "contacts")

@Composable
private fun MessageSelectActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val fg = if (enabled) Gomob.colors.accent else Gomob.colors.fg3.copy(alpha = 0.5f)
    Box(
        Modifier
            .clip(Gomob.shapes.r2)
            .background(if (enabled) Gomob.colors.accentSoft else Gomob.colors.bg2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s6),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = label, tint = fg, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, style = Gomob.type.caption, color = fg)
        }
    }
}

internal val MessageForwardActionIcon: ImageVector
    get() {
        _messageForwardActionIcon?.let { return it }
        return ImageVector.Builder(
            name = "MessageForwardActionIcon",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                fill = SolidColor(Color.Transparent),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2.1f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            ) {
                moveTo(4.8f, 17.2f)
                curveTo(7.1f, 11.2f, 12.0f, 8.2f, 18.1f, 8.2f)
                moveTo(14.8f, 4.6f)
                lineTo(19.2f, 8.2f)
                lineTo(14.8f, 11.8f)
            }
        }.build().also { _messageForwardActionIcon = it }
    }

private var _messageForwardActionIcon: ImageVector? = null
