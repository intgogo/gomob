package io.gomob.data.scan

import android.util.Base64
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

/**
 * VIN 数码拓印还原结果（端侧领域类型）。[png] 已从服务端 base64 解码为 PNG 字节；
 * [ok]=false 表承印面倾角 [tiltDeg]>70° 被原厂硬门判废（[png] 为 null）。
 */
data class VinRestoreOutcome(
    val ok: Boolean,
    val png: ByteArray?,
    val width: Int,
    val height: Int,
    val tiltDeg: Double,
    val widthMm: Double,
    val heightMm: Double,
    val inlierRate: Double,
    val rms: Double,
    val medZ: Double,
    val numDet: Int,
    val inkRatio: Double,
    /** ok=false 判废原因：tilt_too_large（承印面过斜）/ low_quality（噪声坏采集）/ 空（成功）。 */
    val rejectReason: String,
    val logId: String,
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

    /**
     * VIN 数码拓印还原（原厂全保真，全程服务端 Go cvengine）。端侧只把采集帧原样上传：
     *
     * @param rgbJpeg   HLSD8 彩色 JPEG 字节（服务端解码取彩色尺寸）
     * @param depthU16  深度裸字节，u16 LE metric mm，长度 = depthW*depthH*2
     * @param depthW/depthH 深度尺寸
     * @param fx/fy/cx/cy   深度内参（服务端按彩色=2×深度 registration 自推彩色内参，不传彩色内参）
     * @param deviceId  上报机型（服务端仅日志用）
     */
    suspend fun restore(
        rgbJpeg: ByteArray,
        depthU16: ByteArray,
        depthW: Int,
        depthH: Int,
        fx: Double,
        fy: Double,
        cx: Double,
        cy: Double,
        deviceId: String,
    ): VinRestoreOutcome {
        val text = "text/plain".toMediaTypeOrNull()
        val rgbPart = MultipartBody.Part.createFormData(
            "image_binary_rgb1300", "rgb1300.jpg",
            rgbJpeg.toRequestBody("image/jpeg".toMediaTypeOrNull()),
        )
        val depthPart = MultipartBody.Part.createFormData(
            "image_binary_depth", "depth.u16",
            depthU16.toRequestBody("application/octet-stream".toMediaTypeOrNull()),
        )
        val resp = cvEngine.vinRestore(
            rgbPart, depthPart,
            depthW.toString().toRequestBody(text), depthH.toString().toRequestBody(text),
            fx.toString().toRequestBody(text), fy.toString().toRequestBody(text),
            cx.toString().toRequestBody(text), cy.toString().toRequestBody(text),
            deviceId.toRequestBody(text),
        ).data ?: throw ApiException(50001, 500, "VIN 还原响应缺数据")

        val png = if (resp.ok && resp.resultPngBase64.isNotEmpty()) {
            Base64.decode(resp.resultPngBase64, Base64.DEFAULT)
        } else {
            null
        }
        return VinRestoreOutcome(
            ok = resp.ok,
            png = png,
            width = resp.width,
            height = resp.height,
            tiltDeg = resp.tiltDeg,
            widthMm = resp.widthMm,
            heightMm = resp.heightMm,
            inlierRate = resp.inlierRate,
            rms = resp.rms,
            medZ = resp.medZ,
            numDet = resp.numDet,
            inkRatio = resp.inkRatio,
            rejectReason = resp.rejectReason,
            logId = resp.logId,
        )
    }
}
