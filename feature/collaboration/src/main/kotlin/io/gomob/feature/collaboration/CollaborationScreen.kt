package io.gomob.feature.collaboration

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

// ---- 第一视角 mock 数据 ----
private data class LiveStream(
    val id: String,
    val inspector: String,
    val station: String,
    val duration: String,
    val warn: Boolean,
)

private data class Recording(
    val id: String,
    val title: String,
    val inspector: String,
    val duration: String,
    val views: Int,
    val time: String,
)

private val LIVE_STREAMS = listOf(
    LiveStream("L1", "刘沿", "西湖区检测站", "12:34", warn = false),
    LiveStream("L2", "陈工", "余杭区检测站", "08:21", warn = true),
    LiveStream("L3", "周文俊", "拱墅区检测站", "23:07", warn = false),
    LiveStream("L4", "吴敏", "滨江区检测站", "01:42", warn = false),
)

private val RECORDINGS = listOf(
    Recording("R1", "VIN 复核全流程", "沈海明", "06:18", views = 87, time = "今日 09:12"),
    Recording("R2", "ODB 异常处置范例", "陈工", "12:04", views = 132, time = "昨日 16:48"),
    Recording("R3", "外观加装件标注", "刘沿", "04:55", views = 41, time = "昨日 14:02"),
)

// ---- 案例公开 mock ----
private data class Case(
    val id: String,
    val title: String,
    val type: String,
    val tone: StatusTone,
    val station: String,
    val views: Int,
)

private val CASES = listOf(
    Case("C1", "篡改铭牌识别要点", "合规", StatusTone.Danger, "杭州西湖区", 312),
    Case("C2", "改装件年检判定", "外观", StatusTone.Warn, "杭州余杭区", 218),
    Case("C3", "OBD 数据异常排查", "OBD", StatusTone.Accent, "杭州拱墅区", 154),
    Case("C4", "新能源 VIN 验证", "合规", StatusTone.Ok, "杭州滨江区", 96),
)

// ---- 抽查复核 mock ----
private val BAR_DAYS = listOf("一", "二", "三", "四", "五", "六", "日")
private val BAR_VALUES = listOf(35, 30, 23, 28, 47, 60, 42)

@Composable
fun CollaborationRoute(
    onOpenReview: (String) -> Unit = {},
    onOpenLiveStream: (String) -> Unit = {},
) {
    var sub by remember { mutableStateOf(0) }

    Column(
        Modifier.fillMaxSize().background(Gomob.colors.bg0),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        val (eyebrow, badge, badgeTone) = when (sub) {
            0 -> Triple("团队 · 实时第一视角直播", "8 在线", StatusTone.Accent)
            1 -> Triple("团队 · 抽查复核 / 工单分发", "127 待办", StatusTone.Warn)
            else -> Triple("团队 · 公开案例库", "780 案例", StatusTone.Neutral)
        }
        ScreenHeader(
            title = "多方协作",
            eyebrow = eyebrow,
            trailing = { StatusTag(text = badge, tone = badgeTone, showDot = true) },
        )

        Row(
            Modifier.padding(horizontal = Gomob.spacing.s16),
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            SUB_TABS.forEachIndexed { i, label ->
                Chip(text = label, selected = i == sub, onClick = { sub = i })
            }
        }

        Box(Modifier.fillMaxSize()) {
            when (sub) {
                0 -> FirstPersonBoard(onOpenLiveStream = onOpenLiveStream)
                1 -> ReviewBoard(onOpenReview = onOpenReview)
                else -> CaseLibBoard()
            }
        }
    }
}

// ============================================================================
// 第一视角 — 实时直播 + 录像分享
// ============================================================================
@Composable
private fun FirstPersonBoard(onOpenLiveStream: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gomob.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12)) {
            MetricTile(
                label = "在线视角",
                value = "8",
                delta = "+2",
                trend = MetricTrend.Up,
                caption = "较昨日",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "今日观看",
                value = "127",
                delta = "+18%",
                trend = MetricTrend.Up,
                caption = "覆盖 4 站",
                modifier = Modifier.weight(1f),
            )
        }

        Text("在线视角", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)

        // 2-col grid (静态 4 cell — 用 2 行 Row,避免引入 LazyVerticalGrid 依赖)
        LIVE_STREAMS.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12)) {
                rowItems.forEach {
                    LiveStreamTile(
                        stream = it,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpenLiveStream(it.id) },
                    )
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }

        Text("近期录像", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)

        HairlineCard(padding = 0.dp) {
            Column {
                RECORDINGS.forEachIndexed { i, r ->
                    RecordingRow(r)
                    if (i != RECORDINGS.lastIndex) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Gomob.spacing.s16)
                                .height(Gomob.spacing.hairline)
                                .background(Gomob.colors.line1),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Gomob.spacing.s16))
    }
}

@Composable
private fun LiveStreamTile(
    stream: LiveStream,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1)
            .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2)
            .clickable(onClick = onClick),
    ) {
        // 视频缩略图 16:9 占位
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .background(Gomob.colors.bg2),
        ) {
            // LIVE 角标
            Row(
                Modifier
                    .padding(Gomob.spacing.s8)
                    .clip(Gomob.shapes.r1)
                    .background(Gomob.colors.danger.copy(alpha = 0.92f))
                    .padding(horizontal = Gomob.spacing.s8, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(Gomob.colors.bg0),
                )
                Text("LIVE", style = Gomob.type.caption, color = Gomob.colors.bg0)
            }
            // 警示
            if (stream.warn) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(Gomob.spacing.s8)
                        .clip(Gomob.shapes.r1)
                        .background(Gomob.colors.warnSoft)
                        .border(Gomob.spacing.hairline, Gomob.colors.warn.copy(alpha = 0.32f), Gomob.shapes.r1)
                        .padding(horizontal = Gomob.spacing.s6, vertical = 2.dp),
                ) {
                    Text("预警", style = Gomob.type.caption, color = Gomob.colors.warn)
                }
            }
            // 时长
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Gomob.spacing.s8)
                    .clip(Gomob.shapes.r1)
                    .background(Gomob.colors.bg0.copy(alpha = 0.72f))
                    .padding(horizontal = Gomob.spacing.s6, vertical = 2.dp),
            ) {
                Text(stream.duration, style = Gomob.type.numInline, color = Gomob.colors.fg0)
            }
        }
        // meta
        Column(
            Modifier.padding(Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stream.inspector, style = Gomob.type.body, color = Gomob.colors.fg0)
            Text(stream.station, style = Gomob.type.caption, color = Gomob.colors.fg3)
        }
    }
}

@Composable
private fun RecordingRow(r: Recording) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {}
            .padding(Gomob.spacing.s12),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 缩略图
        Box(
            Modifier
                .size(width = 88.dp, height = 56.dp)
                .clip(Gomob.shapes.r1)
                .background(Gomob.colors.bg2)
                .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r1),
            contentAlignment = Alignment.Center,
        ) {
            Text(r.duration, style = Gomob.type.numInline, color = Gomob.colors.fg2)
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(r.title, style = Gomob.type.body, color = Gomob.colors.fg0)
            Text(
                "${r.inspector} · ${r.time} · ${r.views} 次观看",
                style = Gomob.type.caption,
                color = Gomob.colors.fg3,
            )
        }
    }
}

// ============================================================================
// 抽查复核 — 看板 + 待办
// ============================================================================
@Composable
private fun ReviewBoard(onOpenReview: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gomob.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12)) {
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

        HairlineCard {
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

        HairlineCard(onClick = { onOpenReview("CLCY2025052089757") }) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
                    Text("最新接收 → 开始复核", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    Text(
                        "CLCY2025052089757 · 2026/03/10 14:24",
                        style = Gomob.type.body,
                        color = Gomob.colors.fg1,
                    )
                }
                StatusTag(text = "实时", tone = StatusTone.Accent, showDot = true)
            }
        }

        Spacer(Modifier.height(Gomob.spacing.s16))
    }
}

// ============================================================================
// 案例公开 — 公开案例库(培训/参考)
// ============================================================================
@Composable
private fun CaseLibBoard() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gomob.spacing.s16),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        HairlineCard {
            Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                Text("案例库说明", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                Text(
                    "已公开 780 例,跨 31 个检测站。点入查看完整查验流程与判定依据,可作为新员工培训素材。",
                    style = Gomob.type.bodySm,
                    color = Gomob.colors.fg1,
                )
            }
        }

        HairlineCard(padding = 0.dp) {
            Column {
                CASES.forEachIndexed { i, c ->
                    CaseRow(c)
                    if (i != CASES.lastIndex) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Gomob.spacing.s16)
                                .height(Gomob.spacing.hairline)
                                .background(Gomob.colors.line1),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(Gomob.spacing.s16))
    }
}

@Composable
private fun CaseRow(c: Case) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {}
            .padding(Gomob.spacing.s12),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusTag(text = c.type, tone = c.tone)
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(c.title, style = Gomob.type.body, color = Gomob.colors.fg0)
            Text(
                "${c.station} · ${c.views} 次浏览",
                style = Gomob.type.caption,
                color = Gomob.colors.fg3,
            )
        }
    }
}
