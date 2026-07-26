package io.gomob.feature.collaboration

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import io.gomob.designsystem.component.HeaderTabItem
import io.gomob.designsystem.component.HeaderTabs
import io.gomob.designsystem.component.MetricTile
import io.gomob.designsystem.component.MetricTrend
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.glass.glassPanelBg
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.model.message.LiveSessionSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

const val COLLAB_ROUTE = "collaboration"

// 暗面板固定色（非主题 token）：视频缩略图底与 scan3d 视口同款暗面板，明暗主题下保持一致
private val VideoPanelBg = Color(0xFF0B0E13)

// 暗面板固定亮红（非主题 token）：LIVE 角标只出现在固定暗面板上，不随主题翻转
private val LiveBadgeRed = Color(0xFFF87171)

// 暗面板固定亮青（非主题 token）：缩略图居中头像块首字用色
private val AvatarInitialTeal = Color(0xFF5EEAD4)

private val SUB_TABS = listOf(
    HeaderTabItem("第一视角"),
    HeaderTabItem("抽查复核"),
    HeaderTabItem("案例公开"),
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

// TODO(demo-data R1): 近期录像列表为占位假数据，未接真实录像/分享统计 API。
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

// TODO(demo-data R1): 公开案例库为占位假数据，未接真实案例库统计 API。
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
    val pagerState = rememberPagerState(pageCount = { SUB_TABS.size })
    val pagerScope = rememberCoroutineScope()
    val sub = pagerState.currentPage
    val liveSessions by viewModel.liveSessions.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        viewModel.refreshLiveSessions()
    }

    // 3 个子板各持独立滚动状态；scaffold 只接当前选中板的，分隔线随该板滚动渐显
    val boardScrollStates = listOf(rememberScrollState(), rememberScrollState(), rememberScrollState())

    // TODO(demo-data R1): 复核“127 待办”、案例“780 案例”徽标为占位假数据，未接真实统计 API。
    val (badge, badgeTone) = when (sub) {
        1 -> "127 待办" to StatusTone.Warn
        else -> "780 案例" to StatusTone.Neutral
    }
    GlassHeaderScaffold(
        scrollState = boardScrollStates[sub],
        header = {
            Column {
                ScreenHeader(
                    title = "多方协作",
                    trailing = {
                        if (sub == 0) {
                            OnlineCountPill(text = "${liveSessions.size} 在线")
                        } else {
                            StatusTag(text = badge, tone = badgeTone, showDot = true)
                        }
                    },
                )
                HeaderTabs(
                    items = SUB_TABS,
                    selectedIndex = sub,
                    onSelect = { page ->
                        pagerScope.launch { pagerState.animateScrollToPage(page) }
                    },
                )
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            Box(Modifier.fillMaxSize()) {
                when (page) {
                    0 -> FirstPersonBoard(
                        liveSessions = liveSessions,
                        onOpenLiveStream = onOpenLiveStream,
                        scrollState = boardScrollStates[0],
                        padding = padding,
                    )
                    1 -> ReviewBoard(
                        onOpenReview = onOpenReview,
                        scrollState = boardScrollStates[1],
                        padding = padding,
                    )
                    else -> CaseLibBoard(
                        scrollState = boardScrollStates[2],
                        padding = padding,
                    )
                }
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

/** header 右上"N 在线"小 pill：胶囊 + okSoft 底 + ok 点/字 */
@Composable
private fun OnlineCountPill(text: String) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Gomob.colors.okSoft)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(Gomob.colors.ok),
        )
        Text(text, fontSize = 11.sp, color = Gomob.colors.ok)
    }
}

@Composable
private fun FirstPersonBoard(
    liveSessions: List<LiveSessionSummary>,
    onOpenLiveStream: (String) -> Unit,
    scrollState: ScrollState,
    padding: PaddingValues,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            // scaffold padding 并进内容 padding：内容从玻璃 header 下穿过再避让
            .padding(
                start = Gomob.spacing.s16,
                end = Gomob.spacing.s16,
                top = padding.calculateTopPadding() + Gomob.spacing.s12,
                bottom = padding.calculateBottomPadding(),
            ),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12)) {
            MetricTile(
                label = "在线视角",
                value = liveSessions.size.toString(),
                delta = "实时",
                trend = MetricTrend.Flat,
                caption = "较昨日",
                compact = true,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "今日观看",
                value = "127",
                delta = "+18%",
                trend = MetricTrend.Up,
                caption = "覆盖 4 站",
                compact = true,
                modifier = Modifier.weight(1f),
            )
        }

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("在线视角", style = Gomob.type.sectionTitle, color = Gomob.colors.fg1)
            Text(
                "LIVE · ${liveSessions.size}",
                style = Gomob.type.numInline.copy(fontSize = 11.sp),
                color = Gomob.colors.fg3,
            )
        }

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

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("近期录像", style = Gomob.type.sectionTitle, color = Gomob.colors.fg1)
            Text(
                "%02d 条".format(RECORDINGS.size),
                style = Gomob.type.numInline.copy(fontSize = 11.sp),
                color = Gomob.colors.fg3,
            )
        }

        HairlineCard(padding = 0.dp) {
            Column {
                RECORDINGS.forEachIndexed { i, r ->
                    RecordingRow(r)
                    if (i != RECORDINGS.lastIndex) {
                        ListRowDivider(start = 74.dp)
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
            .glassPanelBg(shape = Gomob.shapes.r3)
            .clickable(onClick = onClick),
    ) {
        // 视频缩略图 16:9 占位 — 暗面板固定色底
        Box(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .background(VideoPanelBg),
        ) {
            // LIVE 角标 — 暗面板固定亮红，非主题 token
            Row(
                Modifier
                    .padding(Gomob.spacing.s8)
                    .clip(Gomob.shapes.r1)
                    .background(LiveBadgeRed.copy(alpha = 0.18f))
                    .padding(horizontal = 7.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Box(
                    Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(LiveBadgeRed),
                )
                Text(
                    "LIVE",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = LiveBadgeRed,
                )
            }
            // 居中装饰：发布者首字头像块（无名则留空）
            val initial = session.title.trim().take(1)
            if (initial.isNotEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                        .clip(Gomob.shapes.r3)
                        .background(Gomob.colors.accent.copy(alpha = 0.25f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        initial,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = AvatarInitialTeal,
                    )
                }
            }
        }
        // meta
        Column(
            Modifier.padding(horizontal = Gomob.spacing.s12, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                session.title,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Gomob.colors.fg0,
            )
            Text("发布者 #${session.publisherId}", style = Gomob.type.micro, color = Gomob.colors.fg3)
        }
    }
}

@Composable
private fun ListRowDivider(start: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = start)
            .height(1.dp)
            .background(Gomob.colors.line1),
    )
}

@Composable
private fun RecordingRow(r: Recording) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Gomob.spacing.rowList)
            .clickable {}
            .padding(horizontal = Gomob.spacing.s14),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 缩略图
        Box(
            Modifier
                .size(width = 48.dp, height = 32.dp)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.bg3),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                r.duration,
                style = Gomob.type.numInline.copy(fontSize = 11.sp),
                color = Gomob.colors.fg1,
            )
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                r.title,
                style = Gomob.type.bodySm.copy(fontWeight = FontWeight.Medium),
                color = Gomob.colors.fg0,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${r.inspector} · ${r.time} · ${r.views} 次观看",
                style = Gomob.type.micro,
                color = Gomob.colors.fg3,
            )
        }
        Text("›", fontSize = 15.sp, color = Gomob.colors.fg3)
    }
}

// ============================================================================
// 抽查复核 — 4 KPI + 趋势柱图 + 开始复核 CTA + 即时预警三段比例条
// jsx coop.jsx 主体内容
// ============================================================================
// TODO(demo-data R1): 抽查复核 KPI / 趋势柱图 / 即时预警三段比例条均为硬编码占位假数据，
// 未接真实复核统计 API（今日/待/历史复核量、近 7 日趋势、预警分布）。终态由后端统计接口回填。
private val REVIEW_TREND = listOf(18, 22, 15, 17, 24, 31, 21)
private val REVIEW_DAYS = listOf("一", "二", "三", "四", "五", "六", "七")

@Composable
private fun ReviewBoard(
    onOpenReview: (String) -> Unit,
    scrollState: ScrollState,
    padding: PaddingValues,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(
                start = Gomob.spacing.s20,
                end = Gomob.spacing.s20,
                top = padding.calculateTopPadding() + Gomob.spacing.s12,
                bottom = padding.calculateBottomPadding(),
            ),
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
private fun CaseLibBoard(
    scrollState: ScrollState,
    padding: PaddingValues,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(
                start = Gomob.spacing.s16,
                end = Gomob.spacing.s16,
                top = padding.calculateTopPadding() + Gomob.spacing.s12,
                bottom = padding.calculateBottomPadding(),
            ),
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
