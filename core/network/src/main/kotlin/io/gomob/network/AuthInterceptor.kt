package io.gomob.network

import okhttp3.Interceptor
import okhttp3.Response

/** 给所有请求带 Authorization: Bearer。 */
internal class AuthInterceptor(private val tokenProvider: TokenProvider) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val token = tokenProvider.currentAccessToken()
        val newReq = if (token != null) {
            req.newBuilder()
                .addHeader("Authorization", "Bearer $token")
                .addHeader("X-Gomob-Client", "android/0.1.0")
                .build()
        } else {
            req.newBuilder()
                .addHeader("X-Gomob-Client", "android/0.1.0")
                .build()
        }
        return chain.proceed(newReq)
    }
}
