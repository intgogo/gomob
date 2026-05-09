package io.gomob.feature.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import io.gomob.designsystem.theme.Gomob

@Composable
fun ExpertDetailRoute(
    onBack: () -> Unit,
    viewModel: ExpertDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = (state as? ExpertDetailUiState.Content)?.expert?.name ?: "专家详情",
            onBack = onBack,
            eyebrow = "在线求助 · 固定专家",
            trailing = {
                StatusTag(
                    text = if (state is ExpertDetailUiState.Content) "可联系" else "加载中",
                    tone = if (state is ExpertDetailUiState.Content) StatusTone.Ok else StatusTone.Neutral,
                    showDot = true,
                )
            },
        )

        when (state) {
            ExpertDetailUiState.Loading -> ExpertDetailStateBlock("正在加载专家", StatusTone.Neutral, null)
            is ExpertDetailUiState.Error -> ExpertDetailStateBlock(
                text = (state as ExpertDetailUiState.Error).message,
                tone = StatusTone.Danger,
                onClick = viewModel::refresh,
            )
            is ExpertDetailUiState.Content -> ExpertDetailCard((state as ExpertDetailUiState.Content).expert)
        }
    }
}

@Composable
private fun ExpertDetailCard(expert: HelpExpertRowUi) {
    Column(
        Modifier
            .padding(horizontal = Gomob.spacing.s20)
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
            .padding(Gomob.spacing.s16),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.accentSoft)
                    .border(Gomob.spacing.hairline, Gomob.colors.accentLine, Gomob.shapes.r2),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    expert.initials,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Gomob.colors.accentStrong,
                )
            }
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
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
        Text(label, style = Gomob.type.eyebrow, color = Gomob.colors.fg3)
        Text(value, style = Gomob.type.bodySm, color = Gomob.colors.fg1)
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
            .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(Gomob.spacing.s16),
    ) {
        StatusTag(text = text, tone = tone, showDot = tone != StatusTone.Neutral)
    }
}
