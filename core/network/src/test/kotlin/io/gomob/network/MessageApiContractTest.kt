package io.gomob.network

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import retrofit2.http.DELETE

class MessageApiContractTest {

    @Test
    fun deleteConversationUsesConversationDeleteEndpoint() {
        val method = MessageApi::class.java.declaredMethods.single { it.name == "deleteConversation" }
        val delete = method.getAnnotation(DELETE::class.java)

        assertThat(delete).isNotNull()
        assertThat(delete?.value).isEqualTo("v1/conversations/{id}")
    }
}
