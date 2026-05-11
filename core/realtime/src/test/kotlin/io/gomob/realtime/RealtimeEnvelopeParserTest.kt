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

    @Test
    fun recvPayloadParsesMessageIdentity() {
        val event = parser.toEvent(
            parser.parse(
                """
                {
                  "type":"msg.recv",
                  "payload":{
                    "message_id":100,
                    "conversation_id":7,
                    "server_seq":43,
                    "sender_id":31,
                    "kind":"text",
                    "content":{"text":"实时消息"},
                    "client_msg_id":"peer-c-1",
                    "created_at":"2026-05-08T12:00:01Z"
                  }
                }
                """.trimIndent(),
            ),
        )

        assertThat(event).isEqualTo(
            RealtimeEvent.MessageReceived(
                messageId = 100,
                conversationId = 7,
                serverSeq = 43,
                senderId = 31,
                kind = "text",
                content = parser.parse("""{"type":"x","payload":{"text":"实时消息"}}""").payload,
                clientMsgId = "peer-c-1",
                createdAt = "2026-05-08T12:00:01Z",
            ),
        )
    }

    @Test
    fun transcriptUpdatedPayloadParses() {
        val event = parser.toEvent(
            parser.parse(
                """
                {
                  "type":"msg.transcript.updated",
                  "payload":{
                    "message_id":100,
                    "conversation_id":7,
                    "server_seq":43,
                    "kind":"voice",
                    "content":{
                      "transcript_status":"done",
                      "transcript_normalized_text":"请复核第三工位"
                    },
                    "updated_at":"2026-05-08T12:00:03Z"
                  }
                }
                """.trimIndent(),
            ),
        )

        assertThat(event).isEqualTo(
            RealtimeEvent.TranscriptUpdated(
                messageId = 100,
                conversationId = 7,
                serverSeq = 43,
                kind = "voice",
                content = parser.parse(
                    """{"type":"x","payload":{"transcript_status":"done","transcript_normalized_text":"请复核第三工位"}}""",
                ).payload,
                updatedAt = "2026-05-08T12:00:03Z",
            ),
        )
    }
}
