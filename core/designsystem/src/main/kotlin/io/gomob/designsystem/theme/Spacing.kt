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

    // 全局版式语义
    val pageGutter: Dp = 16.dp,
    val cardGap: Dp = 12.dp,
    val sectionGap: Dp = 20.dp,

    // 细线
    val hairline: Dp = 0.5.dp,

    // 触控目标
    val touchMin: Dp = 44.dp,

    // 组件高度
    val rowSetting: Dp = 56.dp,
    val rowSettingTall: Dp = 64.dp,    // 单选 / 副标题加密的列表行
    val rowList: Dp = 64.dp,
    val rowConversation: Dp = 68.dp,
    val tabBarHeight: Dp = 54.dp,
    val headerHeight: Dp = 52.dp,
    val compactComposerHeight: Dp = 64.dp,
    val chipHeight: Dp = 20.dp,

    // 状态点 / 圆指示器（CircleShape 直径）
    val dot4: Dp = 4.dp,
    val dot6: Dp = 6.dp,
    val dot8: Dp = 8.dp,

    // 图标尺寸（系统图标方框）
    val icon16: Dp = 16.dp,
    val icon20: Dp = 20.dp,
    val icon24: Dp = 24.dp,

    // 头像 / 圆形可点目标（28/40 仅用于纯展示头像或带 ripple 的 bg overlay）
    val avatar28: Dp = 28.dp,
    val avatar40: Dp = 40.dp,
    val avatar48: Dp = 48.dp,
    val avatarConversation: Dp = 44.dp,
    val avatarHero: Dp = 56.dp,
    val btnCircle72: Dp = 72.dp,        // 拍摄主按钮

    // 自制开关 / 单选环
    val switchW: Dp = 40.dp,
    val switchH: Dp = 20.dp,
    val switchThumb: Dp = 16.dp,
    val switchPad: Dp = 2.dp,
    val radioOuter: Dp = 20.dp,
    val radioInner: Dp = 8.dp,

    // 数据可视化
    val cellH28: Dp = 28.dp,            // 周 / 月历格高
    val barChartH: Dp = 80.dp,          // 7 列柱图主体高

    // 视频上覆 overlay 卡（左右 inspector / metrics 卡）
    val overlayCardWSm: Dp = 200.dp,
    val overlayCardWMd: Dp = 220.dp,

    // 卡片 padding
    val cardPadding: Dp = 14.dp,
    val metricTileMinH: Dp = 96.dp,
    val metricTileCompactMinH: Dp = 88.dp,
)

internal val DefaultSpacing = GomobSpacing()
