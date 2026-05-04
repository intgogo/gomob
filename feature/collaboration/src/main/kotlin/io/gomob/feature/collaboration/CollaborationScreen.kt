package io.gomob.feature.collaboration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.component.Chip
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.MetricTile
import io.gomob.designsystem.component.MetricTrend
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob

const val COLLAB_ROUTE = "collaboration"

private val SUB_TABS = listOf("第一视角", "抽查复核", "案例公开")
private val BAR_DAYS = listOf("一", "二", "三", "四", "五", "六", "日")
private val BAR_VALUES = listOf(35, 30, 23, 28, 47, 60, 42)

@Composable
fun CollaborationRoute() {
    var sub by remember { mutableStateOf(1) }

    Column(
        Modifier.fillMaxSize().background(Gomob.colors.bg0),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        ScreenHeader(
            title = "多方协作",
            eyebrow = "团队 · 第一视角 / 抽查复核 / 案例公开",
            trailing = { StatusTag(text = "127 待办", tone = StatusTone.Warn, showDot = true) },
        )

        Row(
            Modifier.padding(horizontal = Gomob.spacing.s16),
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            SUB_TABS.forEachIndexed { i, label ->
                Chip(text = label, selected = i == sub, onClick = { sub = i })
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Gomob.spacing.s16),
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            MetricTile(
                label = "今日复核",
                value = "26",
                delta = "+24.5%",
                trend = MetricTrend.Up,
                caption = "环比",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "待复核",
                value = "37",
                delta = "+8",
                trend = MetricTrend.Down,
                caption = "今日新增",
                modifier = Modifier.weight(1f),
            )
        }

        HairlineCard(modifier = Modifier.padding(horizontal = Gomob.spacing.s16)) {
            Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("每日复核量", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    Text("近 7 天", style = Gomob.type.caption, color = Gomob.colors.fg3)
                }
                Text("382", style = Gomob.type.metricLg, color = Gomob.colors.accentStrong)
                Text("周环比 +6%", style = Gomob.type.numInline, color = Gomob.colors.ok)

                Row(
                    Modifier.fillMaxWidth().height(80.dp),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                ) {
                    BAR_VALUES.forEach { v ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(v.dp)
                                .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                                .background(Gomob.colors.accentSoft),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                ) {
                    BAR_DAYS.forEach { d ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            Text(d, style = Gomob.type.caption, color = Gomob.colors.fg3)
                        }
                    }
                }
            }
        }

        HairlineCard(modifier = Modifier.padding(horizontal = Gomob.spacing.s16)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
                    Text("最新接收", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    Text("2026/03/10 14:24", style = Gomob.type.body, color = Gomob.colors.fg1)
                }
                StatusTag(text = "实时", tone = StatusTone.Accent, showDot = true)
            }
        }
    }
}
