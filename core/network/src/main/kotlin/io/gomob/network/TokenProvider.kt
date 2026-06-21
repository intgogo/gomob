package io.gomob.network

/**
 * 网络层从外部拿 access token；通常 :core:data 实现，从 DataStore 读。
 *
 * 设计：网络层不直接读 DataStore，避免对 :core:data 反向依赖。
 */
interface TokenProvider {
    /** 同步取 access token；登录前可能 null。 */
    fun currentAccessToken(): String?

    /**
     * 同步用 refresh token 静默续期。
     *
     * 返回新的 access token；refresh token 不存在 / 续期失败时返回 null。
     * 实现负责在成功时持久化新的 access+refresh token。
     * 在 OkHttp 请求线程上同步调用（[Authenticator] / [EnvelopeErrorInterceptor]），
     * 实现需自行串行化避免并发刷新风暴。
     */
    fun refreshAccessToken(): String? = null

    /** 网络层识别到登录态彻底失效（refresh 也失败）时通知外部清理本地会话。 */
    fun onAuthExpired(message: String) = Unit
}
