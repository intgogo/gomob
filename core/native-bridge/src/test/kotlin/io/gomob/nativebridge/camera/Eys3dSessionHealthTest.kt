package io.gomob.nativebridge.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class Eys3dSessionHealthTest {
    @Test
    fun `启动中负poll且native仍Idle不会触发teardown`() {
        val decision = evaluateEys3dSessionHealth(
            nativeState = NativeCameraSessionState.Idle,
            firstDepthFrameArrived = false,
            startupElapsedMs = 25L,
            startupTimeoutMs = 10_000L,
        )

        assertThat(decision).isEqualTo(Eys3dSessionHealth.HealthyOrStarting)
    }

    @Test
    fun `启动中负poll且native明确Error会触发teardown`() {
        val decision = evaluateEys3dSessionHealth(
            nativeState = NativeCameraSessionState.Error,
            firstDepthFrameArrived = false,
            startupElapsedMs = 25L,
            startupTimeoutMs = 10_000L,
        )

        assertThat(decision).isEqualTo(Eys3dSessionHealth.NativeTerminal)
    }

    @Test
    fun `首帧超过deadline即使native仍Starting也会触发teardown`() {
        val decision = evaluateEys3dSessionHealth(
            nativeState = NativeCameraSessionState.Starting,
            firstDepthFrameArrived = false,
            startupElapsedMs = 10_000L,
            startupTimeoutMs = 10_000L,
        )

        assertThat(decision).isEqualTo(Eys3dSessionHealth.StartupTimedOut)
    }

    @Test
    fun `已经出过首帧不再受startup deadline影响`() {
        val decision = evaluateEys3dSessionHealth(
            nativeState = NativeCameraSessionState.Streaming,
            firstDepthFrameArrived = true,
            startupElapsedMs = 60_000L,
            startupTimeoutMs = 10_000L,
        )

        assertThat(decision).isEqualTo(Eys3dSessionHealth.HealthyOrStarting)
    }
}
