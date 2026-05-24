package io.gomob.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.data.prefs.ThemeMode
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.SegmentedTabItem
import io.gomob.designsystem.component.SegmentedTabs
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.AllColorSchemes
import io.gomob.designsystem.theme.ColorScheme
import io.gomob.designsystem.theme.Gomob
import io.gomob.designsystem.theme.GomobColorSchemeSet
import io.gomob.designsystem.theme.GomobColors

/**
 * 主题设置页 —— 外观模式（系统/浅/深）× 色彩主题（5 套）。
 *
 * 关键设计：
 * - 5 套色彩主题用 2 列 grid 一屏可见，避免横滚卡片被切；
 *   原 LazyRow 方案下"默金珊瑚"几乎被切到屏外，用户根本意识不到它存在。
 * - 「实时预览」卡用当前全局 Gomob.colors 渲染一段 mini UI sample（标题/正文/
 *   主次按钮/状态 chip/列表行/分隔线），让切换的视觉反馈强且立刻可感。
 *   原方案只换了一段描述文字，缺少"真实色板的样子"。
 * - 卡片内部不再是抽象 hairline，而是 mini 卡：accent 主按钮条 + fg 字号示意 +
 *   3 个状态色 chip，能直观看出每套主题的对比度 / 暖冷感 / 重音色性格。
 * - 选了非默认时给「恢复默认」出口（薄荷青绿 + 跟随系统）。
 */
@Composable
fun ThemeSettingsRoute(
    onBack: () -> Unit,
    vm: AppearanceViewModel = hiltViewModel(),
) {
    val mode by vm.mode.collectAsStateWithLifecycle()
    val scheme by vm.colorScheme.collectAsStateWithLifecycle()
    val systemDark = isSystemInDarkTheme()
    val effectiveDark = when (mode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> systemDark
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Gomob.colors.bg0),
    ) {
        BackHeader(title = "主题设置", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = Gomob.spacing.s24),
        ) {
            SectionTitle("外观模式")
            Box(Modifier.padding(horizontal = Gomob.spacing.s20)) {
                SegmentedTabs(
                    items = listOf(
                        SegmentedTabItem("跟随系统"),
                        SegmentedTabItem("浅色"),
                        SegmentedTabItem("深色"),
                    ),
                    selectedIndex = when (mode) {
                        ThemeMode.System -> 0
                        ThemeMode.Light -> 1
                        ThemeMode.Dark -> 2
                    },
                    onSelect = { i ->
                        vm.setMode(
                            when (i) {
                                0 -> ThemeMode.System
                                1 -> ThemeMode.Light
                                else -> ThemeMode.Dark
                            },
                        )
                    },
                )
            }

            Spacer(Modifier.height(Gomob.spacing.s24))
            SectionTitle("色彩主题")
            SchemeGrid(
                selected = scheme,
                darkPreview = effectiveDark,
                onPick = vm::setColorScheme,
            )

            Spacer(Modifier.height(Gomob.spacing.s24))
            SectionTitle("实时预览")
            Box(Modifier.padding(horizontal = Gomob.spacing.s20)) {
                LivePreviewCard(scheme = scheme, dark = effectiveDark)
            }

            // 非默认配置时给「恢复默认」出口（默认 = 跟随系统 + 薄荷青绿）
            if (mode != ThemeMode.System || scheme != ColorScheme.Mint) {
                Spacer(Modifier.height(Gomob.spacing.s24))
                ResetDefaultButton(onClick = vm::resetToDefault)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = Gomob.type.eyebrow,
        color = Gomob.colors.fg2,
        modifier = Modifier.padding(
            start = Gomob.spacing.s20,
            top = Gomob.spacing.s12,
            bottom = Gomob.spacing.s8,
        ),
    )
}

// ─── 色板 grid（2 列） ───────────────────────────────────────────────────────
@Composable
private fun SchemeGrid(
    selected: ColorScheme,
    darkPreview: Boolean,
    onPick: (ColorScheme) -> Unit,
) {
    val rows = AllColorSchemes.chunked(2)
    Column(
        Modifier.padding(horizontal = Gomob.spacing.s20),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12)) {
                row.forEach { set ->
                    Box(Modifier.weight(1f)) {
                        ThemePreviewCard(
                            set = set,
                            darkPreview = darkPreview,
                            selected = selected == set.scheme,
                            onClick = { onPick(set.scheme) },
                        )
                    }
                }
                // 末行不足 2 列时补占位，保证最后一张不被拉满到整行
                repeat(2 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * 主题预览卡 —— 内部完全用本套主题在当前外观模式下的色板渲染（不串到全局 Gomob.colors）。
 *
 * 卡片内是一个 mini UI sample：
 *  - 顶部 accent 圆 + "Aa" 字号示意 + 主题名
 *  - 中部一行 fg1 文字示意（标题感）+ 一行 fg2 文字示意（正文感）+ 一行 line2 分隔
 *  - accent 主按钮条
 *  - 底部 3 个状态色圆（warn/danger/ok）
 */
@Composable
private fun ThemePreviewCard(
    set: GomobColorSchemeSet,
    darkPreview: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c: GomobColors = if (darkPreview) set.dark else set.light
    val ringColor = if (selected) Gomob.colors.accent else c.line2
    val ringWidth = if (selected) 2.dp else 1.dp
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(c.bg1)
            .border(width = ringWidth, color = ringColor, shape = Gomob.shapes.r3)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 顶部行
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(c.accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                "Aa",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.fg0,
            )
            Spacer(Modifier.weight(1f))
            if (selected) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(Gomob.colors.accent),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        GomobIcons.Check,
                        contentDescription = null,
                        tint = Gomob.colors.bg0,
                        modifier = Modifier.size(11.dp),
                    )
                }
            }
        }
        // 模拟两行文本 + 分隔线
        MiniBar(width = 1.0f, height = 6.dp, color = c.fg1.copy(alpha = 0.55f))
        MiniBar(width = 0.7f, height = 4.dp, color = c.fg2.copy(alpha = 0.5f))
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.line2),
        )
        // accent 主按钮条
        Row(
            Modifier
                .fillMaxWidth()
                .height(20.dp)
                .clip(Gomob.shapes.r1)
                .background(c.accentSoft)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(width = 28.dp, height = 4.dp)
                    .clip(Gomob.shapes.r1)
                    .background(c.accent),
            )
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(width = 14.dp, height = 4.dp)
                    .clip(Gomob.shapes.r1)
                    .background(c.accentStrong),
            )
        }
        // 状态色圆 + 主题名
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.warn))
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.danger))
            Spacer(Modifier.width(4.dp))
            Box(Modifier.size(8.dp).clip(CircleShape).background(c.ok))
            Spacer(Modifier.weight(1f))
            Text(
                set.scheme.displayName,
                fontSize = 11.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) c.accent else c.fg1,
            )
        }
    }
}

@Composable
private fun MiniBar(width: Float, height: androidx.compose.ui.unit.Dp, color: Color) {
    Box(
        Modifier
            .fillMaxWidth(width)
            .height(height)
            .clip(Gomob.shapes.r1)
            .background(color),
    )
}

// ─── 实时预览卡（用全局 Gomob.colors，切主题会立刻刷新） ──────────────────────
@Composable
private fun LivePreviewCard(scheme: ColorScheme, dark: Boolean) {
    HairlineCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12)) {
            // 顶部标识：当前主题名 + 外观模式 chip
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "LIVE PREVIEW",
                        style = Gomob.type.eyebrow,
                        color = Gomob.colors.fg3,
                    )
                    Text(
                        scheme.displayName,
                        style = Gomob.type.title,
                        color = Gomob.colors.fg0,
                    )
                }
                ModeChip(if (dark) "深色" else "浅色")
            }
            // 描述
            Text(
                text = currentThemeBlurb(scheme),
                style = Gomob.type.bodySm,
                color = Gomob.colors.fg2,
            )
            // 主次按钮 + 状态 chips
            Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
                PrimaryDemoButton("主操作")
                SecondaryDemoButton("辅助")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
                StatusDemoChip("已通过", Gomob.colors.ok, Gomob.colors.okSoft)
                StatusDemoChip("预警", Gomob.colors.warn, Gomob.colors.warnSoft)
                StatusDemoChip("异常", Gomob.colors.danger, Gomob.colors.dangerSoft)
            }
            // 列表行 + 分隔线（模拟设置项）
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.bg2),
            ) {
                DemoSettingRow("VIN 智能识别", "已启用")
                Box(
                    Modifier
                        .padding(start = Gomob.spacing.s14)
                        .fillMaxWidth()
                        .height(Gomob.spacing.hairline)
                        .background(Gomob.colors.line1),
                )
                DemoSettingRow("自动上传云端", "仅 Wi-Fi")
            }
        }
    }
}

@Composable
private fun ModeChip(text: String) {
    Box(
        Modifier
            .clip(Gomob.shapes.r1)
            .background(Gomob.colors.accentSoft)
            .padding(horizontal = Gomob.spacing.s8, vertical = 3.dp),
    ) {
        Text(
            text,
            fontSize = 11.sp,
            color = Gomob.colors.accent,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.04.em,
        )
    }
}

@Composable
private fun PrimaryDemoButton(text: String) {
    Box(
        Modifier
            .height(32.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.accent)
            .padding(horizontal = Gomob.spacing.s14),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Gomob.colors.bg0,
        )
    }
}

@Composable
private fun SecondaryDemoButton(text: String) {
    Box(
        Modifier
            .height(32.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.accentSoft)
            .padding(horizontal = Gomob.spacing.s14),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = Gomob.colors.accent,
        )
    }
}

@Composable
private fun StatusDemoChip(text: String, fg: Color, bg: Color) {
    Row(
        Modifier
            .height(22.dp)
            .clip(Gomob.shapes.r1)
            .background(bg)
            .padding(horizontal = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
    ) {
        Box(Modifier.size(Gomob.spacing.dot6).clip(CircleShape).background(fg))
        Text(text, fontSize = 11.sp, color = fg)
    }
}

@Composable
private fun DemoSettingRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = Gomob.colors.fg0, modifier = Modifier.weight(1f))
        Text(value, fontSize = 12.sp, color = Gomob.colors.fg2)
        Spacer(Modifier.width(Gomob.spacing.s8))
        Icon(
            GomobIcons.ChevronRight,
            contentDescription = null,
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(14.dp),
        )
    }
}

// ─── 恢复默认按钮 ────────────────────────────────────────────────────────────
@Composable
private fun ResetDefaultButton(onClick: () -> Unit) {
    Box(
        Modifier.padding(horizontal = Gomob.spacing.s20),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.bg2)
                .clickable(onClick = onClick),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                "恢复默认（薄荷青绿 · 跟随系统）",
                fontSize = 13.sp,
                color = Gomob.colors.fg1,
            )
        }
    }
}

private fun currentThemeBlurb(scheme: ColorScheme): String = when (scheme) {
    ColorScheme.Mint -> "暖石墨底 + 薄荷青绿。现代产品感，长时间使用不刺眼。"
    ColorScheme.Gold -> "深海蓝底 + 暖锡金。专业仪表盘风，冷暖对比强。"
    ColorScheme.Frost -> "炭黑底 + 冷青苔。OLED 真黑 + 锐利冷感，工业气质。"
    ColorScheme.Lilac -> "暮霭紫灰底 + 薄紫。柔和克制，区别其它科技调。"
    ColorScheme.Coral -> "暖石底 + 珊瑚橙。活力暖色，区别冷调主流。"
}
