package io.gomob.network

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * OkHttp [Authenticator] —— 服务端以 HTTP 401 拒绝时静默续期并重发原请求。
 *
 * Why: access token 每 2h 过期，旧实现里 [EnvelopeErrorInterceptor] 一遇 40102 直接
 * 强制登出（每 2h 踢一次）。本类用 refresh token 走 [TokenProvider.refreshAccessToken]
 * 续期后重发原请求；只有续期失败（返回 null）才真正进入会话过期路径。
 *
 * 与 [EnvelopeErrorInterceptor] 的分工：本类只处理"裸 HTTP 401"；envelope code==40102
 * （通常 HTTP 200 包错误信封）由 [EnvelopeErrorInterceptor] 内联续期处理。两者都委托
 * 同一个 [TokenProvider.refreshAccessToken]，刷新串行化在实现侧保证。
 */
internal class TokenAuthenticator(
    private val tokenProvider: TokenProvider,
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // 原请求没带 Authorization → 不是会话态请求，无从续期，放弃（返回 null 让 401 透出）。
        val priorAuth = response.request.header("Authorization")
        if (priorAuth.isNullOrBlank()) return null

        // 防无限重试：同一请求已被本 Authenticator 重发过一次仍 401 → 续期无效，放弃。
        if (responseCount(response) >= 2) return null

        val newAccess = tokenProvider.refreshAccessToken() ?: return null
        val newAuth = "Bearer $newAccess"
        // refresh 后 token 仍与原请求相同（并发场景下已被其它线程刷成同值）→ 不重试避免空转。
        if (newAuth == priorAuth) return null

        return response.request.newBuilder()
            .header("Authorization", newAuth)
            .build()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }
}
