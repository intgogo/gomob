package io.gomob.data.auth

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tokenDataStore by preferencesDataStore(name = "gomob_token")

/**
 * Token 持久化 — DataStore Preferences。
 *
 * - access_token：每 2h 过期，登录后即写；过期由 [refreshTokenSuspend] 配合网络层静默续期
 * - refresh_token：7d 有效
 *
 * 性能：[currentAccessToken] / [refreshTokenSuspend] 被 OkHttp 拦截器在每次出站请求线程上
 * 同步调用。为避免每请求一次 runBlocking 读 DataStore（磁盘 IO 在请求线程上放大尾延迟），
 * 维护内存缓存 [cachedAccess] / [cachedRefresh]：稳态命中缓存零阻塞，仅缓存未初始化时回退
 * 一次 runBlocking 读盘；DataStore flow 变更会 onEach 回灌缓存，写路径（save/clear/expire/
 * refresh）直接同步更新缓存，保证 SSOT 不漂移。
 */
@Singleton
class TokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keyAccess = stringPreferencesKey("access_token")
    private val keyRefresh = stringPreferencesKey("refresh_token")
    private val keySessionNotice = stringPreferencesKey("session_notice")

    // 内存缓存：null = 未初始化（需回退读盘）；持有 Holder 即已初始化（值可能为 null token）。
    private val cachedAccess = AtomicReference<Holder?>(null)
    private val cachedRefresh = AtomicReference<Holder?>(null)

    private class Holder(val value: String?)

    val accessTokenFlow: Flow<String?> =
        context.tokenDataStore.data
            .map { it[keyAccess] }
            .onEach { cachedAccess.set(Holder(it)) }

    val refreshTokenFlow: Flow<String?> =
        context.tokenDataStore.data
            .map { it[keyRefresh] }
            .onEach { cachedRefresh.set(Holder(it)) }

    val sessionNoticeFlow: Flow<String?> =
        context.tokenDataStore.data.map { it[keySessionNotice] }

    val currentUserIdFlow: Flow<Long?> =
        accessTokenFlow.map { it.accessTokenUserId() }

    suspend fun save(access: String, refresh: String) {
        cachedAccess.set(Holder(access))
        cachedRefresh.set(Holder(refresh))
        context.tokenDataStore.edit {
            it[keyAccess] = access
            it[keyRefresh] = refresh
            it.remove(keySessionNotice)
        }
    }

    suspend fun clear() {
        cachedAccess.set(Holder(null))
        cachedRefresh.set(Holder(null))
        context.tokenDataStore.edit {
            it.remove(keyAccess)
            it.remove(keyRefresh)
            // 主动 logout 时也清提示，避免下次启动残留 expireSession 写下的 notice 干扰 LoginScreen。
            it.remove(keySessionNotice)
        }
    }

    suspend fun expireSession(message: String) {
        cachedAccess.set(Holder(null))
        cachedRefresh.set(Holder(null))
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

    /** 给网络层 TokenProvider 接口同步使用：稳态命中内存缓存，未初始化才回退读盘。 */
    fun currentAccessToken(): String? =
        cachedAccess.get()?.value
            ?: runBlocking { accessTokenSuspend() }

    fun currentUserId(): Long? = runBlocking { currentUserIdFlow.first() }

    /** 给网络层续期用：稳态命中内存缓存，未初始化才回退读盘。 */
    fun currentRefreshToken(): String? =
        cachedRefresh.get()?.value
            ?: runBlocking { refreshTokenSuspend() }

    suspend fun refreshTokenSuspend(): String? = refreshTokenFlow.first()
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
