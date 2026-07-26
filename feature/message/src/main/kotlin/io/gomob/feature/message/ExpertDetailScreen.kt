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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.glass.glassChrome
import io.gomob.designsystem.glass.glassPanelBg
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

@Composable
fun ExpertDetailRoute(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenAudioVideo: (String) -> Unit,
    viewModel: ExpertDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val content = state as? ExpertDetailUiState.Content

    LaunchedEffect(viewModel) {
        viewModel.openConversationEvents.collect { conversationId ->
            onOpenConversation(conversationId.toString())
        }
    }

    val listState = rememberLazyListState()
    GlassHeaderScaffold(
        listState = listState,
        header = {
            BackHeader(
                title = content?.expert?.name ?: "专家详情",
                onBack = onBack,
                eyebrow = "多人连线 · 协作成员",
            )
        },
        overlay = {
            // 底部动作栏玻璃吸底: 内容从其下滚过透出模糊背景
            ExpertBottomActions(
                enabled = content != null && !content.openingMessage,
                onMessage = viewModel::openDirectConversation,
                onAudioVideo = {
                    content?.expert?.name
                        ?.let { onOpenAudioVideo("$it · 音视频通话") }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .glassChrome(topEdge = true)
                    .navigationBarsPadding(),
            )
        },
    ) { padding ->
        when (state) {
            ExpertDetailUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding)) {
                ExpertDetailStateBlock("正在加载专家", StatusTone.Neutral, null)
            }
            is ExpertDetailUiState.Error -> Box(Modifier.fillMaxSize().padding(padding)) {
                ExpertDetailStateBlock(
                    text = (state as ExpertDetailUiState.Error).message,
                    tone = StatusTone.Danger,
                    onClick = viewModel::refresh,
                )
            }
            is ExpertDetailUiState.Content -> ExpertDetailContent(
                state = state as ExpertDetailUiState.Content,
                listState = listState,
                contentPadding = padding,
            )
        }
    }
}

@Composable
private fun ExpertDetailContent(
    state: ExpertDetailUiState.Content,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Gomob.spacing.s20,
            end = Gomob.spacing.s20,
            top = contentPadding.calculateTopPadding() + Gomob.spacing.s12,
            // 底部预留吸底动作栏高度(两个 touchMin 按钮 + 间距), 最后一张卡不被压住
            bottom = contentPadding.calculateBottomPadding() + 120.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        item { ExpertDetailCard(state.expert) }
        state.messageError?.let { error ->
            item { ExpertInlineStatus(error, StatusTone.Danger) }
        }
        item { ExpertSectionTitle(title = "发布案例", count = state.cases.size) }
        if (state.cases.isEmpty()) {
            item { ExpertInlineStatus("暂无已发布案例", StatusTone.Neutral) }
        } else {
            items(state.cases, key = { it.id }) { item ->
                ExpertCaseCard(item)
            }
        }
    }
}

@Composable
private fun ExpertSectionTitle(title: String, count: Int? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = Gomob.type.sectionTitle,
            color = Gomob.colors.fg1,
            modifier = Modifier.weight(1f),
        )
        count?.let {
            Text(it.toString(), style = Gomob.type.numInline, color = Gomob.colors.fg3)
        }
    }
}

@Composable
private fun ExpertDetailCard(expert: HelpExpertRowUi) {
    val online = expert.availabilityText == "可发消息"
    Column(
        Modifier
            .fillMaxWidth()
            .glassPanelBg(shape = Gomob.shapes.r3)
            .padding(Gomob.spacing.s16),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            InitialAvatarTile(
                text = expert.initials.ifBlank { expert.name },
                size = Gomob.spacing.avatarHero,
                fontSize = 20.sp,
                online = online,
            )
            Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                Text(
                    expert.name,
                    style = Gomob.type.title.copy(fontWeight = FontWeight.SemiBold),
                    color = Gomob.colors.fg0,
                )
                Text(expert.roleTitle, style = Gomob.type.caption, color = Gomob.colors.accent)
                Text(expert.employeeId, style = Gomob.type.eyebrow, color = Gomob.colors.fg3)
            }
        }

        // 信息行组:前置 12dp 间距 + line1 分隔线
        Spacer(Modifier.height(Gomob.spacing.s12))
        ExpertDetailGroupDivider()
        Spacer(Modifier.height(Gomob.spacing.s12))
        DetailLine(label = "专长", value = expert.specialty)
        Spacer(Modifier.height(Gomob.spacing.s8))
        DetailLine(
            label = "状态",
            value = expert.availabilityText,
            valueColor = if (online) Gomob.colors.ok else Gomob.colors.fg0,
        )
    }
}

@Composable
private fun ExpertCaseCard(item: ExpertCaseRowUi) {
    Column(
        Modifier
            .fillMaxWidth()
            .glassPanelBg(shape = Gomob.shapes.r3)
            .padding(Gomob.spacing.s14),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                item.title,
                style = Gomob.type.bodySm,
                fontWeight = FontWeight.Medium,
                color = Gomob.colors.fg0,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .padding(start = Gomob.spacing.s8)
                    .clip(Gomob.shapes.r1)
                    .background(Gomob.colors.accentSoft)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(item.category, fontSize = 11.sp, color = Gomob.colors.accent, maxLines = 1)
            }
        }
        if (item.summary.isNotBlank()) {
            Text(
                item.summary,
                style = Gomob.type.caption.copy(lineHeight = 18.sp),
                color = Gomob.colors.fg2,
            )
        }
        Text(
            item.publishedAt,
            style = Gomob.type.numInline.copy(fontSize = 11.sp),
            color = Gomob.colors.fg3,
        )
    }
}

@Composable
private fun DetailLine(
    label: String,
    value: String,
    valueColor: Color = Gomob.colors.fg0,
) {
    // 横排:44dp 定宽 label + value
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = Gomob.type.caption,
            color = Gomob.colors.fg3,
            modifier = Modifier.width(44.dp),
        )
        Text(value, fontSize = 13.sp, color = valueColor, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ExpertDetailGroupDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(Gomob.spacing.hairline)
            .background(Gomob.colors.line1),
    )
}

@Composable
private fun ExpertBottomActions(
    enabled: Boolean,
    onMessage: () -> Unit,
    onAudioVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            ExpertActionButton(
                label = "发消息",
                enabled = enabled,
                primary = true,
                icon = { tint ->
                    Icon(
                        GomobIcons.Compose,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(Gomob.spacing.icon20),
                    )
                },
                onClick = onMessage,
                modifier = Modifier.fillMaxWidth(),
            )
            ExpertActionButton(
                label = "音视频通话",
                enabled = enabled,
                primary = false,
                icon = { tint ->
                    Icon(
                        Icons.Filled.Videocam,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = onAudioVideo,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ExpertActionButton(
    label: String,
    enabled: Boolean,
    primary: Boolean,
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 胶囊动作钮:主钮 accentSoft/accent;次钮 lineStrong 细边 + 半透明 bg1
    val bg = when {
        !enabled -> Gomob.colors.bg3
        primary -> Gomob.colors.accentSoft
        else -> Gomob.colors.bg1.copy(alpha = 0.6f)
    }
    val fg = when {
        !enabled -> Gomob.colors.fg3.copy(alpha = 0.5f)
        primary -> Gomob.colors.accent
        else -> Gomob.colors.fg1
    }
    Row(
        Modifier
            .then(modifier)
            .height(Gomob.spacing.touchMin)
            .clip(Gomob.shapes.pill)
            .background(bg)
            .then(
                if (!primary && enabled) {
                    Modifier.border(Gomob.spacing.hairline, Gomob.colors.lineStrong, Gomob.shapes.pill)
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
        ) {
            icon(fg)
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = fg,
            )
        }
    }
}

@Composable
private fun ExpertInlineStatus(text: String, tone: StatusTone) {
    Box(
        Modifier
            .fillMaxWidth()
            .glassPanelBg(shape = Gomob.shapes.r3)
            .padding(Gomob.spacing.s14),
    ) {
        StatusTag(text = text, tone = tone, showDot = tone != StatusTone.Neutral)
    }
}

@Composable
private fun ExpertDetailStateBlock(
    text: String,
    tone: StatusTone,
    onClick: (() -> Unit)?,
) {
    Box(
        Modifier
            .padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s12)
            .fillMaxWidth()
            .glassPanelBg(shape = Gomob.shapes.r3)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(Gomob.spacing.s16),
    ) {
        StatusTag(text = text, tone = tone, showDot = tone != StatusTone.Neutral)
    }
}
