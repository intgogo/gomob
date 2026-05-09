package io.gomob.designsystem.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private val LocalPageDragOffsetPx = compositionLocalOf { 0f }

/**
 * 全局页面弹性拖动容器。
 *
 * 子内容能滚动时优先滚动；滚不动或内容不足时，整页内容产生轻量回弹。
 */
@Composable
fun DefaultPageDragBox(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val maxOffsetPx = with(LocalDensity.current) { 56.dp.toPx() }
    val releaseAnimation = remember { Animatable(0f) }
    var offsetPx by remember { mutableFloatStateOf(0f) }
    var releaseJob by remember { mutableStateOf<Job?>(null) }

    fun applyDrag(delta: Float) {
        if (delta == 0f) return
        releaseJob?.cancel()

        val current = offsetPx
        val sameDirection = current == 0f || current.sign == delta.sign
        val next = if (sameDirection) {
            val progress = (abs(current) / maxOffsetPx).coerceIn(0f, 0.92f)
            val resistance = 0.28f * (1f - progress).coerceAtLeast(0.12f)
            current + delta * resistance
        } else {
            current + delta * 0.55f
        }
        offsetPx = next.coerceIn(-maxOffsetPx, maxOffsetPx)
    }

    fun release() {
        if (abs(offsetPx) < 0.5f) {
            offsetPx = 0f
            return
        }
        releaseJob?.cancel()
        val start = offsetPx
        releaseJob = scope.launch {
            releaseAnimation.snapTo(start)
            releaseAnimation.animateTo(
                targetValue = 0f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) {
                offsetPx = value
            }
            offsetPx = 0f
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                if (source == NestedScrollSource.UserInput) {
                    applyDrag(available.y)
                }
                return Offset.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                release()
                return Velocity.Zero
            }
        }
    }

    val state = rememberScrollableState { delta ->
        applyDrag(delta)
        0f
    }

    DisposableEffect(Unit) {
        onDispose { releaseJob?.cancel() }
    }

    CompositionLocalProvider(LocalPageDragOffsetPx provides offsetPx) {
        Box(
            modifier
                .graphicsLayer { translationY = offsetPx }
                .nestedScroll(nestedScrollConnection)
                .scrollable(
                    state = state,
                    orientation = Orientation.Vertical,
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Final)
                        } while (event.changes.any { it.pressed })
                        release()
                    }
                },
        ) {
            content()
        }
    }
}

/**
 * 固定在页面拖动层之上的控件：标题栏、底部 TabBar、输入栏等。
 */
@Composable
fun Modifier.fixedDuringPageDrag(): Modifier {
    val pageOffset = LocalPageDragOffsetPx.current
    return this
        .graphicsLayer { translationY = -pageOffset }
        .zIndex(1f)
}
