package io.gomob.network

import com.google.common.truth.Truth.assertThat
import io.gomob.network.dto.VinPreviewCalibrationResponse
import kotlinx.serialization.json.Json
import org.junit.Test

class VinPreviewCalibrationResponseTest {
    @Test
    fun `原厂预览标定响应按蛇形字段完整解码`() {
        val response = Json { ignoreUnknownKeys = false }.decodeFromString<VinPreviewCalibrationResponse>(
            """
            {
              "contract_version": 1,
              "projection_model": "vincreator_factory_v3",
              "occlusion_metric": "absolute_camera_z",
              "key": {
                "depth_serial": "BF301208",
                "color_serial": "202303111518",
                "depth_width": 640,
                "depth_height": 128,
                "color_width": 4160,
                "color_height": 832
              },
              "calibration_sha256": "1a87dc030c50d532503218fbb026a453b2c0fa9b17df5316da60782d8d7bf5d2",
              "calibration_version": 3,
              "depth": {
                "sample_format": "disparity_x8_u16",
                "data_type": 1,
                "reference_width": 1280,
                "reference_height": 256,
                "principal_column": 324.0,
                "principal_row": 65.43250274658203,
                "projection_focal_x": 614.60498046875,
                "projection_focal_y": 614.60498046875,
                "disparity_focal": 1229.2099609375,
                "baseline_mm": 49.98929977416992,
                "disparity_unit": 0.125,
                "valid_depth_min_mm": 50.0,
                "valid_depth_max_mm": 1000.0
              },
              "color": {
                "principal_row": 1274.610937612,
                "principal_column": 2119.555128713,
                "focal_row": 5737.022753971,
                "focal_column": 5642.090890116,
                "distortion_pixel_k_p1_p2_s1_s2": [0, 0, 0, 0, 0],
                "rotation_row_major": [1, 0, 0, 0, 1, 0, 0, 0, 1],
                "translation_mm": [1.0, 2.0, 3.0]
              }
            }
            """.trimIndent(),
        )

        assertThat(response.key.depthSerial).isEqualTo("BF301208")
        assertThat(response.depth.disparityFocal).isEqualTo(1229.2099609375)
        assertThat(response.color.distortion).hasSize(5)
        assertThat(response.color.rotation).containsExactly(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0).inOrder()
    }
}
