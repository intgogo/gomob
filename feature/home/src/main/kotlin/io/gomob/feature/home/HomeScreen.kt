package io.gomob.feature.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.decoration.ticks
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import kotlinx.coroutines.delay

const val HOME_ROUTE = "home"

/**
 * 02 首页 — AI 助手对话页（jsx home.jsx）。
 *
 * 视觉骨架：
 *   1. 固定 ScreenHeader "AI 助手 / 大模型辅助作业 · 检测站智能体" + History 按钮
 *   2. 建议关注（竖排 AiWatchRow + VIN mono + 助手注释）
 *   3. 快速专家 2×2 网格（预定义指令 + 点按开始新会话）
 *   4. ChatComposer 吸底；提交后进入新会话子页
 *   5. 历史会话收进右上角侧窗
 */
@Composable
fun HomeRoute(
    onOpenInspection: (String) -> Unit = {},
    onOpenNewChat: (String) -> Unit = {},
) {
    KeepAiInputStableEffect()

    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var composerActive by rememberSaveable { mutableStateOf(false) }
    val exitKeyboard = rememberExitInput()
    val imeVisible = rememberImeVisible()
    val inputActive = composerActive || imeVisible
    val exitInput = remember(exitKeyboard) {
        {
            composerActive = false
            exitKeyboard()
        }
    }
    BackHandler(enabled = inputActive, onBack = exitInput)

    Box(
        Modifier
            .fillMaxSize()
            .background(Gomob.colors.bg0),
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "AI 助手",
                eyebrow = "大模型辅助作业 · 检测站智能体",
                trailing = {
                    HistoryIconButton(
                        active = historyOpen,
                        onClick = { historyOpen = true },
                    )
                },
            )
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 84.dp),
            ) {
                item { SectionTitle(title = "建议关注", hint = "助手主动发现 · 4") }
                item { AiWatchCard(onOpenInspection = onOpenInspection) }
                item { SectionTitle(title = "快速专家", hint = "点按开始新会话") }
                item { QuickActionGrid(onSelect = onOpenNewChat) }
                item { Spacer(Modifier.height(Gomob.spacing.s24)) }
            }
        }
        InputScrim(
            visible = inputActive,
            onDismiss = exitInput,
        )
        ChatComposer(
            onSubmit = onOpenNewChat,
            active = inputActive,
            onActiveChange = { composerActive = it },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
        )
        HistoryPanel(
            visible = historyOpen,
            onDismiss = { historyOpen = false },
        )
    }
}

@Composable
fun HomeAiChatRoute(
    initialPrompt: String,
    onBack: () -> Unit,
) {
    KeepAiInputStableEffect()

    var prompts by rememberSaveable(initialPrompt) { mutableStateOf(listOf(initialPrompt)) }
    var composerActive by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val exitKeyboard = rememberExitInput()
    val imeVisible = rememberImeVisible()
    val inputActive = composerActive || imeVisible
    val exitInput = remember(exitKeyboard) {
        {
            composerActive = false
            exitKeyboard()
        }
    }
    BackHandler(enabled = inputActive, onBack = exitInput)

    LaunchedEffect(prompts.size) {
        if (prompts.isNotEmpty()) {
            listState.animateScrollToItem(prompts.lastIndex)
        }
    }

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = "新会话",
            eyebrow = "AI 助手",
            onBack = onBack,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .dismissInputOnTap(exitInput),
            contentPadding = PaddingValues(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            itemsIndexed(prompts) { index, prompt ->
                ChatTurn(
                    prompt = prompt,
                    streaming = index == prompts.lastIndex,
                    turnNumber = index + 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        ChatComposer(
            onSubmit = { prompt -> prompts = prompts + prompt },
            active = inputActive,
            onActiveChange = { composerActive = it },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun HistoryIconButton(active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Gomob.spacing.touchMin)
            .clip(Gomob.shapes.r1)
            .background(if (active) Gomob.colors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            GomobIcons.History,
            contentDescription = "历史会话",
            tint = if (active) Gomob.colors.accent else Gomob.colors.fg2,
            modifier = Modifier.size(Gomob.spacing.icon20),
        )
    }
}

@Composable
private fun HistoryPanel(visible: Boolean, onDismiss: () -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            HistoryPanelContent()
        }
    }
}

@Composable
private fun HistoryPanelContent() {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val drawerClickSource = remember { MutableInteractionSource() }
    fun dismissInput() {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    Column(
        Modifier
            .fillMaxWidth(0.82f)
            .fillMaxHeight()
            .background(Gomob.colors.bg1)
            .clickable(
                interactionSource = drawerClickSource,
                indication = null,
                onClick = { dismissInput() },
            ),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "HISTORY",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.14.em,
                    color = Gomob.colors.fg3,
                )
                Spacer(Modifier.height(Gomob.spacing.s2))
                Text(
                    "历史会话",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = Gomob.colors.fg0,
                )
            }
        }
        FullDivider()
        Column(
            Modifier
                .weight(1f)
                .padding(18.dp),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Text("近 7 天", fontSize = 12.sp, color = Gomob.colors.fg2)
                Text(
                    "${HISTORY_ITEMS.size}",
                    style = Gomob.type.numInline.copy(fontSize = 18.sp),
                    color = Gomob.colors.accent,
                )
            }
            Column(
                Modifier
                    .clip(Gomob.shapes.r3)
                    .background(Gomob.colors.bg2),
            ) {
                HISTORY_ITEMS.forEachIndexed { i, item ->
                    HistoryRow(item)
                    if (i != HISTORY_ITEMS.lastIndex) FullDivider()
                }
            }
        }
    }
}

// ─── 主对话卡 ───────────────────────────────────────────────────────────────
@Composable
private fun ChatTurn(
    prompt: String,
    streaming: Boolean,
    turnNumber: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        UserMessageBubble(prompt = prompt, turnNumber = turnNumber)
        AssistantMessageCard(prompt = prompt, streaming = streaming)
    }
}

@Composable
private fun UserMessageBubble(prompt: String, turnNumber: Int) {
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            "你 · ${turnNumber.toString().padStart(2, '0')}",
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.08.em,
            color = Gomob.colors.fg3,
        )
        Spacer(Modifier.height(Gomob.spacing.s4))
        Box(
            Modifier
                .fillMaxWidth(0.86f)
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.accentSoft)
                .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
        ) {
            Text(prompt, fontSize = 13.sp, lineHeight = 20.sp, color = Gomob.colors.fg0)
        }
    }
}

@Composable
private fun AssistantMessageCard(
    prompt: String,
    streaming: Boolean,
) {
    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .ticks(),
        ) {
            Column {
                AssistantBubble(prompt = prompt, streaming = streaming)
            }
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
private fun AssistantBubble(prompt: String, streaming: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
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
                    assistantLead(prompt),
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

private fun assistantLead(prompt: String): String = when {
    prompt.contains("日报") -> "我会按预审、外检、OBD 与复核结果汇总，"
    prompt.contains("复核") -> "我会对比上一轮判定、证据链与放行规则，"
    prompt.contains("OBD", ignoreCase = true) || prompt.contains("故障码") -> "根据 OBD 实时报文与 ECU 历史，"
    prompt.contains("异常") -> "我会先按异常等级、触发来源与历史记录排序，"
    else -> "我已结合当前会话上下文与本地知识库，"
}

@Composable
private fun RefChip(label: String) {
    Row(
        Modifier
            .height(16.dp)
            .clip(Gomob.shapes.r1)
            .background(Gomob.colors.accentSoft)
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
            .background(Gomob.colors.bg2)
            .clickable {}
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
    ) {
        Text("›", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Gomob.colors.accent)
        Text(label, fontSize = 11.sp, color = Gomob.colors.fg1)
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
            .background(Gomob.colors.line1.copy(alpha = 0.03f)),
    )
}

@Composable
private fun InsetDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = Gomob.spacing.s14)
            .height(Gomob.spacing.hairline)
            .background(Gomob.colors.line1.copy(alpha = 0.03f)),
    )
}

// ─── 快速专家 2×2 网格 ──────────────────────────────────────────────────────
private data class QuickActionItem(val k: String, val title: String, val sub: String)

@Composable
private fun QuickActionGrid(onSelect: (String) -> Unit) {
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
                row.forEach {
                    QuickActionCell(
                        item = it,
                        onClick = { onSelect(it.title) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickActionCell(
    item: QuickActionItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .ticks()
            .clickable(onClick = onClick)
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
private data class AiWatchItem(val vin: String, val note: String, val ts: String)

@Composable
private fun AiWatchCard(onOpenInspection: (String) -> Unit) {
    val watchItems = listOf(
        AiWatchItem("LSVHM133022221761", "OBD P0420 + 外廓尺寸超差，置信度 87% 建议人工复核", "11:45"),
        AiWatchItem("LSVHM41182123456", "VIN 与出厂日期不一致 · 可能为系统录入差异", "12:18"),
        AiWatchItem("LSVHM98277661003", "历史 3 次外观异常已排除 · 助手判定正常", "12:42"),
        AiWatchItem("LSVHM72811490562", "同批次车辆灯光角度偏差集中出现 · 建议抽样复查", "13:05"),
    )
    Box(Modifier.padding(horizontal = Gomob.spacing.s20)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1),
        ) {
            watchItems.forEachIndexed { i, item ->
                AiWatchRow(item, onClick = { onOpenInspection(item.vin) })
                if (i != watchItems.lastIndex) InsetDivider()
            }
        }
    }
}

@Composable
private fun AiWatchRow(item: AiWatchItem, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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
                    color = Gomob.colors.accent,
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

private val HISTORY_ITEMS = listOf(
    HistoryItem("本周 OBD 异常分布分析", "共识别 56 起，集中在 P0420/P0171…", "昨天 17:24", 12),
    HistoryItem("复核 LSVHM412...", "判定为误报，建议放行", "昨天 11:08", 6),
    HistoryItem("生成 5 月第一周日报", "已导出 PDF · 已发送至督察组", "05/06 08:30", 4),
)

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
                .background(Gomob.colors.bg2),
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
private fun ChatComposer(
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    onActiveChange: (Boolean) -> Unit = {},
) {
    var draft by remember { mutableStateOf("") }
    val exitInput = rememberExitInput()
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }
    var localActive by rememberSaveable { mutableStateOf(false) }
    var focusRequestVersion by remember { mutableStateOf(0) }
    val inputClickSource = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val navigationBottom = WindowInsets.navigationBars.getBottom(density)
    val keyboardOffset = (imeBottom - navigationBottom).coerceAtLeast(0)
    val imeVisible = imeBottom > 0
    val expanded = active || localActive || imeVisible
    val bottomPadding = if (imeVisible) Gomob.spacing.s8 else 10.dp

    fun showKeyboard() {
        keyboardController?.show()
        view.post {
            val imm = view.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun activateInput() {
        localActive = true
        onActiveChange(true)
        focusRequestVersion += 1
    }

    fun submitDraft() {
        val text = draft.trim()
        if (text.isEmpty()) {
            localActive = false
            onActiveChange(false)
            exitInput()
            return
        }
        draft = ""
        localActive = false
        onActiveChange(false)
        exitInput()
        onSubmit(text)
    }

    LaunchedEffect(active, imeVisible) {
        if (!active && !imeVisible) {
            localActive = false
        }
    }

    LaunchedEffect(expanded, focusRequestVersion) {
        if (expanded && focusRequestVersion > 0) {
            delay(40)
            focusRequester.requestFocus()
            showKeyboard()
            delay(120)
            focusRequester.requestFocus()
            showKeyboard()
        }
    }

    Box(
        modifier
            .zIndex(10f)
            .offset { IntOffset(x = 0, y = -keyboardOffset) }
            .background(Gomob.colors.bg0)
            .clickable(
                interactionSource = inputClickSource,
                indication = null,
                onClick = { activateInput() },
            )
            .padding(
                start = Gomob.spacing.s16,
                end = Gomob.spacing.s16,
                top = if (expanded) Gomob.spacing.s8 else 10.dp,
                bottom = bottomPadding,
            ),
    ) {
        if (expanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(Gomob.shapes.r3)
                    .background(Gomob.colors.bg1)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 92.dp, max = 136.dp)
                        .clickable(
                            interactionSource = inputClickSource,
                            indication = null,
                            onClick = { activateInput() },
                        )
                        .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (draft.isEmpty()) {
                        Text(
                            "问助手或输入 / 唤起命令",
                            fontSize = 13.sp,
                            lineHeight = 20.sp,
                            color = Gomob.colors.fg3,
                        )
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 68.dp, max = 112.dp)
                            .focusRequester(focusRequester)
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    localActive = true
                                    onActiveChange(true)
                                }
                            },
                        singleLine = false,
                        minLines = 3,
                        maxLines = 5,
                        textStyle = TextStyle(fontSize = 13.sp, lineHeight = 20.sp, color = Gomob.colors.fg0),
                        cursorBrush = SolidColor(Gomob.colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    )
                }
                Spacer(Modifier.height(Gomob.spacing.s2))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .padding(horizontal = Gomob.spacing.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposerIconButton(
                        icon = GomobIcons.Plus,
                        tint = Gomob.colors.fg2,
                        size = 32.dp,
                        iconSize = 16.dp,
                    )
                    Spacer(Modifier.weight(1f))
                    ComposerIconButton(
                        icon = GomobIcons.Mic,
                        tint = Gomob.colors.fg2,
                        size = 32.dp,
                        iconSize = 16.dp,
                    )
                    Spacer(Modifier.width(Gomob.spacing.s8))
                    ComposerIconButton(
                        icon = GomobIcons.Send,
                        tint = Gomob.colors.accent,
                        bg = Gomob.colors.accentSoft,
                        size = 32.dp,
                        iconSize = 16.dp,
                        onClick = { submitDraft() },
                    )
                }
            }
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Gomob.shapes.r3)
                    .background(Gomob.colors.bg2)
                    .padding(horizontal = 10.dp, vertical = Gomob.spacing.s8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                ComposerIconButton(GomobIcons.Plus, tint = Gomob.colors.fg2)
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = Gomob.spacing.avatar28)
                        .clickable(
                            interactionSource = inputClickSource,
                            indication = null,
                            onClick = { activateInput() },
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        draft.ifEmpty { "问助手或输入 / 唤起命令" },
                        fontSize = 13.sp,
                        color = if (draft.isEmpty()) Gomob.colors.fg3 else Gomob.colors.fg0,
                        maxLines = 1,
                    )
                }
                ComposerIconButton(GomobIcons.Mic, tint = Gomob.colors.fg2)
                ComposerIconButton(
                    icon = GomobIcons.Send,
                    tint = Gomob.colors.accent,
                    bg = Gomob.colors.accentSoft,
                    onClick = { submitDraft() },
                )
            }
        }
    }
}

@Composable
private fun KeepAiInputStableEffect() {
    val activity = LocalView.current.context.findActivity()
    DisposableEffect(activity) {
        val window = activity?.window
        val previousMode = window?.attributes?.softInputMode
        val stableMode = previousMode
            ?.and(WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE)
            ?.or(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            ?: WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        window?.setSoftInputMode(stableMode)
        onDispose {
            if (previousMode != null) {
                window?.setSoftInputMode(previousMode)
            }
        }
    }
}

@Composable
private fun DismissImeOnBack() {
    val exitInput = rememberExitInput()
    val imeVisible = rememberImeVisible()

    BackHandler(enabled = imeVisible, onBack = exitInput)
}

@Composable
private fun rememberImeVisible(): Boolean {
    val density = LocalDensity.current
    return WindowInsets.ime.getBottom(density) > 0
}

@Composable
private fun InputScrim(visible: Boolean, onDismiss: () -> Unit) {
    if (!visible) return
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.1f))
            .clickable(onClick = onDismiss),
    )
}

@Composable
private fun rememberExitInput(): () -> Unit {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    return remember(focusManager, keyboardController) {
        {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Modifier.dismissInputOnTap(exitInput: () -> Unit): Modifier =
    pointerInput(exitInput) {
        detectTapGestures(onTap = { exitInput() })
    }

@Composable
private fun ComposerIconButton(
    icon: ImageVector,
    tint: Color,
    bg: Color = Color.Transparent,
    size: Dp = Gomob.spacing.avatar28,
    iconSize: Dp = 14.dp,
    onClick: () -> Unit = {},
) {
    Box(
        Modifier
            .size(size)
            .clip(Gomob.shapes.r1)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}
