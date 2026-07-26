package io.gomob.data.prefs

import android.content.Context
import androidx.datastore.core.DataMigration
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 主题偏好 — 应用固定浅色，仅持久化色彩主题（Mint/Gold/…）。
 *
 * 与 Token / 网络配置分开存储,避免登出时被清掉。
 *
 * 默认值：色彩主题 key "mint"，首次安装走浅色 + 薄荷青绿。
 *
 * Note: 这里色彩主题用 String key 持久化（不直接依赖 designsystem 的 enum），
 *   feature/AppearanceViewModel 负责 String ↔ ColorScheme 映射。
 */
internal val legacyThemeModeKey = stringPreferencesKey("theme_mode")
internal val colorSchemeKey = stringPreferencesKey("color_scheme")

internal object LegacyThemeModeMigration : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        currentData[legacyThemeModeKey] != null

    override suspend fun migrate(currentData: Preferences): Preferences =
        currentData.toMutablePreferences().apply { remove(legacyThemeModeKey) }

    override suspend fun cleanUp() = Unit
}

private val Context.themeDataStore by preferencesDataStore(
    name = "gomob_appearance",
    produceMigrations = { listOf(LegacyThemeModeMigration) },
)

@Singleton
class ThemePreferenceStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** 色彩主题 key — 默认 "mint"；feature 层做 String ↔ ColorScheme enum 映射 */
    val colorSchemeKeyFlow: Flow<String> =
        context.themeDataStore.data.map { it[colorSchemeKey] ?: DEFAULT_COLOR_SCHEME_KEY }

    suspend fun setColorSchemeKey(key: String) {
        context.themeDataStore.edit { it[colorSchemeKey] = key }
    }

    companion object {
        const val DEFAULT_COLOR_SCHEME_KEY = "mint"
    }
}
