package io.gomob.designsystem.theme

import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThemeColorTest {
    @Test
    fun invalidOrMissingKeyFallsBackToMint() {
        assertThat(ColorScheme.fromKey(null)).isEqualTo(ColorScheme.Mint)
        assertThat(ColorScheme.fromKey("unknown")).isEqualTo(ColorScheme.Mint)
    }

    @Test
    fun allFiveSchemesExposeOnlyLightPalettes() {
        assertThat(AllColorSchemes.map { it.scheme })
            .containsExactlyElementsIn(ColorScheme.entries)
            .inOrder()

        AllColorSchemes.forEach { set ->
            assertThat(set.colors.bg0.luminance()).isGreaterThan(0.8f)
            assertThat(set.colors.fg0.luminance()).isLessThan(0.1f)
            assertThat(colorSchemeSetOf(set.scheme)).isSameInstanceAs(set)
        }
    }
}
