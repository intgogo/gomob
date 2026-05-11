package io.gomob.scan.navigation

import androidx.activity.BackEventCompat
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private const val IOS_BACK_COMPLETE_PROGRESS = 0.48f
private const val IOS_BACK_COMPLETE_VELOCITY_PX = 1200f
private const val IOS_BACK_VERTICAL_SLOP_RATIO = 1.15f
private const val IOS_BACK_START_SLOP_FACTOR = 0.35f

@Composable
internal fun Modifier.iosInteractiveBackGesture(
    enabled: Boolean,
    edgeWidth: Dp = 32.dp,
): Modifier {
    val owner = LocalOnBackPressedDispatcherOwner.current
    val dispatcher = owner?.onBackPressedDispatcher
    val density = LocalDensity.current
    val edgeWidthPx = with(density) { edgeWidth.toPx() }
    val touchSlop = LocalViewConfiguration.current.touchSlop

    return pointerInput(enabled, dispatcher, edgeWidthPx, touchSlop) {
        if (!enabled || dispatcher == null) return@pointerInput

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x > edgeWidthPx || !dispatcher.hasEnabledCallbacks()) {
                waitForUpOrCancellation()
                return@awaitEachGesture
            }

            val velocityTracker = VelocityTracker()
            velocityTracker.addPosition(down.uptimeMillis, down.position)

            var active = false
            var aborted = false
            var started = false
            var latestProgress = 0f
            var pointerId = down.id

            dispatcher.dispatchOnBackStarted(
                BackEventCompat(
                    down.position.x,
                    down.position.y,
                    0f,
                    BackEventCompat.EDGE_LEFT,
                ),
            )
            started = true

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.trackedChange(pointerId) ?: break
                pointerId = change.id
                velocityTracker.addPosition(change.uptimeMillis, change.position)

                if (change.changedToUpIgnoreConsumed()) break

                val delta = change.position - down.position
                if (!active) {
                    val lock = classifyBackGesture(
                        delta = delta,
                        startSlop = touchSlop * IOS_BACK_START_SLOP_FACTOR,
                        abortSlop = touchSlop,
                    )
                    if (lock == GestureLock.Abort) {
                        aborted = true
                        break
                    }
                    if (lock == GestureLock.Start) {
                        active = true
                    }
                }

                if (active) {
                    latestProgress = (delta.x / size.width.toFloat()).coerceIn(0f, 1f)
                    dispatcher.dispatchOnBackProgressed(
                        BackEventCompat(
                            change.position.x,
                            change.position.y,
                            latestProgress,
                            BackEventCompat.EDGE_LEFT,
                        ),
                    )
                    change.consume()
                }
            }

            if (!active) {
                if (started) dispatcher.dispatchOnBackCancelled()
                return@awaitEachGesture
            }

            val velocityX = velocityTracker.calculateVelocity().x
            if (!aborted && (latestProgress >= IOS_BACK_COMPLETE_PROGRESS || velocityX >= IOS_BACK_COMPLETE_VELOCITY_PX)) {
                dispatcher.onBackPressed()
            } else {
                dispatcher.dispatchOnBackCancelled()
            }
        }
    }
}

private enum class GestureLock {
    Pending,
    Start,
    Abort,
}

private fun classifyBackGesture(
    delta: Offset,
    startSlop: Float,
    abortSlop: Float,
): GestureLock {
    val absX = abs(delta.x)
    val absY = abs(delta.y)
    if (delta.x <= -startSlop || absY >= abortSlop && absY > absX) return GestureLock.Abort
    if (absX < startSlop && absY < abortSlop) return GestureLock.Pending
    return if (delta.x > 0f && absX > absY * IOS_BACK_VERTICAL_SLOP_RATIO) {
        GestureLock.Start
    } else {
        GestureLock.Pending
    }
}

private fun androidx.compose.ui.input.pointer.PointerEvent.trackedChange(
    pointerId: PointerId,
): PointerInputChange? =
    changes.firstOrNull { it.id == pointerId } ?: changes.firstOrNull { it.pressed }
