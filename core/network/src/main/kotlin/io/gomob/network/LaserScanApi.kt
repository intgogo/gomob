package io.gomob.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * 激光双单元车辆外廓扫描网络接口（M8'，经 gateway 反代 laserworker :18087）。
 *
 * 请求驱动：POST 起一次扫描即返回（capturing），采集中的增量点经 /v1/ws 的 laser.points 实时帧推送，
 * 完成事件 scan.fusion_done(kind=laser) 同经 ws；最终三朵 PCD 经 [downloadCloud] 流式取回。
 * 契约真理源：server internal/laser/handler.go。
 */
interface LaserScanApi {
    @POST("v1/scans/laser")
    suspend fun start(@Body req: LaserScanStartRequest): LaserScanStartResponse

    @POST("v1/scans/laser/{id}/stop")
    suspend fun stop(@Path("id") scanId: Long): LaserScanStatusResponse

    @GET("v1/scans/laser/{id}")
    suspend fun status(@Path("id") scanId: Long): LaserScanStatusResponse

    /** 流式下载一朵 PCD。name ∈ fused|unit_a|unit_b。 */
    @Streaming
    @GET("v1/scans/laser/{id}/cloud/{name}")
    suspend fun downloadCloud(
        @Path("id") scanId: Long,
        @Path("name") name: String,
    ): ResponseBody
}

@Serializable
data class LaserScanStartRequest(
    @SerialName("inspection_id") val inspectionId: Long? = null,
    @SerialName("unit_a_ip") val unitAIp: String? = null,
    @SerialName("unit_b_ip") val unitBIp: String? = null,
    // 默认 none(纯 union)：ICP 跨单元配准对固定双机位不稳，无强共同结构(空场/少特征)即发散，
    // 把 B 甩出数十米。固定基线的正解是 site 标定外参(待 laserworker 接线 SiteJSON);在此之前
    // none 给有界可渲染的 union 结果。
    val align: String = "none", // icp|none|site
    @SerialName("keep_ratio") val keepRatio: Float? = null,
)

@Serializable
data class LaserScanStartResponse(
    @SerialName("scan_id") val scanId: Long,
    @SerialName("session_key") val sessionKey: String,
    val status: String,
)

/** GET 状态 / stop 的统一视图（字段随状态机渐次出现，未就绪为 null）。对齐 handler.go jobView。 */
@Serializable
data class LaserScanStatusResponse(
    @SerialName("scan_id") val scanId: Long,
    @SerialName("session_key") val sessionKey: String? = null,
    val status: String,
    val align: String? = null,
    @SerialName("align_method") val alignMethod: String? = null,
    val points: Int? = null,
    @SerialName("pts_a") val ptsA: Int? = null,
    @SerialName("pts_b") val ptsB: Int? = null,
    @SerialName("result_object_key") val resultObjectKey: String? = null,
    @SerialName("unit_a_object_key") val unitAObjectKey: String? = null,
    @SerialName("unit_b_object_key") val unitBObjectKey: String? = null,
    val error: String? = null,
)
