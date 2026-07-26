package io.gomob.network

import com.google.common.truth.Truth.assertThat
import io.gomob.network.dto.VinRestoreResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test

class VinRestoreResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `解析完整字符锚定元数据`() {
        val response = json.decodeFromString<VinRestoreResponse>(
            """
            {
              "ok": true,
              "width": 4425,
              "height": 600,
              "device_id": "BF301208",
              "color_device_id": "HLSD8-001",
              "anchor_count": 17,
              "anchor_candidate_count": 19,
              "anchor_pitch_px": 63.5,
              "anchor_rms_px": 5.2,
              "anchor_mean_score": 0.91,
              "anchor_height_px": 72.0,
              "anchor_rotation_deg": -0.2,
              "anchor_scale": 1.01,
              "calibration_sha256": "1a87dc030c50d532503218fbb026a453b2c0fa9b17df5316da60782d8d7bf5d2",
              "calibration_version": 3,
              "unknown": "允许服务端扩展"
            }
            """.trimIndent(),
        )

        assertThat(response.anchorCount).isEqualTo(17)
        assertThat(response.anchorCandidateCount).isEqualTo(19)
        assertThat(response.anchorPitchPx).isEqualTo(63.5)
        assertThat(response.anchorRmsPx).isEqualTo(5.2)
        assertThat(response.anchorMeanScore).isEqualTo(0.91)
        assertThat(response.anchorHeightPx).isEqualTo(72.0)
        assertThat(response.anchorRotationDeg).isEqualTo(-0.2)
        assertThat(response.anchorScale).isEqualTo(1.01)
        assertThat(response.calibrationSha256)
            .isEqualTo("1a87dc030c50d532503218fbb026a453b2c0fa9b17df5316da60782d8d7bf5d2")
        assertThat(response.calibrationVersion).isEqualTo(3)
        assertThat(response.width).isEqualTo(4425)
        assertThat(response.height).isEqualTo(600)
        assertThat(response.deviceId).isEqualTo("BF301208")
        assertThat(response.colorDeviceId).isEqualTo("HLSD8-001")
    }

    @Test
    fun `字符格架不可靠按结构化原因解析`() {
        val response = json.decodeFromString<VinRestoreResponse>(
            """
            {
              "ok": false,
              "anchor_count": 17,
              "anchor_candidate_count": 20,
              "anchor_mean_score": 0.69,
              "reject_reason": "text_anchor_unreliable"
            }
            """.trimIndent(),
        )

        assertThat(response.ok).isFalse()
        assertThat(response.rejectReason).isEqualTo("text_anchor_unreliable")
        assertThat(response.anchorCandidateCount).isEqualTo(20)
    }
}
