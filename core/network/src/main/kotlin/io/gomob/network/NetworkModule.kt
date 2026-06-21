package io.gomob.network

import dagger.Lazy
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
        // 用 dagger.Lazy 打断依赖环:provideOkHttp→OkHttp→Retrofit→AuthApi→TokenProviderImpl→TokenProvider。
        // TokenProviderImpl 注入 AuthApi(走本 OkHttp)做静默续期,直接注 TokenProvider 会成环;
        // 三个消费者都只在请求期(intercept/authenticate)用 token,故包一层 lazy 委托,图构建期不实例化。
        tokenProvider: Lazy<TokenProvider>,
        hostSelection: HostSelectionInterceptor,
    ): OkHttpClient {
        val lazyToken = object : TokenProvider {
            override fun currentAccessToken(): String? = tokenProvider.get().currentAccessToken()
            override fun refreshAccessToken(): String? = tokenProvider.get().refreshAccessToken()
            override fun onAuthExpired(message: String) = tokenProvider.get().onAuthExpired(message)
        }
        val logging = HttpLoggingInterceptor().apply {
            // BODY 会把流式 PCD/媒体响应读成字符串，百万级点云下载会直接触发 OOM。
            level = HttpLoggingInterceptor.Level.BASIC
        }
        return OkHttpClient.Builder()
            // 顺序: HostSelection 必须在最前 —— 改完 host:port 再走 Auth/Envelope/Logging
            .addInterceptor(hostSelection)
            .addInterceptor(AuthInterceptor(lazyToken))
            .addInterceptor(EnvelopeErrorInterceptor(lazyToken))
            .addInterceptor(logging)
            // 裸 HTTP 401 → 用 refresh token 静默续期重发；续期失败才会话过期。
            // envelope code==40102 由 EnvelopeErrorInterceptor 内联续期，两者共用 refreshAccessToken。
            .authenticator(TokenAuthenticator(lazyToken))
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
    fun provideLaserScanApi(retrofit: Retrofit): LaserScanApi = retrofit.create(LaserScanApi::class.java)

    @Provides
    @Singleton
    fun provideCVEngineApi(retrofit: Retrofit): CVEngineApi = retrofit.create(CVEngineApi::class.java)
}
