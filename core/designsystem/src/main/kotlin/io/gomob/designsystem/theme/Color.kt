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
 * 来源：jsx tokens.css（HTML 原型权威）。OKLCH 已转 sRGB。
 * 设计语言：精炼、克制、仪表盘式。OLED 近黑底色 + 单一冷青蓝主色 + 等宽数字。
 */
internal object Palette {

    // ─── Dark surfaces — near-black with faint cool cast ────────────────────
    // 严格对齐 jsx tokens.css 的 --bg-0/1/2/3。OLED 真黑 → 深石板的渐进。
    val Ink0 = Color(0xFF07080B)   // page         (jsx --bg-0)
    val Ink1 = Color(0xFF0C0E13)   // panel        (jsx --bg-1)
    val Ink2 = Color(0xFF11141A)   // raised       (jsx --bg-2)
    val Ink3 = Color(0xFF161A22)   // input/hover  (jsx --bg-3)

    // ─── Light surfaces (paper, cool-neutral) ───────────────────────────────
    // jsx 只给 dark；浅色按同色系反向推（保留现仓自补）
    val Paper0 = Color(0xFFF6F7F9)
    val Paper1 = Color(0xFFFFFFFF)
    val Paper2 = Color(0xFFFAFBFC)
    val Paper3 = Color(0xFFEEF1F4)

    // ─── Foreground on dark — jsx --fg-0/1/2/3 (rgba) ───────────────────────
    val FgOnDark0 = Color(0xF5ECF0F8)   // 96%
    val FgOnDark1 = Color(0xC7D6DCE8)   // 78%
    val FgOnDark2 = Color(0x99AAB4C4)   // 60%
    val FgOnDark3 = Color(0x708C98AA)   // 44%

    // ─── Foreground on light ────────────────────────────────────────────────
    val FgOnLight0 = Color(0xF50B0F16)
    val FgOnLight1 = Color(0xC72A323F)
    val FgOnLight2 = Color(0x99535C6B)
    val FgOnLight3 = Color(0x70808997)

    // ─── Hairlines — jsx --line-1/2/strong (alpha 6 / 10 / 16%) ─────────────
    val LineOnDark1 = Color(0x0FFFFFFF)         // 6%
    val LineOnDark2 = Color(0x1AFFFFFF)         // 10%
    val LineOnDarkStrong = Color(0x29FFFFFF)    // 16%
    val HlTopDark = Color(0x0AFFFFFF)           // 4% 顶部内高光 (jsx --hl-top)

    val LineOnLight1 = Color(0x0F0B0F16)
    val LineOnLight2 = Color(0x1A0B0F16)
    val LineOnLightStrong = Color(0x290B0F16)
    val HlTopLight = Color(0x14FFFFFF)

    // ─── Brand accent — oklch(0.78 0.10 220) → sRGB ─────────────────────────
    val AccentDark = Color(0xFF65C6E4)              // jsx --acc
    val AccentDarkStrong = Color(0xFF56E5FF)        // jsx --acc-strong = oklch(0.86 0.13 218)
    val AccentDarkSoft = Color(0x2465C6E4)          // 14%
    val AccentDarkLine = Color(0x5265C6E4)          // 32%

    val AccentLight = Color(0xFF1F7FAF)
    val AccentLightStrong = Color(0xFF155F86)
    val AccentLightSoft = Color(0x1F1F7FAF)
    val AccentLightLine = Color(0x521F7FAF)

    // ─── Status — jsx OKLCH 转 sRGB；soft 12%、line 32%（与 acc 对齐） ─────
    val WarnDark = Color(0xFFF2B95A); val WarnDarkSoft = Color(0x1FF2B95A); val WarnDarkLine = Color(0x52F2B95A)
    val DangerDark = Color(0xFFF97676); val DangerDarkSoft = Color(0x1FF97676); val DangerDarkLine = Color(0x52F97676)
    val OkDark = Color(0xFF6BCCB4); val OkDarkSoft = Color(0x1F6BCCB4); val OkDarkLine = Color(0x526BCCB4)

    val WarnLight = Color(0xFFB17518); val WarnLightSoft = Color(0x1FB17518); val WarnLightLine = Color(0x52B17518)
    val DangerLight = Color(0xFFB04030); val DangerLightSoft = Color(0x1FB04030); val DangerLightLine = Color(0x52B04030)
    val OkLight = Color(0xFF2C7A6A); val OkLightSoft = Color(0x1F2C7A6A); val OkLightLine = Color(0x522C7A6A)
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
    val warn: Color, val warnSoft: Color, val warnLine: Color,
    val danger: Color, val dangerSoft: Color, val dangerLine: Color,
    val ok: Color, val okSoft: Color, val okLine: Color,
    val isLight: Boolean,
)

internal val DarkColors = GomobColors(
    bg0 = Palette.Ink0, bg1 = Palette.Ink1, bg2 = Palette.Ink2, bg3 = Palette.Ink3,
    fg0 = Palette.FgOnDark0, fg1 = Palette.FgOnDark1, fg2 = Palette.FgOnDark2, fg3 = Palette.FgOnDark3,
    line1 = Palette.LineOnDark1, line2 = Palette.LineOnDark2,
    lineStrong = Palette.LineOnDarkStrong, hlTop = Palette.HlTopDark,
    accent = Palette.AccentDark, accentStrong = Palette.AccentDarkStrong,
    accentSoft = Palette.AccentDarkSoft, accentLine = Palette.AccentDarkLine,
    warn = Palette.WarnDark, warnSoft = Palette.WarnDarkSoft, warnLine = Palette.WarnDarkLine,
    danger = Palette.DangerDark, dangerSoft = Palette.DangerDarkSoft, dangerLine = Palette.DangerDarkLine,
    ok = Palette.OkDark, okSoft = Palette.OkDarkSoft, okLine = Palette.OkDarkLine,
    isLight = false,
)

internal val LightColors = GomobColors(
    bg0 = Palette.Paper0, bg1 = Palette.Paper1, bg2 = Palette.Paper2, bg3 = Palette.Paper3,
    fg0 = Palette.FgOnLight0, fg1 = Palette.FgOnLight1, fg2 = Palette.FgOnLight2, fg3 = Palette.FgOnLight3,
    line1 = Palette.LineOnLight1, line2 = Palette.LineOnLight2,
    lineStrong = Palette.LineOnLightStrong, hlTop = Palette.HlTopLight,
    accent = Palette.AccentLight, accentStrong = Palette.AccentLightStrong,
    accentSoft = Palette.AccentLightSoft, accentLine = Palette.AccentLightLine,
    warn = Palette.WarnLight, warnSoft = Palette.WarnLightSoft, warnLine = Palette.WarnLightLine,
    danger = Palette.DangerLight, dangerSoft = Palette.DangerLightSoft, dangerLine = Palette.DangerLightLine,
    ok = Palette.OkLight, okSoft = Palette.OkLightSoft, okLine = Palette.OkLightLine,
    isLight = true,
)
