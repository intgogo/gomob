package io.gomob.logging

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.logSyncDataStore by preferencesDataStore(name = "log_sync_prefs")

/**
 * 「日志同步」开关 — 用户在 Profile 设置里手动开启 / 关闭。
 * Debug 包默认开启，便于模拟器 / 真机问题直接落服务端日志；用户显式关闭后保留关闭状态。
 *
 * 持久化到 DataStore Preferences，进程重启保留。
 */
@Singleton
class LogSyncPreferences @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    private val keyEnabled = booleanPreferencesKey("enabled")

    /** 当前是否启用日志同步（响应式 Flow，UI 切换 Switch 时立即反映） */
    val enabledFlow: Flow<Boolean> = ctx.logSyncDataStore.data.map { it[keyEnabled] ?: defaultEnabled }

    suspend fun setEnabled(enabled: Boolean) {
        ctx.logSyncDataStore.edit { it[keyEnabled] = enabled }
    }

    private val defaultEnabled: Boolean
        get() = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
}
