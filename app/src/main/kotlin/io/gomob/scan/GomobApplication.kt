package io.gomob.scan

import android.app.Application
import android.os.Build
import android.util.Log
import androidx.camera.camera2.Camera2Config
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraXConfig
import dagger.hilt.android.HiltAndroidApp
import io.gomob.logging.LogSyncManager
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class GomobApplication : Application(), CameraXConfig.Provider {

    @Inject lateinit var logSync: LogSyncManager

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        Log.i("GomobApplication", "应用启动 debug=${BuildConfig.DEBUG}")
        // 启动开关监听 + drainer；用户在 Profile 里切到 ON 后真正抓 logcat
        logSync.start()
    }

    override fun getCameraXConfig(): CameraXConfig {
        val builder = CameraXConfig.Builder.fromConfig(Camera2Config.defaultConfig())
        if (isAndroidEmulator()) {
            builder.setAvailableCamerasLimiter(CameraSelector.DEFAULT_BACK_CAMERA)
        }
        return builder.build()
    }
}

private fun isAndroidEmulator(): Boolean =
    Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.startsWith("unknown") ||
        Build.MODEL.contains("Android SDK built for") ||
        Build.MODEL.contains("Emulator") ||
        Build.MANUFACTURER.contains("Genymotion") ||
        Build.HARDWARE.contains("goldfish") ||
        Build.HARDWARE.contains("ranchu")
