package io.gomob.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** cv-engine `/cv/ocr/v1/vin_pipeline` 响应（整图 → VMASK → 字符 → 厂家字形库比对 → verdict）。 */
@Serializable
data class VinPipelineResponse(
    val verdict: String = "fail",
    val reasons: List<String> = emptyList(),
    @SerialName("avg_similarity") val avgSimilarity: Double = 0.0,
    @SerialName("min_similarity") val minSimilarity: Double = 0.0,
    val detections: Int = 0,
    val scored: Int = 0,
    @SerialName("vehicle_model_id") val vehicleModelId: String = "",
    @SerialName("batch_id") val batchId: String = "",
    val characters: List<VinPipelineChar> = emptyList(),
)

@Serializable
data class VinPipelineChar(
    val index: Int = 0,
    val character: String = "",
    val similarity: Double = 0.0,
    val status: String = "",
    @SerialName("detection_score") val detectionScore: Double = 0.0,
)
