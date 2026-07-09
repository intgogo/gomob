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
import androidx.compose.ui.text.font.FontFamily
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
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

@Composable
fun ContactDetailRoute(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenAudioVideo: (String) -> Unit,
    viewModel: ContactDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val content = state as? ContactDetailUiState.Content

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
                title = content?.contact?.name ?: "联系人详情",
                onBack = onBack,
                eyebrow = content?.contact?.organization ?: "联系人",
            )
        },
        overlay = {
            // 底部动作栏玻璃吸底: 内容从其下滚过透出模糊背景
            ContactBottomActions(
                enabled = content != null && !content.openingMessage,
                onMessage = viewModel::openDirectConversation,
                onAudioVideo = {
                    content?.contact?.name
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
            ContactDetailUiState.Loading -> Box(Modifier.fillMaxSize().padding(padding)) {
                ContactDetailStateBlock("正在加载联系人", StatusTone.Neutral, null)
            }
            is ContactDetailUiState.Error -> Box(Modifier.fillMaxSize().padding(padding)) {
                ContactDetailStateBlock(
                    text = (state as ContactDetailUiState.Error).message,
                    tone = StatusTone.Danger,
                    onClick = viewModel::refresh,
                )
            }
            is ContactDetailUiState.Content -> ContactDetailContent(
                state = state as ContactDetailUiState.Content,
                listState = listState,
                contentPadding = padding,
            )
        }
    }
}

@Composable
private fun ContactDetailContent(
    state: ContactDetailUiState.Content,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val contact = state.contact
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
        item { ContactDetailCard(contact) }
        state.messageError?.let { error ->
            item { ContactInlineStatus(error, StatusTone.Danger) }
        }
        if (state.cases.isNotEmpty() || contact.peerUserId != null) {
            item { Text("发布案例", style = Gomob.type.eyebrow, color = Gomob.colors.fg2) }
            if (state.cases.isEmpty()) {
                item { ContactInlineStatus("暂无已发布案例", StatusTone.Neutral) }
            } else {
                items(state.cases, key = { it.id }) { item ->
                    ContactCaseCard(item)
                }
            }
        } else {
            item { Text("协作信息", style = Gomob.type.eyebrow, color = Gomob.colors.fg2) }
            item { ContactCollaborationCard(contact) }
        }
    }
}

@Composable
private fun ContactDetailCard(contact: ContactProfileUi) {
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
                seed = contact.avatarSeed,
                size = 54.dp,
                shape = Gomob.shapes.r2,
                online = contact.online,
            )
            Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                Text(contact.name, style = Gomob.type.metricMd, color = Gomob.colors.fg0)
                Text(contact.roleTitle, style = Gomob.type.bodySm, color = Gomob.colors.accent)
                Text(
                    contact.employeeId,
                    style = Gomob.type.numInline.copy(fontFamily = FontFamily.Monospace),
                    color = Gomob.colors.fg3,
                )
            }
        }

        Spacer(Modifier.height(Gomob.spacing.s16))
        ContactDetailLine(label = "组织", value = contact.organization)
        Spacer(Modifier.height(Gomob.spacing.s8))
        ContactDetailLine(label = "专长", value = contact.specialty)
        Spacer(Modifier.height(Gomob.spacing.s8))
        ContactDetailLine(label = "状态", value = contact.availabilityText)
    }
}

@Composable
private fun ContactCollaborationCard(contact: ContactProfileUi) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .padding(Gomob.spacing.s14),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        ContactDetailLine(label = "协作角色", value = contact.roleTitle)
        ContactDetailLine(label = "联系人编号", value = contact.employeeId)
        ContactDetailLine(label = "当前状态", value = contact.availabilityText)
    }
}

@Composable
private fun ContactCaseCard(item: ExpertCaseRowUi) {
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
private fun ContactDetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
        Text(label, style = Gomob.type.eyebrow, color = Gomob.colors.fg3)
        Text(value, style = Gomob.type.bodySm, color = Gomob.colors.fg1)
    }
}

@Composable
private fun ContactBottomActions(
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
            ContactActionButton(
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
            ContactActionButton(
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
private fun ContactActionButton(
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
private fun ContactInlineStatus(text: String, tone: StatusTone) {
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
private fun ContactDetailStateBlock(
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
