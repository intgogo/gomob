package io.gomob.network

import io.gomob.network.dto.LoginRequest
import io.gomob.network.dto.LoginResponse
import io.gomob.network.dto.RefreshRequest
import io.gomob.network.dto.RefreshResponse
import io.gomob.network.dto.RegisterRequest
import io.gomob.network.dto.RegisterResponse
import io.gomob.network.dto.UserDto
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

interface AuthApi {

    @POST("v1/auth/register")
    suspend fun register(@Body req: RegisterRequest): Envelope<RegisterResponse>

    @POST("v1/auth/login")
    suspend fun login(@Body req: LoginRequest): Envelope<LoginResponse>

    @POST("v1/auth/refresh")
    suspend fun refresh(@Body req: RefreshRequest): Envelope<RefreshResponse>

    @GET("v1/me")
    suspend fun me(): Envelope<UserDto>
}
