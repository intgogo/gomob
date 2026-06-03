package io.gomob.network

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Streaming

/**
 * 扫描相关网络接口。
 *
 * 上传走 [AssetApi]（kind=scan3d_bundle）；本接口只负责【融合结果回看】下载：
 * 按 session_key 经 server 流式中转拉融合产物 GLB（owner 鉴权），避免端侧直连 MinIO 内网。
 */
interface ScanApi {
    @Streaming
    @GET("v1/scans/{session_key}/result")
    suspend fun downloadFusionResult(
        @Path("session_key") sessionKey: String,
    ): ResponseBody
}
