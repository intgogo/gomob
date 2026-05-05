package io.gomob.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * gomob · Raw color palette
 *
 * 这里只放"原色" — 业务代码不要直接引用。
 * 业务代码只读 [GomobColors] 的语义字段（surface / fg / accent / status…），
 * 由 [GomobTheme] 在 light/dark 时切换具体值。
 *
 * 来源：mob3d 设计交付包（工业仪表风），与 web 端 tokens.css 一一对应。
 */
internal object Palette {

    // ─── Dark surfaces (deep slate with cool cast) ──────────────────────────
    // 不走 OLED 全黑 — 工业仪表盘的"金属/夜场"感来自深石板蓝灰,而非纯黑。
    // 整套从 #07-#16 抬升到 #11-#26,让卡片在 page 上有明显的层次。
    val Ink0 = Color(0xFF11141B)   // page
    val Ink1 = Color(0xFF181C25)   // panel
    val Ink2 = Color(0xFF1F242E)   // raised
    val Ink3 = Color(0xFF262C38)   // input

    // ─── Light surfaces (paper, cool-neutral) ───────────────────────────────
    val Paper0 = Color(0xFFF6F7F9)
    val Paper1 = Color(0xFFFFFFFF)
    val Paper2 = Color(0xFFFAFBFC)
    val Paper3 = Color(0xFFEEF1F4)

    // ─── Foreground on dark ─────────────────────────────────────────────────
    val FgOnDark0 = Color(0xF5ECF0F8)
    val FgOnDark1 = Color(0xC7D6DCE8)
    val FgOnDark2 = Color(0x99AAB4C4)
    val FgOnDark3 = Color(0x708C98AA)

    // ─── Foreground on light ────────────────────────────────────────────────
    val FgOnLight0 = Color(0xF50B0F16)
    val FgOnLight1 = Color(0xC72A323F)
    val FgOnLight2 = Color(0x99535C6B)
    val FgOnLight3 = Color(0x70808997)

    // ─── Hairlines (alpha over surface) ─────────────────────────────────────
    // page 抬升后,line1/2 同步抬一档,卡片间分隔不再糊
    val LineOnDark1 = Color(0x14FFFFFF)
    val LineOnDark2 = Color(0x24FFFFFF)
    val LineOnDarkStrong = Color(0x33FFFFFF)
    val HlTopDark = Color(0x14FFFFFF)

    val LineOnLight1 = Color(0x0F0B0F16)
    val LineOnLight2 = Color(0x1A0B0F16)
    val LineOnLightStrong = Color(0x290B0F16)
    val HlTopLight = Color(0x14FFFFFF)

    // ─── Brand accent (oklch h=220, single hue both themes) ─────────────────
    val AccentDark = Color(0xFF7FC6E5)
    val AccentDarkStrong = Color(0xFFA6DDF2)
    val AccentDarkSoft = Color(0x247FC6E5)
    val AccentDarkLine = Color(0x527FC6E5)

    val AccentLight = Color(0xFF1F7FAF)
    val AccentLightStrong = Color(0xFF155F86)
    val AccentLightSoft = Color(0x1F1F7FAF)
    val AccentLightLine = Color(0x521F7FAF)

    // ─── Status (hue-shifted siblings of accent) ────────────────────────────
    val WarnDark = Color(0xFFE7B25E); val WarnDarkSoft = Color(0x1FE7B25E)
    val DangerDark = Color(0xFFE07560); val DangerDarkSoft = Color(0x1FE07560)
    val OkDark = Color(0xFF7CC9B8); val OkDarkSoft = Color(0x1F7CC9B8)

    val WarnLight = Color(0xFFB17518); val WarnLightSoft = Color(0x1FB17518)
    val DangerLight = Color(0xFFB04030); val DangerLightSoft = Color(0x1FB04030)
    val OkLight = Color(0xFF2C7A6A); val OkLightSoft = Color(0x1F2C7A6A)
}

/**
 * 语义色板。组件只读这个接口，不读 [Palette]。
 *
 * 字段命名和 web tokens.css 对齐。
 */
@Immutable
data class GomobColors(
    val bg0: Color, val bg1: Color, val bg2: Color, val bg3: Color,
    val fg0: Color, val fg1: Color, val fg2: Color, val fg3: Color,
    val line1: Color, val line2: Color, val lineStrong: Color, val hlTop: Color,
    val accent: Color, val accentStrong: Color, val accentSoft: Color, val accentLine: Color,
    val warn: Color, val warnSoft: Color,
    val danger: Color, val dangerSoft: Color,
    val ok: Color, val okSoft: Color,
    val isLight: Boolean,
)

internal val DarkColors = GomobColors(
    bg0 = Palette.Ink0, bg1 = Palette.Ink1, bg2 = Palette.Ink2, bg3 = Palette.Ink3,
    fg0 = Palette.FgOnDark0, fg1 = Palette.FgOnDark1, fg2 = Palette.FgOnDark2, fg3 = Palette.FgOnDark3,
    line1 = Palette.LineOnDark1, line2 = Palette.LineOnDark2,
    lineStrong = Palette.LineOnDarkStrong, hlTop = Palette.HlTopDark,
    accent = Palette.AccentDark, accentStrong = Palette.AccentDarkStrong,
    accentSoft = Palette.AccentDarkSoft, accentLine = Palette.AccentDarkLine,
    warn = Palette.WarnDark, warnSoft = Palette.WarnDarkSoft,
    danger = Palette.DangerDark, dangerSoft = Palette.DangerDarkSoft,
    ok = Palette.OkDark, okSoft = Palette.OkDarkSoft,
    isLight = false,
)

internal val LightColors = GomobColors(
    bg0 = Palette.Paper0, bg1 = Palette.Paper1, bg2 = Palette.Paper2, bg3 = Palette.Paper3,
    fg0 = Palette.FgOnLight0, fg1 = Palette.FgOnLight1, fg2 = Palette.FgOnLight2, fg3 = Palette.FgOnLight3,
    line1 = Palette.LineOnLight1, line2 = Palette.LineOnLight2,
    lineStrong = Palette.LineOnLightStrong, hlTop = Palette.HlTopLight,
    accent = Palette.AccentLight, accentStrong = Palette.AccentLightStrong,
    accentSoft = Palette.AccentLightSoft, accentLine = Palette.AccentLightLine,
    warn = Palette.WarnLight, warnSoft = Palette.WarnLightSoft,
    danger = Palette.DangerLight, dangerSoft = Palette.DangerLightSoft,
    ok = Palette.OkLight, okSoft = Palette.OkLightSoft,
    isLight = true,
)
