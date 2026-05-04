package io.gomob.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * gomob · Spacing & dimension tokens
 *
 * 业务代码不要写裸的 dp 值 — 找最近的 token；找不到加新的，不要混入 11dp / 13dp 这类脏值。
 */
@Immutable
data class GomobSpacing(
    // 间距阶梯
    val s2: Dp = 2.dp,
    val s4: Dp = 4.dp,
    val s6: Dp = 6.dp,
    val s8: Dp = 8.dp,
    val s12: Dp = 12.dp,
    val s14: Dp = 14.dp,
    val s16: Dp = 16.dp,
    val s20: Dp = 20.dp,
    val s24: Dp = 24.dp,
    val s28: Dp = 28.dp,
    val s32: Dp = 32.dp,

    // 描边
    val hairline: Dp = 1.dp,

    // 触控目标
    val touchMin: Dp = 44.dp,

    // 组件高度
    val rowSetting: Dp = 56.dp,
    val tabBarHeight: Dp = 56.dp,
    val headerHeight: Dp = 52.dp,
    val chipHeight: Dp = 20.dp,

    // 卡片 padding
    val cardPadding: Dp = 14.dp,
    val metricTileMinH: Dp = 96.dp,
)

internal val DefaultSpacing = GomobSpacing()
