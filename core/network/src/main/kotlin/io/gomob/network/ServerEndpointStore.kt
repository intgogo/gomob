package io.gomob.network

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gomob.common.net.Ipv4AddressDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 服务端网关地址 — IP + 端口。
 *
 * 设计：App 仅配置一个网关地址，后端把请求反代到内部各服务（auth / device / asset ...）。
 * 不在 App 端枚举每个微服务的 host —— 那是网关 / 反向代理的职责。
 *
 * `baseUrl()` 末尾带 "/"，符合 Retrofit baseUrl 约定。
 */
data class ServerEndpoint(val ip: String, val port: Int) {
    fun baseUrl(): String = "http://$ip:$port/"
    fun display(): String = "$ip:$port"
}

private val Context.serverEndpointDataStore by preferencesDataStore(name = "gomob_server_endpoint")

/**
 * 服务端地址持久化 — DataStore Preferences。
 *
 * 单一真理源 (SSOT)：登录页 DiagnosticStrip 的"服务端 IP:端口"展示，
 * 网络设置页的编辑表单，OkHttp 拦截器请求时改写 host:port，全部读这一份。
 *
 * 默认 `127.0.0.1:8808` —— 仅 emulator + adb reverse 场景能直接通；
 * 真机首次安装必须由用户在登录页或网络设置改成局域网网关 IP。
 */
@Singleton
class ServerEndpointStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keyIp = stringPreferencesKey("server_ip")
    private val keyPort = intPreferencesKey("server_port")

    val endpointFlow: Flow<ServerEndpoint> =
        context.serverEndpointDataStore.data.map {
            ServerEndpoint(
                ip = it[keyIp] ?: DEFAULT_IP,
                port = it[keyPort] ?: DEFAULT_PORT,
            )
        }

    /**
     * 同步读 —— 给 OkHttp 拦截器在请求线程上用。频率为每次出站请求一次，
     * runBlocking 影响可接受（与 [io.gomob.data.auth.TokenStore.currentAccessToken] 同思路）。
     */
    fun current(): ServerEndpoint = runBlocking { endpointFlow.first() }

    suspend fun set(ip: String, port: Int) {
        val normalizedIp = Ipv4AddressDraft.from(ip).normalizedOrNull()
            ?: throw IllegalArgumentException("invalid IPv4 address: $ip")
        require(port in 1..65535) { "port must be in 1..65535: $port" }
        context.serverEndpointDataStore.edit {
            it[keyIp] = normalizedIp
            it[keyPort] = port
        }
    }

    companion object {
        const val DEFAULT_IP = "127.0.0.1"
        const val DEFAULT_PORT = 8808
    }
}
