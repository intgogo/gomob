package io.gomob.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String,
)

@Serializable
data class RegisterRequest(
    val username: String,
    val password: String,
    @SerialName("real_name") val realName: String,
    @SerialName("employee_id") val employeeId: String,
    @SerialName("station_name_hint") val stationNameHint: String? = null,
    val note: String? = null,
)

@Serializable
data class RegisterResponse(
    @SerialName("user_id") val userId: String,
    val status: String,
    val message: String,
)

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    val user: UserDto,
)

@Serializable
data class RefreshRequest(
    @SerialName("refresh_token") val refreshToken: String,
)

@Serializable
data class RefreshResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("expires_in") val expiresIn: Long,
)

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    @SerialName("real_name") val realName: String,
    @SerialName("employee_id") val employeeId: String,
    val role: String,
    val station: StationDto? = null,
)

@Serializable
data class StationDto(
    val id: String,
    val name: String,
)
