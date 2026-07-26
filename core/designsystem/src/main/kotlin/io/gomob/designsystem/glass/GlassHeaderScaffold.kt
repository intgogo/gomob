package io.gomob.designsystem.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import io.gomob.designsystem.motion.fixedDuringPageDrag
import io.gomob.designsystem.theme.Gomob

/**
 * 玻璃 Header 屏骨架 — 替代旧的 `Column { Header; 内容 }` 流内布局。
 *
 * 结构（z 序自底向上）：
 * 1. bg0 底 + [AmbientGlow] 氛围光晕
 * 2. 内容层（hazeSource 采样源）— 占满全屏，从 Header 底下穿过
 * 3. Header 玻璃条（hazeEffect）— 叠在内容上，吃状态栏 inset
 *
 * 内容通过 `content(padding)` 拿到避让区：top = 状态栏 + Header 实测高度，
 * bottom = TabBar（root 屏，经 [LocalContentBottomInset]）或导航栏。
 * 滚动容器把它并进 contentPadding，非滚动布局直接 `Modifier.padding(padding)`。
 *
 * Header 滚动渐显分隔线：传 [listState] / [scrollState] / [gridState] 任一，
 * 未滚动时无分隔线（玻璃下无内容 ≈ 实底），滚动后分隔线渐显。
 */
@Composable
fun GlassHeaderScaffold(
    modifier: Modifier = Modifier,
    listState: LazyListState? = null,
    scrollState: ScrollState? = null,
    gridState: LazyGridState? = null,
    ambient: Boolean = true,
    header: @Composable () -> Unit,
    overlay: @Composable BoxScope.(PaddingValues) -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    // 全 App 共享一个 HazeState: Shell 经 LocalHazeState 下发(TabBar/来电浮窗同源消费),
    // 采样源只有当前屏的内容层这一处 —— 源不嵌套(层中录层会把外层录空, hazeChild 全透明)
    val hazeState = LocalHazeState.current ?: remember { HazeState() }
    val density = LocalDensity.current
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val defaultHeaderHeight = Gomob.spacing.headerHeight
    // 首帧用 headerHeight token 预估，避免内容跳位；onSizeChanged 后收敛到实测值
    var headerHeightPx by remember(density, statusBarTop) {
        mutableIntStateOf(with(density) { (statusBarTop + defaultHeaderHeight).roundToPx() })
    }
    val scrolled by remember(listState, scrollState, gridState) {
        derivedStateOf {
            when {
                listState != null -> listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
                scrollState != null -> scrollState.value > 0
                gridState != null -> gridState.firstVisibleItemIndex > 0 || gridState.firstVisibleItemScrollOffset > 0
                else -> true // 没接滚动源 → 分隔线常显
            }
        }
    }
    val edgeAlpha by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "header-edge",
    )

    Box(modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        if (ambient) AmbientGlow(Modifier.fillMaxSize())

        val topInset = with(density) { headerHeightPx.toDp() }
        val bottomChrome = LocalContentBottomInset.current
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val padding = PaddingValues(
            top = topInset,
            bottom = if (bottomChrome > 0.dp) bottomChrome else navBottom,
        )
        Box(Modifier.fillMaxSize().haze(hazeState)) {
            content(padding)
        }

        CompositionLocalProvider(
            LocalHazeState provides hazeState,
            LocalGlassHeader provides true,
        ) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    // 固定完整玻璃面，而不是只反向移动内部标题文字。
                    .fixedDuringPageDrag()
                    .fillMaxWidth()
                    .onSizeChanged { headerHeightPx = it.height }
                    .glassChrome(bottomEdge = true, edgeAlpha = edgeAlpha),
            ) {
                Box(Modifier.statusBarsPadding()) { header() }
            }
        }

        // 浮层槽位：吸底输入条 / 侧滑面板等。拿到 LocalHazeState 可对内容做真模糊
        CompositionLocalProvider(LocalHazeState provides hazeState) {
            Box(Modifier.fillMaxSize().fixedDuringPageDrag()) {
                overlay(padding)
            }
        }
    }
}

/** Header 在玻璃骨架内 → 自身不再画不透明底（由玻璃层负责）。 */
val LocalGlassHeader = staticCompositionLocalOf { false }

/**
 * 底部 chrome 避让高度。Shell 在 root tab 屏下发 TabBar 总高
 * （tabBarHeight + 导航栏 inset，ime 弹出时动画归零）；
 * 二级页为 0 → scaffold 退回导航栏 inset。
 * 值会动画变化，用 compositionLocalOf 做细粒度失效。
 */
val LocalContentBottomInset = compositionLocalOf { 0.dp }
