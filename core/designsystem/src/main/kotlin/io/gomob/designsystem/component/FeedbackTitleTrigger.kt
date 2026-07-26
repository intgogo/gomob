package io.gomob.designsystem.component

import android.os.SystemClock
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

val LocalFeedbackTitleTrigger = compositionLocalOf<((String) -> Unit)?> { null }

internal const val FeedbackTitleRequiredTapCount = 5
internal const val FeedbackTitleMaxTapGapMillis = 700L

/**
 * 标题隐藏入口的连续点击计数器。
 *
 * 相邻点击超过时间窗或标题发生变化时，从当前点击重新计数；触发后立即清零，避免第六次点击
 * 再次误触发。
 */
internal class ConsecutiveTapCounter(
    private val requiredTapCount: Int = FeedbackTitleRequiredTapCount,
    private val maxGapMillis: Long = FeedbackTitleMaxTapGapMillis,
) {
    private var currentKey: String? = null
    private var tapCount = 0
    private var lastTapAtMillis = Long.MIN_VALUE

    init {
        require(requiredTapCount > 0)
        require(maxGapMillis >= 0)
    }

    fun registerTap(key: String, nowMillis: Long): Boolean {
        val continuesSequence = currentKey == key &&
            lastTapAtMillis != Long.MIN_VALUE &&
            nowMillis >= lastTapAtMillis &&
            nowMillis - lastTapAtMillis <= maxGapMillis

        tapCount = if (continuesSequence) tapCount + 1 else 1
        currentKey = key
        lastTapAtMillis = nowMillis

        if (tapCount < requiredTapCount) return false
        reset()
        return true
    }

    fun reset() {
        currentKey = null
        tapCount = 0
        lastTapAtMillis = Long.MIN_VALUE
    }
}

/**
 * 页面大标题连续点击五次后触发反馈入口。
 *
 * 使用 pointerInput 而非 clickable，避免把隐藏入口暴露成没有正常点击语义的无障碍按钮。
 */
@Composable
fun Modifier.feedbackTitleFiveTap(
    title: String,
    onTrigger: (String) -> Unit,
): Modifier {
    val currentTrigger = rememberUpdatedState(onTrigger)
    val tapCounter = remember { ConsecutiveTapCounter() }
    return pointerInput(title, tapCounter) {
        detectTapGestures {
            if (tapCounter.registerTap(title, SystemClock.uptimeMillis())) {
                currentTrigger.value(title)
            }
        }
    }
}
