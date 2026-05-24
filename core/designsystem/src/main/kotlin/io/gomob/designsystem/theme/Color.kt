package io.gomob.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * gomob · Color system
 *
 * 设计原则：
 * - 业务代码只读 [GomobColors] 的语义字段（surface / fg / accent / status…），不直接引用原色
 * - 提供 5 套色彩主题（Mint/Gold/Frost/Lilac/Coral），每套有 dark / light 两份 GomobColors
 * - [GomobTheme] 接受 (darkTheme, ColorScheme) 两个维度，挑出最终的 GomobColors
 *
 * 命名约定：
 *   <scheme><Dark|Light>  — 单套色板（GomobColors）
 *   colorSchemeOf(scheme) — 工厂函数，返回 [GomobColorSchemeSet]（dark + light）
 */

/** 色彩主题枚举 — 用户在「主题设置」中选择 */
enum class ColorScheme(val key: String, val displayName: String) {
    Mint("mint", "薄荷青绿"),
    Gold("gold", "深海锡金"),
    Frost("frost", "炭黑冷青"),
    Lilac("lilac", "暮霭薄紫"),
    Coral("coral", "默金珊瑚"),
    ;

    companion object {
        fun fromKey(key: String?): ColorScheme = entries.firstOrNull { it.key == key } ?: Mint
    }
}

/**
 * 语义色板。组件只读这个接口，不读原色。
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

/** 一套主题 = dark + light 两份 GomobColors */
@Immutable
data class GomobColorSchemeSet(
    val scheme: ColorScheme,
    val dark: GomobColors,
    val light: GomobColors,
)

// ════════════════════════════════════════════════════════════════════════════
// Mint — 默认主题：暖石墨 + 薄荷青绿（Linear / Vercel / GitHub Dark Dimmed 风）
// ════════════════════════════════════════════════════════════════════════════
internal val MintDark = GomobColors(
    bg0 = Color(0xFF0E1014), bg1 = Color(0xFF14171D), bg2 = Color(0xFF1B1F26), bg3 = Color(0xFF232831),
    fg0 = Color(0xF7F0F4F8), fg1 = Color(0xD4DBE0EA), fg2 = Color(0xB0B8C2D0), fg3 = Color(0x8898A0AE),
    line1 = Color(0x0FFFFFFF), line2 = Color(0x1AFFFFFF),
    lineStrong = Color(0x2EFFFFFF), hlTop = Color(0x14FFFFFF),
    accent = Color(0xFF5EEAD4), accentStrong = Color(0xFF99F6E4),
    accentSoft = Color(0x245EEAD4), accentLine = Color(0x525EEAD4),
    warn = Color(0xFFF0B95C), warnSoft = Color(0x1FF0B95C), warnLine = Color(0x52F0B95C),
    danger = Color(0xFFF87171), dangerSoft = Color(0x1FF87171), dangerLine = Color(0x52F87171),
    ok = Color(0xFF34D399), okSoft = Color(0x1F34D399), okLine = Color(0x5234D399),
    isLight = false,
)

internal val MintLight = GomobColors(
    bg0 = Color(0xFFF6F7F9), bg1 = Color(0xFFFFFFFF), bg2 = Color(0xFFFAFBFC), bg3 = Color(0xFFEEF1F4),
    fg0 = Color(0xF50B0F16), fg1 = Color(0xC72A323F), fg2 = Color(0x99535C6B), fg3 = Color(0x70808997),
    line1 = Color(0x0F0B0F16), line2 = Color(0x1A0B0F16),
    lineStrong = Color(0x290B0F16), hlTop = Color(0x14FFFFFF),
    accent = Color(0xFF0E8A75), accentStrong = Color(0xFF0A6E5C),
    accentSoft = Color(0x1F0E8A75), accentLine = Color(0x520E8A75),
    warn = Color(0xFFB17518), warnSoft = Color(0x1FB17518), warnLine = Color(0x52B17518),
    danger = Color(0xFFB04030), dangerSoft = Color(0x1FB04030), dangerLine = Color(0x52B04030),
    ok = Color(0xFF2C7A6A), okSoft = Color(0x1F2C7A6A), okLine = Color(0x522C7A6A),
    isLight = true,
)

// ════════════════════════════════════════════════════════════════════════════
// Gold — 深海蓝 + 暖锡金（专业仪表盘风，适合长时间工业场景）
// ════════════════════════════════════════════════════════════════════════════
internal val GoldDark = GomobColors(
    bg0 = Color(0xFF0B121A), bg1 = Color(0xFF12192A), bg2 = Color(0xFF1A2438), bg3 = Color(0xFF232F48),
    fg0 = Color(0xF7FAF6EE), fg1 = Color(0xD4E0D9C7), fg2 = Color(0xB0B7B0A0), fg3 = Color(0x888C8676),
    line1 = Color(0x0FFFFFFF), line2 = Color(0x1AFFFFFF),
    lineStrong = Color(0x2EFFFFFF), hlTop = Color(0x14FFFFFF),
    accent = Color(0xFFF4A361), accentStrong = Color(0xFFFFD08F),
    accentSoft = Color(0x24F4A361), accentLine = Color(0x52F4A361),
    warn = Color(0xFFFACC15), warnSoft = Color(0x1FFACC15), warnLine = Color(0x52FACC15),
    danger = Color(0xFFF87171), dangerSoft = Color(0x1FF87171), dangerLine = Color(0x52F87171),
    ok = Color(0xFF4ADE80), okSoft = Color(0x1F4ADE80), okLine = Color(0x524ADE80),
    isLight = false,
)

internal val GoldLight = GomobColors(
    bg0 = Color(0xFFF8F6F0), bg1 = Color(0xFFFFFFFF), bg2 = Color(0xFFFAF9F4), bg3 = Color(0xFFEEEBE2),
    fg0 = Color(0xF5160F02), fg1 = Color(0xC73B3219), fg2 = Color(0x99685C42), fg3 = Color(0x70958A6E),
    line1 = Color(0x0F160F02), line2 = Color(0x1A160F02),
    lineStrong = Color(0x29160F02), hlTop = Color(0x14FFFFFF),
    accent = Color(0xFFB17518), accentStrong = Color(0xFF8E5A0F),
    accentSoft = Color(0x1FB17518), accentLine = Color(0x52B17518),
    warn = Color(0xFFA16207), warnSoft = Color(0x1FA16207), warnLine = Color(0x52A16207),
    danger = Color(0xFFB04030), dangerSoft = Color(0x1FB04030), dangerLine = Color(0x52B04030),
    ok = Color(0xFF15803D), okSoft = Color(0x1F15803D), okLine = Color(0x5215803D),
    isLight = true,
)

// ════════════════════════════════════════════════════════════════════════════
// Frost — 炭黑 + 冷青苔（OLED 极致 + 锐利冷感，原 v1 风格延续）
// ════════════════════════════════════════════════════════════════════════════
internal val FrostDark = GomobColors(
    bg0 = Color(0xFF0A0B0D), bg1 = Color(0xFF131418), bg2 = Color(0xFF1C1E24), bg3 = Color(0xFF262932),
    fg0 = Color(0xF7EFF2F7), fg1 = Color(0xD4C5CBD6), fg2 = Color(0xB099A1B0), fg3 = Color(0x886E768A),
    line1 = Color(0x0FFFFFFF), line2 = Color(0x1AFFFFFF),
    lineStrong = Color(0x38FFFFFF), hlTop = Color(0x0FFFFFFF),
    accent = Color(0xFF67E8E0), accentStrong = Color(0xFFA5F3EF),
    accentSoft = Color(0x2467E8E0), accentLine = Color(0x5267E8E0),
    warn = Color(0xFFEAB35A), warnSoft = Color(0x1FEAB35A), warnLine = Color(0x52EAB35A),
    danger = Color(0xFFEF6F6F), dangerSoft = Color(0x1FEF6F6F), dangerLine = Color(0x52EF6F6F),
    ok = Color(0xFF64C5A8), okSoft = Color(0x1F64C5A8), okLine = Color(0x5264C5A8),
    isLight = false,
)

internal val FrostLight = GomobColors(
    bg0 = Color(0xFFF2F4F7), bg1 = Color(0xFFFFFFFF), bg2 = Color(0xFFF7F9FB), bg3 = Color(0xFFE8ECF1),
    fg0 = Color(0xF5060812), fg1 = Color(0xC7242935), fg2 = Color(0x99535B6B), fg3 = Color(0x707C8595),
    line1 = Color(0x0F060812), line2 = Color(0x1A060812),
    lineStrong = Color(0x33060812), hlTop = Color(0x14FFFFFF),
    accent = Color(0xFF0D7570), accentStrong = Color(0xFF095954),
    accentSoft = Color(0x1F0D7570), accentLine = Color(0x520D7570),
    warn = Color(0xFFA16207), warnSoft = Color(0x1FA16207), warnLine = Color(0x52A16207),
    danger = Color(0xFFB04030), dangerSoft = Color(0x1FB04030), dangerLine = Color(0x52B04030),
    ok = Color(0xFF15803D), okSoft = Color(0x1F15803D), okLine = Color(0x5215803D),
    isLight = true,
)

// ════════════════════════════════════════════════════════════════════════════
// Lilac — 暮霭深紫灰 + 薄紫（柔和气质，区别其它科技调）
// ════════════════════════════════════════════════════════════════════════════
internal val LilacDark = GomobColors(
    bg0 = Color(0xFF11101A), bg1 = Color(0xFF181724), bg2 = Color(0xFF20202F), bg3 = Color(0xFF29293D),
    fg0 = Color(0xF7F2EFF8), fg1 = Color(0xD4D2CCE0), fg2 = Color(0xB0A39CB8), fg3 = Color(0x887B748A),
    line1 = Color(0x0FFFFFFF), line2 = Color(0x1AFFFFFF),
    lineStrong = Color(0x2EFFFFFF), hlTop = Color(0x14FFFFFF),
    accent = Color(0xFFC4B5FD), accentStrong = Color(0xFFDDD6FE),
    accentSoft = Color(0x24C4B5FD), accentLine = Color(0x52C4B5FD),
    warn = Color(0xFFFBBF24), warnSoft = Color(0x1FFBBF24), warnLine = Color(0x52FBBF24),
    danger = Color(0xFFF87171), dangerSoft = Color(0x1FF87171), dangerLine = Color(0x52F87171),
    ok = Color(0xFF6EE7B7), okSoft = Color(0x1F6EE7B7), okLine = Color(0x526EE7B7),
    isLight = false,
)

internal val LilacLight = GomobColors(
    bg0 = Color(0xFFF7F5FA), bg1 = Color(0xFFFFFFFF), bg2 = Color(0xFFF9F7FC), bg3 = Color(0xFFECE7F4),
    fg0 = Color(0xF5100A1A), fg1 = Color(0xC72E2640), fg2 = Color(0x99554B6F), fg3 = Color(0x7082789A),
    line1 = Color(0x0F100A1A), line2 = Color(0x1A100A1A),
    lineStrong = Color(0x29100A1A), hlTop = Color(0x14FFFFFF),
    accent = Color(0xFF6D4FDA), accentStrong = Color(0xFF5238B5),
    accentSoft = Color(0x1F6D4FDA), accentLine = Color(0x526D4FDA),
    warn = Color(0xFFA16207), warnSoft = Color(0x1FA16207), warnLine = Color(0x52A16207),
    danger = Color(0xFFB04030), dangerSoft = Color(0x1FB04030), dangerLine = Color(0x52B04030),
    ok = Color(0xFF15803D), okSoft = Color(0x1F15803D), okLine = Color(0x5215803D),
    isLight = true,
)

// ════════════════════════════════════════════════════════════════════════════
// Coral — 默金暖石 + 珊瑚橙（活力暖色，区别冷调）
// ════════════════════════════════════════════════════════════════════════════
internal val CoralDark = GomobColors(
    bg0 = Color(0xFF110D0C), bg1 = Color(0xFF181311), bg2 = Color(0xFF211B18), bg3 = Color(0xFF2A221E),
    fg0 = Color(0xF7FBF5F1), fg1 = Color(0xD4DAD0C7), fg2 = Color(0xB0AA9F94), fg3 = Color(0x8880766C),
    line1 = Color(0x0FFFFFFF), line2 = Color(0x1AFFFFFF),
    lineStrong = Color(0x2EFFFFFF), hlTop = Color(0x14FFFFFF),
    accent = Color(0xFFFB923C), accentStrong = Color(0xFFFFC299),
    accentSoft = Color(0x24FB923C), accentLine = Color(0x52FB923C),
    warn = Color(0xFFFACC15), warnSoft = Color(0x1FFACC15), warnLine = Color(0x52FACC15),
    danger = Color(0xFFEF4444), dangerSoft = Color(0x1FEF4444), dangerLine = Color(0x52EF4444),
    ok = Color(0xFF4ADE80), okSoft = Color(0x1F4ADE80), okLine = Color(0x524ADE80),
    isLight = false,
)

internal val CoralLight = GomobColors(
    bg0 = Color(0xFFFCF5F1), bg1 = Color(0xFFFFFFFF), bg2 = Color(0xFFFCF8F4), bg3 = Color(0xFFF2E7DC),
    fg0 = Color(0xF5180A02), fg1 = Color(0xC73E2616), fg2 = Color(0x99704F38), fg3 = Color(0x70957C66),
    line1 = Color(0x0F180A02), line2 = Color(0x1A180A02),
    lineStrong = Color(0x29180A02), hlTop = Color(0x14FFFFFF),
    accent = Color(0xFFB45309), accentStrong = Color(0xFF92400E),
    accentSoft = Color(0x1FB45309), accentLine = Color(0x52B45309),
    warn = Color(0xFFA16207), warnSoft = Color(0x1FA16207), warnLine = Color(0x52A16207),
    danger = Color(0xFFB91C1C), dangerSoft = Color(0x1FB91C1C), dangerLine = Color(0x52B91C1C),
    ok = Color(0xFF15803D), okSoft = Color(0x1F15803D), okLine = Color(0x5215803D),
    isLight = true,
)

// ════════════════════════════════════════════════════════════════════════════
// 工厂函数
// ════════════════════════════════════════════════════════════════════════════
val MintScheme = GomobColorSchemeSet(ColorScheme.Mint, MintDark, MintLight)
val GoldScheme = GomobColorSchemeSet(ColorScheme.Gold, GoldDark, GoldLight)
val FrostScheme = GomobColorSchemeSet(ColorScheme.Frost, FrostDark, FrostLight)
val LilacScheme = GomobColorSchemeSet(ColorScheme.Lilac, LilacDark, LilacLight)
val CoralScheme = GomobColorSchemeSet(ColorScheme.Coral, CoralDark, CoralLight)

val AllColorSchemes: List<GomobColorSchemeSet> = listOf(
    MintScheme, GoldScheme, FrostScheme, LilacScheme, CoralScheme,
)

/** 根据枚举找到对应色板组 */
fun colorSchemeSetOf(scheme: ColorScheme): GomobColorSchemeSet = when (scheme) {
    ColorScheme.Mint -> MintScheme
    ColorScheme.Gold -> GoldScheme
    ColorScheme.Frost -> FrostScheme
    ColorScheme.Lilac -> LilacScheme
    ColorScheme.Coral -> CoralScheme
}

/** 根据 (scheme, darkTheme) 选最终 GomobColors */
fun gomobColorsOf(scheme: ColorScheme, darkTheme: Boolean): GomobColors =
    colorSchemeSetOf(scheme).let { if (darkTheme) it.dark else it.light }
