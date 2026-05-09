package io.gomob.network

import io.gomob.network.dto.AssetUploadCompleteRequest
import io.gomob.network.dto.AssetUploadCompleteResponse
import io.gomob.network.dto.AssetUploadChunkResponse
import io.gomob.network.dto.AssetUploadInitRequest
import io.gomob.network.dto.AssetUploadInitResponse
import okhttp3.RequestBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

interface AssetApi {
    @POST("v1/assets/upload/init")
    suspend fun initUpload(
        @Body request: AssetUploadInitRequest,
    ): Envelope<AssetUploadInitResponse>

    @PUT("v1/assets/upload/{upload_id}/chunk/{n}")
    suspend fun uploadChunk(
        @Path("upload_id") uploadId: String,
        @Path("n") chunkNumber: Int,
        @Body body: RequestBody,
    ): Envelope<AssetUploadChunkResponse>

    @POST("v1/assets/upload/{upload_id}/complete")
    suspend fun completeUpload(
        @Path("upload_id") uploadId: String,
        @Body request: AssetUploadCompleteRequest,
    ): Envelope<AssetUploadCompleteResponse>

    @GET("v1/assets/{id}/url")
    suspend fun presignDownload(
        @Path("id") assetId: String,
    ): Envelope<AssetUploadCompleteResponse>
}
