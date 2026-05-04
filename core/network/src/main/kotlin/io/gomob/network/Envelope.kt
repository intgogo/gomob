package io.gomob.network

import kotlinx.serialization.Serializable

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

/** 业务异常（带服务端 code + message） — 网络层抛给上层用。 */
class ApiException(
    val code: Int,
    val httpStatus: Int,
    override val message: String,
    val traceId: String? = null,
) : RuntimeException(message) {
    val isAuthExpired: Boolean get() = code == 40102
    val isLoginFailed: Boolean get() = code == 40101
    val isAccountInactive: Boolean get() = code == 40104
}
