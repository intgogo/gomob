package io.gomob.scan

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import io.gomob.logging.LogSyncManager
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class GomobApplication : Application() {

    @Inject lateinit var logSync: LogSyncManager

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        // 启动开关监听 + drainer；用户在 Profile 里切到 ON 后真正抓 logcat
        logSync.start()
    }
}
