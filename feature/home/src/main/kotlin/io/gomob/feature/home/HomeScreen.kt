package io.gomob.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import io.gomob.designsystem.decoration.scanline
import io.gomob.designsystem.decoration.ticks
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

const val HOME_ROUTE = "home"

/**
 * 02 首页 — AI 助手对话页（jsx home.jsx）。
 *
 * 视觉骨架：
 *   1. ScreenHeader "AI 助手 / 大模型辅助作业 · 检测站智能体" + History 按钮
 *   2. 主对话卡（card + ticks 4 角 + AssistantHeader + UserBubble + AssistantBubble
 *      流式态 + RefChip + InlineAction + ModelFooter token 计数）
 *   3. 快捷指令 2×2 网格（QuickAction 编号 01-04 + 标题 + 副）
 *   4. AI 建议关注（3 行 AiWatchRow，左侧 4dp 色条 + VIN mono + AI 注释）
 *   5. 历史会话（3 行 HistoryRow，左侧 24dp turns 数量框）
 *   6. ChatComposer 吸底（+ 加号 + 输入框 + 麦 + accent 发送）
 */
@Composable
fun HomeRoute(onOpenInspection: (String) -> Unit = {}) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Gomob.colors.bg0),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 84.dp),
        ) {
            item {
                ScreenHeader(
                    title = "AI 助手",
                    eyebrow = "大模型辅助作业 · 检测站智能体",
                    trailing = { HistoryIconButton() },
                )
            }
            item { ConversationCard() }
            item { SectionTitle(title = "快捷指令", hint = "长按可置顶") }
            item { QuickActionGrid() }
            item { SectionTitle(title = "AI 建议关注", hint = "助手主动发现 · 4") }
            item { AiWatchCard(onOpenInspection = onOpenInspection) }
            item { SectionTitle(title = "历史会话", hint = "近 7 天") }
            item { HistoryCard() }
            item { Spacer(Modifier.height(Gomob.spacing.s24)) }
        }
        ChatComposer(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun HistoryIconButton() {
    Box(
        Modifier
            .size(Gomob.spacing.touchMin)
            .clickable {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            GomobIcons.History,
            contentDescription = "历史会话",
            tint = Gomob.colors.fg2,
            modifier = Modifier.size(Gomob.spacing.icon20),
        )
    }
}

// ─── 主对话卡 ───────────────────────────────────────────────────────────────
@Composable
private fun ConversationCard() {
    Box(Modifier.padding(horizontal = Gomob.spacing.s20)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
                .ticks(),
        ) {
            // 顶部 1dp 内高光
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Gomob.spacing.hairline)
                    .background(Gomob.colors.hlTop)
                    .align(Alignment.TopCenter),
            )
            Column {
                AssistantHeader()
                FullDivider()
                UserBubble("刚才那台沪A12345的OBD检验异常，是真问题还是误报？")
                FullDivider()
                AssistantBubble(streaming = true)
                FullDivider()
                ModelFooter()
            }
        }
    }
}

@Composable
private fun AssistantHeader() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(Gomob.spacing.avatar28)
                .clip(Gomob.shapes.r1)
                .background(Gomob.colors.accentSoft)
                .border(Gomob.spacing.hairline, Gomob.colors.accentLine, Gomob.shapes.r1),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "AI",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.04.em,
                color = Gomob.colors.accent,
            )
        }
        Column(Modifier.weight(1f)) {
            Text("mob3d 智能助理", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
            Spacer(Modifier.height(Gomob.spacing.s2))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
            ) {
                Box(Modifier.size(Gomob.spacing.dot6).clip(CircleShape).background(Gomob.colors.accent))
                Text("在线 · 思考中", fontSize = 10.sp, color = Gomob.colors.fg2)
                Text("·", fontSize = 10.sp, color = Gomob.colors.fg3)
                Text("0.42s", style = Gomob.type.numInline.copy(fontSize = 10.sp), color = Gomob.colors.fg3)
            }
        }
        // 模型徽 Qwen-Max
        Row(
            Modifier
                .height(Gomob.spacing.chipHeight)
                .clip(Gomob.shapes.r1)
                .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r1)
                .padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
        ) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(Gomob.colors.ok))
            Text("Qwen-Max", fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.04.em, color = Gomob.colors.fg1)
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
    ) {
        Text(
            "你",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.08.em,
            color = Gomob.colors.fg3,
        )
        Spacer(Modifier.height(Gomob.spacing.s4))
        Text(text, fontSize = 13.sp, lineHeight = 20.sp, color = Gomob.colors.fg1)
    }
}

@Composable
private fun AssistantBubble(streaming: Boolean) {
    // 整段顶部水平扫描线（流式态）
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .scanline(streaming),
    ) {
        // 左侧 2dp accent 边
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(Gomob.colors.accent),
        )
        Column(
            Modifier.padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
        ) {
            // 助手 eyebrow
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
            ) {
                Text(
                    "助手",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.08.em,
                    color = Gomob.colors.accent,
                )
                if (streaming) {
                    Text("· 正在生成…", fontSize = 10.sp, color = Gomob.colors.fg3)
                }
            }
            // 第一段：含内嵌 RefChip "3 项"
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
            ) {
                Text(
                    "根据 OBD 实时报文与 ECU 历史，",
                    fontSize = 13.sp,
                    color = Gomob.colors.fg0,
                )
                RefChip("3 项")
            }
            Text(
                "读取到 3 项故障码，其中 P0420 触发 2 次 — 属于催化器效率低于阈值的常见误报模式。",
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = Gomob.colors.fg0,
            )
            // 第二段：含内嵌 accentStrong 数字
            Text(
                buildAnnotatedString {
                    append("建议复核外观与排放外观件，预计置信度 ")
                    withStyle(
                        SpanStyle(
                            color = Gomob.colors.accentStrong,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = (-0.01).em,
                        ),
                    ) { append("87.3%") }
                    append("。")
                },
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = Gomob.colors.fg0,
            )
            // 第三段：3 个 InlineAction
            Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
                InlineAction("调出该车明细")
                InlineAction("查看 OBD 报文")
                InlineAction("标记为误报")
            }
        }
    }
}

@Composable
private fun RefChip(label: String) {
    Row(
        Modifier
            .height(16.dp)
            .clip(Gomob.shapes.r1)
            .background(Gomob.colors.accentSoft)
            .border(Gomob.spacing.hairline, Gomob.colors.accentLine, Gomob.shapes.r1)
            .clickable {}
            .padding(horizontal = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            "↗",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            color = Gomob.colors.accent,
        )
        Text(label, fontSize = 11.sp, color = Gomob.colors.accent)
    }
}

@Composable
private fun InlineAction(label: String) {
    Row(
        Modifier
            .height(26.dp)
            .clip(Gomob.shapes.r1)
            .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r1)
            .clickable {}
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
    ) {
        Text("›", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Gomob.colors.accent)
        Text(label, fontSize = 11.sp, color = Gomob.colors.fg1)
    }
}

@Composable
private fun ModelFooter() {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Gomob.colors.bg2)
            .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s8),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "已联网 · 含本地 OBD 知识库",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.06.em,
            color = Gomob.colors.fg3,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
        ) {
            Text("tokens", fontSize = 10.sp, color = Gomob.colors.fg2)
            Text(
                "1,284 / 8,192",
                style = Gomob.type.numInline.copy(fontSize = 10.sp),
                color = Gomob.colors.fg3,
            )
        }
    }
}

// ─── SectionTitle / 分隔线 ──────────────────────────────────────────────────
@Composable
private fun SectionTitle(title: String, hint: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, top = Gomob.spacing.s20, bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
        Text(
            hint,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.06.em,
            color = Gomob.colors.fg3,
        )
    }
}

@Composable
private fun FullDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(Gomob.spacing.hairline)
            .background(Gomob.colors.line1),
    )
}

@Composable
private fun InsetDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s14)
            .height(Gomob.spacing.hairline)
            .background(Gomob.colors.line1),
    )
}

// ─── 快捷指令 2×2 网格 ──────────────────────────────────────────────────────
private data class QuickActionItem(val k: String, val title: String, val sub: String)

@Composable
private fun QuickActionGrid() {
    val items = listOf(
        QuickActionItem("01", "分析当前预审异常", "基于实时 290 条记录"),
        QuickActionItem("02", "解释 OBD 故障码", "P/U/B/C 全协议"),
        QuickActionItem("03", "生成今日工作日报", "含 KPI · 待复核"),
        QuickActionItem("04", "复核我上一次决定", "过去 24 小时"),
    )
    Column(
        Modifier.padding(horizontal = Gomob.spacing.s20),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
                row.forEach { QuickActionCell(it, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun QuickActionCell(item: QuickActionItem, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
            .ticks()
            .clickable {}
            .padding(start = Gomob.spacing.s12, end = Gomob.spacing.s12, top = Gomob.spacing.s12, bottom = Gomob.spacing.s14),
    ) {
        Column {
            Text(
                item.k,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.14.em,
                color = Gomob.colors.fg3,
            )
            Spacer(Modifier.height(Gomob.spacing.s8))
            Text(
                item.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
                color = Gomob.colors.fg0,
            )
            Spacer(Modifier.height(Gomob.spacing.s6))
            Text(item.sub, fontSize = 10.sp, color = Gomob.colors.fg2)
        }
        Text(
            "›",
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = Gomob.colors.accent,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 10.dp, bottom = 10.dp),
        )
    }
}

// ─── AI 建议关注 ────────────────────────────────────────────────────────────
private data class AiWatchItem(val tone: WatchTone, val vin: String, val note: String, val ts: String)
private enum class WatchTone { Accent, Warn, Danger }

@Composable
private fun AiWatchCard(onOpenInspection: (String) -> Unit) {
    val items = listOf(
        AiWatchItem(WatchTone.Danger, "LSVHM133022221761", "OBD P0420 + 外廓尺寸超差，置信度 87% 建议人工复核", "11:45"),
        AiWatchItem(WatchTone.Warn, "LSVHM41182123456", "VIN 与出厂日期不一致 · 可能为系统录入差异", "12:18"),
        AiWatchItem(WatchTone.Accent, "LSVHM98277661003", "历史 3 次外观异常已排除 · 助手判定正常", "12:42"),
    )
    Box(Modifier.padding(horizontal = Gomob.spacing.s20)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3),
        ) {
            items.forEachIndexed { i, item ->
                AiWatchRow(item, onClick = { onOpenInspection(item.vin) })
                if (i != items.lastIndex) InsetDivider()
            }
        }
    }
}

@Composable
private fun AiWatchRow(item: AiWatchItem, onClick: () -> Unit) {
    val color = when (item.tone) {
        WatchTone.Accent -> Gomob.colors.accent
        WatchTone.Warn -> Gomob.colors.warn
        WatchTone.Danger -> Gomob.colors.danger
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(Gomob.spacing.dot4)
                .fillMaxHeight()
                .background(color),
        )
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    item.vin,
                    style = Gomob.type.numInline.copy(fontSize = 12.sp, letterSpacing = 0.04.em),
                    color = Gomob.colors.fg0,
                )
                Text(
                    item.ts,
                    style = Gomob.type.numInline.copy(fontSize = 10.sp),
                    color = Gomob.colors.fg3,
                )
            }
            Spacer(Modifier.height(Gomob.spacing.s4))
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Text(
                    "AI",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.08.em,
                    color = color,
                    modifier = Modifier.padding(top = 1.dp),
                )
                Text(
                    item.note,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = Gomob.colors.fg1,
                )
            }
        }
    }
}

// ─── 历史会话 ──────────────────────────────────────────────────────────────
private data class HistoryItem(val title: String, val snippet: String, val ts: String, val turns: Int)

@Composable
private fun HistoryCard() {
    val items = listOf(
        HistoryItem("本周 OBD 异常分布分析", "共识别 56 起，集中在 P0420/P0171…", "昨天 17:24", 12),
        HistoryItem("复核 LSVHM412...", "判定为误报，建议放行", "昨天 11:08", 6),
        HistoryItem("生成 5 月第一周日报", "已导出 PDF · 已发送至督察组", "05/06 08:30", 4),
    )
    Box(Modifier.padding(horizontal = Gomob.spacing.s20)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3),
        ) {
            items.forEachIndexed { i, item ->
                HistoryRow(item)
                if (i != items.lastIndex) InsetDivider()
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {}
            .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(Gomob.shapes.r1)
                .background(Gomob.colors.bg2)
                .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r1),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                item.turns.toString(),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Gomob.colors.fg2,
            )
        }
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text(
                    item.title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Gomob.colors.fg0,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Spacer(Modifier.width(Gomob.spacing.s8))
                Text(
                    item.ts,
                    style = Gomob.type.numInline.copy(fontSize = 10.sp),
                    color = Gomob.colors.fg3,
                )
            }
            Spacer(Modifier.height(3.dp))
            Text(
                item.snippet,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = Gomob.colors.fg2,
                maxLines = 1,
            )
        }
    }
}

// ─── ChatComposer 吸底 ──────────────────────────────────────────────────────
@Composable
private fun ChatComposer(modifier: Modifier = Modifier) {
    var draft by remember { mutableStateOf("") }
    Box(
        modifier
            .background(Gomob.colors.bg0)
            .padding(horizontal = Gomob.spacing.s16, vertical = 10.dp),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg2)
                .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
                .padding(horizontal = 10.dp, vertical = Gomob.spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            ComposerIconButton(GomobIcons.Plus, tint = Gomob.colors.fg2)
            Box(Modifier.weight(1f)) {
                if (draft.isEmpty()) {
                    Text(
                        "问助手或输入 / 唤起命令",
                        fontSize = 13.sp,
                        color = Gomob.colors.fg3,
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 13.sp, color = Gomob.colors.fg0),
                    cursorBrush = SolidColor(Gomob.colors.accent),
                )
            }
            ComposerIconButton(GomobIcons.Mic, tint = Gomob.colors.fg2)
            ComposerIconButton(
                icon = GomobIcons.Send,
                tint = Gomob.colors.accent,
                bg = Gomob.colors.accentSoft,
                border = Gomob.colors.accentLine,
            )
        }
    }
}

@Composable
private fun ComposerIconButton(
    icon: ImageVector,
    tint: Color,
    bg: Color = Color.Transparent,
    border: Color = Gomob.colors.line2,
) {
    Box(
        Modifier
            .size(Gomob.spacing.avatar28)
            .clip(Gomob.shapes.r1)
            .background(bg)
            .border(Gomob.spacing.hairline, border, Gomob.shapes.r1)
            .clickable {},
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
    }
}
