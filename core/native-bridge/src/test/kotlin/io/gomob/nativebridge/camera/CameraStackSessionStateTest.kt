package io.gomob.nativebridge.camera

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import org.junit.Test

class CameraStackSessionStateTest {
    @Test
    fun `native状态值完整映射且未来值为Unknown`() {
        assertThat(NativeCameraSessionState.fromRawValue(0)).isEqualTo(NativeCameraSessionState.Idle)
        assertThat(NativeCameraSessionState.fromRawValue(1)).isEqualTo(NativeCameraSessionState.Starting)
        assertThat(NativeCameraSessionState.fromRawValue(2)).isEqualTo(NativeCameraSessionState.Streaming)
        assertThat(NativeCameraSessionState.fromRawValue(3)).isEqualTo(NativeCameraSessionState.Error)
        assertThat(NativeCameraSessionState.fromRawValue(4)).isEqualTo(NativeCameraSessionState.Stopped)
        assertThat(NativeCameraSessionState.fromRawValue(99)).isEqualTo(NativeCameraSessionState.Unknown)
    }

    @Test
    fun `CameraStack从stats读取typed状态`() {
        val native = StateCameraNativeApi(NativeCameraSessionState.Starting.rawValue)
        val stack = CameraStack(native)
        assertThat(stack.start(CameraModel.Eys3d, intArrayOf(9))).isTrue()

        assertThat(stack.sessionState()).isEqualTo(NativeCameraSessionState.Starting)

        native.state = NativeCameraSessionState.Streaming.rawValue
        assertThat(stack.sessionState()).isEqualTo(NativeCameraSessionState.Streaming)

        stack.stop()
        assertThat(stack.sessionState()).isEqualTo(NativeCameraSessionState.Stopped)
    }

    private class StateCameraNativeApi(initialState: Long) : CameraNativeApi {
        var state: Long = initialState

        override fun openByFds(vid: Int, pid: Int, fds: IntArray, configJson: ByteArray): Long = 42L
        override fun pollDepthMm(handle: Long, buffer: ByteBuffer, outInfo: LongArray): Int = 0
        override fun pollColor(handle: Long): ByteArray? = null
        override fun pollColorWithInfo(handle: Long, outInfo: LongArray): ByteArray? = null
        override fun stats(handle: Long): LongArray = longArrayOf(0, 0, 0, 0, state)
        override fun setControls(
            handle: Long,
            confThr: Float,
            temporal: Int,
            spatial: Int,
            ae: Int,
            gain: Int,
            irCurrent: Int,
        ): Boolean = true

        override fun stop(handle: Long) = Unit
    }
}
