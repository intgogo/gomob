package io.gomob.network

/**
 * 网络层从外部拿 access token；通常 :core:data 实现，从 DataStore 读。
 *
 * 设计：网络层不直接读 DataStore，避免对 :core:data 反向依赖。
 */
interface TokenProvider {
    /** 同步取 token；登录前可能 null。 */
    fun currentAccessToken(): String?

    /** 网络层识别到登录态失效时通知外部清理本地会话。 */
    fun onAuthExpired(message: String) = Unit
}
