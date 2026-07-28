package io.gomob.feature.scan3d

import com.google.common.truth.Truth.assertThat
import io.gomob.data.scan.VinRestoreOutcome
import io.gomob.data.scan.VinRestoreRejectReason
import org.junit.Test

class VinRestoreMessageTest {
    @Test
    fun `字符格架不可靠提示完整入框和减少反光`() {
        val message = vinRestoreRejectMessage(
            outcome(VinRestoreRejectReason.TextAnchorUnreliable),
            seq = 12,
        )

        assertThat(message).contains("完整 17 位 VIN")
        assertThat(message).contains("减少反光")
        assertThat(message).doesNotContain("检出 1")
    }

    @Test
    fun `未知判废原因不向用户暴露服务端内部字符串`() {
        val message = vinRestoreRejectMessage(
            outcome(VinRestoreRejectReason.Unknown("future_internal_reason")),
            seq = 3,
        )

        assertThat(message).contains("调整取景后重拍")
        assertThat(message).doesNotContain("future_internal_reason")
    }

    @Test
    fun `未检测到VIN时提示十七位全部入框并对焦`() {
        val message = vinRestoreRejectMessage(
            outcome(VinRestoreRejectReason.VinNotDetected),
            seq = 6,
        )

        assertThat(message).contains("17 位字符全部进入红框")
        assertThat(message).contains("对焦清晰")
    }

    @Test
    fun `标定未发布时引导去网页端处理`() {
        val message = vinRestoreRejectMessage(
            outcome(VinRestoreRejectReason.CalibrationUnavailable),
            seq = 8,
        )

        assertThat(message).contains("网页端发布")
        assertThat(message).contains("第 8 张")
    }

    private fun outcome(reason: VinRestoreRejectReason) = VinRestoreOutcome(
        ok = false,
        png = null,
        rulerPng = null,
        metrics = null,
        width = 0,
        height = 0,
        tiltDeg = 12.0,
        widthMm = 0.0,
        heightMm = 0.0,
        inlierRate = 0.0,
        rms = 0.0,
        medZ = 0.0,
        numDet = 1,
        textAnchor = null,
        syncDeltaUs = 12_000,
        rejectReason = reason,
        logId = "log-1",
    )
}
