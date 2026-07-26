package io.gomob.data.prefs

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ThemePreferenceStoreTest {
    @Test
    fun legacyDarkModeMigrationKeepsSelectedColorScheme() = runTest {
        val original = mutablePreferencesOf(
            legacyThemeModeKey to "dark",
            colorSchemeKey to "coral",
        )

        assertThat(LegacyThemeModeMigration.shouldMigrate(original)).isTrue()

        val migrated = LegacyThemeModeMigration.migrate(original)

        assertThat(migrated[legacyThemeModeKey]).isNull()
        assertThat(migrated[colorSchemeKey]).isEqualTo("coral")
    }

    @Test
    fun migrationSkipsDataWithoutLegacyMode() = runTest {
        val current = mutablePreferencesOf(colorSchemeKey to "gold")

        assertThat(LegacyThemeModeMigration.shouldMigrate(current)).isFalse()
        assertThat(ThemePreferenceStore.DEFAULT_COLOR_SCHEME_KEY).isEqualTo("mint")
    }
}
