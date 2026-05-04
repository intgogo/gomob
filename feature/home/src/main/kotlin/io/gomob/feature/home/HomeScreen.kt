package io.gomob.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.gomob.designsystem.component.Chip
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.MetricTile
import io.gomob.designsystem.component.MetricTrend
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob

const val HOME_ROUTE = "home"

private data class FilterChip(val label: String, val key: String)

private val FILTER_CHIPS = listOf(
    FilterChip("全部", "all"),
    FilterChip("已审通过", "passed"),
    FilterChip("车型代码", "model"),
    FilterChip("OBD 异常", "obd"),
    FilterChip("外观异常", "shape"),
    FilterChip("待复核", "review"),
)

private data class TaskItem(
    val id: String,
    val vin: String,
    val site: String,
    val tone: StatusTone,
    val statusLabel: String,
    val time: String,
)

private val TASKS = listOf(
    TaskItem("1", "LSVHM133022221761", "大众系列 · 沪A12345",
        StatusTone.Danger, "外廓尺寸异常", "2024/05/10 11:45"),
    TaskItem("2", "LSVHM41182123456", "大众系列 · 沪A57Y0",
        StatusTone.Warn, "VIN 校验存疑", "2024/05/10 12:18"),
    TaskItem("3", "THGCM6263312345", "丰田系列 · 沪AAR757",
        StatusTone.Ok, "审核通过", "2024/05/10 14:30"),
    TaskItem("4", "WJN1133022221761", "日产系列 · 沪A12345",
        StatusTone.Warn, "OBD 异常", "2024/05/10 15:02"),
)

@Composable
fun HomeRoute() {
    var selected by remember { mutableStateOf("all") }
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        ScreenHeader(
            title = "智能预审",
            eyebrow = "当班 · 杭州市西湖区车管所检测站",
            trailing = { StatusTag(text = "实时", tone = StatusTone.Accent, showDot = true) },
        )

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Gomob.spacing.s16),
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            MetricTile(
                label = "存在预警",
                value = "183",
                delta = "+12",
                trend = MetricTrend.Down,
                caption = "起止 12/05 11:43",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "暂无预警",
                value = "107",
                delta = "+35",
                trend = MetricTrend.Up,
                caption = "起止 12/05 09:12",
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s12),
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            FILTER_CHIPS.forEach { chip ->
                Chip(
                    text = chip.label,
                    selected = chip.key == selected,
                    onClick = { selected = chip.key },
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(
                start = Gomob.spacing.s16,
                end = Gomob.spacing.s16,
                bottom = Gomob.spacing.s24,
            ),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            items(TASKS, key = { it.id }) { TaskRow(it) }
        }
    }
}

@Composable
private fun TaskRow(task: TaskItem) {
    HairlineCard(padding = Gomob.spacing.s12) {
        Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
            Text(task.vin, style = Gomob.type.body, color = Gomob.colors.fg0)
            Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
                StatusTag(text = task.statusLabel, tone = task.tone, showDot = true)
                Text(task.site, style = Gomob.type.caption, color = Gomob.colors.fg2)
            }
            Text(task.time, style = Gomob.type.numInline, color = Gomob.colors.fg3)
        }
    }
}
