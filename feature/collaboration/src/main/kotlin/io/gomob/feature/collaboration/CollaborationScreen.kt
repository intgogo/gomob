package io.gomob.feature.collaboration

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AssignmentTurnedIn
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.gomob.designsystem.component.GlassCard
import io.gomob.designsystem.component.PrimaryButton
import io.gomob.designsystem.theme.Accent
import io.gomob.designsystem.theme.AccentDim
import io.gomob.designsystem.theme.Primary
import io.gomob.designsystem.theme.StateDanger
import io.gomob.designsystem.theme.StateInfo
import io.gomob.designsystem.theme.StateSuccess
import io.gomob.designsystem.theme.StateWarning
import io.gomob.designsystem.theme.SurfaceCard
import io.gomob.designsystem.theme.SurfaceDeep
import io.gomob.designsystem.theme.TextSecondary
import io.gomob.designsystem.theme.TextTertiary

const val COLLAB_ROUTE = "collaboration"

private val TABS = listOf("第一视角", "抽查复核", "案例公开")

@Composable
fun CollaborationRoute() {
    var tab by remember { mutableStateOf(1) } // 默认抽查复核
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDeep),
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Header() }
        item { TabRow(selected = tab, onSelect = { tab = it }) }
        item { StatGrid() }
        item { TrendCard() }
        item {
            PrimaryButton(
                text = "开始复核",
                onClick = {},
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        item { AlertCard() }
    }
}

@Composable
private fun Header() {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(text = "多方协作", style = MaterialTheme.typography.headlineLarge)
        Text(
            text = "第一视角 · 抽查复核 · 案例公开",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TabRow(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceCard)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        TABS.forEachIndexed { i, label ->
            val isSelected = i == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isSelected) {
                            Brush.horizontalGradient(listOf(Primary, Accent))
                        } else {
                            Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
                        },
                    )
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (isSelected) Color.White else TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun StatGrid() {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigCell(modifier = Modifier.weight(1f), label = "今日复核", value = "26",
                trend = "环比 ↑ 24.53%", trendColor = StateSuccess, accent = Primary)
            BigCell(modifier = Modifier.weight(1f), label = "待复核", value = "37",
                trend = "今日新增 8", trendColor = StateWarning, accent = StateWarning)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigCell(modifier = Modifier.weight(1f), label = "历史复核", value = "1132",
                trend = "近 7 日 +327", trendColor = StateInfo, accent = Accent)
            BigCell(modifier = Modifier.weight(1f), label = "历史过期", value = "3",
                trend = "今日 0", trendColor = TextTertiary, accent = StateDanger)
        }
    }
}

@Composable
private fun BigCell(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    trend: String,
    trendColor: Color,
    accent: Color,
) {
    GlassCard(
        modifier = modifier.height(108.dp),
        background = Brush.verticalGradient(listOf(accent.copy(alpha = 0.18f), SurfaceCard)),
        borderColor = accent.copy(alpha = 0.4f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = MaterialTheme.typography.labelMedium)
            Text(text = value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = accent)
            Text(text = trend, style = MaterialTheme.typography.labelSmall, color = trendColor)
        }
    }
}

@Composable
private fun TrendCard() {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(160.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "复核趋势", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "近 7 天",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
            }
            Spacer(Modifier.weight(1f))
            // 简易折线占位 — 真实曲线 M2 用 Vico 接入
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(35, 30, 23, 28, 47, 60, 42).forEach { v ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height((v * 1.2).dp)
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(
                                Brush.verticalGradient(
                                    listOf(Primary, AccentDim.copy(alpha = 0.4f)),
                                ),
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertCard() {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(StateDanger.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Visibility, contentDescription = null, tint = StateDanger)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "即时预警", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "最新接收 2026/03/10 14:24",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }
                Text(
                    text = "127",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = StateDanger,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                Box(
                    modifier = Modifier
                        .weight(0.78f)
                        .fillMaxSize()
                        .background(StateSuccess),
                )
                Box(
                    modifier = Modifier
                        .weight(0.22f)
                        .fillMaxSize()
                        .background(StateDanger),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row {
                LegendDot(StateSuccess, "结果正确")
                Spacer(Modifier.width(16.dp))
                LegendDot(StateDanger, "结果错误")
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
    }
}
