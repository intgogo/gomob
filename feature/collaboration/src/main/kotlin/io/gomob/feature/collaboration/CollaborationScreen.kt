package io.gomob.feature.collaboration

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.message.LiveSessionRepository
import io.gomob.data.message.MediaSessionRepository
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.MetricTile
import io.gomob.designsystem.component.MetricTrend
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.component.SegmentedTabItem
import io.gomob.designsystem.component.SegmentedTabs
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.model.message.LiveSessionSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

const val COLLAB_ROUTE = "collaboration"

private val SUB_TABS = listOf(
    SegmentedTabItem("第一视角"),
    SegmentedTabItem("抽查复核"),
    SegmentedTabItem("案例公开"),
)

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
    viewModel: CollaborationViewModel = hiltViewModel(),
) {
    var sub by remember { mutableStateOf(0) }
    val liveSessions by viewModel.liveSessions.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.refreshLiveSessions()
    }

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        val (eyebrow, badge, badgeTone) = when (sub) {
            0 -> Triple("团队 · 实时第一视角直播", "${liveSessions.size} 在线", StatusTone.Accent)
            1 -> Triple("团队 · 抽查复核 / 工单分发", "127 待办", StatusTone.Warn)
            else -> Triple("团队 · 公开案例库", "780 案例", StatusTone.Neutral)
        }
        ScreenHeader(
            title = "多方协作",
            eyebrow = eyebrow,
            trailing = { StatusTag(text = badge, tone = badgeTone, showDot = true) },
        )

        SegmentedTabs(
            items = SUB_TABS,
            selectedIndex = sub,
            onSelect = { sub = it },
            modifier = Modifier.padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, bottom = 14.dp),
        )

        Box(Modifier.fillMaxSize()) {
            when (sub) {
                0 -> FirstPersonBoard(liveSessions = liveSessions, onOpenLiveStream = onOpenLiveStream)
                1 -> ReviewBoard(onOpenReview = onOpenReview)
                else -> CaseLibBoard()
            }
        }
    }
}

@HiltViewModel
class CollaborationViewModel @Inject constructor(
    liveSessionRepository: LiveSessionRepository,
    private val mediaSessionRepository: MediaSessionRepository,
) : ViewModel() {
    val liveSessions: StateFlow<List<LiveSessionSummary>> =
        liveSessionRepository.observeLiveSessions().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList(),
        )

    fun refreshLiveSessions() {
        viewModelScope.launch {
            runCatching { mediaSessionRepository.refreshLiveSessions() }
        }
    }
}

// ============================================================================
// 第一视角 — 实时直播 + 录像分享
// ============================================================================
@Composable
private fun FirstPersonBoard(
    liveSessions: List<LiveSessionSummary>,
    onOpenLiveStream: (String) -> Unit,
) {
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
                value = liveSessions.size.toString(),
                delta = "实时",
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

        if (liveSessions.isEmpty()) {
            HairlineCard(padding = Gomob.spacing.s16) {
                StatusTag(text = "暂无在线第一视角", tone = StatusTone.Neutral, showDot = false)
            }
        } else {
            liveSessions.chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12)) {
                    rowItems.forEach {
                        LiveStreamTile(
                            session = it,
                            modifier = Modifier.weight(1f),
                            onClick = { onOpenLiveStream(it.id.toString()) },
                        )
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }

        Text("近期录像", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)

        HairlineCard(padding = 0.dp) {
            Column {
                RECORDINGS.forEachIndexed { i, r ->
                    RecordingRow(r)
                    if (i != RECORDINGS.lastIndex) {
                        ListRowDivider(start = 112.dp)
                    }
                }
            }
        }

        Spacer(Modifier.height(Gomob.spacing.s16))
    }
}

@Composable
private fun LiveStreamTile(
    session: LiveSessionSummary,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1)
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
            // 时长
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Gomob.spacing.s8)
                    .clip(Gomob.shapes.r1)
                    .background(Gomob.colors.bg0.copy(alpha = 0.72f))
                    .padding(horizontal = Gomob.spacing.s6, vertical = 2.dp),
            ) {
                Text("LIVE", style = Gomob.type.numInline, color = Gomob.colors.fg0)
            }
        }
        // meta
        Column(
            Modifier.padding(Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(session.title, style = Gomob.type.body, color = Gomob.colors.fg0)
            Text("发布者 #${session.publisherId}", style = Gomob.type.caption, color = Gomob.colors.fg3)
        }
    }
}

@Composable
private fun ListRowDivider(start: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = start, end = Gomob.spacing.s16)
            .height(Gomob.spacing.hairline)
            .background(Gomob.colors.line1.copy(alpha = 0.03f)),
    )
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
                .background(Gomob.colors.bg2),
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
// 抽查复核 — 4 KPI + 趋势柱图 + 开始复核 CTA + 即时预警三段比例条
// jsx coop.jsx 主体内容
// ============================================================================
private val REVIEW_TREND = listOf(18, 22, 15, 17, 24, 31, 21)
private val REVIEW_DAYS = listOf("一", "二", "三", "四", "五", "六", "七")

@Composable
private fun ReviewBoard(onOpenReview: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Gomob.spacing.s20),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        // 4 KPI tile 2×2 网格
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile("今日复核", "26", "环比 ↑ 24.53%", KpiTone.Acc, Modifier.weight(1f))
                KpiTile("待复核", "37", "今日新增 8", KpiTone.Warn, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile("历史复核", "1132", "近 7 日 +327", KpiTone.Acc, Modifier.weight(1f))
                KpiTile("历史过期", "3", "今日 0", KpiTone.Danger, Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))

        // 趋势柱图卡
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .padding(start = 16.dp, end = 16.dp, top = Gomob.spacing.s14, bottom = Gomob.spacing.s12),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text("复核趋势", fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = Gomob.colors.fg0)
                    Text("每日复核量", fontSize = 11.sp, color = Gomob.colors.fg2, modifier = Modifier.padding(top = Gomob.spacing.s4))
                }
                Text("近 7 天", fontSize = 11.sp, color = Gomob.colors.fg3)
            }
            Spacer(Modifier.height(14.dp))
            BarChart(values = REVIEW_TREND, days = REVIEW_DAYS)
        }

        // 开始复核 CTA — 字距 0.3em + 右侧 ArrowRight
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.accentSoft)
                .clickable { onOpenReview("CLCY2025052089757") },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "开始复核",
                fontSize = 13.sp,
                letterSpacing = 0.3.em,
                color = Gomob.colors.accent,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Box(
                Modifier.fillMaxWidth().padding(end = 16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    GomobIcons.ArrowRight,
                    contentDescription = null,
                    tint = Gomob.colors.accent,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        // 即时预警卡 (Eyeball 图标 + 大数 + 三段比例条)
        LiveAlertCard()

        Spacer(Modifier.height(Gomob.spacing.s24))
    }
}

private enum class KpiTone { Acc, Warn, Danger, Ok }

@Composable
private fun KpiTile(
    label: String,
    value: String,
    meta: String,
    tone: KpiTone,
    modifier: Modifier = Modifier,
) {
    val color = when (tone) {
        KpiTone.Acc -> Gomob.colors.accent
        KpiTone.Warn -> Gomob.colors.warn
        KpiTone.Danger -> Gomob.colors.danger
        KpiTone.Ok -> Gomob.colors.ok
    }
    Box(
        modifier
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
    ) {
        Column(
            Modifier.padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        ) {
            Text(label, fontSize = 11.sp, color = Gomob.colors.fg2)
            Spacer(Modifier.height(Gomob.spacing.s2))
            Text(
                value,
                style = Gomob.type.numInline.copy(
                    fontSize = 22.sp,
                    letterSpacing = (-0.01).em,
                    lineHeight = 24.sp,
                ),
                color = color,
            )
            Spacer(Modifier.height(Gomob.spacing.s4))
            Text(meta, fontSize = 10.sp, color = Gomob.colors.fg3)
        }
    }
}

@Composable
private fun BarChart(values: List<Int>, days: List<String>) {
    val max = values.max()
    Column {
        // 柱体 (height = max(8, v/max * 76))
        Row(
            Modifier.fillMaxWidth().height(80.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            values.forEach { v ->
                val h = (8 + (v.toFloat() / max) * 68f).coerceAtLeast(8f)
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        v.toString(),
                        style = Gomob.type.numInline.copy(fontSize = 9.sp),
                        color = Gomob.colors.fg3,
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(h.dp)
                            .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                            .background(
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(Gomob.colors.accent, Gomob.colors.accentSoft),
                                ),
                            ),
                    )
                }
            }
        }
        Spacer(Modifier.height(Gomob.spacing.s8))
        Spacer(Modifier.height(Gomob.spacing.s6))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            days.forEach { d ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text(d, fontSize = 10.sp, color = Gomob.colors.fg3)
                }
            }
        }
    }
}

@Composable
private fun LiveAlertCard() {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                Icon(
                    GomobIcons.Eyeball,
                    contentDescription = null,
                    tint = Gomob.colors.danger,
                    modifier = Modifier.size(Gomob.spacing.icon16),
                )
                Column {
                    Text(
                        "即时预警",
                        fontSize = 13.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        color = Gomob.colors.fg0,
                    )
                    Spacer(Modifier.height(3.dp))
                    Row {
                        Text("最新接收 ", fontSize = 10.sp, color = Gomob.colors.fg3)
                        Text(
                            "2026/03/10 14:24",
                            style = Gomob.type.numInline.copy(fontSize = 10.sp),
                            color = Gomob.colors.fg3,
                        )
                    }
                }
            }
            Text(
                "127",
                style = Gomob.type.numInline.copy(fontSize = 22.sp, letterSpacing = (-0.01).em),
                color = Gomob.colors.danger,
            )
        }
        Spacer(Modifier.height(Gomob.spacing.s12))
        // 三段比例条 4dp 高
        Row(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Gomob.colors.bg3),
        ) {
            Box(Modifier.weight(0.58f).fillMaxHeight().background(Gomob.colors.ok))
            Box(Modifier.weight(0.20f).fillMaxHeight().background(Gomob.colors.warn))
            Box(Modifier.weight(0.22f).fillMaxHeight().background(Gomob.colors.danger))
        }
        Spacer(Modifier.height(Gomob.spacing.s6))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            LegendStat("正常", "74")
            LegendStat("警示", "25")
            LegendStat("异常", "28")
        }
    }
}

@Composable
private fun LegendStat(label: String, value: String) {
    Row {
        Text("$label ", fontSize = 10.sp, color = Gomob.colors.fg3)
        Text(
            value,
            style = Gomob.type.numInline.copy(fontSize = 10.sp),
            color = Gomob.colors.fg3,
        )
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
                        ListRowDivider(start = Gomob.spacing.s16)
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
