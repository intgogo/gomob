package io.gomob.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.glass.glassPanelBg
import io.gomob.designsystem.theme.Gomob

private data class DayData(val total: Int, val normal: Int, val warn: Int, val danger: Int)

private val DAY_DATA: Map<Int, DayData?> = mapOf(
    1 to DayData(142, 38, 42, 62),
    2 to DayData(168, 51, 47, 70),
    3 to DayData(155, 48, 39, 68),
    4 to DayData(191, 62, 51, 78),
    5 to DayData(170, 44, 48, 78),    // 选中
    6 to null, 7 to null,
    8 to DayData(132, 41, 38, 53),
    9 to DayData(158, 49, 44, 65),
    10 to DayData(144, 45, 39, 60),
    11 to DayData(176, 55, 51, 70),
    12 to DayData(188, 60, 53, 75),
    13 to null, 14 to null,
    15 to DayData(150, 47, 41, 62),
    16 to DayData(162, 50, 44, 68),
    17 to DayData(171, 53, 46, 72),
    18 to DayData(145, 44, 40, 61),
    19 to DayData(199, 65, 56, 78),
    20 to null, 21 to null,
    22 to DayData(138, 42, 38, 58),
    23 to DayData(165, 51, 45, 69),
    24 to DayData(141, 43, 39, 59),
    25 to DayData(0, 0, 0, 0),     // 今日，未开始
)

private val WEEK_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 07 历史日历 — jsx history.jsx。
 *
 * 视觉骨架:
 *   1. 顶部 BackHeader (历史数据 · 眉标"我的")
 *   2. 月切换条 (拟玻璃卡 52dp + ‹ + 2025/12 + ›)
 *   3. 月历卡 (拟玻璃卡 + 周标题 + 31 格 HxDayCell + 图例)
 *   4. 选中日总览卡 (日期 mono + 查看流水钮 + total 30sp + 三段比例条 +
 *      HxStat × 3 正常/预警/异常)
 */
@Composable
fun HistoryRoute(onBack: () -> Unit) {
    var selected by remember { mutableStateOf(5) }
    val sel = DAY_DATA[selected]

    val listState = rememberLazyListState()
    GlassHeaderScaffold(
        listState = listState,
        header = { BackHeader(title = "历史数据", onBack = onBack, eyebrow = "我的") },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 28.dp,
            ),
        ) {
            item { MonthSwitcher() }
            item {
                MonthCalendar(
                    selected = selected,
                    onSelect = { selected = it },
                )
            }
            item {
                SelectedDayCard(
                    year = 2025,
                    month = 12,
                    day = selected,
                    sel = sel,
                )
            }
        }
    }
}

@Composable
private fun MonthSwitcher() {
    Box(Modifier.padding(horizontal = Gomob.spacing.pageGutter).padding(bottom = Gomob.spacing.s12)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.headerHeight)
                .glassPanelBg(shape = Gomob.shapes.r3)
                .padding(horizontal = Gomob.spacing.s8),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HdrBtnSquare("‹")
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
                Text(
                    "2025",
                    style = Gomob.type.numInline.copy(fontSize = 17.sp),
                    fontWeight = FontWeight.Medium,
                    color = Gomob.colors.fg0,
                )
                Text("/", fontSize = 12.sp, color = Gomob.colors.fg3)
                Text(
                    "12",
                    style = Gomob.type.numInline.copy(fontSize = 17.sp),
                    fontWeight = FontWeight.Medium,
                    color = Gomob.colors.fg0,
                )
            }
            HdrBtnSquare("›")
        }
    }
}

@Composable
private fun HdrBtnSquare(symbol: String) {
    Box(
        Modifier
            .size(36.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.fg0.copy(alpha = 0.04f))
            .clickable {},
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = Gomob.colors.fg1)
    }
}

// ─── 月历主体 ──────────────────────────────────────────────────────────────
@Composable
private fun MonthCalendar(selected: Int, onSelect: (Int) -> Unit) {
    val firstDow = 1   // 12/1 是周一
    val daysInMonth = 31
    val cells = buildList<Int?> {
        repeat(firstDow) { add(null) }
        for (d in 1..daysInMonth) add(d)
        while (size % 7 != 0) add(null)
    }
    Box(Modifier.padding(horizontal = Gomob.spacing.pageGutter).padding(bottom = 14.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .glassPanelBg(shape = Gomob.shapes.r3)
                .padding(Gomob.spacing.s12),
        ) {
            // 周标题
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = Gomob.spacing.s8),
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
            ) {
                WEEK_LABELS.forEach { w ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(w, fontSize = 11.sp, color = Gomob.colors.fg3)
                    }
                }
            }
            Spacer(Modifier.height(Gomob.spacing.s6))
            // 31 格 (按 7 列分行)
            cells.chunked(7).forEach { rowCells ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
                ) {
                    rowCells.forEach { d ->
                        if (d == null) {
                            Box(Modifier.weight(1f).height(42.dp))
                        } else {
                            HxDayCell(
                                day = d,
                                data = DAY_DATA[d],
                                selected = d == selected,
                                today = d == 25,
                                modifier = Modifier.weight(1f),
                                onClick = { if (DAY_DATA[d] != null) onSelect(d) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Gomob.spacing.s4))
            }
            // 图例
            Spacer(Modifier.height(Gomob.spacing.s12))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HxLegend(Gomob.colors.ok, "正常")
                HxLegend(Gomob.colors.warn, "预警")
                HxLegend(Gomob.colors.danger, "异常")
                Spacer(Modifier.weight(1f))
                Text(
                    "颜色 = 异常占比",
                    fontSize = 11.sp,
                    color = Gomob.colors.fg3,
                )
            }
        }
    }
}

@Composable
private fun HxDayCell(
    day: Int,
    data: DayData?,
    selected: Boolean,
    today: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val (toneColor, toneAlpha) = if (data != null && data.total > 0) {
        val dangerPct = data.danger.toFloat() / data.total
        val color = when {
            dangerPct >= 0.45f -> Gomob.colors.danger
            dangerPct >= 0.30f -> Gomob.colors.warn
            else -> Gomob.colors.ok
        }
        val alpha = (0.5f + (data.total / 250f).coerceAtMost(0.5f))
        color to alpha
    } else null to 0.5f
    val isInactive = data == null

    Box(
        modifier
            .height(42.dp)
            .clip(Gomob.shapes.r2)
            .background(if (selected) Gomob.colors.accentSoft else Color.Transparent)
            .then(
                if (selected) {
                    Modifier.border(1.dp, Gomob.colors.accentLine, Gomob.shapes.r2)
                } else {
                    Modifier
                },
            )
            .clickable(enabled = !isInactive, onClick = onClick)
            .padding(4.dp),
    ) {
        Text(
            day.toString(),
            modifier = Modifier.align(Alignment.TopCenter),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (isInactive) Gomob.colors.fg3.copy(alpha = 0.6f) else Gomob.colors.fg0,
        )
        if (today) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(Gomob.colors.ok),
            )
        }
        if (toneColor != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .size(width = 16.dp, height = 3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(toneColor.copy(alpha = toneAlpha)),
            )
        }
    }
}

@Composable
private fun HxLegend(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
    ) {
        Box(
            Modifier
                .size(width = 10.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color),
        )
        Text(label, fontSize = 11.sp, color = Gomob.colors.fg2)
    }
}

// ─── 选中日总览卡 ───────────────────────────────────────────────────────────
@Composable
private fun SelectedDayCard(year: Int, month: Int, day: Int, sel: DayData?) {
    Box(Modifier.padding(horizontal = Gomob.spacing.pageGutter).padding(bottom = 14.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .glassPanelBg(shape = Gomob.shapes.r3)
                .padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s14),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$year/${month.toString().padStart(2, '0')}/${day.toString().padStart(2, '0')}",
                    style = Gomob.type.numInline.copy(fontSize = 14.sp),
                    fontWeight = FontWeight.Medium,
                    color = Gomob.colors.fg0,
                )
                Row(
                    Modifier
                        .height(30.dp)
                        .clip(Gomob.shapes.r2)
                        .background(Gomob.colors.accentSoft)
                        .clickable {}
                        .padding(horizontal = Gomob.spacing.s12),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
                ) {
                    Text("查看流水", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.accent)
                    Text("›", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Gomob.colors.accent)
                }
            }
            if (sel == null || sel.total == 0) {
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Gomob.spacing.s20),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (sel != null && sel.total == 0) "今日未开始预审" else "当日未排班",
                        fontSize = 12.sp,
                        color = Gomob.colors.fg3,
                    )
                }
            } else {
                Spacer(Modifier.height(Gomob.spacing.s12))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
                    Text(
                        sel.total.toString(),
                        style = Gomob.type.numInline.copy(
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Medium,
                            lineHeight = 30.sp,
                        ),
                        color = Gomob.colors.accent,
                    )
                    Text("条预审记录", fontSize = 11.sp, color = Gomob.colors.fg3)
                }
                Spacer(Modifier.height(Gomob.spacing.s12))
                // 三段比例条
                val total = sel.total.toFloat()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(Gomob.colors.bg3),
                ) {
                    Box(Modifier.weight(sel.normal / total).fillMaxHeight().background(Gomob.colors.ok))
                    Box(Modifier.weight(sel.warn / total).fillMaxHeight().background(Gomob.colors.warn))
                    Box(Modifier.weight(sel.danger / total).fillMaxHeight().background(Gomob.colors.danger))
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    HxStat(sel.normal, "正常", Gomob.colors.ok, Modifier.weight(1f))
                    HxStat(sel.warn, "预警", Gomob.colors.warn, Modifier.weight(1f))
                    HxStat(sel.danger, "异常", Gomob.colors.danger, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HxStat(n: Int, label: String, tone: Color, modifier: Modifier = Modifier) {
    Column(
        modifier,
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
    ) {
        Text(
            n.toString(),
            style = Gomob.type.numInline.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium),
            color = tone,
        )
        Text(label, fontSize = 11.sp, color = Gomob.colors.fg2)
    }
}
