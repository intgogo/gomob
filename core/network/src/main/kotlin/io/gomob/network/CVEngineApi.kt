package io.gomob.network

import io.gomob.network.dto.VinPipelineResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * cv-engine OCR 接口（经 devserver 反代 cv/ocr 路径）。
 *
 * 鉴权：App 只带 JWT（[AuthInterceptor] 注入）；HMAC 双轨验签由 devserver 服务端转发时加（密钥不下发端侧）。
 */
interface CVEngineApi {
    @Multipart
    @POST("cv/ocr/v1/vin_pipeline")
    suspend fun vinPipeline(
        @Part("vehicle_model_id") vehicleModelId: RequestBody,
        @Part("tag") tag: RequestBody,
        @Part image: MultipartBody.Part,
    ): Envelope<VinPipelineResponse>
}
