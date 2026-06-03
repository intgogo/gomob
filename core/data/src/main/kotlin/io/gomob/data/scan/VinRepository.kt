package io.gomob.data.scan

import io.gomob.network.ApiException
import io.gomob.network.CVEngineApi
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/** VIN 数码拓印识别结果（端侧领域类型，剥离 core:network DTO）。 */
data class VinResult(
    val verdict: String,
    val reasons: List<String>,
    val avgSimilarity: Double,
    val minSimilarity: Double,
    val detections: Int,
    val scored: Int,
    /** 按位置排序拼出的识别 VIN（仅含已识别字符）。 */
    val recognizedVin: String,
    val characters: List<VinCharResult>,
)

data class VinCharResult(
    val index: Int,
    val character: String,
    val similarity: Double,
    val status: String,
)

/** VIN 识别仓库：把端侧拍到的整图喂服务端 `vin_pipeline` 拿真 verdict + 字符比对。 */
@Singleton
class VinRepository @Inject constructor(
    private val cvEngine: CVEngineApi,
) {
    /**
     * @param vehicleModelId 车型 ID（决定拉哪套厂家字形库对照）
     * @param jpeg VIN 钢印整图 JPEG 字节
     */
    suspend fun recognize(vehicleModelId: Long, jpeg: ByteArray): VinResult {
        val text = "text/plain".toMediaTypeOrNull()
        val vmid = vehicleModelId.toString().toRequestBody(text)
        val tag = "VMASK".toRequestBody(text)
        val image = MultipartBody.Part.createFormData(
            "image_binary", "vin.jpg",
            jpeg.toRequestBody("image/jpeg".toMediaTypeOrNull()),
        )
        val resp = cvEngine.vinPipeline(vmid, tag, image).data
            ?: throw ApiException(50001, 500, "VIN 识别响应缺数据")

        val chars = resp.characters
            .sortedBy { it.index }
            .map { VinCharResult(it.index, it.character, it.similarity, it.status) }
        val recognized = chars.joinToString("") { it.character }
        return VinResult(
            verdict = resp.verdict,
            reasons = resp.reasons,
            avgSimilarity = resp.avgSimilarity,
            minSimilarity = resp.minSimilarity,
            detections = resp.detections,
            scored = resp.scored,
            recognizedVin = recognized,
            characters = chars,
        )
    }
}
