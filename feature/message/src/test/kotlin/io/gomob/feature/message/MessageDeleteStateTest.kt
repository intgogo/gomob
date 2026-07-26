package io.gomob.feature.message

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageDeleteStateTest {

    @Test
    fun longPressSameConversationTogglesDeleteState() {
        assertThat(nextConversationDeleteTarget(current = null, pressed = 7)).isEqualTo(7)
        assertThat(nextConversationDeleteTarget(current = 7, pressed = 7)).isNull()
    }

    @Test
    fun longPressAnotherConversationMovesDeleteState() {
        assertThat(nextConversationDeleteTarget(current = 7, pressed = 9)).isEqualTo(9)
    }
}
