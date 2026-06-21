package io.gomob.network

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gomob.common.net.Ipv4AddressDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端网关地址 — IP + 端口 + 是否 TLS。
 *
 * 设计：App 仅配置一个网关地址，后端把请求反代到内部各服务（auth / device / asset ...）。
 * 不在 App 端枚举每个微服务的 host —— 那是网关 / 反向代理的职责。
 *
 * [tls] 决定 HTTP/WS 走 https/wss 还是 http/ws —— 契约要求生产 TLS，dev 仍可走明文。
 * 默认 false（明文）以不破坏现有 dev http 连通；服务发现 / 手动配置可显式开启。
 *
 * `baseUrl()` 末尾带 "/"，符合 Retrofit baseUrl 约定。
 */
data class ServerEndpoint(val ip: String, val port: Int, val tls: Boolean = false) {
    /**
     * HTTP/WS scheme：http / https。
     * OkHttp WebSocket 同样用 http/https scheme（内部自动升级到 ws/wss），故统一用此值。
     */
    val httpScheme: String get() = if (tls) "https" else "http"

    fun baseUrl(): String = "$httpScheme://$ip:$port/"
    fun display(): String = "$ip:$port"
}

private val Context.serverEndpointDataStore by preferencesDataStore(name = "gomob_server_endpoint")

/**
 * 服务端地址持久化 — DataStore Preferences。
 *
 * 单一真理源 (SSOT)：登录页 DiagnosticStrip 的"服务端 IP:端口"展示与保存，
 * OkHttp / WebSocket 出站请求，全部读这一份。进入 App 后不再二次配置服务端地址。
 *
 * 默认 `127.0.0.1:8808`（明文）—— emulator 通过 `adb reverse tcp:8808 tcp:18808`
 * 访问宿主机开发网关；`./dev.sh install/run` 会自动设置这条反向代理。
 * 登录页会自动刷新服务发现：发现到网关即写入最佳结果，找不到则恢复默认网关。
 *
 * 性能：[current] 被 OkHttp 拦截器在每次出站请求线程上同步调用，维护内存缓存 [cached]：
 * 稳态命中缓存零阻塞，仅未初始化时回退一次 runBlocking 读盘；flow 变更 onEach 回灌缓存，
 * 写路径 [set] 同步更新缓存，保证 SSOT 不漂移。
 */
@Singleton
class ServerEndpointStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keyIp = stringPreferencesKey("server_ip")
    private val keyPort = intPreferencesKey("server_port")
    private val keyTls = booleanPreferencesKey("server_tls")

    private val cached = AtomicReference<ServerEndpoint?>(null)

    val endpointFlow: Flow<ServerEndpoint> =
        context.serverEndpointDataStore.data
            .map {
                ServerEndpoint(
                    ip = it[keyIp] ?: DEFAULT_IP,
                    port = it[keyPort] ?: DEFAULT_PORT,
                    tls = it[keyTls] ?: DEFAULT_TLS,
                )
            }
            .onEach { cached.set(it) }

    /**
     * 同步读 —— 给 OkHttp 拦截器在请求线程上用。稳态命中内存缓存零阻塞，
     * 仅缓存未初始化时回退一次 runBlocking 读盘
     * （与 [io.gomob.data.auth.TokenStore.currentAccessToken] 同思路）。
     */
    fun current(): ServerEndpoint =
        cached.get() ?: runBlocking { endpointFlow.first() }

    /** [tls] 缺省时沿用当前已配置的 scheme（不破坏 dev http 连通）。 */
    suspend fun set(ip: String, port: Int, tls: Boolean? = null) {
        val normalizedIp = Ipv4AddressDraft.from(ip).normalizedOrNull()
            ?: throw IllegalArgumentException("invalid IPv4 address: $ip")
        require(port in 1..65535) { "port must be in 1..65535: $port" }
        val resolvedTls = tls ?: current().tls
        context.serverEndpointDataStore.edit {
            it[keyIp] = normalizedIp
            it[keyPort] = port
            it[keyTls] = resolvedTls
        }
        cached.set(ServerEndpoint(normalizedIp, port, resolvedTls))
    }

    suspend fun resetToDefault() {
        set(DEFAULT_IP, DEFAULT_PORT, DEFAULT_TLS)
    }

    companion object {
        const val DEFAULT_IP = "127.0.0.1"
        const val DEFAULT_PORT = 8808
        const val DEFAULT_TLS = false
    }
}
