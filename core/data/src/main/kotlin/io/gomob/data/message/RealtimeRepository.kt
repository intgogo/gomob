package io.gomob.data.message

import io.gomob.realtime.RealtimeConnectionState
import io.gomob.realtime.RealtimeEnvelope
import io.gomob.realtime.RealtimeEvent
import io.gomob.realtime.RealtimeSocketClient
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealtimeRepository @Inject constructor(
    private val socket: RealtimeSocketClient,
) {
    val state: StateFlow<RealtimeConnectionState> = socket.state
    val events: SharedFlow<RealtimeEvent> = socket.events

    fun connect() = socket.connect()

    fun disconnect() = socket.disconnect()

    fun sendMessage(
        toUserId: Long,
        kind: String,
        content: JsonElement,
        clientMsgId: String,
    ): Boolean = socket.send(
        RealtimeEnvelope(
            type = "msg.send",
            payload = buildJsonObject {
                put("to_user_id", toUserId)
                put("kind", kind)
                put("content", content)
                put("client_msg_id", clientMsgId)
            },
        ),
    )
}
