package io.gomob.network

import io.gomob.network.dto.VinRecognizeResponse
import io.gomob.network.dto.VinPreviewCalibrationResponse
import io.gomob.network.dto.VinRestoreResponse
import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Multipart
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

/**
 * cv-engine OCR 接口（经 devserver 反代 cv/ocr 路径）。
 *
 * 鉴权：App 只带 JWT（[AuthInterceptor] 注入）；HMAC 双轨验签由 devserver 服务端转发时加（密钥不下发端侧）。
 */
interface CVEngineApi {
    /** 按完整双相机 rig/profile 获取只读原厂预览投影参数。 */
    @GET("cv/ocr/v1/vin_preview_calibration")
    suspend fun vinPreviewCalibration(
        @Query("depth_serial") depthSerial: String,
        @Query("color_serial") colorSerial: String,
        @Query("depth_width") depthWidth: Int,
        @Query("depth_height") depthHeight: Int,
        @Query("color_width") colorWidth: Int,
        @Query("color_height") colorHeight: Int,
    ): Envelope<VinPreviewCalibrationResponse>

    @Multipart
    @POST("cv/ocr/v1/vin_recognize")
    suspend fun vinRecognize(
        @Part image: MultipartBody.Part,
    ): Envelope<VinRecognizeResponse>

    /**
     * VIN 数码拓印还原（深度 RANSAC 承印面 + 17 字符刚性格架 + 原始彩色一次等比正射采样）。
     *
     * 端侧只「拍 + 传」：上传 HLSD8 彩色 JPEG + RS-D550 mode25 原始 u16 LE 视差（×8）+ 双相机序列号/流档位 +
     * 深度内参 + 两路 native 收帧时间戳。
     * 服务端会再次强制校验同步窗，整条还原算法在 Go cvengine 跑。
     */
    @Multipart
    @POST("cv/ocr/v1/vin_restore")
    suspend fun vinRestore(
        @Part rgb: MultipartBody.Part,        // image_binary_rgb1300（HLSD8 彩色 JPEG）
        @Part depth: MultipartBody.Part,      // image_binary_depth（mode25 原始 u16 LE 视差×8）
        @Part("depth_w") depthW: RequestBody,
        @Part("depth_h") depthH: RequestBody,
        @Part("fx") fx: RequestBody,
        @Part("fy") fy: RequestBody,
        @Part("cx") cx: RequestBody,
        @Part("cy") cy: RequestBody,
        @Part("device_id") deviceId: RequestBody,
        @Part("color_device_id") colorDeviceId: RequestBody,
        @Part("color_w") colorW: RequestBody,
        @Part("color_h") colorH: RequestBody,
        @Part("color_timestamp_us") colorTimestampUs: RequestBody,
        @Part("depth_timestamp_us") depthTimestampUs: RequestBody,
    ): Envelope<VinRestoreResponse>
}
