package io.gomob.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
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
import io.gomob.designsystem.theme.Accent
import io.gomob.designsystem.theme.Primary
import io.gomob.designsystem.theme.StateDanger
import io.gomob.designsystem.theme.StateInfo
import io.gomob.designsystem.theme.StateSuccess
import io.gomob.designsystem.theme.StateWarning
import io.gomob.designsystem.theme.SurfaceCard
import io.gomob.designsystem.theme.SurfaceDeep
import io.gomob.designsystem.theme.TextSecondary
import io.gomob.designsystem.theme.TextTertiary

const val HOME_ROUTE = "home"

private data class FilterChip(val label: String, val key: String)

private val FILTER_CHIPS = listOf(
    FilterChip("全部", "all"),
    FilterChip("已审通过", "passed"),
    FilterChip("车型代码异常", "model"),
    FilterChip("OBD 异常", "obd"),
    FilterChip("外观异常", "shape"),
    FilterChip("待复核", "review"),
)

private data class TodayMetric(val label: String, val value: Int, val color: Color)

private val TODAY_METRICS = listOf(
    TodayMetric("车型代码", 56, Primary),
    TodayMetric("OBD 异常", 28, StateWarning),
    TodayMetric("外观异常", 20, StateDanger),
    TodayMetric("年份码", 22, Accent),
    TodayMetric("正常", 44, StateSuccess),
)

private data class InspectionItem(
    val vin: String,
    val model: String,
    val tags: List<String>,
    val timeAgo: String,
    val status: Color,
)

private val RECENT_ITEMS = listOf(
    InspectionItem("LSVHM133022221761", "大众系列 · 小型汽车 · 沪A12345",
        listOf("OBD 检验", "外廓尺寸"), "2024/05/10 11:45", StateDanger),
    InspectionItem("LSVHM41182123456", "大众系列 · 小型汽车 · 沪A57Y0",
        listOf("VIN 车架号", "出厂日期"), "2024/05/10 12:18", StateWarning),
    InspectionItem("THGCM6263312345", "丰田系列 · 小型汽车 · 沪AAR757",
        listOf("VIN 车架号"), "2024/05/10 14:30", StateSuccess),
    InspectionItem("WJN1133022221761", "日产系列 · 小型汽车 · 沪A12345",
        listOf("OBD 异常", "排放"), "2024/05/10 15:02", StateWarning),
)

@Composable
fun HomeRoute() {
    HomeContent()
}

@Composable
private fun HomeContent() {
    var selectedFilter by remember { mutableStateOf("all") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDeep),
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { TopHeader() }
        item { FilterRow(selectedFilter) { selectedFilter = it } }
        item { PrimaryWarningCards() }
        item { TodayMetricsCard() }
        item {
            Text(
                text = "最近预审",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        items(RECENT_ITEMS) { item -> RecentInspectionCard(item) }
    }
}

@Composable
private fun TopHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "智能预审", style = MaterialTheme.typography.headlineLarge)
            Text(
                text = "AI · 杭州市西湖区车管所检测站",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        IconBubble(icon = Icons.Filled.Search)
        Spacer(Modifier.width(12.dp))
        Box {
            IconBubble(icon = Icons.Filled.Notifications)
            Box(
                modifier = Modifier
                    .padding(start = 30.dp, top = 4.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(StateDanger),
            )
        }
    }
}

@Composable
private fun IconBubble(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(SurfaceCard),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = TextSecondary)
    }
}

@Composable
private fun FilterRow(selected: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FILTER_CHIPS.forEach { chip ->
            val isSelected = chip.key == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(
                        if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceCard,
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = chip.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Primary else TextSecondary,
                )
            }
        }
    }
}

@Composable
private fun PrimaryWarningCards() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BigStatCard(
            modifier = Modifier.weight(1f),
            title = "存在预警",
            value = "183",
            subtitle = "起止 2025/12/05 11:43",
            accent = StateDanger,
        )
        BigStatCard(
            modifier = Modifier.weight(1f),
            title = "暂无预警",
            value = "107",
            subtitle = "起止 2025/12/05 09:12",
            accent = StateSuccess,
        )
    }
}

@Composable
private fun BigStatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtitle: String,
    accent: Color,
) {
    GlassCard(
        modifier = modifier.height(132.dp),
        background = Brush.verticalGradient(
            listOf(accent.copy(alpha = 0.18f), SurfaceCard),
        ),
        borderColor = accent.copy(alpha = 0.4f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = title, style = MaterialTheme.typography.labelLarge)
            }
            Text(
                text = value,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
            )
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        }
    }
}

@Composable
private fun TodayMetricsCard() {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Primary),
                )
                Spacer(Modifier.width(8.dp))
                Text(text = "今日预审 · 当班数据", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Text(text = "Live", style = MaterialTheme.typography.labelSmall, color = StateInfo)
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TODAY_METRICS.forEach { metric ->
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = metric.value.toString(),
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            color = metric.color,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = metric.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentInspectionCard(item: InspectionItem) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(SurfaceCard, item.status.copy(alpha = 0.4f)),
                        ),
                    ),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = item.vin, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(text = item.model, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item.tags.forEach { tag ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(item.status.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = item.status,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.timeAgo,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(item.status),
            )
        }
    }
}
