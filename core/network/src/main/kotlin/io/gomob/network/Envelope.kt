package io.gomob.network

import kotlinx.serialization.Serializable
import java.io.IOException

/**
 * 服务端统一响应信封 — 详见 docs/architecture/server/02-api-contract.md §1.3。
 */
@Serializable
data class Envelope<T>(
    val code: Int,
    val data: T? = null,
    val message: String? = null,
    val trace_id: String? = null,
)

/**
 * 业务异常（带服务端 code + message） — 网络层抛给上层用。
 *
 * 继承 [IOException] 是为了让 OkHttp dispatcher 把它当成"正常网络错误"路径处理
 * （非 IOException 在 [okhttp3.internal.connection.RealCall.AsyncCall.run] 里会被 rethrow，
 * 导致整个 OkHttp Dispatcher 线程崩溃 → AndroidRuntime FATAL）。
 */
class ApiException(
    val code: Int,
    val httpStatus: Int,
    override val message: String,
    val traceId: String? = null,
) : IOException(message) {
    val isAuthExpired: Boolean get() = code == 40102
    val isLoginFailed: Boolean get() = code == 40101
    val isAccountInactive: Boolean get() = code == 40104
}
