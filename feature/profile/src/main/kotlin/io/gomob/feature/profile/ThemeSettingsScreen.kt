package io.gomob.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.theme.AllColorSchemes
import io.gomob.designsystem.theme.ColorScheme
import io.gomob.designsystem.theme.Gomob
import io.gomob.designsystem.theme.GomobColorSchemeSet
import io.gomob.designsystem.theme.GomobColors

/**
 * 主题设置页 —— 应用固定浅色，仅选择色彩主题（5 套）。
 *
 * 关键设计：
 * - 5 套色彩主题用 2 列 grid 一屏可见，避免横滚卡片被切；
 *   原 LazyRow 方案下"默金珊瑚"几乎被切到屏外，用户根本意识不到它存在。
 * - 卡片内部是 mini UI 示意：Aa 字号 + 两行文本 bar + accent 主按钮条 +
 *   3 个状态色点，直观看出每套主题的对比度 / 暖冷感 / 重音色性格；
 *   选中态 = 1.5dp accent 描边 + 右上角 ✓ 圆角标。
 */
@Composable
fun ThemeSettingsRoute(
    onBack: () -> Unit,
    vm: AppearanceViewModel = hiltViewModel(),
) {
    val scheme by vm.colorScheme.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()
    GlassHeaderScaffold(
        scrollState = scrollState,
        header = { BackHeader(title = "主题设置", onBack = onBack, eyebrow = "设置") },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                // padding 挂在 verticalScroll 之后 → 属于内容, 随内容滚动
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + Gomob.spacing.s24,
                ),
        ) {
            SectionTitle("浅色主题")
            SchemeGrid(
                selected = scheme,
                onPick = vm::setColorScheme,
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = Gomob.type.sectionTitle,
        color = Gomob.colors.fg1,
        modifier = Modifier.padding(
            start = Gomob.spacing.pageGutter,
            top = Gomob.spacing.s12,
            bottom = Gomob.spacing.s8,
        ),
    )
}

// ─── 色板 grid（2 列） ───────────────────────────────────────────────────────
@Composable
private fun SchemeGrid(
    selected: ColorScheme,
    onPick: (ColorScheme) -> Unit,
) {
    val rows = AllColorSchemes.chunked(2)
    Column(
        Modifier.padding(horizontal = Gomob.spacing.pageGutter),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12)) {
                row.forEach { set ->
                    Box(Modifier.weight(1f)) {
                        ThemePreviewCard(
                            set = set,
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
 * 主题预览卡 —— 内部完全用本套主题的浅色色板渲染（不串到全局 Gomob.colors）。
 *
 * 卡片内是一个 mini UI sample：
 *  - 顶部 accent 圆 + "Aa" 字号示意
 *  - 中部两行文本 bar 示意（fg0 深浅两档）
 *  - accent 主按钮条（10dp, 内含比例双条）
 *  - 底部 3 个 5dp 状态色点 + 主题名
 *  - 选中态: 1.5dp accent 描边 + 右上角绝对定位 16dp ✓ 圆角标
 */
@Composable
private fun ThemePreviewCard(
    set: GomobColorSchemeSet,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c: GomobColors = set.colors
    Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r3)
                .background(c.bg1)
                .border(
                    width = if (selected) 1.5.dp else 1.dp,
                    color = if (selected) Gomob.colors.accent else c.line1,
                    shape = Gomob.shapes.r3,
                )
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
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.fg0,
                )
            }
            // 模拟两行文本
            MiniBar(width = 1.0f, height = 5.dp, color = c.fg0.copy(alpha = 0.25f))
            MiniBar(width = 0.7f, height = 5.dp, color = c.fg0.copy(alpha = 0.12f))
            // accent 主按钮条
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .clip(Gomob.shapes.r1)
                    .background(c.accentSoft)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .weight(0.32f)
                        .height(4.dp)
                        .clip(Gomob.shapes.r1)
                        .background(c.accent),
                )
                Spacer(Modifier.weight(0.56f))
                Box(
                    Modifier
                        .weight(0.12f)
                        .height(4.dp)
                        .clip(Gomob.shapes.r1)
                        .background(c.accentStrong),
                )
            }
            // 状态色点 + 主题名
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(c.warn))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(5.dp).clip(CircleShape).background(c.danger))
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(5.dp).clip(CircleShape).background(c.ok))
                Spacer(Modifier.weight(1f))
                Text(
                    set.scheme.displayName,
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) c.accent else c.fg1,
                )
            }
        }
        // 选中 ✓ 角标: 绝对定位在卡片右上角内缩 10dp 处
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = (-10).dp, y = 10.dp)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(Gomob.colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "✓",
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                )
            }
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
