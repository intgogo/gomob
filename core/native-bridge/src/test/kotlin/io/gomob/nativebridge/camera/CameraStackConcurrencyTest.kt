package io.gomob.nativebridge.camera

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Test

class CameraStackConcurrencyTest {
    @Test
    fun `stop等待在途poll结束后才释放native会话`() {
        val native = BlockingCameraNativeApi()
        val stack = CameraStack(native)
        assertThat(stack.start(CameraModel.Eys3d, intArrayOf(9))).isTrue()

        val pollThread = thread(name = "camera-poll") {
            stack.pollDepthMm(ByteBuffer.allocateDirect(2), LongArray(4))
        }
        assertThat(native.pollEntered.await(1, TimeUnit.SECONDS)).isTrue()

        val stopAttempted = CountDownLatch(1)
        val stopThread = thread(name = "camera-stop") {
            stopAttempted.countDown()
            stack.stop()
        }
        assertThat(stopAttempted.await(1, TimeUnit.SECONDS)).isTrue()
        assertThat(native.stopEntered.await(150, TimeUnit.MILLISECONDS)).isFalse()

        native.releasePoll.countDown()
        pollThread.join(1_000)
        stopThread.join(1_000)

        assertThat(pollThread.isAlive).isFalse()
        assertThat(stopThread.isAlive).isFalse()
        assertThat(native.stopCalls.get()).isEqualTo(1)
        assertThat(stack.isOpen).isFalse()
        assertThat(stack.pollDepthMm(ByteBuffer.allocateDirect(2), LongArray(4))).isEqualTo(-1)
        assertThat(native.pollCalls.get()).isEqualTo(1)
    }

    private class BlockingCameraNativeApi : CameraNativeApi {
        val pollEntered = CountDownLatch(1)
        val releasePoll = CountDownLatch(1)
        val stopEntered = CountDownLatch(1)
        val pollCalls = AtomicInteger(0)
        val stopCalls = AtomicInteger(0)

        override fun openByFds(vid: Int, pid: Int, fds: IntArray, configJson: ByteArray): Long = 42L

        override fun pollDepthMm(handle: Long, buffer: ByteBuffer, outInfo: LongArray): Int {
            pollCalls.incrementAndGet()
            pollEntered.countDown()
            check(releasePoll.await(2, TimeUnit.SECONDS)) { "测试未放行 poll" }
            return 0
        }

        override fun pollColor(handle: Long): ByteArray? = null

        override fun pollColorWithInfo(handle: Long, outInfo: LongArray): ByteArray? = null

        override fun stats(handle: Long): LongArray = LongArray(5)

        override fun setControls(
            handle: Long,
            confThr: Float,
            temporal: Int,
            spatial: Int,
            ae: Int,
            gain: Int,
            irCurrent: Int,
        ): Boolean = true

        override fun stop(handle: Long) {
            stopCalls.incrementAndGet()
            stopEntered.countDown()
        }
    }
}
