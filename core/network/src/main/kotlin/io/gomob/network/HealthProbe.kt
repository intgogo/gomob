package io.gomob.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 直接探指定 endpoint 的 `/healthz`，不走 Retrofit / [HostSelectionInterceptor]。
 *
 * Why: 编辑面板里的"测试连接"按钮要测的是用户**草稿值**，不是当前已保存的值；
 * 而主 OkHttp 客户端的拦截器读的是 store 当前值。所以这里用一个临时的 mini
 * client 只为探活，自带短超时，不污染主链路。
 */
object HealthProbe {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .build()

    /**
     * @return 成功收到 2xx 返回 HTTP 状态码；否则抛异常 (timeout/connect refused/...)。
     */
    suspend fun ping(ep: ServerEndpoint): Int = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${ep.baseUrl()}healthz")
            .get()
            .build()
        client.newCall(req).execute().use { it.code }
    }
}
