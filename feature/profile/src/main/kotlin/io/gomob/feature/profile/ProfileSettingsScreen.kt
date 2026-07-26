package io.gomob.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.glass.glassPanelBg
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置 — 独立子页(原「我的」右滑抽屉平移过来, 抽屉已删)。
 *
 * 一张拟玻璃卡 5 行设置项(账号与安全 / 切换主题 / 清理缓存 / 通知设置 / 关于)
 * + 下方独立拟玻璃卡「⇄ 切换账号」。行为全部沿用原抽屉的真实逻辑。
 */
@Composable
fun ProfileSettingsRoute(
    onBack: () -> Unit,
    onOpenAccount: () -> Unit = {},
    onOpenNotification: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    onOpenTheme: () -> Unit = {},
    vm: ProfileViewModel = hiltViewModel(),
    appearance: AppearanceViewModel = hiltViewModel(),
) {
    val colorScheme by appearance.colorScheme.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val cacheRoot = remember(context) { context.cacheDir }
    var cacheSize by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(cacheRoot) {
        cacheSize = withContext(Dispatchers.IO) { dirSize(cacheRoot) }
    }

    val items = listOf(
        SettingEntry(GomobIcons.Lock, "账号与安全", onClick = onOpenAccount),
        SettingEntry(
            GomobIcons.Moon,
            "切换主题",
            value = "浅色 · ${colorScheme.displayName}",
            onClick = onOpenTheme,
        ),
        SettingEntry(
            GomobIcons.Cache,
            "清理缓存",
            value = formatCacheSize(cacheSize),
            mono = true,
            onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { clearDir(cacheRoot) }
                    cacheSize = withContext(Dispatchers.IO) { dirSize(cacheRoot) }
                }
            },
        ),
        SettingEntry(GomobIcons.Bell, "通知设置", onClick = onOpenNotification),
        SettingEntry(GomobIcons.Info, "关于", value = "v0.1.0", mono = true, onClick = onOpenAbout),
    )

    val scrollState = rememberScrollState()
    GlassHeaderScaffold(
        scrollState = scrollState,
        header = { BackHeader(title = "设置", onBack = onBack, eyebrow = "我的") },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(
                    top = padding.calculateTopPadding() + Gomob.spacing.s8,
                    bottom = padding.calculateBottomPadding() + Gomob.spacing.s24,
                )
                .padding(horizontal = Gomob.spacing.pageGutter),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.cardGap),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .glassPanelBg(shape = Gomob.shapes.r3),
            ) {
                items.forEachIndexed { i, item ->
                    SettingsRow(item)
                    if (i != items.lastIndex) SettingsRowDivider()
                }
            }
            SwitchAccountCard(onClick = vm::logout)
        }
    }
}

private data class SettingEntry(
    val icon: ImageVector,
    val label: String,
    val value: String? = null,
    val mono: Boolean = false,
    val onClick: () -> Unit,
)

@Composable
private fun SettingsRow(item: SettingEntry) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Gomob.spacing.rowSetting)
            .clickable(onClick = item.onClick)
            .padding(horizontal = Gomob.spacing.s14),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.fg0.copy(alpha = 0.04f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                item.icon,
                contentDescription = null,
                tint = Gomob.colors.fg1,
                modifier = Modifier.size(Gomob.spacing.icon16),
            )
        }
        Text(
            item.label,
            fontSize = 14.sp,
            color = Gomob.colors.fg0,
            modifier = Modifier.weight(1f),
        )
        if (item.value != null) {
            Text(
                item.value,
                style = if (item.mono) Gomob.type.numInline.copy(fontSize = 12.sp) else Gomob.type.caption,
                color = Gomob.colors.fg3,
            )
        }
        Text("›", fontSize = 15.sp, color = Gomob.colors.fg3)
    }
}

@Composable
private fun SettingsRowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 58.dp)
            .height(Gomob.spacing.hairline)
            .background(Gomob.colors.line1),
    )
}

@Composable
private fun SwitchAccountCard(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .glassPanelBg(shape = Gomob.shapes.r3)
            .clickable(onClick = onClick),
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
        Text("切换账号", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg1)
    }
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
