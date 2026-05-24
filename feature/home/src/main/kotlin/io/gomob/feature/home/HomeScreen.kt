package io.gomob.feature.home

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.ime
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

const val HOME_ROUTE = "home"

/**
 * 02 首页 — 车辆检验智能体对话入口。
 *
 * 视觉骨架：
 *   1. ScreenHeader "车辆检验智能体" + History 按钮
 *   2. 智能体能力 SectionTitle + 三项核心能力卡（外观改装 / 车架号伪刻 / 标牌伪造）
 *   3. ChatComposer 吸底（内置相机入口）；提交后进入新会话子页
 *   4. 历史会话收进右上角侧窗
 */
@Composable
fun HomeRoute(
    onOpenInspection: (String) -> Unit = {},
    onOpenNewChat: (String, String?) -> Unit = { _, _ -> },
    onOpenAgent: (String) -> Unit = {},
) {
    KeepAiInputStableEffect()

    var historyOpen by rememberSaveable { mutableStateOf(false) }
    var composerActive by rememberSaveable { mutableStateOf(false) }
    var attachedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var requestCapture by remember { mutableStateOf(false) }
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview(),
    ) { bmp ->
        if (bmp != null) {
            attachedBitmap = bmp
            composerActive = true
        }
        requestCapture = false
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            captureLauncher.launch(null)
        } else {
            requestCapture = false
        }
    }
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    var bottomAnchorInsetPx by remember { mutableStateOf<Int?>(null) }
    val bottomAnchorInset = bottomAnchorInsetPx
        ?.let { with(density) { it.toDp() } }
        ?: Gomob.spacing.tabBarHeight
    LaunchedEffect(requestCapture) {
        if (requestCapture) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                captureLauncher.launch(null)
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    val exitKeyboard = rememberExitInput()
    val exitInput = remember(exitKeyboard) {
        {
            composerActive = false
            exitKeyboard()
        }
    }
    BackHandler(enabled = composerActive, onBack = exitInput)

    Box(
        Modifier
            .fillMaxSize()
            .trackWindowBottomInset(view) { bottomAnchorInsetPx = it }
            .consumeWindowInsets(WindowInsets.ime)
            .background(Gomob.colors.bg0),
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "AI 助手",
                eyebrow = "车辆检验智能体 · 检测站合规识别",
                trailing = {
                    HistoryIconButton(
                        active = historyOpen,
                        onClick = { historyOpen = true },
                    )
                },
            )
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                val compact = maxWidth < 360.dp
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = if (compact) 104.dp else 112.dp),
                ) {
                    item { AgentIntroCard(compact = compact) }
                    item { Spacer(Modifier.height(if (compact) Gomob.spacing.s12 else Gomob.spacing.s16)) }
                    item {
                        AgentCapabilityList(
                            onSelect = { onOpenAgent(it.key) },
                            compact = compact,
                        )
                    }
                    item { Spacer(Modifier.height(if (compact) Gomob.spacing.s16 else Gomob.spacing.s24)) }
                }
            }
        }
        InputScrim(
            visible = composerActive,
            onDismiss = exitInput,
        )
        ChatComposer(
            onSubmit = { text ->
                val token = attachedBitmap?.let(HomeImageHolder::put)
                attachedBitmap = null
                onOpenNewChat(text, token)
            },
            attachedBitmap = attachedBitmap,
            onClearAttachment = { attachedBitmap = null },
            onCameraClick = { requestCapture = true },
            active = composerActive,
            onActiveChange = { composerActive = it },
            bottomAnchorInset = bottomAnchorInset,
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
    imageToken: String? = null,
    agentKey: String? = null,
    onBack: () -> Unit,
) {
    KeepAiInputStableEffect()

    val agent = remember(agentKey) { findAgentCapability(agentKey) }
    val seedPrompts = remember(initialPrompt) {
        if (initialPrompt.isBlank()) emptyList() else listOf(initialPrompt)
    }
    var prompts by rememberSaveable(initialPrompt, agentKey) { mutableStateOf(seedPrompts) }
    var composerActive by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val view = LocalView.current
    val density = LocalDensity.current
    var bottomAnchorInsetPx by remember { mutableStateOf<Int?>(null) }
    val bottomAnchorInset = bottomAnchorInsetPx
        ?.let { with(density) { it.toDp() } }
        ?: 0.dp
    // 仅在首次 enter 时把 token 兑换成 Bitmap；之后 token 在 holder 里被移除，避免泄漏。
    val attached = remember(imageToken) { imageToken?.let(HomeImageHolder::take) }
    val exitKeyboard = rememberExitInput()
    val exitInput = remember(exitKeyboard) {
        {
            composerActive = false
            exitKeyboard()
        }
    }
    BackHandler(enabled = composerActive, onBack = exitInput)

    LaunchedEffect(prompts.size) {
        if (prompts.isNotEmpty()) {
            val agentIntroOffset = if (agent != null) 1 else 0
            listState.animateScrollToItem(prompts.lastIndex + agentIntroOffset)
        }
    }

    val headerTitle = when {
        agent != null -> agent.title
        attached != null -> "拍图问答"
        else -> "新会话"
    }

    Column(
        Modifier
            .fillMaxSize()
            .trackWindowBottomInset(view) { bottomAnchorInsetPx = it }
            .background(Gomob.colors.bg0)
    ) {
        BackHeader(
            title = headerTitle,
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
            if (agent != null) {
                item { AgentIntroBubble(agent = agent, modifier = Modifier.fillMaxWidth()) }
            }
            itemsIndexed(prompts) { index, prompt ->
                ChatTurn(
                    prompt = prompt,
                    streaming = index == prompts.lastIndex,
                    turnNumber = index + 1,
                    firstTurnImage = if (index == 0 && agent == null) attached else null,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        ChatComposer(
            onSubmit = { prompt -> prompts = prompts + prompt },
            active = composerActive,
            onActiveChange = { composerActive = it },
            bottomAnchorInset = bottomAnchorInset,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 进入专攻智能体会话时的助手开场气泡：图标 + 头衔 + 自我介绍。 */
@Composable
private fun AgentIntroBubble(agent: AgentCapability, modifier: Modifier = Modifier) {
    Box(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .ticks(),
        ) {
            Column(
                Modifier.padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                ) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(Gomob.shapes.r2)
                            .background(Gomob.colors.accentSoft),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            agent.icon,
                            contentDescription = null,
                            tint = Gomob.colors.accent,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            "助手 · ${agent.k}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.08.em,
                            color = Gomob.colors.accent,
                        )
                        Text(
                            agent.title,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Gomob.colors.fg0,
                        )
                    }
                }
                Text(
                    agent.intro,
                    fontSize = 13.sp,
                    lineHeight = 21.sp,
                    color = Gomob.colors.fg0,
                )
            }
        }
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
    firstTurnImage: Bitmap? = null,
) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        UserMessageBubble(
            prompt = prompt,
            turnNumber = turnNumber,
            image = firstTurnImage,
        )
        if (firstTurnImage != null && isVehicleDossierAsk(prompt)) {
            VehicleDossierCard()
        }
        AssistantMessageCard(
            prompt = prompt,
            streaming = streaming,
            hasImage = firstTurnImage != null,
        )
    }
}

private fun isVehicleDossierAsk(prompt: String): Boolean {
    val keys = listOf("档案", "VIN", "vin", "车型号", "车型", "品牌", "铭牌", "基础信息", "出厂")
    return keys.any { prompt.contains(it) }
}

@Composable
private fun UserMessageBubble(prompt: String, turnNumber: Int, image: Bitmap? = null) {
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
        if (image != null) {
            Box(
                Modifier
                    .fillMaxWidth(0.62f)
                    .clip(Gomob.shapes.r3)
                    .background(Gomob.colors.bg2),
            ) {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "你发送的图片",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(Gomob.spacing.s6))
        }
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
private fun VehicleDossierCard() {
    val rows = listOf(
        "VIN" to "LSVHM133022221761",
        "车型号" to "SVW7186LJD",
        "品牌" to "上汽大众",
        "出厂日期" to "2021/07/18",
        "车辆颜色" to "极地白",
    )
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .ticks()
                .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "VEHICLE DOSSIER",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.14.em,
                    color = Gomob.colors.accent,
                )
                Text(
                    "识别置信度 92%",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Gomob.colors.fg3,
                )
            }
            Spacer(Modifier.height(2.dp))
            rows.forEach { (label, value) ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(label, fontSize = 11.sp, color = Gomob.colors.fg3)
                    Text(
                        value,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Gomob.colors.fg0,
                    )
                }
            }
        }
    }
}

@Composable
private fun AssistantMessageCard(
    prompt: String,
    streaming: Boolean,
    hasImage: Boolean = false,
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
                AssistantBubble(prompt = prompt, streaming = streaming, hasImage = hasImage)
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
private fun AssistantBubble(prompt: String, streaming: Boolean, hasImage: Boolean = false) {
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
                    assistantLead(prompt, hasImage),
                    fontSize = 13.sp,
                    color = Gomob.colors.fg0,
                )
                RefChip(if (hasImage) "图 + 上下文" else "3 项")
            }
            Text(
                assistantBody(prompt, hasImage),
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = Gomob.colors.fg0,
            )
            Text(
                buildAnnotatedString {
                    append("供你参考，可信度 ")
                    withStyle(
                        SpanStyle(
                            color = Gomob.colors.accentStrong,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = (-0.01).em,
                        ),
                    ) { append(if (hasImage) "92.4%" else "87.3%") }
                    append("。")
                },
                fontSize = 13.sp,
                lineHeight = 21.sp,
                color = Gomob.colors.fg0,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
                assistantActions(prompt, hasImage).forEach { InlineAction(it) }
            }
        }
    }
}

private fun assistantLead(prompt: String, hasImage: Boolean = false): String = when {
    hasImage && isVehicleDossierAsk(prompt) -> "已识别图中车辆并比对档案，"
    hasImage && (prompt.contains("修") || prompt.contains("维修")) -> "结合图中可见状态与常见维修流程，"
    hasImage && (prompt.contains("价") || prompt.contains("估")) -> "基于图中车型外观与近期市场行情，"
    hasImage && (prompt.contains("是什么") || prompt.contains("识别")) -> "我对照知识库识别了图中的主要对象，"
    hasImage -> "我已分析你给出的图片，结合上下文整理出关键信息，"
    prompt.contains("日报") -> "我会按预审、外检、OBD 与复核结果汇总，"
    prompt.contains("复核") -> "我会对比上一轮判定、证据链与放行规则，"
    prompt.contains("OBD", ignoreCase = true) || prompt.contains("故障码") -> "根据 OBD 实时报文与 ECU 历史，"
    prompt.contains("异常") -> "我会先按异常等级、触发来源与历史记录排序，"
    else -> "我已结合当前会话上下文与本地知识库，"
}

private fun assistantBody(prompt: String, hasImage: Boolean): String = when {
    hasImage && isVehicleDossierAsk(prompt) ->
        "图中车辆型号已与档案条目匹配，基础信息已展示在上方卡片。"
    hasImage && (prompt.contains("修") || prompt.contains("维修")) ->
        "图中能看到的可疑部位与对应维修步骤已整理，建议按由易到难逐项排查。"
    hasImage && (prompt.contains("价") || prompt.contains("估")) ->
        "同款车型近 30 天成交价区间已估算，地区差与车况会影响最终成交价。"
    hasImage -> "我从图里识别出主要对象与可能的关键细节，可继续追问细节。"
    else -> "读取到 3 项故障码，其中 P0420 触发 2 次 — 属于催化器效率低于阈值的常见误报模式。"
}

private fun assistantActions(prompt: String, hasImage: Boolean): List<String> = when {
    hasImage && isVehicleDossierAsk(prompt) -> listOf("看更多档案字段", "查历史预审", "导出 PDF")
    hasImage && (prompt.contains("修") || prompt.contains("维修")) -> listOf("看维修步骤", "查配件价", "找附近门店")
    hasImage && (prompt.contains("价") || prompt.contains("估")) -> listOf("看价格区间", "查同款成交", "标记关注")
    hasImage -> listOf("再换角度拍一张", "查相关档案", "保存到笔记")
    else -> listOf("调出该车明细", "查看 OBD 报文", "标记为误报")
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
private fun SectionTitle(title: String, hint: String, compact: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(
                start = Gomob.spacing.s20,
                end = Gomob.spacing.s20,
                top = if (compact) Gomob.spacing.s12 else Gomob.spacing.s20,
                bottom = if (compact) Gomob.spacing.s8 else 10.dp,
            ),
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

// ─── 智能体身份卡 ──────────────────────────────────────────────────────────
@Composable
private fun AgentIntroCard(compact: Boolean = false) {
    Box(
        Modifier.padding(
            start = Gomob.spacing.s20,
            end = Gomob.spacing.s20,
            top = if (compact) Gomob.spacing.s12 else Gomob.spacing.s16,
        ),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .ticks()
                .padding(
                    horizontal = Gomob.spacing.s14,
                    vertical = if (compact) Gomob.spacing.s12 else Gomob.spacing.s14,
                ),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "INSPECTION AGENT",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.14.em,
                    color = Gomob.colors.accent,
                )
                Text(
                    "能力 · ${AGENT_CAPABILITIES.size} 项主线",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Gomob.colors.fg3,
                )
            }
            Text(
                "拍照即问 · 实时识别车辆合规风险",
                fontSize = if (compact) 13.sp else 14.sp,
                fontWeight = FontWeight.Medium,
                color = Gomob.colors.fg0,
            )
            Text(
                "聚焦改装识别、车架号伪刻、铭牌伪造，给出可信度判定与依据。",
                fontSize = 11.sp,
                lineHeight = 16.sp,
                color = Gomob.colors.fg2,
            )
        }
    }
}

// ─── 智能体能力卡片列 ──────────────────────────────────────────────────────
internal data class AgentCapability(
    val k: String,          // 序号编码 "01" 等，UI 用
    val key: String,        // 路由 key
    val title: String,
    val description: String,
    val intro: String,      // 进入会话时助手自我介绍
    val icon: ImageVector,
)

internal val AGENT_CAPABILITIES = listOf(
    AgentCapability(
        k = "01",
        key = "exterior",
        title = "车辆外观改装分析",
        description = "识别加装件、违规改色、外观件非原厂",
        intro = "我是「车辆外观改装识别」专攻智能体，专门帮你分辨车身违规改装、改色与非原厂外观件。直接拍一张外观照片，或描述你看到的可疑细节即可。",
        icon = GomobIcons.Eyeball,
    ),
    AgentCapability(
        k = "02",
        key = "vin",
        title = "车架号伪刻识别",
        description = "比对 VIN 钢印字体、压痕、底材异常",
        intro = "我是「车架号伪刻识别」专攻智能体，专注比对 VIN 钢印字体、压痕与底材异常。直接拍车架号特写，或描述你看到的可疑特征即可。",
        icon = GomobIcons.ID,
    ),
    AgentCapability(
        k = "03",
        key = "nameplate",
        title = "标牌伪造识别",
        description = "辨别铭牌字体、铆钉、材质原厂度",
        intro = "我是「标牌伪造识别」专攻智能体，专攻铭牌字体、铆钉、材质等原厂度判定。直接拍铭牌照片，或描述你想核对的字段即可。",
        icon = GomobIcons.Stamp,
    ),
)

internal fun findAgentCapability(key: String?): AgentCapability? =
    key?.let { k -> AGENT_CAPABILITIES.firstOrNull { it.key == k } }

@Composable
private fun AgentCapabilityList(
    onSelect: (AgentCapability) -> Unit,
    compact: Boolean = false,
) {
    Column(
        Modifier.padding(horizontal = Gomob.spacing.s20),
        verticalArrangement = Arrangement.spacedBy(if (compact) Gomob.spacing.s6 else Gomob.spacing.s8),
    ) {
        AGENT_CAPABILITIES.forEach { item ->
            CapabilityRow(item = item, compact = compact, onClick = { onSelect(item) })
        }
    }
}

@Composable
private fun CapabilityRow(
    item: AgentCapability,
    compact: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .ticks()
            .clickable(onClick = onClick)
            .padding(
                horizontal = Gomob.spacing.s14,
                vertical = if (compact) Gomob.spacing.s12 else Gomob.spacing.s14,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        Box(
            Modifier
                .size(if (compact) 40.dp else 44.dp)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.accentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                item.icon,
                contentDescription = null,
                tint = Gomob.colors.accent,
                modifier = Modifier.size(if (compact) 20.dp else 22.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
            ) {
                Text(
                    item.k,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.14.em,
                    color = Gomob.colors.fg3,
                )
                Text(
                    item.title,
                    fontSize = if (compact) 13.sp else 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Gomob.colors.fg0,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                item.description,
                fontSize = 11.sp,
                color = Gomob.colors.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "›",
            fontSize = 18.sp,
            fontFamily = FontFamily.Monospace,
            color = Gomob.colors.accent,
        )
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
    bottomAnchorInset: Dp = 0.dp,
    attachedBitmap: Bitmap? = null,
    onClearAttachment: () -> Unit = {},
    onCameraClick: () -> Unit = {},
) {
    var draft by remember { mutableStateOf("") }
    val exitInput = rememberExitInput()
    val keyboardController = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val focusRequester = remember { FocusRequester() }
    var localActive by rememberSaveable { mutableStateOf(false) }
    var focusRequestVersion by remember { mutableStateOf(0) }
    var imeSeenWhileExpanded by remember { mutableStateOf(false) }
    val inputClickSource = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val imeBottom = WindowInsets.ime.getBottom(density)
    val bottomAnchorInsetPx = with(density) { bottomAnchorInset.roundToPx() }
    val imeVisible = imeBottom > 0
    val expanded = active || localActive
    val keyboardTravel = if (expanded) {
        (imeBottom - bottomAnchorInsetPx).coerceAtLeast(0)
    } else {
        0
    }
    val bottomPadding = if (expanded && imeVisible) Gomob.spacing.s8 else 10.dp

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

    fun collapseInput() {
        localActive = false
        onActiveChange(false)
        exitInput()
    }

    fun submitDraft() {
        val text = draft.trim()
        if (text.isEmpty() && attachedBitmap == null) {
            collapseInput()
            return
        }
        val outgoing = text.ifEmpty { "看看这张图，能告诉我什么？" }
        draft = ""
        collapseInput()
        onSubmit(outgoing)
    }

    LaunchedEffect(active) {
        if (!active) {
            localActive = false
            imeSeenWhileExpanded = false
        }
    }

    LaunchedEffect(expanded, imeVisible) {
        if (!expanded) {
            imeSeenWhileExpanded = false
        } else if (imeVisible) {
            imeSeenWhileExpanded = true
        } else if (imeSeenWhileExpanded) {
            imeSeenWhileExpanded = false
            collapseInput()
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
            .offset { IntOffset(x = 0, y = -keyboardTravel) }
            .background(Gomob.colors.bg0)
            .clickable(
                interactionSource = inputClickSource,
                indication = null,
                onClick = { activateInput() },
            )
            .padding(
                start = Gomob.spacing.s16,
                end = Gomob.spacing.s16,
                top = 9.dp,
                bottom = bottomPadding,
            )
            .animateContentSize(animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)),
    ) {
        Crossfade(
            targetState = expanded,
            animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
            label = "composer-expand",
        ) { isExpanded ->
        if (isExpanded) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(Gomob.shapes.r3)
                    .background(Gomob.colors.bg1)
            ) {
                if (attachedBitmap != null) {
                    AttachmentThumb(
                        bitmap = attachedBitmap,
                        onClear = onClearAttachment,
                    )
                }
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
                            if (attachedBitmap != null) "对这张图问点什么 · 留空将走默认提问" else "问助手或输入 / 唤起命令",
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
                                } else if (!active) {
                                    localActive = false
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
                        .height(48.dp)
                        .padding(horizontal = Gomob.spacing.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ComposerIconButton(
                        icon = GomobIcons.Camera,
                        tint = Gomob.colors.accent,
                        bg = Gomob.colors.accentSoft,
                        size = 36.dp,
                        iconSize = 20.dp,
                        onClick = onCameraClick,
                    )
                    Spacer(Modifier.weight(1f))
                    ComposerIconButton(
                        icon = GomobIcons.Mic,
                        tint = Gomob.colors.fg1,
                        size = 36.dp,
                        iconSize = 20.dp,
                    )
                    Spacer(Modifier.width(Gomob.spacing.s8))
                    ComposerIconButton(
                        icon = GomobIcons.Send,
                        tint = Gomob.colors.accent,
                        bg = Gomob.colors.accentSoft,
                        size = 36.dp,
                        iconSize = 20.dp,
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
                    .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s6),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                ComposerIconButton(
                    icon = GomobIcons.Camera,
                    tint = Gomob.colors.accent,
                    bg = Gomob.colors.accentSoft,
                    size = 36.dp,
                    iconSize = 20.dp,
                    onClick = onCameraClick,
                )
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 36.dp)
                        .clickable(
                            interactionSource = inputClickSource,
                            indication = null,
                            onClick = { activateInput() },
                        ),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        draft.ifEmpty { "拍图问助手 · 或输入文字提问" },
                        fontSize = 13.sp,
                        color = if (draft.isEmpty()) Gomob.colors.fg3 else Gomob.colors.fg0,
                        maxLines = 1,
                    )
                }
                ComposerIconButton(
                    icon = GomobIcons.Mic,
                    tint = Gomob.colors.fg1,
                    size = 36.dp,
                    iconSize = 20.dp,
                )
                ComposerIconButton(
                    icon = GomobIcons.Send,
                    tint = Gomob.colors.accent,
                    bg = Gomob.colors.accentSoft,
                    size = 36.dp,
                    iconSize = 20.dp,
                    onClick = { submitDraft() },
                )
            }
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
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }
}

private fun Modifier.trackWindowBottomInset(
    view: android.view.View,
    onInsetChanged: (Int) -> Unit,
): Modifier = onGloballyPositioned { coordinates ->
    val windowHeight = view.rootView.height.takeIf { it > 0 } ?: view.height
    if (windowHeight > 0) {
        val bottomInset = (windowHeight - coordinates.boundsInWindow().bottom)
            .roundToInt()
            .coerceAtLeast(0)
        onInsetChanged(bottomInset)
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
private fun AttachmentThumb(bitmap: Bitmap, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Gomob.spacing.s14, end = Gomob.spacing.s8, top = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.bg2),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "已附图片",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                "已附图片 · 助手将基于此图回答",
                fontSize = 11.sp,
                color = Gomob.colors.fg1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${bitmap.width}×${bitmap.height} · JPEG",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Gomob.colors.fg3,
            )
        }
        Box(
            Modifier
                .size(28.dp)
                .clip(Gomob.shapes.r1)
                .background(Gomob.colors.bg2)
                .clickable(onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                GomobIcons.Close,
                contentDescription = "移除",
                tint = Gomob.colors.fg2,
                modifier = Modifier.size(14.dp),
            )
        }
    }
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
