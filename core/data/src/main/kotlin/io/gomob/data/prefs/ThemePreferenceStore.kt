package io.gomob.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 外观偏好 — 跟随系统 / 深色 / 浅色。
 *
 * 与 Token / 网络配置分开存储,避免登出时被清掉。
 *
 * 默认值是 [Dark] 而不是 [System] — mob3d 设计语言"工业科技风"是 dark-first,
 * 首次安装直接给 OLED 黑底,浅色是可选退化。
 */
enum class ThemeMode(val key: String) {
    System("system"),
    Dark("dark"),
    Light("light"),
    ;

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.firstOrNull { it.key == key } ?: Dark
    }
}

private val Context.themeDataStore by preferencesDataStore(name = "gomob_appearance")

@Singleton
class ThemePreferenceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val keyMode = stringPreferencesKey("theme_mode")

    val modeFlow: Flow<ThemeMode> =
        context.themeDataStore.data.map { ThemeMode.fromKey(it[keyMode]) }

    suspend fun setMode(mode: ThemeMode) {
        context.themeDataStore.edit { it[keyMode] = mode.key }
    }
}
