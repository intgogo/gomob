package io.gomob.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * gomob · Type tokens
 *
 * 中文系统字体（PingFang / Noto Sans CJK / 思源），等宽 fallback 用平台 monospace。
 * 嵌入自带字体：把 res/font/ 资源换进下面两个 FontFamily。
 *
 * 数字类指标（金额/计数/KPI）必须用 [metricLg] / [metricMd] / [numInline] —
 * 它们走 mono + tabular，避免数字宽度抖动。
 */
private val Sans: FontFamily = FontFamily.Default
private val Mono: FontFamily = FontFamily.Monospace

@Immutable
data class GomobType(
    val display: TextStyle,
    val heroTitle: TextStyle,
    val screenTitle: TextStyle,
    val sectionTitle: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodySm: TextStyle,
    val caption: TextStyle,
    val micro: TextStyle,
    val eyebrow: TextStyle,
    val metricLg: TextStyle,
    val metricMd: TextStyle,
    val numInline: TextStyle,
)

internal val DefaultType = GomobType(
    display = TextStyle(
        fontFamily = Sans, fontSize = 22.sp, fontWeight = FontWeight.Medium,
        lineHeight = 26.4.sp, letterSpacing = 0.em,
    ),
    heroTitle = TextStyle(
        fontFamily = Sans, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        lineHeight = 27.sp, letterSpacing = 0.em,
    ),
    screenTitle = TextStyle(
        fontFamily = Sans, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
        lineHeight = 24.sp, letterSpacing = 0.em,
    ),
    sectionTitle = TextStyle(
        fontFamily = Sans, fontSize = 13.sp, fontWeight = FontWeight.Medium,
        lineHeight = 18.sp, letterSpacing = 0.em,
    ),
    title = TextStyle(
        fontFamily = Sans, fontSize = 17.sp, fontWeight = FontWeight.Medium,
        lineHeight = 22.sp,
    ),
    body = TextStyle(
        fontFamily = Sans, fontSize = 15.sp, fontWeight = FontWeight.Normal,
        lineHeight = 21.sp,
    ),
    bodySm = TextStyle(
        fontFamily = Sans, fontSize = 14.sp, fontWeight = FontWeight.Normal,
        lineHeight = 20.sp,
    ),
    caption = TextStyle(
        fontFamily = Sans, fontSize = 12.sp, fontWeight = FontWeight.Normal,
        lineHeight = 16.sp,
    ),
    micro = TextStyle(
        fontFamily = Sans, fontSize = 11.sp, fontWeight = FontWeight.Normal,
        lineHeight = 14.sp,
    ),
    eyebrow = TextStyle(
        fontFamily = Mono, fontSize = 11.sp, fontWeight = FontWeight.Normal,
        lineHeight = 14.sp,
        letterSpacing = 0.em,
    ),
    metricLg = TextStyle(
        fontFamily = Mono, fontSize = 34.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.em,
    ),
    metricMd = TextStyle(
        fontFamily = Mono, fontSize = 20.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.em,
    ),
    numInline = TextStyle(
        fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Normal,
        letterSpacing = 0.em,
    ),
)
