package io.gomob.network

import retrofit2.Response
import retrofit2.http.GET

/**
 * 服务端探活 —— 网关只要 200 就算 OK，不解析 body。
 *
 * 服务端实现见 server/cmd/devserver/main.go 等若干 `/healthz` 端点；
 * 通过反向代理统一暴露在网关根路径下。
 */
interface HealthApi {
    @GET("healthz")
    suspend fun healthz(): Response<Unit>
}
