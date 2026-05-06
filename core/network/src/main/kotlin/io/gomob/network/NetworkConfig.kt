package io.gomob.network

object NetworkConfig {
    /**
     * Retrofit 启动时需要一个语法合法的 baseUrl 才能 build()，但实际 host:port
     * 由 [HostSelectionInterceptor] 在每次请求时根据 [ServerEndpointStore] 的当前
     * 值动态改写。所以这里只是占位，App 启动后用户改服务端地址不需要重建 Retrofit。
     */
    const val PLACEHOLDER_BASE_URL = "http://127.0.0.1/"
}
