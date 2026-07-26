package io.gomob.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

/**
 * 06 我的 — jsx profile.jsx 主屏。
 *
 * 视觉骨架:
 *   1. 玻璃条右上 Settings 按钮 → 「设置」独立子页(profile/settings, 原右滑抽屉已删);
 *      身份 hero(头像/姓名/工号)沉在内容区
 *   2. 身份卡: 点击进入我的资料; 56dp acc 头像方框 "沈" + 沈海明 17sp medium +
 *      查验员 acc tag + 工号 mono + 检测站 + 我的案例入口
 *   3. 今日流水: 标题 "今日流水" + "历史数据 ›" 链接 → 跳到 07 历史日历
 *      4 FilterChip 按状态配色计数 + 6 FlowRow (状态点 + VIN num + 时间 + 车型 + tags)
 *
 * 用户指示"现有首页主要功能迁移到了'我的'里面" — 现 Home 的智能预审业务
 * (KPI 双卡 + ChipRow + VIN 列表) 在这里以 FilterChip 计数 + FlowRow 列表的形式
 * 完整融合, 不丢业务。
 */
@Composable
fun ProfileRoute(
    onOpenPersonal: () -> Unit = {},
    onOpenCases: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    vm: ProfileViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    GlassHeaderScaffold(
        listState = listState,
        header = {
            ScreenHeader(
                title = "我的",
                trailing = { SettingsIconButton(onClick = onOpenSettings) },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 28.dp,
            ),
        ) {
            item {
                ProfileHero(
                    state = state,
                    onOpenEdit = onOpenPersonal,
                    onOpenCases = onOpenCases,
                )
            }
            item { Spacer(Modifier.height(Gomob.spacing.s16)) }
            item { FlowSectionHeader(onOpenHistory = onOpenHistory) }
            item { FilterChipRow() }
            item { Spacer(Modifier.height(10.dp)) }
            item {
                HairlineCard(
                    modifier = Modifier.padding(horizontal = Gomob.spacing.pageGutter),
                    padding = 0.dp,
                ) {
                    Column {
                        FLOW_ROWS.forEachIndexed { index, row ->
                            FlowRow(row)
                            if (index != FLOW_ROWS.lastIndex) FlowRowDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsIconButton(onClick: () -> Unit) {
    // 44dp 命中区内 36dp 圆底, 视觉收敛、触控不缩水
    Box(
        Modifier.size(Gomob.spacing.touchMin).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Gomob.colors.bg3),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                GomobIcons.Settings,
                contentDescription = "设置",
                tint = Gomob.colors.fg1,
                modifier = Modifier.size(Gomob.spacing.icon20),
            )
        }
    }
}

// ─── 身份 hero ───────────────────────────────────────────────────────────────
// 无卡壳直接铺在氛围光晕上(iOS 设置页风); 点主体进编辑资料, 案例数是独立小入口。
@Composable
private fun ProfileHero(
    state: ProfileUiState,
    onOpenEdit: () -> Unit,
    onOpenCases: () -> Unit,
) {
    val name = state.profile?.realName ?: "沈海明"
    val role = state.profile?.roleLabel ?: "查验员"
    val empId = state.profile?.employeeId ?: "ZAA0120230001"
    val station = state.profile?.stationName ?: "杭州市西湖区 · 车管所检测站"
    Column(Modifier.padding(horizontal = Gomob.spacing.pageGutter)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .clickable(onClick = onOpenEdit)
                .padding(vertical = Gomob.spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                Modifier
                    .size(Gomob.spacing.avatarHero)
                    .clip(Gomob.shapes.r3)
                    .background(Gomob.colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    state.profile?.avatarInitial ?: name.take(1),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.04.em,
                    color = Gomob.colors.accent,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                ) {
                    Text(name, style = Gomob.type.screenTitle, color = Gomob.colors.fg0)
                    AccTag(role)
                }
                Text(
                    "工号 $empId",
                    style = Gomob.type.numInline.copy(fontSize = 11.sp),
                    color = Gomob.colors.fg3,
                )
            }
            Icon(
                imageVector = GomobIcons.ChevronRight,
                contentDescription = "编辑资料",
                tint = Gomob.colors.fg3,
                modifier = Modifier.size(18.dp),
            )
        }
        // 次要信息行: 站点 + 我的案例入口
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = Gomob.spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                station,
                fontSize = 12.sp,
                color = Gomob.colors.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Row(
                Modifier
                    .clip(Gomob.shapes.r1)
                    .clickable(onClick = onOpenCases)
                    .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
            ) {
                Text("我的案例", fontSize = 12.sp, color = Gomob.colors.accent)
                Text(
                    "06",
                    style = Gomob.type.numInline.copy(fontSize = 12.sp),
                    color = Gomob.colors.accent,
                )
                Icon(
                    imageVector = GomobIcons.ChevronRight,
                    contentDescription = null,
                    tint = Gomob.colors.accent,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
    }
}

@Composable
private fun AccTag(text: String) {
    Box(
        Modifier
            .clip(Gomob.shapes.r1)
            .background(Gomob.colors.accentSoft)
            .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s2),
    ) {
        Text(text, fontSize = 11.sp, color = Gomob.colors.accent)
    }
}

// ─── 预审流水 ───────────────────────────────────────────────────────────────
@Composable
private fun FlowSectionHeader(onOpenHistory: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.pageGutter)
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("今日流水", style = Gomob.type.sectionTitle, color = Gomob.colors.fg1)
        Row(
            Modifier.clickable(onClick = onOpenHistory),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
        ) {
            Text("历史数据", fontSize = 12.sp, color = Gomob.colors.accent)
            Text("›", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Gomob.colors.accent)
        }
    }
}

private enum class FilterTone { Accent, Danger, Warn, Ok }

private data class FilterCount(val label: String, val n: Int, val tone: FilterTone)

// TODO(demo-data R1): 这是占位假数据,未接真实统计 Repository(工单分类计数);终态见 feature/profile 接 ProfileRepository。
private val FILTER_COUNTS = listOf(
    FilterCount("全部", 170, FilterTone.Accent),
    FilterCount("异常", 78, FilterTone.Danger),
    FilterCount("预警", 48, FilterTone.Warn),
    FilterCount("正常", 44, FilterTone.Ok),
)

@Composable
private fun FilterChipRow() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.pageGutter),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        FILTER_COUNTS.forEach { FlowFilterChip(it, Modifier.weight(1f)) }
    }
}

@Composable
private fun FlowFilterChip(c: FilterCount, modifier: Modifier = Modifier) {
    val (fg, bg) = when (c.tone) {
        FilterTone.Accent -> Gomob.colors.accent to Gomob.colors.accentSoft
        FilterTone.Danger -> Gomob.colors.danger to Gomob.colors.dangerSoft
        FilterTone.Warn -> Gomob.colors.warn to Gomob.colors.warnSoft
        FilterTone.Ok -> Gomob.colors.ok to Gomob.colors.okSoft
    }
    Row(
        modifier
            .height(30.dp)
            .clip(Gomob.shapes.r2)
            .background(bg)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            c.label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = fg,
        )
        Spacer(Modifier.size(Gomob.spacing.s4))
        Text(
            c.n.toString(),
            style = Gomob.type.numInline.copy(fontSize = 11.sp),
            color = fg,
        )
    }
}

private enum class FlowStatus { Ok, Warn, Danger }
private data class FlowTag(val label: String, val tone: FlowStatus)
private data class FlowRowData(
    val ts: String,
    val vin: String,
    val model: String,
    val tags: List<FlowTag>,
    val status: FlowStatus,
)

// TODO(demo-data R1): 这是占位假数据,未接真实工单流水 Repository;终态见 feature/profile 接 ProfileRepository / 后端 /v1 工单列表。
private val FLOW_ROWS = listOf(
    FlowRowData("12:42", "LSVHM98277661003", "丰田系列 · 小型汽车 · 浙A88K90",
        listOf(FlowTag("已通过", FlowStatus.Ok)), FlowStatus.Ok),
    FlowRowData("12:18", "LSVHM41182123456", "大众系列 · 小型汽车 · 沪A57Y0",
        listOf(FlowTag("VIN车架号", FlowStatus.Warn), FlowTag("出厂日期", FlowStatus.Warn)),
        FlowStatus.Warn),
    FlowRowData("11:45", "LSVHM133022221761", "大众系列 · 小型汽车 · 沪A12345",
        listOf(FlowTag("OBD检验", FlowStatus.Danger), FlowTag("外廓尺寸", FlowStatus.Warn)),
        FlowStatus.Danger),
    FlowRowData("11:12", "LSVHM52017788321", "本田系列 · 小型汽车 · 浙A91K20",
        listOf(FlowTag("已通过", FlowStatus.Ok)), FlowStatus.Ok),
    FlowRowData("10:48", "LSVHM77129003344", "比亚迪 · 小型汽车 · 浙B22T01",
        listOf(FlowTag("OBD检验", FlowStatus.Warn)), FlowStatus.Warn),
    FlowRowData("10:22", "LSVHM33409912200", "吉利系列 · 小型汽车 · 浙A30A10",
        listOf(FlowTag("已通过", FlowStatus.Ok)), FlowStatus.Ok),
)

// TODO(终态): 流水详情页 — 接工单详情路由后整行可点跳转, 当前设计无箭头不可点。
@Composable
private fun FlowRow(r: FlowRowData) {
    val statusColor = when (r.status) {
        FlowStatus.Ok -> Gomob.colors.ok
        FlowStatus.Warn -> Gomob.colors.warn
        FlowStatus.Danger -> Gomob.colors.danger
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            Box(
                Modifier
                    .size(Gomob.spacing.dot6)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            Text(
                r.vin,
                modifier = Modifier.weight(1f),
                style = Gomob.type.numInline.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.04.em,
                ),
                color = Gomob.colors.fg0,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                r.ts,
                style = Gomob.type.numInline.copy(fontSize = 11.sp, letterSpacing = 0.02.em),
                color = Gomob.colors.fg3,
                maxLines = 1,
            )
        }
        Text(
            r.model,
            modifier = Modifier.padding(start = 14.dp),
            fontSize = 12.sp,
            color = Gomob.colors.fg2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            r.tags.forEach { FlowTagPill(it) }
        }
    }
}

@Composable
private fun FlowRowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = Gomob.spacing.s28)
            .height(Gomob.spacing.hairline)
            .background(Gomob.colors.line1),
    )
}

@Composable
private fun FlowTagPill(t: FlowTag) {
    val (fg, bg) = when (t.tone) {
        FlowStatus.Ok -> Gomob.colors.ok to Gomob.colors.okSoft
        FlowStatus.Warn -> Gomob.colors.warn to Gomob.colors.warnSoft
        FlowStatus.Danger -> Gomob.colors.danger to Gomob.colors.dangerSoft
    }
    Row(
        Modifier
            .height(Gomob.spacing.chipHeight)
            .clip(Gomob.shapes.r1)
            .background(bg)
            .padding(horizontal = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            t.label,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.04.em,
            color = fg,
        )
    }
}
