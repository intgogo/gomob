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

    @Test
    fun `解析刻度尺图与字符度量`() {
        val response = json.decodeFromString<VinRestoreResponse>(
            """
            {
              "ok": true,
              "width": 4425,
              "height": 600,
              "ruler_png_base64": "UE5H",
              "character_metrics": {
                "pixels_per_mm": 25.0,
                "total_width_mm": 114.85,
                "total_width_px": 2871.25,
                "center_span_mm": 109.28,
                "pitch_mm": 6.83,
                "pitch_px": 170.75,
                "gap_mm": 0.95,
                "gap_px": 23.75,
                "char_width_mm": 5.88,
                "char_width_px": 147.0,
                "char_height_mm": 9.92,
                "char_height_px": 248.0,
                "left_px": 776.0,
                "right_px": 3647.25,
                "baseline_y_px": 299.5,
                "characters": [
                  {
                    "index": 0,
                    "character": "L",
                    "score": 0.93,
                    "center_x_px": 845.5,
                    "center_y_px": 299.4,
                    "width_mm": 4.1,
                    "height_mm": 9.9
                  }
                ]
              }
            }
            """.trimIndent(),
        )

        assertThat(response.rulerPngBase64).isEqualTo("UE5H")
        val metrics = requireNotNull(response.characterMetrics)
        assertThat(metrics.pixelsPerMM).isEqualTo(25.0)
        assertThat(metrics.totalWidthMm).isEqualTo(114.85)
        assertThat(metrics.pitchMm).isEqualTo(6.83)
        assertThat(metrics.gapMm).isEqualTo(0.95)
        assertThat(metrics.charWidthMm).isEqualTo(5.88)
        assertThat(metrics.charHeightMm).isEqualTo(9.92)
        assertThat(metrics.characters).hasSize(1)
        assertThat(metrics.characters[0].character).isEqualTo("L")
        assertThat(metrics.characters[0].centerXPx).isEqualTo(845.5)
    }

    @Test
    fun `无刻度尺字段时按缺省解析而不抛错`() {
        val response = json.decodeFromString<VinRestoreResponse>("""{"ok": false}""")

        assertThat(response.rulerPngBase64).isEmpty()
        assertThat(response.characterMetrics).isNull()
    }
}
