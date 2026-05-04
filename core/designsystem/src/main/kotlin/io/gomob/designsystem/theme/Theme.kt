package io.gomob.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
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
 *   GomobTheme {                                // 跟随系统
 *     // …
 *   }
 * }
 * ```
 * 强制单一主题：`GomobTheme(darkTheme = true) { … }`
 *
 * 设计原则：
 * - 全局只暴露 [Gomob] 一个对象，组件用 `Gomob.colors.bg1` / `Gomob.type.body` 取值
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
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors

    val m3Scheme = if (darkTheme) {
        darkColorScheme(
            background = colors.bg0, onBackground = colors.fg0,
            surface = colors.bg1, onSurface = colors.fg0,
            surfaceVariant = colors.bg2, onSurfaceVariant = colors.fg1,
            primary = colors.accent, onPrimary = colors.bg0,
            secondary = colors.accentStrong, onSecondary = colors.bg0,
            error = colors.danger, onError = colors.bg0,
            outline = colors.lineStrong, outlineVariant = colors.line2,
        )
    } else {
        lightColorScheme(
            background = colors.bg0, onBackground = colors.fg0,
            surface = colors.bg1, onSurface = colors.fg0,
            surfaceVariant = colors.bg2, onSurfaceVariant = colors.fg1,
            primary = colors.accent, onPrimary = colors.bg1,
            secondary = colors.accentStrong, onSecondary = colors.bg1,
            error = colors.danger, onError = colors.bg1,
            outline = colors.lineStrong, outlineVariant = colors.line2,
        )
    }

    CompositionLocalProvider(
        LocalColors provides colors,
        LocalType provides DefaultType,
        LocalShapes provides DefaultShapes,
        LocalSpacing provides DefaultSpacing,
    ) {
        MaterialTheme(colorScheme = m3Scheme, content = content)
    }
}
