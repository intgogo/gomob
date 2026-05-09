package io.gomob.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AssetUploadInitRequest(
    @SerialName("inspection_id") val inspectionId: String? = null,
    val kind: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    val sha256: String,
    val mime: String,
    @SerialName("chunk_mb") val chunkMb: Int? = null,
)

@Serializable
data class AssetUploadInitResponse(
    @SerialName("upload_id") val uploadId: String,
    @SerialName("chunk_size") val chunkSize: Int,
)

@Serializable
data class AssetUploadCompleteRequest(
    @SerialName("total_chunks") val totalChunks: Int,
)

@Serializable
data class AssetUploadChunkResponse(
    @SerialName("part_number") val partNumber: Int,
    val etag: String,
    val size: Long,
)

@Serializable
data class AssetUploadCompleteResponse(
    @SerialName("asset_id") val assetId: String,
    @SerialName("object_key") val objectKey: String,
    @SerialName("download_url") val downloadUrl: String? = null,
)
