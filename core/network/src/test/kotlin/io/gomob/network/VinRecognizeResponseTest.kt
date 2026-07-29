package io.gomob.network

import com.google.common.truth.Truth.assertThat
import io.gomob.network.dto.VinRecognizeResponse
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import org.junit.Assert.assertThrows
import org.junit.Test

class VinRecognizeResponseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `完整 Envelope 按纯 OCR 契约解析`() {
        val envelope = json.decodeFromString<Envelope<VinRecognizeResponse>>(
            """
            {
              "code": 0,
              "data": {
                "provider": "gosmart",
                "vin": "ABC",
                "confidence": 0.94,
                "character_scores": [0.91, 0.94, 0.97],
                "character_count": 3,
                "log_id": "log-1",
                "infer_ms": 327,
                "character_crops": [
                  {
                    "position": 1,
                    "character": "A",
                    "image": {
                      "mime_type": "image/webp",
                      "data_base64": "UklGRg==",
                      "width": 64,
                      "height": 128
                    }
                  },
                  {
                    "position": 2,
                    "character": "B",
                    "image": {
                      "mime_type": "image/webp",
                      "data_base64": "UklGRg==",
                      "width": 64,
                      "height": 128
                    }
                  },
                  {
                    "position": 3,
                    "character": "C",
                    "image": {
                      "mime_type": "image/webp",
                      "data_base64": "UklGRg==",
                      "width": 64,
                      "height": 128
                    }
                  }
                ],
                "unknown": "允许服务端扩展"
              }
            }
            """.trimIndent(),
        )

        assertThat(envelope.code).isEqualTo(0)
        assertThat(envelope.data?.provider).isEqualTo("gosmart")
        assertThat(envelope.data?.vin).isEqualTo("ABC")
        assertThat(envelope.data?.characterScores).containsExactly(0.91, 0.94, 0.97).inOrder()
        assertThat(envelope.data?.characterCount).isEqualTo(3)
        assertThat(envelope.data?.inferMs).isEqualTo(327L)
        assertThat(envelope.data?.characterCrops?.map { it.character })
            .containsExactly("A", "B", "C").inOrder()
        assertThat(envelope.data?.characterCrops?.first()?.image?.mimeType).isEqualTo("image/webp")
        assertThat(envelope.data?.characterCrops?.first()?.image?.width).isEqualTo(64)
    }

    @Test
    fun `单字符切割图位置与图片字段完整解析`() {
        val response = json.decodeFromString<VinRecognizeResponse>(
            """
            {
              "provider": "gosmart",
              "vin": "A",
              "confidence": 0.8,
              "character_scores": [0.8],
              "character_count": 1,
              "log_id": "log-2",
              "infer_ms": 12,
              "character_crops": [{
                "position": 1,
                "character": "A",
                "image": {
                  "mime_type": "image/webp",
                  "data_base64": "UklGRg==",
                  "width": 64,
                  "height": 128
                }
              }]
            }
            """.trimIndent(),
        )

        assertThat(response.characterCrops.single().position).isEqualTo(1)
        assertThat(response.characterCrops.single().character).isEqualTo("A")
        assertThat(response.characterCrops.single().image.height).isEqualTo(128)
    }

    @Test
    fun `缺失关键字段必须解析失败`() {
        assertThrows(SerializationException::class.java) {
            json.decodeFromString<VinRecognizeResponse>(
                """
                {
                  "provider": "gosmart",
                  "vin": "ABC",
                  "confidence": 0.8,
                  "character_scores": [],
                  "character_count": 3,
                  "log_id": "log-3",
                  "infer_ms": 12
                }
                """.trimIndent(),
            )
        }
    }
}
