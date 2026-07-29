package io.gomob.data.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VinRecognitionMappingTest {
    @Test
    fun `合法 17 位 VIN 标记为识别完成`() {
        val result = result(vin = "LA99FRP32G0LTH013", characterCount = 17)

        assertThat(result.status).isEqualTo(VinRecognitionStatus.Completed)
    }

    @Test
    fun `长度非法字符及服务端计数不一致均需复核`() {
        val invalid = listOf(
            result(vin = "LA99FRP32G0LTH01", characterCount = 16),
            result(vin = "LA99FRP32G0LTH0138", characterCount = 18),
            result(vin = "LA99FRP32G0LTH01I", characterCount = 17),
            result(vin = "LA99FRP32G0LTH01O", characterCount = 17),
            result(vin = "LA99FRP32G0LTH01Q", characterCount = 17),
            result(vin = "la99frp32g0lth013", characterCount = 17),
            result(vin = "车A99FRP32G0LTH013", characterCount = 17),
            result(vin = "LA99FRP32G0LTH013", characterCount = 16),
        )

        assertThat(invalid.map { it.status }).containsExactlyElementsIn(
            List(invalid.size) { VinRecognitionStatus.NeedsReview },
        )
    }

    @Test
    fun `逐字符结果保存位置字符和置信度`() {
        val result = result(
            vin = "ABC",
            characterCount = 3,
            scores = listOf(0.91, 0.92, 0.93),
        )

        assertThat(result.characterCrops.map { it.position }).containsExactly(1, 2, 3).inOrder()
        assertThat(result.characterCrops.map { it.character }).containsExactly("A", "B", "C").inOrder()
        assertThat(result.characterCrops.map { it.confidence }).containsExactly(0.91, 0.92, 0.93).inOrder()
    }

    private fun result(
        vin: String,
        characterCount: Int,
        scores: List<Double> = List(vin.length) { 0.9 },
    ) = VinRecognitionResult(
        provider = "gosmart",
        vin = vin,
        confidence = 0.9,
        characterScores = scores,
        characterCount = characterCount,
        logId = "log",
        inferMs = 12,
        characterCrops = vin.mapIndexed { index, character ->
            VinCharacterCrop(
                position = index + 1,
                character = character.toString(),
                confidence = scores[index],
                image = VinCropImage(
                    mimeType = "image/webp",
                    bytes = byteArrayOf(1),
                    width = 64,
                    height = 128,
                ),
            )
        },
    )
}
