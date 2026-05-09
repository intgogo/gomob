package io.gomob.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tokenDataStore by preferencesDataStore(name = "gomob_token")

/**
 * Token 持久化 — DataStore Preferences。
 *
 * - access_token：每 2h 过期，登录后即写
 * - refresh_token：7d 有效
 *
 * 同步读 [currentAccessToken] 走 runBlocking — 调用频率低（每次请求一次），
 * 影响可忽略；如有性能问题再换成 in-memory cache + flow 同步。
 */
@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keyAccess = stringPreferencesKey("access_token")
    private val keyRefresh = stringPreferencesKey("refresh_token")
    private val keySessionNotice = stringPreferencesKey("session_notice")

    val accessTokenFlow: Flow<String?> =
        context.tokenDataStore.data.map { it[keyAccess] }

    val sessionNoticeFlow: Flow<String?> =
        context.tokenDataStore.data.map { it[keySessionNotice] }

    val currentUserIdFlow: Flow<Long?> =
        accessTokenFlow.map { it.accessTokenUserId() }

    suspend fun save(access: String, refresh: String) {
        context.tokenDataStore.edit {
            it[keyAccess] = access
            it[keyRefresh] = refresh
            it.remove(keySessionNotice)
        }
    }

    suspend fun clear() {
        context.tokenDataStore.edit {
            it.remove(keyAccess)
            it.remove(keyRefresh)
        }
    }

    suspend fun expireSession(message: String) {
        context.tokenDataStore.edit {
            it.remove(keyAccess)
            it.remove(keyRefresh)
            it[keySessionNotice] = message.ifBlank { "登录已超时，请重新登录" }
        }
    }

    suspend fun clearSessionNotice() {
        context.tokenDataStore.edit {
            it.remove(keySessionNotice)
        }
    }

    suspend fun accessTokenSuspend(): String? = accessTokenFlow.first()

    /** 给网络层 TokenProvider 接口同步使用。 */
    fun currentAccessToken(): String? = runBlocking { accessTokenSuspend() }

    fun currentUserId(): Long? = runBlocking { currentUserIdFlow.first() }

    suspend fun refreshTokenSuspend(): String? =
        context.tokenDataStore.data.map { it[keyRefresh] }.first()
}

private fun String?.accessTokenUserId(): Long? {
    val token = this ?: return null
    val payload = token.split('.').getOrNull(1) ?: return null
    return runCatching {
        val padding = (4 - payload.length % 4) % 4
        val normalized = payload + "=".repeat(padding)
        val json = Base64.getUrlDecoder().decode(normalized).decodeToString()
        Json.parseToJsonElement(json).jsonObject["uid"]?.jsonPrimitive?.longOrNull
    }.getOrNull()
}
