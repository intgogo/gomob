package io.gomob.nativebridge.camera

import com.google.common.truth.Truth.assertThat
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Test

class CameraLeaseStateTest {
    @Test
    fun `最后一个消费者释放后才生成停流令牌`() {
        val state = CameraLeaseState()

        assertThat(state.acquire()).isEqualTo(CameraLeaseState.AcquireResult(1, true))
        assertThat(state.acquire()).isEqualTo(CameraLeaseState.AcquireResult(2, false))

        val firstRelease = state.release()
        assertThat(firstRelease.count).isEqualTo(1)
        assertThat(firstRelease.stopToken).isNull()

        val finalRelease = state.release()
        assertThat(finalRelease.count).isEqualTo(0)
        assertThat(finalRelease.stopToken).isNotNull()
        assertThat(state.canStop(finalRelease.stopToken!!)).isTrue()
    }

    @Test
    fun `宽限期内重新获取会立即作废旧停流令牌`() {
        val state = CameraLeaseState()
        state.acquire()
        val oldToken = state.release().stopToken!!

        assertThat(state.acquire().shouldStart).isTrue()

        assertThat(state.canStop(oldToken)).isFalse()
        assertThat(state.consumerCount).isEqualTo(1)
    }

    @Test
    fun `旧宽限任务不能提前消费新一轮释放`() {
        val state = CameraLeaseState()
        state.acquire()
        val oldToken = state.release().stopToken!!
        state.acquire()
        val newToken = state.release().stopToken!!

        assertThat(state.canStop(oldToken)).isFalse()
        assertThat(state.canStop(newToken)).isTrue()
    }

    @Test
    fun `重复release不破坏已经排队的合法停流`() {
        val state = CameraLeaseState()
        state.acquire()
        val token = state.release().stopToken!!

        val underflow = state.release()

        assertThat(underflow.underflow).isTrue()
        assertThat(underflow.stopToken).isNull()
        assertThat(state.canStop(token)).isTrue()
    }

    @Test
    fun `并发消费者仍只有一次首启动和一次最终停流`() {
        val state = CameraLeaseState()
        val acquired = CountDownLatch(100)
        val releaseGate = CountDownLatch(1)
        val acquireResults = Collections.synchronizedList(mutableListOf<CameraLeaseState.AcquireResult>())
        val releaseResults = Collections.synchronizedList(mutableListOf<CameraLeaseState.ReleaseResult>())
        val threads = List(100) {
            thread(start = true) {
                acquireResults += state.acquire()
                acquired.countDown()
                check(releaseGate.await(2, TimeUnit.SECONDS))
                releaseResults += state.release()
            }
        }

        assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue()
        assertThat(state.consumerCount).isEqualTo(100)
        releaseGate.countDown()
        threads.forEach { it.join(2_000) }

        assertThat(threads.none { it.isAlive }).isTrue()
        assertThat(acquireResults.count { it.shouldStart }).isEqualTo(1)
        assertThat(releaseResults.count { it.stopToken != null }).isEqualTo(1)
        assertThat(releaseResults.any { it.underflow }).isFalse()
        assertThat(state.consumerCount).isEqualTo(0)
        assertThat(state.canStop(releaseResults.single { it.stopToken != null }.stopToken!!)).isTrue()
    }
}
