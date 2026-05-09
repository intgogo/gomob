package io.gomob.data.auth

import io.gomob.network.TokenProvider
import javax.inject.Inject
import javax.inject.Singleton

/** 把 TokenStore 暴露给 :core:network 用。 */
@Singleton
class TokenProviderImpl @Inject constructor(
    private val tokenStore: TokenStore,
) : TokenProvider {
    override fun currentAccessToken(): String? = tokenStore.currentAccessToken()

    override fun onAuthExpired(message: String) {
        kotlinx.coroutines.runBlocking {
            tokenStore.expireSession(message)
        }
    }
}
