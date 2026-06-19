package io.gomob.network

import io.gomob.network.dto.VinPipelineResponse
import io.gomob.network.dto.VinRestoreResponse
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

    /**
     * VIN 数码拓印还原（原厂全保真：深度 RANSAC 平面去透视 + YOLO-OBB 锚定四角单应正射 + 去阴影二值化）。
     *
     * 端侧只「拍 + 传」：上传 HLSD8 彩色 JPEG + 深度 u16 LE mm 裸字节 + 深度内参；服务端按 2× registration 自推
     * 彩色内参，整条还原算法在 Go cvengine 跑。字段对齐服务端 VinRestore handler 的 multipart 契约。
     */
    @Multipart
    @POST("cv/ocr/v1/vin_restore")
    suspend fun vinRestore(
        @Part rgb: MultipartBody.Part,        // image_binary_rgb1300（HLSD8 彩色 JPEG）
        @Part depth: MultipartBody.Part,      // image_binary_depth（深度 u16 LE mm 裸字节）
        @Part("depth_w") depthW: RequestBody,
        @Part("depth_h") depthH: RequestBody,
        @Part("fx") fx: RequestBody,
        @Part("fy") fy: RequestBody,
        @Part("cx") cx: RequestBody,
        @Part("cy") cy: RequestBody,
        @Part("device_id") deviceId: RequestBody,
    ): Envelope<VinRestoreResponse>
}
