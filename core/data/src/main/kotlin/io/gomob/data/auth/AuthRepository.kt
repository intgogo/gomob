package io.gomob.data.auth

import io.gomob.model.user.UserProfile
import io.gomob.network.ApiException
import io.gomob.network.AuthApi
import io.gomob.network.dto.LoginRequest
import io.gomob.network.dto.RegisterRequest
import io.gomob.network.dto.UserDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val api: AuthApi,
    private val tokenStore: TokenStore,
) {

    /** 是否已登录（access_token 是否存在）。 */
    val isLoggedIn: Flow<Boolean> = tokenStore.accessTokenFlow.map { !it.isNullOrEmpty() }

    val sessionNotice: Flow<String?> = tokenStore.sessionNoticeFlow

    /**
     * 登录 — 成功后写 token；失败抛 [ApiException]（含 code 区分 40101 / 40104）。
     */
    suspend fun login(username: String, password: String): UserProfile {
        val resp = api.login(LoginRequest(username, password))
        val data = resp.data ?: throw ApiException(50001, 500, "服务端响应缺数据")
        tokenStore.save(data.accessToken, data.refreshToken)
        return data.user.toDomain()
    }

    /** 注册 — 不持久化任何 token；返回 message 给 UI 展示（含审核提示）。 */
    suspend fun register(
        username: String,
        password: String,
        realName: String,
        employeeId: String,
        stationNameHint: String,
        note: String?,
    ): String {
        val resp = api.register(
            RegisterRequest(
                username = username,
                password = password,
                realName = realName,
                employeeId = employeeId,
                stationNameHint = stationNameHint,
                note = note,
            ),
        )
        val data = resp.data ?: throw ApiException(50001, 500, "服务端响应缺数据")
        return data.message
    }

    /** 拉自身资料 — 用于 profile tab 替换硬编码沈海明 / auth gate 鉴权探测。 */
    suspend fun me(): UserProfile {
        val resp = api.me()
        val data = resp.data ?: throw ApiException(50001, 500, "/v1/me 响应缺数据")
        return data.toDomain()
    }

    suspend fun logout() {
        tokenStore.clear()
    }

    suspend fun clearSessionNotice() {
        tokenStore.clearSessionNotice()
    }
}

private fun UserDto.toDomain(): UserProfile = UserProfile(
    id = id,
    username = username,
    realName = realName,
    employeeId = employeeId,
    role = role,
    stationName = station?.name,
)
