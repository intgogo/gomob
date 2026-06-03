package io.gomob.network

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(
        tokenProvider: TokenProvider,
        hostSelection: HostSelectionInterceptor,
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        return OkHttpClient.Builder()
            // 顺序: HostSelection 必须在最前 —— 改完 host:port 再走 Auth/Envelope/Logging
            .addInterceptor(hostSelection)
            .addInterceptor(AuthInterceptor(tokenProvider))
            .addInterceptor(EnvelopeErrorInterceptor(tokenProvider))
            .addInterceptor(logging)
            // ping/healthz 路径短，统一短超时让 UI 反馈快
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(okHttp: OkHttpClient, json: Json): Retrofit {
        val mt = "application/json; charset=utf-8".toMediaType()
        return Retrofit.Builder()
            // baseUrl 是占位 —— 真正的 host:port 由 HostSelectionInterceptor 动态改写
            .baseUrl(NetworkConfig.PLACEHOLDER_BASE_URL)
            .client(okHttp)
            .addConverterFactory(json.asConverterFactory(mt))
            .build()
    }

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideHealthApi(retrofit: Retrofit): HealthApi = retrofit.create(HealthApi::class.java)

    @Provides
    @Singleton
    fun provideLogsApi(retrofit: Retrofit): LogsApi = retrofit.create(LogsApi::class.java)

    @Provides
    @Singleton
    fun provideMessageApi(retrofit: Retrofit): MessageApi = retrofit.create(MessageApi::class.java)

    @Provides
    @Singleton
    fun provideAssetApi(retrofit: Retrofit): AssetApi = retrofit.create(AssetApi::class.java)

    @Provides
    @Singleton
    fun provideMediaApi(retrofit: Retrofit): MediaApi = retrofit.create(MediaApi::class.java)

    @Provides
    @Singleton
    fun provideScanApi(retrofit: Retrofit): ScanApi = retrofit.create(ScanApi::class.java)

    @Provides
    @Singleton
    fun provideCVEngineApi(retrofit: Retrofit): CVEngineApi = retrofit.create(CVEngineApi::class.java)
}
