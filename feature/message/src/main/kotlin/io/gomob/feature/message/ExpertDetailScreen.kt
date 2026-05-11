package io.gomob.feature.message

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
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

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = content?.expert?.name ?: "专家详情",
            onBack = onBack,
            eyebrow = "专家连线 · 固定专家",
        )

        Box(Modifier.weight(1f)) {
            when (state) {
                ExpertDetailUiState.Loading -> ExpertDetailStateBlock("正在加载专家", StatusTone.Neutral, null)
                is ExpertDetailUiState.Error -> ExpertDetailStateBlock(
                    text = (state as ExpertDetailUiState.Error).message,
                    tone = StatusTone.Danger,
                    onClick = viewModel::refresh,
                )
                is ExpertDetailUiState.Content -> ExpertDetailContent(state as ExpertDetailUiState.Content)
            }
        }
        ExpertBottomActions(
            enabled = content != null && !content.openingMessage,
            onMessage = viewModel::openDirectConversation,
            onAudioVideo = {
                content?.expert?.name
                    ?.let { onOpenAudioVideo("$it · 音视频通话") }
            },
        )
    }
}

@Composable
private fun ExpertDetailContent(
    state: ExpertDetailUiState.Content,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            horizontal = Gomob.spacing.s20,
            vertical = Gomob.spacing.s12,
        ),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        item { ExpertDetailCard(state.expert) }
        state.messageError?.let { error ->
            item { ExpertInlineStatus(error, StatusTone.Danger) }
        }
        item {
            Text("发布案例", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
        }
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
private fun ExpertDetailCard(expert: HelpExpertRowUi) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .padding(Gomob.spacing.s16),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            MessageAvatarImage(
                seed = "expert-${expert.userId}-${expert.name}",
                size = 54.dp,
                shape = Gomob.shapes.r2,
                online = expert.availabilityText == "可发消息",
            )
            Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                Text(expert.name, style = Gomob.type.metricMd, color = Gomob.colors.fg0)
                Text(expert.roleTitle, style = Gomob.type.bodySm, color = Gomob.colors.accent)
                Text(
                    expert.employeeId,
                    style = Gomob.type.numInline.copy(fontFamily = FontFamily.Monospace),
                    color = Gomob.colors.fg3,
                )
            }
        }

        Spacer(Modifier.height(Gomob.spacing.s16))
        DetailLine(label = "专长", value = expert.specialty)
        Spacer(Modifier.height(Gomob.spacing.s8))
        DetailLine(label = "状态", value = expert.availabilityText)
    }
}

@Composable
private fun ExpertCaseCard(item: ExpertCaseRowUi) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
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
                style = Gomob.type.body,
                fontWeight = FontWeight.Medium,
                color = Gomob.colors.fg0,
                modifier = Modifier.weight(1f),
            )
            Text(
                item.category,
                style = Gomob.type.eyebrow,
                color = Gomob.colors.accent,
                modifier = Modifier.padding(start = Gomob.spacing.s8),
            )
        }
        if (item.summary.isNotBlank()) {
            Text(item.summary, style = Gomob.type.bodySm, color = Gomob.colors.fg2)
        }
        Text(item.publishedAt, style = Gomob.type.numInline, color = Gomob.colors.fg3)
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
        Text(label, style = Gomob.type.eyebrow, color = Gomob.colors.fg3)
        Text(value, style = Gomob.type.bodySm, color = Gomob.colors.fg1)
    }
}

@Composable
private fun ExpertBottomActions(
    enabled: Boolean,
    onMessage: () -> Unit,
    onAudioVideo: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Gomob.colors.bg1).navigationBarsPadding()) {
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
                        Icons.Filled.VideoCall,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(Gomob.spacing.icon20),
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
    val bg = when {
        !enabled -> Gomob.colors.bg3
        primary -> Gomob.colors.accentSoft
        else -> Gomob.colors.bg2
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
            .clip(Gomob.shapes.r2)
            .background(bg)
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
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
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
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(Gomob.spacing.s16),
    ) {
        StatusTag(text = text, tone = tone, showDot = tone != StatusTone.Neutral)
    }
}
