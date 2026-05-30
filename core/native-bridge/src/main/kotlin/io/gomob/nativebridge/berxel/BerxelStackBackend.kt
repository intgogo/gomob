package io.gomob.nativebridge.berxel

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.gomob.nativebridge.BuildConfig
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Berxel 设备底层 stack 后端。
 *
 * - [SDK]：用厂商 `BerxelHawkContext`（libBerxelHawk.so + libuvc-0.0.7 + 自家 libusb stack）。
 *   主线生产路径，5 台测试机里只有 2510DRK44C 跑通 DUAL；其它 BSP 在双流时 host kill。
 * - [NATIVE_REWRITE]：用 [BerxelNativeStack]（libusb-1.0 + 自实现 Sonix XU 协议）。
 *   M1.6.6 实验路径，2026-05-27 sweep 在 vivo PD2324 上不能拿到持续流（≤100ms 内死），
 *   暂不作生产默认，但 DI 走 [NATIVE_REWRITE] 时 [BerxelService.start] 会改走该路径。
 *
 * 翻法：改 `core/native-bridge/build.gradle.kts` 的 `BERXEL_STACK_BACKEND` 字段重编，
 * 或在 App 启动时 `BerxelStackBackendOverride.set(NATIVE_REWRITE)`（仅 debug 流程用）。
 */
enum class BerxelStackBackend { SDK, NATIVE_REWRITE }

/**
 * 运行时覆盖（仅 debug / harness 用）— 不为 null 时优先于 [BuildConfig.BERXEL_STACK_BACKEND]。
 */
object BerxelStackBackendOverride {
    @Volatile
    private var override: BerxelStackBackend? = null
    fun set(b: BerxelStackBackend?) { override = b }
    fun get(): BerxelStackBackend? = override
}

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BerxelStack

@Module
@InstallIn(SingletonComponent::class)
object BerxelStackBackendModule {
    @Provides
    @Singleton
    @BerxelStack
    fun provideBackend(): BerxelStackBackend {
        BerxelStackBackendOverride.get()?.let { return it }
        return runCatching { BerxelStackBackend.valueOf(BuildConfig.BERXEL_STACK_BACKEND) }
            .getOrDefault(BerxelStackBackend.SDK)
    }
}
