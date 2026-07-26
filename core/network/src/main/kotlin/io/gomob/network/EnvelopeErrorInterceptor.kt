package io.gomob.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Response

/**
 * 当后端返回非 2xx 或 envelope.code != 0 时，转成 [ApiException]。
 *
 * 实现：只 peek 固定上限的响应前缀。成功响应的原 body 原样交给 Retrofit；错误同时兼容平台
 * envelope（code/message）与 laserworker 等子服务的普通 {"error":"..."} 形状。
 */
internal class EnvelopeErrorInterceptor(
    private val tokenProvider: TokenProvider,
) : Interceptor {
    private val json = Json { ignoreUnknownKeys = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val resp = chain.proceed(request)
        // WebSocket 握手的成功状态是 101，不属于 HTTP 2xx。该响应由 OkHttp 的
        // RealWebSocket 消费，不能按普通 REST 错误信封解析，否则实时通道永远无法建立。
        if (resp.code == 101) {
            return resp
        }
        val body = resp.body
        if (body == null) {
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, resp.code, httpStatusMessage(resp))
            }
            return resp
        }
        val mt = body.contentType()
        // 二进制/流式响应（如融合结果 GLB,model/gltf-binary;@Streaming 大文件）直接放过，
        // 不调 body.bytes() 全量缓冲——否则数百 MB 模型会被读进内存致 OOM 且废掉 @Streaming。
        // 仅 json / text / 未知类型(mt==null,保留 startsWith("{") 兜底)才 peek 解析错误信封。
        if (resp.isSuccessful && mt != null && mt.subtype != "json" && mt.type != "text") {
            return resp
        }
        val text = runCatching { resp.peekBody(ERROR_PEEK_BYTES).string() }
            .getOrElse {
                if (resp.isSuccessful) return resp
                resp.close()
                throw ApiException(resp.code, resp.code, httpStatusMessage(resp))
            }
        val obj = if (mt?.subtype == "json" || text.trimStart().startsWith("{")) {
            runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        } else {
            null
        }
        val envelopeCode = obj?.get("code")?.jsonPrimitive?.intOrNull
        val apiErr = when {
            envelopeCode != null && envelopeCode != 0 -> ApiException(
                code = envelopeCode,
                httpStatus = resp.code,
                message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误",
                traceId = obj["trace_id"]?.jsonPrimitive?.contentOrNull,
            )
            !resp.isSuccessful -> ApiException(
                code = envelopeCode?.takeIf { it != 0 } ?: resp.code,
                httpStatus = resp.code,
                message = obj?.get("error")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("message")?.jsonPrimitive?.contentOrNull
                    ?: text.trim().takeIf { it.isNotEmpty() }?.take(MAX_ERROR_MESSAGE_CHARS)
                    ?: httpStatusMessage(resp),
                traceId = obj?.get("trace_id")?.jsonPrimitive?.contentOrNull,
            )
            else -> null
        }
        if (apiErr != null) {
            // 仅当请求实际带了 Authorization header 时，40102 才是"会话过期"。
            // 未登录态的请求（如 LogSyncManager 启动期间）也会被 server 拒成 40102，
            // 但语义上不是用户会话过期，不能触发 LoginScreen 弹"登录已过期"。
            val priorAuth = request.header("Authorization")
            if (apiErr.isAuthExpired && !priorAuth.isNullOrBlank()) {
                // access token 过期：先用 refresh token 静默续期并重发原请求；
                // 仅当续期失败（refresh 也过期/不存在）才真正 expireSession 触发重新登录。
                val newAccess = tokenProvider.refreshAccessToken()
                if (!newAccess.isNullOrBlank() && "Bearer $newAccess" != priorAuth) {
                    resp.close()
                    val retried = request.newBuilder()
                        .header("Authorization", "Bearer $newAccess")
                        .build()
                    return chain.proceed(retried)
                }
                tokenProvider.onAuthExpired(apiErr.message)
            }
            resp.close()
            throw apiErr
        }

        return resp
    }

    private val kotlinx.serialization.json.JsonPrimitive.intOrNull
        get() = content.toIntOrNull()
    private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
        get() = if (isString) content else content.takeIf { it != "null" }

    private fun httpStatusMessage(resp: Response): String =
        resp.message.takeIf { it.isNotBlank() }?.let { "HTTP ${resp.code} $it" } ?: "HTTP ${resp.code}"

    private companion object {
        const val ERROR_PEEK_BYTES = 64L * 1024L
        const val MAX_ERROR_MESSAGE_CHARS = 1_000
    }
}
