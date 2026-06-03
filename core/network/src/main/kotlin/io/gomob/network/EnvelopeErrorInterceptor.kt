package io.gomob.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * 当后端返回非 2xx 或 envelope.code != 0 时，转成 [ApiException]。
 *
 * 实现：把 body 复制出来 peek 解析 code/message，若是错误则 throw；否则把 body 重新塞回去给下游 Retrofit converter。
 */
internal class EnvelopeErrorInterceptor(
    private val tokenProvider: TokenProvider,
) : Interceptor {
    private val json = Json { ignoreUnknownKeys = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val resp = chain.proceed(request)
        val body = resp.body ?: return resp
        val mt = body.contentType()
        // 二进制/流式响应（如融合结果 GLB,model/gltf-binary;@Streaming 大文件）直接放过，
        // 不调 body.bytes() 全量缓冲——否则数百 MB 模型会被读进内存致 OOM 且废掉 @Streaming。
        // 仅 json / text / 未知类型(mt==null,保留 startsWith("{") 兜底)才 peek 解析错误信封。
        if (mt != null && mt.subtype != "json" && mt.type != "text") {
            return resp
        }
        val raw = body.bytes()
        val text = raw.toString(Charsets.UTF_8)

        // 非 JSON 直接放过
        if (mt?.subtype != "json" && !text.trimStart().startsWith("{")) {
            return resp.newBuilder().body(raw.toResponseBody(mt)).build()
        }

        val apiErr: ApiException? = try {
            val obj = json.parseToJsonElement(text).jsonObject
            val code = obj["code"]?.jsonPrimitive?.intOrNull
            if (code != null && code != 0) {
                ApiException(
                    code = code,
                    httpStatus = resp.code,
                    message = obj["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误",
                    traceId = obj["trace_id"]?.jsonPrimitive?.contentOrNull,
                )
            } else null
        } catch (_: Exception) {
            // JSON 解析失败 → 当成正常响应继续
            null
        }
        if (apiErr != null) {
            // 仅当请求实际带了 Authorization header 时，40102 才是"会话过期"。
            // 未登录态的请求（如 LogSyncManager 启动期间）也会被 server 拒成 40102，
            // 但语义上不是用户会话过期，不能触发 LoginScreen 弹"登录已过期"。
            if (apiErr.isAuthExpired && !request.header("Authorization").isNullOrBlank()) {
                tokenProvider.onAuthExpired(apiErr.message)
            }
            throw apiErr
        }

        return resp.newBuilder().body(raw.toResponseBody(mt)).build()
    }

    private val kotlinx.serialization.json.JsonPrimitive.intOrNull
        get() = content.toIntOrNull()
    private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
        get() = if (isString) content else content.takeIf { it != "null" }
}
