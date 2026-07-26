package io.gomob.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * gomob · Theme entry point
 *
 * 用法：
 * ```
 * setContent {
 *   GomobTheme {                                                // 固定浅色 + 默认 Mint
 *     // …
 *   }
 * }
 * ```
 * 显式指定：`GomobTheme(colorScheme = ColorScheme.Gold) { … }`
 *
 * 设计原则：
 * - 全局只暴露 [Gomob] 一个对象，组件用 `Gomob.colors.bg1` / `Gomob.type.body` 取值
 * - 应用固定浅色，用户只切换 [ColorScheme] 色彩主题
 * - Material3 仍然挂着 — 三方组件（BottomSheetScaffold / DatePicker…）落色不会瞎
 *   它的 ColorScheme 是从我们的语义 token 派生的，不是另一套真相
 */

private val LocalColors = staticCompositionLocalOf<GomobColors> { error("GomobTheme not applied: missing colors") }
private val LocalType = staticCompositionLocalOf<GomobType> { error("GomobTheme not applied: missing type") }
private val LocalShapes = staticCompositionLocalOf<GomobShapes> { error("GomobTheme not applied: missing shapes") }
private val LocalSpacing = staticCompositionLocalOf<GomobSpacing> { error("GomobTheme not applied: missing spacing") }

object Gomob {
    val colors: GomobColors @Composable @ReadOnlyComposable get() = LocalColors.current
    val type: GomobType @Composable @ReadOnlyComposable get() = LocalType.current
    val shapes: GomobShapes @Composable @ReadOnlyComposable get() = LocalShapes.current
    val spacing: GomobSpacing @Composable @ReadOnlyComposable get() = LocalSpacing.current
}

@Composable
fun GomobTheme(
    colorScheme: ColorScheme = ColorScheme.Mint,
    content: @Composable () -> Unit,
) {
    val colors = colorSchemeSetOf(colorScheme).colors

    val m3Scheme = lightColorScheme(
        background = colors.bg0, onBackground = colors.fg0,
        surface = colors.bg1, onSurface = colors.fg0,
        surfaceVariant = colors.bg2, onSurfaceVariant = colors.fg1,
        surfaceTint = colors.accent,
        surfaceContainerLowest = colors.bg0,
        surfaceContainerLow = colors.bg1,
        surfaceContainer = colors.bg2,
        surfaceContainerHigh = colors.bg3,
        surfaceContainerHighest = colors.bg3,
        primary = colors.accent, onPrimary = colors.bg1,
        primaryContainer = colors.accentSoft, onPrimaryContainer = colors.accent,
        secondary = colors.accentStrong, onSecondary = colors.bg1,
        tertiary = colors.ok, onTertiary = colors.bg1,
        error = colors.danger, onError = colors.bg1,
        errorContainer = colors.dangerSoft, onErrorContainer = colors.danger,
        outline = colors.lineStrong, outlineVariant = colors.line2,
        scrim = colors.bg0,
    )

    CompositionLocalProvider(
        LocalColors provides colors,
        LocalType provides DefaultType,
        LocalShapes provides DefaultShapes,
        LocalSpacing provides DefaultSpacing,
    ) {
        MaterialTheme(colorScheme = m3Scheme, content = content)
    }
}
