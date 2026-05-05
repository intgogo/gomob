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
internal class EnvelopeErrorInterceptor : Interceptor {
    private val json = Json { ignoreUnknownKeys = true }

    override fun intercept(chain: Interceptor.Chain): Response {
        val resp = chain.proceed(chain.request())
        val body = resp.body ?: return resp
        val mt = body.contentType()
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
        if (apiErr != null) throw apiErr

        return resp.newBuilder().body(raw.toResponseBody(mt)).build()
    }

    private val kotlinx.serialization.json.JsonPrimitive.intOrNull
        get() = content.toIntOrNull()
    private val kotlinx.serialization.json.JsonPrimitive.contentOrNull: String?
        get() = if (isString) content else content.takeIf { it != "null" }
}
