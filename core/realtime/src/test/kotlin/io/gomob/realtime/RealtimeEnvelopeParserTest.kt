package io.gomob.realtime

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class RealtimeEnvelopeParserTest {
    private val parser = RealtimeEnvelopeParser(
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        },
    )

    @Test
    fun unknownTypeBecomesUnknownEvent() {
        val event = parser.toEvent(
            parser.parse("""{"type":"new.future.event","payload":{"x":1},"frame_seq":9}"""),
        )

        assertThat(event).isInstanceOf(RealtimeEvent.Unknown::class.java)
        assertThat((event as RealtimeEvent.Unknown).envelope.type).isEqualTo("new.future.event")
    }

    @Test
    fun deliveredPayloadParses() {
        val event = parser.toEvent(
            parser.parse(
                """
                {
                  "type":"msg.delivered",
                  "payload":{
                    "client_msg_id":"c-1",
                    "conversation_id":7,
                    "server_seq":42,
                    "message_id":99,
                    "created_at":"2026-05-08T12:00:00Z"
                  }
                }
                """.trimIndent(),
            ),
        )

        assertThat(event).isEqualTo(
            RealtimeEvent.MessageDelivered(
                clientMsgId = "c-1",
                conversationId = 7,
                serverSeq = 42,
                messageId = 99,
                createdAt = "2026-05-08T12:00:00Z",
            ),
        )
    }
}
