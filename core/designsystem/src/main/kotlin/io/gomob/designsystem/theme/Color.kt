package io.gomob.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * gomob · Color system
 *
 * 设计原则：
 * - 业务代码只读 [GomobColors] 的语义字段（surface / fg / accent / status…），不直接引用原色
 * - 提供 5 套浅色色彩主题（Mint/Gold/Frost/Lilac/Coral）
 * - [GomobTheme] 只接受 [ColorScheme]，不再暴露深浅模式
 *
 * 命名约定：
 *   <scheme>Colors          — 单套浅色色板（GomobColors）
 *   colorSchemeSetOf(scheme) — 工厂函数，返回 [GomobColorSchemeSet]
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
)

/** 一套浅色主题。 */
@Immutable
data class GomobColorSchemeSet(
    val scheme: ColorScheme,
    val colors: GomobColors,
)

// ════════════════════════════════════════════════════════════════════════════
// Mint — 默认主题：雾白 + 薄荷青绿
// ════════════════════════════════════════════════════════════════════════════
internal val MintColors = GomobColors(
    bg0 = Color(0xFFF6F7F9), bg1 = Color(0xFFFFFFFF), bg2 = Color(0xFFFAFBFC), bg3 = Color(0xFFEEF1F4),
    fg0 = Color(0xF50B0F16), fg1 = Color(0xC72A323F), fg2 = Color(0xB3535C6B), fg3 = Color(0x8A808997),
    line1 = Color(0x0F0B0F16), line2 = Color(0x1A0B0F16),
    lineStrong = Color(0x290B0F16), hlTop = Color(0x14FFFFFF),
    accent = Color(0xFF0E8A75), accentStrong = Color(0xFF0A6E5C),
    accentSoft = Color(0x1F0E8A75), accentLine = Color(0x520E8A75),
    warn = Color(0xFFB17518), warnSoft = Color(0x1FB17518), warnLine = Color(0x52B17518),
    danger = Color(0xFFB04030), dangerSoft = Color(0x1FB04030), dangerLine = Color(0x52B04030),
    ok = Color(0xFF2C7A6A), okSoft = Color(0x1F2C7A6A), okLine = Color(0x522C7A6A),
)

// ════════════════════════════════════════════════════════════════════════════
// Gold — 深海蓝 + 暖锡金（专业仪表盘风，适合长时间工业场景）
// ════════════════════════════════════════════════════════════════════════════
internal val GoldColors = GomobColors(
    bg0 = Color(0xFFF8F6F0), bg1 = Color(0xFFFFFFFF), bg2 = Color(0xFFFAF9F4), bg3 = Color(0xFFEEEBE2),
    fg0 = Color(0xF5160F02), fg1 = Color(0xC73B3219), fg2 = Color(0xB3685C42), fg3 = Color(0x8A958A6E),
    line1 = Color(0x0F160F02), line2 = Color(0x1A160F02),
    lineStrong = Color(0x29160F02), hlTop = Color(0x14FFFFFF),
    accent = Color(0xFFB17518), accentStrong = Color(0xFF8E5A0F),
    accentSoft = Color(0x1FB17518), accentLine = Color(0x52B17518),
    warn = Color(0xFFA16207), warnSoft = Color(0x1FA16207), warnLine = Color(0x52A16207),
    danger = Color(0xFFB04030), dangerSoft = Color(0x1FB04030), dangerLine = Color(0x52B04030),
    ok = Color(0xFF15803D), okSoft = Color(0x1F15803D), okLine = Color(0x5215803D),
)

// ════════════════════════════════════════════════════════════════════════════
// Frost — 炭黑 + 冷青苔（OLED 极致 + 锐利冷感，原 v1 风格延续）
// ════════════════════════════════════════════════════════════════════════════
internal val FrostColors = GomobColors(
    bg0 = Color(0xFFF2F4F7), bg1 = Color(0xFFFFFFFF), bg2 = Color(0xFFF7F9FB), bg3 = Color(0xFFE8ECF1),
    fg0 = Color(0xF5060812), fg1 = Color(0xC7242935), fg2 = Color(0xB3535B6B), fg3 = Color(0x8A7C8595),
    line1 = Color(0x0F060812), line2 = Color(0x1A060812),
    lineStrong = Color(0x33060812), hlTop = Color(0x14FFFFFF),
    accent = Color(0xFF0D7570), accentStrong = Color(0xFF095954),
    accentSoft = Color(0x1F0D7570), accentLine = Color(0x520D7570),
    warn = Color(0xFFA16207), warnSoft = Color(0x1FA16207), warnLine = Color(0x52A16207),
    danger = Color(0xFFB04030), dangerSoft = Color(0x1FB04030), dangerLine = Color(0x52B04030),
    ok = Color(0xFF15803D), okSoft = Color(0x1F15803D), okLine = Color(0x5215803D),
)

// ════════════════════════════════════════════════════════════════════════════
// Lilac — 暮霭深紫灰 + 薄紫（柔和气质，区别其它科技调）
// ════════════════════════════════════════════════════════════════════════════
internal val LilacColors = GomobColors(
    bg0 = Color(0xFFF7F5FA), bg1 = Color(0xFFFFFFFF), bg2 = Color(0xFFF9F7FC), bg3 = Color(0xFFECE7F4),
    fg0 = Color(0xF5100A1A), fg1 = Color(0xC72E2640), fg2 = Color(0xB3554B6F), fg3 = Color(0x8A82789A),
    line1 = Color(0x0F100A1A), line2 = Color(0x1A100A1A),
    lineStrong = Color(0x29100A1A), hlTop = Color(0x14FFFFFF),
    accent = Color(0xFF6D4FDA), accentStrong = Color(0xFF5238B5),
    accentSoft = Color(0x1F6D4FDA), accentLine = Color(0x526D4FDA),
    warn = Color(0xFFA16207), warnSoft = Color(0x1FA16207), warnLine = Color(0x52A16207),
    danger = Color(0xFFB04030), dangerSoft = Color(0x1FB04030), dangerLine = Color(0x52B04030),
    ok = Color(0xFF15803D), okSoft = Color(0x1F15803D), okLine = Color(0x5215803D),
)

// ════════════════════════════════════════════════════════════════════════════
// Coral — 默金暖石 + 珊瑚橙（活力暖色，区别冷调）
// ════════════════════════════════════════════════════════════════════════════
internal val CoralColors = GomobColors(
    bg0 = Color(0xFFFCF5F1), bg1 = Color(0xFFFFFFFF), bg2 = Color(0xFFFCF8F4), bg3 = Color(0xFFF2E7DC),
    fg0 = Color(0xF5180A02), fg1 = Color(0xC73E2616), fg2 = Color(0xB3704F38), fg3 = Color(0x8A957C66),
    line1 = Color(0x0F180A02), line2 = Color(0x1A180A02),
    lineStrong = Color(0x29180A02), hlTop = Color(0x14FFFFFF),
    accent = Color(0xFFB45309), accentStrong = Color(0xFF92400E),
    accentSoft = Color(0x1FB45309), accentLine = Color(0x52B45309),
    warn = Color(0xFFA16207), warnSoft = Color(0x1FA16207), warnLine = Color(0x52A16207),
    danger = Color(0xFFB91C1C), dangerSoft = Color(0x1FB91C1C), dangerLine = Color(0x52B91C1C),
    ok = Color(0xFF15803D), okSoft = Color(0x1F15803D), okLine = Color(0x5215803D),
)

// ════════════════════════════════════════════════════════════════════════════
// 工厂函数
// ════════════════════════════════════════════════════════════════════════════
val MintScheme = GomobColorSchemeSet(ColorScheme.Mint, MintColors)
val GoldScheme = GomobColorSchemeSet(ColorScheme.Gold, GoldColors)
val FrostScheme = GomobColorSchemeSet(ColorScheme.Frost, FrostColors)
val LilacScheme = GomobColorSchemeSet(ColorScheme.Lilac, LilacColors)
val CoralScheme = GomobColorSchemeSet(ColorScheme.Coral, CoralColors)

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
