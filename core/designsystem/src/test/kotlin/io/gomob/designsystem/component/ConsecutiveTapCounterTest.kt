package io.gomob.designsystem.component

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConsecutiveTapCounterTest {
    @Test
    fun fiveContinuousTapsTriggerOnceAndReset() {
        val counter = ConsecutiveTapCounter(requiredTapCount = 5, maxGapMillis = 700)

        assertThat(counter.registerTap("首页", 0)).isFalse()
        assertThat(counter.registerTap("首页", 100)).isFalse()
        assertThat(counter.registerTap("首页", 200)).isFalse()
        assertThat(counter.registerTap("首页", 300)).isFalse()
        assertThat(counter.registerTap("首页", 400)).isTrue()
        assertThat(counter.registerTap("首页", 500)).isFalse()
    }

    @Test
    fun tapAfterTimeoutStartsNewSequence() {
        val counter = ConsecutiveTapCounter(requiredTapCount = 5, maxGapMillis = 700)

        repeat(4) { index ->
            assertThat(counter.registerTap("消息", index * 100L)).isFalse()
        }
        assertThat(counter.registerTap("消息", 1_001)).isFalse()
        repeat(3) { index ->
            assertThat(counter.registerTap("消息", 1_101L + index * 100L)).isFalse()
        }
        assertThat(counter.registerTap("消息", 1_401)).isTrue()
    }

    @Test
    fun titleChangeResetsSequence() {
        val counter = ConsecutiveTapCounter(requiredTapCount = 5, maxGapMillis = 700)

        repeat(4) { index ->
            assertThat(counter.registerTap("首页", index * 100L)).isFalse()
        }
        assertThat(counter.registerTap("消息", 400)).isFalse()
        repeat(3) { index ->
            assertThat(counter.registerTap("消息", 500L + index * 100L)).isFalse()
        }
        assertThat(counter.registerTap("消息", 800)).isTrue()
    }

    @Test
    fun clockRollbackStartsNewSequence() {
        val counter = ConsecutiveTapCounter(requiredTapCount = 2, maxGapMillis = 700)

        assertThat(counter.registerTap("协作", 1_000)).isFalse()
        assertThat(counter.registerTap("协作", 900)).isFalse()
        assertThat(counter.registerTap("协作", 1_000)).isTrue()
    }
}
