package io.gomob.feature.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.data.prefs.ThemeMode
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.decoration.ticks
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

const val PROFILE_ROUTE = "profile"

/**
 * 06 我的 — jsx profile.jsx 主屏。
 *
 * 视觉骨架:
 *   1. ScreenHeader "我的 / 工号 ZAA0120230001" + 右上 Settings 按钮 → 抽屉
 *   2. 身份卡: 56dp acc 头像方框 "沈" + 沈海明 17sp medium + 查验员 acc tag +
 *      工号 mono + 检测站
 *   3. 预审流水: 标题 "预审流水" + "历史数据 ›" 链接 → 跳到 07 历史日历
 *      4 FilterChip (全部 170 / 异常 78 / 预警 48 / 正常 44)
 *      6 FlowRow (左侧时间 + 状态点 + 分隔线 + VIN num + 车型 + tags 数组 + ›)
 *   4. SettingsDrawer (右侧滑入 82% 宽): 6 SettingRow + "切换账号" CTA
 *      → 复用 ProfileSubscreens 的子页路由
 *
 * 用户指示"现有首页主要功能迁移到了'我的'里面" — 现 Home 的智能预审业务
 * (KPI 双卡 + ChipRow + VIN 列表) 在这里以 FilterChip 计数 + FlowRow 列表的形式
 * 完整融合, 不丢业务。
 */
@Composable
fun ProfileRoute(
    onOpenPersonal: () -> Unit = {},
    onOpenAccount: () -> Unit = {},
    onOpenNotification: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    vm: ProfileViewModel = hiltViewModel(),
    appearance: AppearanceViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val themeMode by appearance.mode.collectAsStateWithLifecycle()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cacheRoot = remember(context) { context.cacheDir }
    var cacheSize by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(cacheRoot) {
        cacheSize = withContext(Dispatchers.IO) { dirSize(cacheRoot) }
    }

    BackHandler(enabled = settingsOpen) {
        settingsOpen = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Gomob.colors.bg0),
    ) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "我的",
                eyebrow = "工号 ${state.profile?.employeeId ?: "ZAA0120230001"}",
                trailing = {
                    SettingsIconButton(onClick = { settingsOpen = !settingsOpen })
                },
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                item { IdentityCard(state) }
                item { Spacer(Modifier.height(Gomob.spacing.s12)) }
                item { FlowSectionHeader(onOpenHistory = onOpenHistory) }
                item { FilterChipRow() }
                item { Spacer(Modifier.height(10.dp)) }
                items(FLOW_ROWS.size) { i ->
                    Box(
                        Modifier
                            .padding(horizontal = Gomob.spacing.s20)
                            .padding(bottom = Gomob.spacing.s8),
                    ) { FlowRow(FLOW_ROWS[i]) }
                }
            }
        }

        // 设置抽屉 — Box.matchParentSize + AnimatedVisibility
        SettingsDrawer(
            visible = settingsOpen,
            onClose = { settingsOpen = false },
            onLogout = {
                vm.logout()
                settingsOpen = false
            },
            onOpenPersonal = onOpenPersonal,
            onOpenAccount = onOpenAccount,
            onOpenNotification = onOpenNotification,
            onOpenAbout = onOpenAbout,
            themeLabel = themeLabel(themeMode),
            onCycleTheme = { appearance.setMode(nextMode(themeMode)) },
            cacheText = formatCacheSize(cacheSize),
            onClearCache = {
                scope.launch {
                    withContext(Dispatchers.IO) { clearDir(cacheRoot) }
                    cacheSize = withContext(Dispatchers.IO) { dirSize(cacheRoot) }
                }
            },
        )
    }
}

private fun themeLabel(m: ThemeMode): String = when (m) {
    ThemeMode.System -> "跟随系统"
    ThemeMode.Light -> "浅色"
    ThemeMode.Dark -> "深色"
}

private fun nextMode(m: ThemeMode): ThemeMode = when (m) {
    ThemeMode.System -> ThemeMode.Light
    ThemeMode.Light -> ThemeMode.Dark
    ThemeMode.Dark -> ThemeMode.System
}

private fun dirSize(root: File): Long {
    if (!root.exists()) return 0L
    return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

private fun clearDir(root: File) {
    root.listFiles()?.forEach { it.deleteRecursively() }
}

private fun formatCacheSize(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.0f KB".format(bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

@Composable
private fun SettingsIconButton(onClick: () -> Unit) {
    Box(
        Modifier.size(Gomob.spacing.touchMin).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            GomobIcons.Settings,
            contentDescription = "设置",
            tint = Gomob.colors.fg2,
            modifier = Modifier.size(Gomob.spacing.icon20),
        )
    }
}

// ─── 身份卡 ─────────────────────────────────────────────────────────────────
@Composable
private fun IdentityCard(state: ProfileUiState) {
    val name = state.profile?.realName ?: "沈海明"
    val role = state.profile?.roleLabel ?: "查验员"
    val empId = state.profile?.employeeId ?: "ZAA0120230001"
    val station = state.profile?.stationName ?: "杭州市西湖区 · 车管所检测站"
    Box(Modifier.padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, bottom = Gomob.spacing.s12)) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
                .ticks()
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // 56dp 头像方框 (acc-soft 底 + acc-line 边)
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(Gomob.shapes.r2)
                        .background(Gomob.colors.accentSoft)
                        .border(Gomob.spacing.hairline, Gomob.colors.accentLine, Gomob.shapes.r2),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        name.take(1),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.04.em,
                        color = Gomob.colors.accentStrong,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                    ) {
                        Text(name, fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
                        AccTag(role)
                    }
                    Spacer(Modifier.height(Gomob.spacing.s6))
                    Row {
                        Text("工号 ", fontSize = 11.sp, color = Gomob.colors.fg2)
                        Text(
                            empId,
                            style = Gomob.type.numInline.copy(fontSize = 11.sp),
                            color = Gomob.colors.fg2,
                        )
                    }
                    Spacer(Modifier.height(Gomob.spacing.s4))
                    Text(station, fontSize = 11.sp, color = Gomob.colors.fg2)
                }
            }
        }
    }
}

@Composable
private fun AccTag(text: String) {
    Row(
        Modifier
            .height(Gomob.spacing.chipHeight)
            .clip(Gomob.shapes.r1)
            .background(Gomob.colors.accentSoft)
            .border(Gomob.spacing.hairline, Gomob.colors.accentLine, Gomob.shapes.r1)
            .padding(horizontal = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.04.em,
            color = Gomob.colors.accent,
        )
    }
}

// ─── 预审流水 ───────────────────────────────────────────────────────────────
@Composable
private fun FlowSectionHeader(onOpenHistory: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s20)
            .padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("预审流水", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
        Row(
            Modifier.clickable(onClick = onOpenHistory),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
        ) {
            Text("历史数据", fontSize = 11.sp, color = Gomob.colors.accent)
            Text("›", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Gomob.colors.accent)
        }
    }
}

private data class FilterCount(val label: String, val n: Int, val active: Boolean = false)

private val FILTER_COUNTS = listOf(
    FilterCount("全部", 170, active = true),
    FilterCount("异常", 78),
    FilterCount("预警", 48),
    FilterCount("正常", 44),
)

@Composable
private fun FilterChipRow() {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s20),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        FILTER_COUNTS.forEach { FlowFilterChip(it) }
    }
}

@Composable
private fun FlowFilterChip(c: FilterCount) {
    Row(
        Modifier
            .height(26.dp)
            .clip(Gomob.shapes.r1)
            .background(if (c.active) Gomob.colors.accentSoft else Color.Transparent)
            .border(
                Gomob.spacing.hairline,
                if (c.active) Gomob.colors.accentLine else Gomob.colors.line2,
                Gomob.shapes.r1,
            )
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
    ) {
        Text(
            c.label,
            fontSize = 11.sp,
            color = if (c.active) Gomob.colors.accent else Gomob.colors.fg1,
        )
        Text(
            c.n.toString(),
            style = Gomob.type.numInline.copy(fontSize = 11.sp),
            color = if (c.active)
                Gomob.colors.accent.copy(alpha = 0.7f)
            else
                Gomob.colors.fg1.copy(alpha = 0.7f),
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

@Composable
private fun FlowRow(r: FlowRowData) {
    val statusColor = when (r.status) {
        FlowStatus.Ok -> Gomob.colors.ok
        FlowStatus.Warn -> Gomob.colors.warn
        FlowStatus.Danger -> Gomob.colors.danger
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
            .clickable {}
            .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        // 左侧时间列 + 状态点 + 右分隔线
        Column(
            Modifier
                .padding(end = Gomob.spacing.s12)
                .width(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                r.ts,
                style = Gomob.type.numInline.copy(fontSize = 13.sp, letterSpacing = 0.02.em),
                color = Gomob.colors.fg1,
            )
            Spacer(Modifier.height(Gomob.spacing.s6))
            Box(
                Modifier
                    .size(Gomob.spacing.dot6)
                    .clip(CircleShape)
                    .background(statusColor),
            )
        }
        // 中间内容（与左侧分隔线共享高度）
        Box(
            Modifier
                .width(Gomob.spacing.hairline)
                .fillMaxHeight()
                .background(Gomob.colors.line1),
        )
        Column(
            Modifier
                .padding(start = Gomob.spacing.s12)
                .weight(1f),
        ) {
            Text(
                r.vin,
                style = Gomob.type.numInline.copy(fontSize = 13.sp, letterSpacing = 0.04.em),
                color = Gomob.colors.fg0,
                maxLines = 1,
            )
            Spacer(Modifier.height(Gomob.spacing.s4))
            Text(
                r.model,
                fontSize = 11.sp,
                color = Gomob.colors.fg2,
                maxLines = 1,
            )
            Spacer(Modifier.height(Gomob.spacing.s8))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                r.tags.forEach { FlowTagPill(it) }
            }
        }
        Icon(
            GomobIcons.ChevronRight,
            contentDescription = null,
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(14.dp).align(Alignment.CenterVertically),
        )
    }
}

@Composable
private fun FlowTagPill(t: FlowTag) {
    val (fg, line, bg) = when (t.tone) {
        FlowStatus.Ok -> Triple(Gomob.colors.ok, Gomob.colors.okLine, Gomob.colors.okSoft)
        FlowStatus.Warn -> Triple(Gomob.colors.warn, Gomob.colors.warnLine, Gomob.colors.warnSoft)
        FlowStatus.Danger -> Triple(Gomob.colors.danger, Gomob.colors.dangerLine, Gomob.colors.dangerSoft)
    }
    Row(
        Modifier
            .height(Gomob.spacing.chipHeight)
            .clip(Gomob.shapes.r1)
            .background(bg)
            .border(Gomob.spacing.hairline, line, Gomob.shapes.r1)
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

// ─── 设置抽屉 ──────────────────────────────────────────────────────────────
private data class SettingItem(
    val icon: ImageVector,
    val label: String,
    val value: String? = null,
    val mono: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun SettingsDrawer(
    visible: Boolean,
    onClose: () -> Unit,
    onLogout: () -> Unit,
    onOpenPersonal: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenNotification: () -> Unit,
    onOpenAbout: () -> Unit,
    themeLabel: String,
    onCycleTheme: () -> Unit,
    cacheText: String,
    onClearCache: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onClose),
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            DrawerContent(
                onClose = onClose,
                onLogout = onLogout,
                onOpenPersonal = onOpenPersonal,
                onOpenAccount = onOpenAccount,
                onOpenNotification = onOpenNotification,
                onOpenAbout = onOpenAbout,
                themeLabel = themeLabel,
                onCycleTheme = onCycleTheme,
                cacheText = cacheText,
                onClearCache = onClearCache,
            )
        }
    }
}

@Composable
private fun DrawerContent(
    onClose: () -> Unit,
    onLogout: () -> Unit,
    onOpenPersonal: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenNotification: () -> Unit,
    onOpenAbout: () -> Unit,
    themeLabel: String,
    onCycleTheme: () -> Unit,
    cacheText: String,
    onClearCache: () -> Unit,
) {
    val items = listOf(
        SettingItem(GomobIcons.ID, "个人信息", onClick = onOpenPersonal),
        SettingItem(GomobIcons.Lock, "账号与安全", onClick = onOpenAccount),
        SettingItem(GomobIcons.Moon, "切换主题", value = themeLabel, onClick = onCycleTheme),
        SettingItem(GomobIcons.Cache, "清理缓存", value = cacheText, onClick = onClearCache),
        SettingItem(GomobIcons.Bell, "通知设置", onClick = onOpenNotification),
        SettingItem(GomobIcons.Info, "关于 mob3d", value = "v0.1.0", onClick = onOpenAbout),
    )
    Column(
        Modifier
            .fillMaxWidth(0.82f)
            .fillMaxHeight()
            .background(Gomob.colors.bg1)
            .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r3),
    ) {
        // 抽屉顶
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "SETTINGS",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.14.em,
                    color = Gomob.colors.fg3,
                )
                Spacer(Modifier.height(Gomob.spacing.s2))
                Text("设置", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
            }
            Box(
                Modifier
                    .size(Gomob.spacing.avatar28)
                    .clip(Gomob.shapes.r1)
                    .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r1)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "✕",
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Gomob.colors.fg1,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.hairline)
                .background(Gomob.colors.line1),
        )
        // 列表
        Column(
            Modifier
                .weight(1f)
                .padding(18.dp),
        ) {
            Column(
                Modifier
                    .clip(Gomob.shapes.r3)
                    .background(Gomob.colors.bg2)
                    .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3),
            ) {
                items.forEachIndexed { i, item ->
                    SettingDrawerRow(item)
                    if (i != items.lastIndex) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(Gomob.spacing.hairline)
                                .background(Gomob.colors.line1),
                        )
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // 切换账号 CTA
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.bg1)
                    .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2)
                    .clickable(onClick = onLogout),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    GomobIcons.ArrowSwap,
                    contentDescription = null,
                    tint = Gomob.colors.fg1,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(Gomob.spacing.s8))
                Text("切换账号", fontSize = 13.sp, color = Gomob.colors.fg1)
            }
        }
    }
}

@Composable
private fun SettingDrawerRow(item: SettingItem) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = item.onClick)
            .padding(horizontal = Gomob.spacing.s14, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        Box(
            Modifier
                .size(Gomob.spacing.avatar28)
                .clip(Gomob.shapes.r1)
                .background(Gomob.colors.bg3)
                .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r1),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                item.icon,
                contentDescription = null,
                tint = Gomob.colors.accent,
                modifier = Modifier.size(14.dp),
            )
        }
        Text(
            item.label,
            fontSize = 13.sp,
            color = Gomob.colors.fg0,
            modifier = Modifier.weight(1f),
        )
        if (item.value != null) {
            Text(
                item.value,
                fontSize = if (item.mono) 11.sp else 12.sp,
                fontFamily = if (item.mono) FontFamily.Monospace else FontFamily.Default,
                letterSpacing = if (item.mono) 0.04.em else 0.em,
                color = Gomob.colors.fg2,
            )
        }
        Icon(
            GomobIcons.ChevronRight,
            contentDescription = null,
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(14.dp),
        )
    }
}
