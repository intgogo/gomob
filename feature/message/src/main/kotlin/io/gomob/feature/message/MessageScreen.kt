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
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.decoration.ticks
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

const val MESSAGE_ROUTE = "message"

private enum class MsgTab { List, Help }

/**
 * 03 消息中心 — jsx message.jsx。
 *
 * 视觉骨架:
 *   ScreenHeader "消息中心 / 实时协同 · 监管督查 · 专家会审" + Compose 按钮
 *   Segmented Tab 2 选 1: 消息列表 4 / 在线求助
 *   - tab=List: 搜索框 (Search + 输入 + ⌘K) + 7 行 MsgRow (38×38 头像方框)
 *   - tab=Help: 当前参与 (4 ExpertChip 44×44 + 邀请 + 录制中) + 3 ChatBubble + ComposerBar
 */
@Composable
fun MessageRoute(onOpenConversation: (String) -> Unit = {}) {
    var tab by remember { mutableStateOf(MsgTab.List) }
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        ScreenHeader(
            title = "消息中心",
            eyebrow = "实时协同 · 监管督查 · 专家会审",
            trailing = { ComposeIconButton() },
        )
        SegmentedTabs(
            tab = tab,
            onChange = { tab = it },
        )
        when (tab) {
            MsgTab.List -> ListPane(onOpenConversation = onOpenConversation)
            MsgTab.Help -> HelpPane()
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

// ─── Segmented ──────────────────────────────────────────────────────────────
@Composable
private fun SegmentedTabs(tab: MsgTab, onChange: (MsgTab) -> Unit) {
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
            divider = true,
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
                    "4",
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
            divider = false,
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
    divider: Boolean,
    content: @Composable () -> Unit,
) {
    Row(
        modifier
            .height(36.dp)
            .background(if (active) Gomob.colors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .let {
                if (divider) it.drawRightDivider() else it
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) { content() }
}

private fun Modifier.drawRightDivider(): Modifier = this.then(
    Modifier.padding(end = 0.dp), // placeholder; 下一行用 Box 实现真实分隔
)

// ─── List Pane ──────────────────────────────────────────────────────────────
private data class MsgRowData(
    val name: String,
    val initials: String,
    val preview: String,
    val time: String,
    val unread: Int = 0,
    val badge: WatchTone = WatchTone.Neutral,
    val kind: AvatarKind = AvatarKind.Neutral,
)

private enum class AvatarKind { System, Call, Video, Image, Neutral }
private enum class WatchTone { Accent, Warn, Danger, Ok, Neutral }

private val MESSAGES = listOf(
    MsgRowData("监管中心", "监", "[实时审核] 异常补拍提醒", "17:02", 3, WatchTone.Danger, AvatarKind.System),
    MsgRowData("周科", "周", "[视频通话  56:02]", "17:11", kind = AvatarKind.Call),
    MsgRowData("系统消息", "系", "专家会审 CLCY2025052089757 已完成会审", "16:34", kind = AvatarKind.System),
    MsgRowData("吴风", "吴", "[视频消息]", "16:20", kind = AvatarKind.Video),
    MsgRowData("刘沿", "刘", "[图片] 第 3 工位 VIN 拓印", "16:11", 1, WatchTone.Warn, AvatarKind.Image),
    MsgRowData("江庆幸", "江", "这是一条文字消息", "16:08"),
    MsgRowData("张老师", "张", "今晚培训记得参加", "16:20"),
)

@Composable
private fun ListPane(onOpenConversation: (String) -> Unit) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Gomob.spacing.s24),
    ) {
        item {
            // 搜索框
            Box(
                Modifier
                    .padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, bottom = 14.dp)
                    .fillMaxWidth(),
            ) {
                SearchBar()
            }
        }
        items(MESSAGES.size) { i ->
            val item = MESSAGES[i]
            Box(
                Modifier
                    .padding(horizontal = Gomob.spacing.s20)
                    .padding(bottom = Gomob.spacing.s8),
            ) {
                MsgRow(item, onClick = { onOpenConversation(item.name) })
            }
        }
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
        // ⌘K mono badge
        Box(
            Modifier
                .clip(Gomob.shapes.r1)
                .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r1)
                .padding(horizontal = Gomob.spacing.s4, vertical = 1.dp),
        ) {
            Text(
                "⌘K",
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.1.em,
                color = Gomob.colors.fg3,
            )
        }
    }
}

@Composable
private fun MsgRow(item: MsgRowData, onClick: () -> Unit) {
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
        MsgAvatar(initials = item.initials, kind = item.kind)
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Gomob.colors.fg0,
                )
                Text(
                    item.time,
                    style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.06.em),
                    color = Gomob.colors.fg3,
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
                if (item.unread > 0) {
                    UnreadBadge(item.unread, item.badge)
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
private fun UnreadBadge(unread: Int, tone: WatchTone) {
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
            .height(Gomob.spacing.icon16)
            .clip(Gomob.shapes.pill)
            .background(color)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            unread.toString(),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = Color.Black,
        )
    }
}

// ─── Help Pane ──────────────────────────────────────────────────────────────
private data class Expert(
    val initials: String,
    val name: String,
    val role: String,
    val status: ExpertStatus,
)

private enum class ExpertStatus { Online, Busy, Offline }

private val EXPERTS = listOf(
    Expert("周", "周科", "OBD 主审", ExpertStatus.Online),
    Expert("吴", "吴风", "外观件专家", ExpertStatus.Online),
    Expert("刘", "刘沿", "VIN 拓印", ExpertStatus.Busy),
    Expert("张", "张老师", "总督察", ExpertStatus.Offline),
)

@Composable
private fun HelpPane() {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Gomob.spacing.s24),
    ) {
        item { ExpertParticipantCard() }
        item { Spacer(Modifier.height(Gomob.spacing.s12)) }
        item { ChatBubbles() }
        item { Spacer(Modifier.height(Gomob.spacing.s12)) }
        item { ChatComposerBar() }
    }
}

@Composable
private fun ExpertParticipantCard() {
    Column(
        Modifier.padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20),
    ) {
        Text(
            "当前参与 · ${EXPERTS.count { it.status == ExpertStatus.Online }} 在线",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.14.em,
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
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    EXPERTS.forEach { ExpertChip(it) }
                    InvitePlusBox()
                }
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(Gomob.spacing.hairline)
                        .background(Gomob.colors.line1),
                )
                Spacer(Modifier.height(Gomob.spacing.s12))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "会话 #CLCY2025052089757",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.04.em,
                        color = Gomob.colors.fg3,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
                    ) {
                        Text(
                            "● 录制中",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.04.em,
                            color = Gomob.colors.ok,
                        )
                        Text(
                            "· 12:34",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.04.em,
                            color = Gomob.colors.fg3,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpertChip(e: Expert) {
    val dot = when (e.status) {
        ExpertStatus.Online -> Gomob.colors.ok
        ExpertStatus.Busy -> Gomob.colors.warn
        ExpertStatus.Offline -> Gomob.colors.fg3
    }
    Column(
        Modifier.width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                Modifier
                    .size(44.dp)
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.accentSoft)
                    .border(Gomob.spacing.hairline, Gomob.colors.accentLine, Gomob.shapes.r2),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    e.initials,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Medium,
                    color = Gomob.colors.accentStrong.copy(
                        alpha = if (e.status == ExpertStatus.Offline) 0.5f else 1f,
                    ),
                )
            }
            // 状态点 9dp 凸出右下，2dp bg1 边
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(dot)
                    .border(2.dp, Gomob.colors.bg1, CircleShape),
            )
        }
        Text(e.name, fontSize = 11.sp, color = Gomob.colors.fg0)
        Text(
            e.role,
            fontSize = 9.sp,
            color = Gomob.colors.fg3,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun InvitePlusBox() {
    Box(
        Modifier
            .size(44.dp)
            .clip(Gomob.shapes.r2)
            .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2)
            .clickable {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            GomobIcons.Plus,
            contentDescription = "邀请",
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(Gomob.spacing.icon16),
        )
    }
}

// ─── ChatBubbles ────────────────────────────────────────────────────────────
@Composable
private fun ChatBubbles() {
    Column(
        Modifier.padding(horizontal = Gomob.spacing.s20),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ChatBubbleOther("周", "周科", BubbleTone.Accent, "12:30") {
            Text(
                "沪A12345 这台 OBD 报 P0420，你那边的 ECU 历史数据能调出来吗？",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Gomob.colors.fg1,
            )
        }
        ChatBubbleMe("12:31") {
            Text(
                buildAnnotatedString {
                    append("已上传，")
                    withStyle(SpanStyle(color = Color.Unspecified)) {
                        // 其实这里要嵌一个 BubbleRef chip - 但 AnnotatedString 不能嵌入
                        // 复杂 Composable, 用 Row 拆分
                        append("[报文.bin · 287 KB]")
                    }
                    append("，请查收")
                },
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Gomob.colors.fg0,
            )
        }
        ChatBubbleOther("吴", "吴风", BubbleTone.Warn, "12:33") {
            Text(
                "外观件我看了，第 3 张图右后翼子板有补漆痕迹",
                fontSize = 12.sp,
                lineHeight = 18.sp,
                color = Gomob.colors.fg1,
            )
        }
    }
}

private enum class BubbleTone { Accent, Warn, Danger, Ok }

@Composable
private fun ChatBubbleOther(
    initials: String,
    name: String,
    tone: BubbleTone,
    time: String,
    body: @Composable () -> Unit,
) {
    val color = when (tone) {
        BubbleTone.Accent -> Gomob.colors.accent
        BubbleTone.Warn -> Gomob.colors.warn
        BubbleTone.Danger -> Gomob.colors.danger
        BubbleTone.Ok -> Gomob.colors.ok
    }
    Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
        Box(
            Modifier
                .size(Gomob.spacing.avatar28)
                .clip(Gomob.shapes.r1)
                .background(Gomob.colors.bg3)
                .border(Gomob.spacing.hairline, color, Gomob.shapes.r1),
            contentAlignment = Alignment.Center,
        ) {
            Text(initials, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color)
        }
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                name,
                fontSize = 10.sp,
                color = Gomob.colors.fg3,
                modifier = Modifier.padding(bottom = 3.dp),
            )
            Box(
                Modifier
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.bg2)
                    .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r2)
                    .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
            ) {
                body()
            }
            Text(
                time,
                style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.06.em),
                color = Gomob.colors.fg3,
                modifier = Modifier.padding(top = Gomob.spacing.s6),
            )
        }
    }
}

@Composable
private fun ChatBubbleMe(time: String, body: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            horizontalAlignment = Alignment.End,
        ) {
            Box(
                Modifier
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.accentSoft)
                    .border(Gomob.spacing.hairline, Gomob.colors.accentLine, Gomob.shapes.r2)
                    .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
            ) {
                body()
            }
            Text(
                time,
                style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.06.em),
                color = Gomob.colors.fg3,
                modifier = Modifier.padding(top = Gomob.spacing.s6),
            )
        }
    }
}

// ─── ChatComposerBar (HelpPane 底部) ────────────────────────────────────────
@Composable
private fun ChatComposerBar() {
    var draft by remember { mutableStateOf("") }
    Box(Modifier.padding(horizontal = Gomob.spacing.s20)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
                .padding(horizontal = 10.dp, vertical = Gomob.spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            ComposerBtn(GomobIcons.Plus, tint = Gomob.colors.fg2)
            Box(Modifier.weight(1f)) {
                if (draft.isEmpty()) {
                    Text(
                        "向当前 4 位专家发送…",
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
            ComposerBtn(GomobIcons.Mic, tint = Gomob.colors.fg2)
            ComposerBtn(
                icon = GomobIcons.Send,
                tint = Gomob.colors.accent,
                bg = Gomob.colors.accentSoft,
                border = Gomob.colors.accentLine,
            )
        }
    }
}

@Composable
private fun ComposerBtn(
    icon: ImageVector,
    tint: Color,
    bg: Color = Color.Transparent,
    border: Color = Gomob.colors.line2,
) {
    Box(
        Modifier
            .size(30.dp)
            .clip(Gomob.shapes.r1)
            .background(bg)
            .border(Gomob.spacing.hairline, border, Gomob.shapes.r1)
            .clickable {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
    }
}
