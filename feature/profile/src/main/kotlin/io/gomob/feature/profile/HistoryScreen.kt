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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.gomob.designsystem.decoration.ticks
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

const val HISTORY_ROUTE = "profile/history"

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
 *   1. 顶部 BackHeader 自画 (ChevronLeft 30dp + HISTORY mono eyebrow + 历史数据 16sp)
 *   2. 月切换条 (HairlineCard + ‹ + 2025/12 + ›)
 *   3. 月历卡 (HairlineCard + ticks + 周标题 + 31 格 HxDayCell + 图例)
 *   4. 选中日总览卡 (SELECTED + 日期 mono + 查看流水 + total 32sp + 三段比例条 +
 *      HxStat × 3 正常/预警/异常)
 */
@Composable
fun HistoryRoute(onBack: () -> Unit) {
    var selected by remember { mutableStateOf(5) }
    val sel = DAY_DATA[selected]

    Column(
        Modifier
            .fillMaxSize()
            .background(Gomob.colors.bg0),
    ) {
        TopHistoryHeader(onBack = onBack)
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 28.dp),
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
private fun TopHistoryHeader(onBack: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, top = 16.dp, bottom = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 返回按钮 30dp r-1 + line-2 边
        Box(
            Modifier
                .size(30.dp)
                .clip(Gomob.shapes.r1)
                .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r1)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "‹",
                fontSize = 18.sp,
                fontFamily = FontFamily.Monospace,
                color = Gomob.colors.fg1,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                "HISTORY",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.14.em,
                color = Gomob.colors.fg3,
            )
            Spacer(Modifier.height(Gomob.spacing.s2))
            Text("历史数据", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
        }
    }
}

@Composable
private fun MonthSwitcher() {
    Box(Modifier.padding(horizontal = Gomob.spacing.s20).padding(bottom = Gomob.spacing.s12)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
                .padding(horizontal = Gomob.spacing.s12, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HdrBtnSquare("‹")
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
                Text(
                    "2025",
                    style = Gomob.type.numInline.copy(fontSize = 18.sp),
                    fontWeight = FontWeight.Medium,
                    color = Gomob.colors.fg0,
                )
                Text("/", fontSize = 12.sp, color = Gomob.colors.fg2)
                Text(
                    "12",
                    style = Gomob.type.numInline.copy(fontSize = 18.sp),
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
            .size(30.dp)
            .clip(Gomob.shapes.r1)
            .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r1)
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
    Box(Modifier.padding(horizontal = Gomob.spacing.s20).padding(bottom = 14.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
                .ticks()
                .padding(horizontal = 10.dp, vertical = Gomob.spacing.s12),
        ) {
            // 周标题
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
                    .padding(bottom = Gomob.spacing.s8),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                WEEK_LABELS.forEach { w ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(
                            w,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.06.em,
                            color = Gomob.colors.fg3,
                        )
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Gomob.spacing.hairline)
                    .background(Gomob.colors.line1),
            )
            Spacer(Modifier.height(Gomob.spacing.s6))
            // 31 格 (按 7 列分行)
            cells.chunked(7).forEach { rowCells ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    rowCells.forEach { d ->
                        if (d == null) {
                            Box(Modifier.weight(1f).aspectRatio(1f))
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
                Spacer(Modifier.height(2.dp))
            }
            // 图例
            Spacer(Modifier.height(Gomob.spacing.s8))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Gomob.spacing.hairline)
                    .background(Gomob.colors.line1),
            )
            Spacer(Modifier.height(10.dp))
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
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.04.em,
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
            .aspectRatio(1f)
            .clip(Gomob.shapes.r1)
            .background(if (selected) Gomob.colors.accentSoft else Color.Transparent)
            .border(
                Gomob.spacing.hairline,
                if (selected) Gomob.colors.accentLine else Color.Transparent,
                Gomob.shapes.r1,
            )
            .clickable(enabled = !isInactive, onClick = onClick)
            .padding(4.dp),
    ) {
        Text(
            day.toString(),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = when {
                selected -> Gomob.colors.accent
                isInactive -> Gomob.colors.fg3.copy(alpha = 0.5f)
                else -> Gomob.colors.fg1
            },
        )
        if (today) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(Gomob.colors.accent),
            )
        }
        if (toneColor != null) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(Gomob.shapes.r1)
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
                .size(width = 8.dp, height = 3.dp)
                .clip(Gomob.shapes.r1)
                .background(color),
        )
        Text(
            label,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.04.em,
            color = Gomob.colors.fg3,
        )
    }
}

// ─── 选中日总览卡 ───────────────────────────────────────────────────────────
@Composable
private fun SelectedDayCard(year: Int, month: Int, day: Int, sel: DayData?) {
    Box(Modifier.padding(horizontal = Gomob.spacing.s20).padding(bottom = 14.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
                .padding(horizontal = 16.dp, vertical = Gomob.spacing.s14),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom,
            ) {
                Column {
                    Text(
                        "SELECTED",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.14.em,
                        color = Gomob.colors.fg3,
                    )
                    Spacer(Modifier.height(Gomob.spacing.s4))
                    Text(
                        "$year/${month.toString().padStart(2, '0')}/${day.toString().padStart(2, '0')}",
                        style = Gomob.type.numInline.copy(fontSize = 16.sp, letterSpacing = 0.02.em),
                        fontWeight = FontWeight.Medium,
                        color = Gomob.colors.fg0,
                    )
                }
                Row(
                    Modifier
                        .clip(Gomob.shapes.r1)
                        .border(Gomob.spacing.hairline, Gomob.colors.accentLine, Gomob.shapes.r1)
                        .clickable {}
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
                ) {
                    Text("查看流水", fontSize = 11.sp, color = Gomob.colors.accent)
                    Text("›", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Gomob.colors.accent)
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
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Normal,
                            letterSpacing = (-0.01).em,
                            lineHeight = 32.sp,
                        ),
                        color = Gomob.colors.accentStrong,
                    )
                    Text("条预审记录", fontSize = 11.sp, color = Gomob.colors.fg3)
                }
                Spacer(Modifier.height(Gomob.spacing.s12))
                // 三段比例条
                val total = sel.total.toFloat()
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(Gomob.shapes.r1)
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
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(Gomob.spacing.hairline)
                .height(40.dp)
                .background(Gomob.colors.line1),
        )
        Column {
            Text(
                n.toString(),
                style = Gomob.type.numInline.copy(fontSize = 18.sp, fontWeight = FontWeight.Normal),
                color = tone,
            )
            Spacer(Modifier.height(Gomob.spacing.s2))
            Text(label, fontSize = 10.sp, color = Gomob.colors.fg2)
        }
    }
}
