package io.gomob.data.message

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
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

interface RealtimeMessageTransport {
    val state: StateFlow<RealtimeConnectionState>
    val events: SharedFlow<RealtimeEvent>

    fun connect()

    fun disconnect()

    fun sendMessage(
        conversationId: Long,
        kind: String,
        content: JsonElement,
        clientMsgId: String,
    ): Boolean
}

@Singleton
class RealtimeRepository @Inject constructor(
    private val socket: RealtimeSocketClient,
) : RealtimeMessageTransport {
    override val state: StateFlow<RealtimeConnectionState> = socket.state
    override val events: SharedFlow<RealtimeEvent> = socket.events

    override fun connect() = socket.connect()

    override fun disconnect() = socket.disconnect()

    override fun sendMessage(
        conversationId: Long,
        kind: String,
        content: JsonElement,
        clientMsgId: String,
    ): Boolean = socket.send(
        RealtimeEnvelope(
            type = "msg.send",
            payload = buildJsonObject {
                put("conversation_id", conversationId)
                put("kind", kind)
                put("content", content)
                put("client_msg_id", clientMsgId)
            },
        ),
    )
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RealtimeDataModule {
    @Binds
    abstract fun bindRealtimeMessageTransport(repository: RealtimeRepository): RealtimeMessageTransport
}
