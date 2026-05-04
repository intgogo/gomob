package io.gomob.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/**
 * gomob 全局主题 — 强制深色科技风，不接系统 Material You。
 *
 * Why: 工作 App 视觉一致性高于"跟随系统"。检测站工位光照不稳定，
 * 让用户在浅色 / 深色随机切换会带来认知成本。
 */
@Composable
fun GomobTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Primary,
        onPrimary = SurfaceDeep,
        primaryContainer = PrimaryDim,
        onPrimaryContainer = TextPrimary,

        secondary = Accent,
        onSecondary = SurfaceDeep,
        secondaryContainer = AccentDim,
        onSecondaryContainer = TextPrimary,

        tertiary = StateInfo,
        onTertiary = SurfaceDeep,

        background = SurfaceDeep,
        onBackground = TextPrimary,

        surface = SurfaceCard,
        onSurface = TextPrimary,
        surfaceVariant = SurfaceCardHigh,
        onSurfaceVariant = TextSecondary,
        surfaceTint = Primary,

        outline = BorderSubtle,
        outlineVariant = BorderGlow,

        error = StateDanger,
        onError = TextPrimary,
    )

    MaterialTheme(
        colorScheme = scheme,
        typography = GomobTypography,
        shapes = GomobShapes,
        content = content,
    )
}
