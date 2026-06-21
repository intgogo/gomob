package io.gomob.data.auth

import android.util.Log
import io.gomob.network.ApiException
import io.gomob.network.AuthApi
import io.gomob.network.TokenProvider
import io.gomob.network.dto.RefreshRequest
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/** 把 TokenStore 暴露给 :core:network 用。 */
@Singleton
class TokenProviderImpl @Inject constructor(
    private val tokenStore: TokenStore,
    private val authApi: AuthApi,
) : TokenProvider {
    private val refreshLock = Any()

    override fun currentAccessToken(): String? = tokenStore.currentAccessToken()

    /**
     * 同步静默续期：在 OkHttp 请求线程上被多个并发请求触发，用 [refreshLock] 串行化，
     * 进锁后先 double-check —— 若 access token 已被先到的线程刷新过（与触发本次的旧值不同），
     * 直接复用，不重复打 refresh 端点（避免刷新风暴 + refresh token 轮换被废）。
     */
    override fun refreshAccessToken(): String? {
        val staleAccess = tokenStore.currentAccessToken()
        synchronized(refreshLock) {
            // double-check：进锁后 token 已变 → 其它线程刚刷过，直接用新值。
            val nowAccess = tokenStore.currentAccessToken()
            if (!nowAccess.isNullOrBlank() && nowAccess != staleAccess) {
                return nowAccess
            }
            val refresh = tokenStore.currentRefreshToken()
            if (refresh.isNullOrBlank()) return null
            return runBlocking {
                runCatching {
                    val resp = authApi.refresh(RefreshRequest(refreshToken = refresh))
                    val data = resp.data
                        ?: throw ApiException(50001, 500, "refresh 响应缺数据")
                    tokenStore.save(data.accessToken, data.refreshToken)
                    data.accessToken
                }.getOrElse { e ->
                    // refresh token 也过期/无效 → 续期失败，让上层进入会话过期路径。
                    Log.w("TokenProviderImpl", "静默续期失败: ${e.message}")
                    null
                }
            }
        }
    }

    override fun onAuthExpired(message: String) {
        runBlocking {
            tokenStore.expireSession(message)
        }
    }
}
