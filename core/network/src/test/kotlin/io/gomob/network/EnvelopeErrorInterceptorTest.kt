package io.gomob.network

import com.google.common.truth.Truth.assertThat
import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import java.util.concurrent.TimeUnit

class EnvelopeErrorInterceptorTest {
    @Test
    fun authExpiredEnvelopeNotifiesTokenProviderAndThrowsApiException() {
        val tokenProvider = RecordingTokenProvider()
        val interceptor = EnvelopeErrorInterceptor(tokenProvider)
        val request = Request.Builder()
            .url("http://127.0.0.1/v1/me")
            .build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body(
                """{"code":40102,"message":"登录已过期，请重新登录"}"""
                    .toResponseBody("application/json; charset=utf-8".toMediaType()),
            )
            .build()

        val thrown = runCatching {
            interceptor.intercept(FakeChain(request, response))
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(ApiException::class.java)
        assertThat((thrown as ApiException).code).isEqualTo(40102)
        assertThat(tokenProvider.expiredMessages).containsExactly("登录已过期，请重新登录")
    }

    @Test
    fun nonAuthEnvelopeDoesNotExpireSession() {
        val tokenProvider = RecordingTokenProvider()
        val interceptor = EnvelopeErrorInterceptor(tokenProvider)
        val request = Request.Builder()
            .url("http://127.0.0.1/v1/auth/login")
            .build()
        val response = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .body(
                """{"code":40101,"message":"用户名或密码错误"}"""
                    .toResponseBody("application/json; charset=utf-8".toMediaType()),
            )
            .build()

        val thrown = runCatching {
            interceptor.intercept(FakeChain(request, response))
        }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(ApiException::class.java)
        assertThat((thrown as ApiException).code).isEqualTo(40101)
        assertThat(tokenProvider.expiredMessages).isEmpty()
    }
}

private class RecordingTokenProvider : TokenProvider {
    val expiredMessages = mutableListOf<String>()

    override fun currentAccessToken(): String? = "token"

    override fun onAuthExpired(message: String) {
        expiredMessages += message
    }
}

private class FakeChain(
    private val request: Request,
    private val response: Response,
) : Interceptor.Chain {
    override fun request(): Request = request

    override fun proceed(request: Request): Response = response

    override fun connection(): Connection? = null

    override fun call(): Call = unsupported()

    override fun connectTimeoutMillis(): Int = 0

    override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

    override fun readTimeoutMillis(): Int = 0

    override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

    override fun writeTimeoutMillis(): Int = 0

    override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this

    private fun unsupported(): Nothing = throw UnsupportedOperationException("FakeChain 不支持该调用")
}
