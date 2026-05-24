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
 * 外观偏好 — 两个独立轴：外观模式（深色/浅色/跟随系统）× 色彩主题（Mint/Gold/…）
 *
 * 与 Token / 网络配置分开存储,避免登出时被清掉。
 *
 * 默认值：外观模式 [Dark]、色彩主题 key "mint" — dark-first，首次安装直接给暖石墨 + 薄荷青绿。
 *
 * Note: 这里色彩主题用 String key 持久化（不直接依赖 designsystem 的 enum），
 *   feature/AppearanceViewModel 负责 String ↔ ColorScheme 映射。
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
    private val keyColorScheme = stringPreferencesKey("color_scheme")

    val modeFlow: Flow<ThemeMode> =
        context.themeDataStore.data.map { ThemeMode.fromKey(it[keyMode]) }

    /** 色彩主题 key — 默认 "mint"；feature 层做 String ↔ ColorScheme enum 映射 */
    val colorSchemeKeyFlow: Flow<String> =
        context.themeDataStore.data.map { it[keyColorScheme] ?: DEFAULT_COLOR_SCHEME_KEY }

    suspend fun setMode(mode: ThemeMode) {
        context.themeDataStore.edit { it[keyMode] = mode.key }
    }

    suspend fun setColorSchemeKey(key: String) {
        context.themeDataStore.edit { it[keyColorScheme] = key }
    }

    companion object {
        const val DEFAULT_COLOR_SCHEME_KEY = "mint"
    }
}
