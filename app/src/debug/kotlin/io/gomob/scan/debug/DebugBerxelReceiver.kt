package io.gomob.scan.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.gomob.nativebridge.berxel.BerxelService
import io.gomob.nativebridge.berxel.BerxelStackBackend
import io.gomob.nativebridge.berxel.BerxelStreamProfiles

class DebugBerxelReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val berxel = EntryPointAccessors
            .fromApplication(context.applicationContext, BerxelEntryPoint::class.java)
            .berxelService()
        when (intent.action) {
            ACTION_START -> {
                val stream = intent.getStringExtra(EXTRA_STREAM)?.lowercase()
                val profileName = intent.getStringExtra(EXTRA_PROFILE)?.lowercase()
                val profile = profileName?.let(BerxelStreamProfiles::fromName)
                if (intent.hasExtra(EXTRA_MASTER_RGB)) {
                    berxel.setNativeMasterStreamForDebug(intent.getBooleanExtra(EXTRA_MASTER_RGB, false))
                }
                if (intent.hasExtra(EXTRA_KA_MS)) {
                    berxel.setNativeKeepaliveMsForDebug(intent.getIntExtra(EXTRA_KA_MS, 50))
                }
                if (intent.hasExtra(EXTRA_DEPTH_FPS)) {
                    berxel.setNativeDepthFpsForDebug(intent.getIntExtra(EXTRA_DEPTH_FPS, 45))
                }
                if (intent.hasExtra(EXTRA_MIX_STRATEGY)) {
                    berxel.setNativeMixStrategyForDebug(intent.getStringExtra(EXTRA_MIX_STRATEGY))
                }
                if (intent.hasExtra(EXTRA_BACKEND)) {
                    parseBackend(intent.getStringExtra(EXTRA_BACKEND))?.let { backend ->
                        berxel.setBackendModeForDebug(backend)
                    }
                }
                if (profile != null) berxel.setStreamProfile(profile)
                Log.i(TAG, "debug 广播启动 Berxel stream=${stream ?: "dual"} profile=${profileName ?: "default"}")
                when (stream) {
                    "color" -> berxel.startColorOnlyForDebug()
                    "depth" -> berxel.startDepthOnlyForDebug()
                    "depth_mix" -> berxel.startDepthInMixModeForDebug()
                    "depth_halfstop" -> berxel.startDepthByDualHalfStopForDebug()
                    "dual_then_stop_color" -> berxel.startDepthByDualThenHalfStopColor()
                    "append_depth" -> berxel.tryAppendDepthForDebug()
                    "ir" -> berxel.startIrOnlyForDebug()
                    "ir_slave_true" -> berxel.startIrOnlySlaveTrueForDebug()
                    "ir_slave_false" -> berxel.startIrOnlySlaveFalseForDebug()
                    else -> berxel.start()
                }
                if (intent.hasExtra(EXTRA_DUMP_FRAMES)) {
                    val frames = intent.getIntExtra(EXTRA_DUMP_FRAMES, 1).coerceIn(1, 30)
                    berxel.triggerDump(frames)
                    Log.i(TAG, "debug 广播触发 Berxel dump frames=$frames")
                }
            }
            ACTION_STOP -> {
                Log.i(TAG, "debug 广播停止 Berxel")
                berxel.stop()
            }
            else -> Log.w(TAG, "未知 action=${intent.action}")
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BerxelEntryPoint {
        fun berxelService(): BerxelService
    }

    private fun parseBackend(value: String?): BerxelStackBackend? =
        when (value?.trim()?.lowercase()) {
            "sdk", "official", "official_sdk" -> BerxelStackBackend.SDK
            "native", "native_rewrite" -> BerxelStackBackend.NATIVE_REWRITE
            else -> null
        }

    private companion object {
        const val TAG = "DebugBerxelReceiver"
        const val ACTION_START = "io.gomob.scan.debug.DEBUG_BERXEL_START"
        const val ACTION_STOP = "io.gomob.scan.debug.DEBUG_BERXEL_STOP"
        const val EXTRA_STREAM = "stream"
        const val EXTRA_PROFILE = "profile"
        const val EXTRA_MASTER_RGB = "master_rgb"
        const val EXTRA_KA_MS = "ka_ms"
        const val EXTRA_DEPTH_FPS = "dfps"
        const val EXTRA_MIX_STRATEGY = "mix_strategy"
        const val EXTRA_BACKEND = "backend"
        const val EXTRA_DUMP_FRAMES = "dump"
    }
}
