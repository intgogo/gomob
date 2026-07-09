package io.gomob.feature.profile

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.data.prefs.ThemeMode
import io.gomob.designsystem.glass.GlassHeaderScaffold
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
 *   1. 玻璃条右上 Settings 按钮 → 抽屉; 身份 hero(头像/姓名/工号)沉在内容区
 *   2. 身份卡: 点击进入个人信息；56dp acc 头像方框 "沈" + 沈海明 17sp medium +
 *      查验员 acc tag + 工号 mono + 检测站
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
    onOpenCases: () -> Unit = onOpenPersonal,
    onOpenAccount: () -> Unit = {},
    onOpenNotification: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenHistory: () -> Unit = {},
    onOpenTheme: () -> Unit = {},
    vm: ProfileViewModel = hiltViewModel(),
    appearance: AppearanceViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val themeMode by appearance.mode.collectAsStateWithLifecycle()
    val colorScheme by appearance.colorScheme.collectAsStateWithLifecycle()
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

    val listState = rememberLazyListState()
    GlassHeaderScaffold(
        listState = listState,
        header = {
            // 身份 hero 化后 header 只留设置入口 —— 姓名/工号沉到内容区 hero, 不再叠床架屋
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s2),
                horizontalArrangement = Arrangement.End,
            ) {
                SettingsIconButton(onClick = { settingsOpen = !settingsOpen })
            }
        },
        overlay = {
            // 设置抽屉 — 全屏浮层放 overlay 槽, 面板内容自吃 systemBars inset
            SettingsDrawer(
                visible = settingsOpen,
                onClose = { settingsOpen = false },
                onLogout = {
                    vm.logout()
                    settingsOpen = false
                },
                onOpenAccount = onOpenAccount,
                onOpenNotification = onOpenNotification,
                onOpenAbout = onOpenAbout,
                themeLabel = themeLabel(themeMode) + " · " + colorScheme.displayName,
                onOpenTheme = {
                    settingsOpen = false
                    onOpenTheme()
                },
                cacheText = formatCacheSize(cacheSize),
                onClearCache = {
                    scope.launch {
                        withContext(Dispatchers.IO) { clearDir(cacheRoot) }
                        cacheSize = withContext(Dispatchers.IO) { dirSize(cacheRoot) }
                    }
                },
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
            items(FLOW_ROWS.size) { i ->
                Box(
                    Modifier
                        .padding(horizontal = Gomob.spacing.s20)
                        .padding(bottom = Gomob.spacing.s8),
                ) { FlowRow(FLOW_ROWS[i]) }
            }
        }
    }
}

private fun themeLabel(m: ThemeMode): String = when (m) {
    ThemeMode.System -> "跟随系统"
    ThemeMode.Light -> "浅色"
    ThemeMode.Dark -> "深色"
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
    Column(Modifier.padding(horizontal = Gomob.spacing.s20)) {
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
                    .size(56.dp)
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.accentSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    state.profile?.avatarInitial ?: name.take(1),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 0.04.em,
                    color = Gomob.colors.accentStrong,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                ) {
                    Text(name, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Gomob.colors.fg0)
                    AccTag(role)
                }
                Row {
                    Text("工号 ", fontSize = 11.sp, color = Gomob.colors.fg2)
                    Text(
                        empId,
                        style = Gomob.type.numInline.copy(fontSize = 11.sp),
                        color = Gomob.colors.fg2,
                    )
                }
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
                fontSize = 11.sp,
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
                Text("我的案例", fontSize = 11.sp, color = Gomob.colors.fg3)
                Text(
                    "06",
                    style = Gomob.type.numInline.copy(fontSize = 12.sp),
                    color = Gomob.colors.fg1,
                )
                Icon(
                    imageVector = GomobIcons.ChevronRight,
                    contentDescription = null,
                    tint = Gomob.colors.fg3,
                    modifier = Modifier.size(12.dp),
                )
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
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("今日流水", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
            Text(
                "170 条 · 异常 78 · 预警 48",
                style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.03.em),
                color = Gomob.colors.fg3,
            )
        }
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

// TODO(demo-data R1): 这是占位假数据,未接真实统计 Repository(工单分类计数);终态见 feature/profile 接 ProfileRepository。
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
        FILTER_COUNTS.forEach { FlowFilterChip(it, Modifier.weight(1f)) }
    }
}

@Composable
private fun FlowFilterChip(c: FilterCount, modifier: Modifier = Modifier) {
    Row(
        modifier
            .height(26.dp)
            .clip(Gomob.shapes.r1)
            .background(if (c.active) Gomob.colors.accentSoft else Gomob.colors.bg2)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
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
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .clickable {}
            .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
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
                style = Gomob.type.numInline.copy(fontSize = 13.sp, letterSpacing = 0.04.em),
                color = Gomob.colors.fg0,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                r.ts,
                style = Gomob.type.numInline.copy(fontSize = 12.sp, letterSpacing = 0.02.em),
                color = Gomob.colors.fg3,
                maxLines = 1,
            )
        }
        Text(
            r.model,
            modifier = Modifier.padding(start = 14.dp),
            fontSize = 11.sp,
            color = Gomob.colors.fg2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                r.tags.forEach { FlowTagPill(it) }
            }
            Icon(
                GomobIcons.ChevronRight,
                contentDescription = null,
                tint = Gomob.colors.fg3,
                modifier = Modifier.size(14.dp),
            )
        }
    }
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
    onOpenAccount: () -> Unit,
    onOpenNotification: () -> Unit,
    onOpenAbout: () -> Unit,
    themeLabel: String,
    onOpenTheme: () -> Unit,
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
                onLogout = onLogout,
                onOpenAccount = onOpenAccount,
                onOpenNotification = onOpenNotification,
                onOpenAbout = onOpenAbout,
                themeLabel = themeLabel,
                onOpenTheme = onOpenTheme,
                cacheText = cacheText,
                onClearCache = onClearCache,
            )
        }
    }
}

@Composable
private fun DrawerContent(
    onLogout: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenNotification: () -> Unit,
    onOpenAbout: () -> Unit,
    themeLabel: String,
    onOpenTheme: () -> Unit,
    cacheText: String,
    onClearCache: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val drawerClickSource = remember { MutableInteractionSource() }
    fun dismissInput() {
        focusManager.clearFocus()
        keyboardController?.hide()
    }
    val items = listOf(
        SettingItem(GomobIcons.Lock, "账号与安全", onClick = onOpenAccount),
        SettingItem(GomobIcons.Moon, "切换主题", value = themeLabel, onClick = onOpenTheme),
        SettingItem(GomobIcons.Cache, "清理缓存", value = cacheText, onClick = onClearCache),
        SettingItem(GomobIcons.Bell, "通知设置", onClick = onOpenNotification),
        SettingItem(GomobIcons.Info, "关于锐眼观车", value = "v0.1.0", onClick = onOpenAbout),
    )
    Column(
        Modifier
            .fillMaxWidth(0.82f)
            .fillMaxHeight()
            .background(Gomob.colors.bg1)
            .clickable(
                interactionSource = drawerClickSource,
                indication = null,
                onClick = { dismissInput() },
            )
            // 面板背景铺满全高, 内容避让状态栏/导航栏
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        // 抽屉顶
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 16.dp),
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
        }
        Spacer(Modifier.height(Gomob.spacing.s2))
        // 列表
        Column(
            Modifier
                .weight(1f)
                .padding(18.dp),
        ) {
            Column(
                Modifier
                    .clip(Gomob.shapes.r3)
                    .background(Gomob.colors.bg2),
            ) {
                items.forEachIndexed { i, item ->
                    SettingDrawerRow(item)
                    if (i != items.lastIndex) {
                        DrawerRowDivider()
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
                    .background(Gomob.colors.bg2)
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
private fun DrawerRowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 56.dp, end = Gomob.spacing.s14)
            .height(Gomob.spacing.hairline)
            .background(Gomob.colors.line1.copy(alpha = 0.03f)),
    )
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
                .background(Gomob.colors.bg3),
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
