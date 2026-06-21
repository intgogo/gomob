package io.gomob.network

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把所有出站 HTTP 请求的 host:port 改写成 [ServerEndpointStore] 当前持有的值。
 *
 * Why: Retrofit 的 baseUrl 在 `Retrofit.Builder().build()` 时定型，
 * 但登录页服务发现 / 手动兜底都能更新服务端地址 —— 不能每次切都重建 Retrofit
 * (会让所有 ApiService 实例失效，Hilt 单例全冲掉)。改用拦截器在请求线程动态改写
 * host/port，Retrofit 只用占位 baseUrl ([NetworkConfig.PLACEHOLDER_BASE_URL])。
 */
@Singleton
class HostSelectionInterceptor @Inject constructor(
    private val store: ServerEndpointStore,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val ep = store.current()
        val req = chain.request()
        val rewritten = req.newBuilder()
            .url(
                req.url.newBuilder()
                    .host(ep.ip)
                    .port(ep.port)
                    // scheme 跟随 endpoint.tls：dev 默认 http，生产可切 https；
                    // 现有已配置 host 的 tls 维持原值，不破坏 dev 明文连通。
                    .scheme(ep.httpScheme)
                    .build()
            )
            .build()
        return chain.proceed(rewritten)
    }
}
