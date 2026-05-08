package io.gomob.realtime

import io.gomob.network.ServerEndpointStore
import io.gomob.network.TokenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealtimeSocketClient @Inject constructor(
    private val okHttp: OkHttpClient,
    private val endpointStore: ServerEndpointStore,
    private val tokenProvider: TokenProvider,
    private val parser: RealtimeEnvelopeParser,
    private val reconnectPolicy: RealtimeReconnectPolicy,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    private var reconnect = false
    private var attempt = 0

    private val mutableState = MutableStateFlow(RealtimeConnectionState.Disconnected)
    val state: StateFlow<RealtimeConnectionState> = mutableState

    private val mutableEvents = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<RealtimeEvent> = mutableEvents

    fun connect() {
        reconnect = true
        openSocket()
    }

    fun disconnect() {
        reconnect = false
        socket?.close(1000, "client disconnect")
        socket = null
        mutableState.value = RealtimeConnectionState.Disconnected
    }

    fun send(envelope: RealtimeEnvelope): Boolean {
        val text = parser.encode(envelope)
        return socket?.send(text) == true
    }

    private fun openSocket() {
        val token = tokenProvider.currentAccessToken()
        if (token.isNullOrBlank()) {
            mutableState.value = RealtimeConnectionState.Disconnected
            return
        }
        val endpoint = endpointStore.current()
        val url = HttpUrl.Builder()
            .scheme("ws")
            .host(endpoint.ip)
            .port(endpoint.port)
            .addPathSegments("v1/ws")
            .addQueryParameter("token", token)
            .build()
        mutableState.value = RealtimeConnectionState.Connecting
        socket = okHttp.newWebSocket(
            Request.Builder().url(url).build(),
            Listener(),
        )
    }

    private fun scheduleReconnect() {
        if (!reconnect) return
        val delayMs = reconnectPolicy.delayMillis(attempt++)
        scope.launch {
            delay(delayMs)
            if (reconnect) openSocket()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            mutableState.value = RealtimeConnectionState.Connected
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = runCatching { parser.toEvent(parser.parse(text)) }
                .getOrElse {
                    RealtimeEvent.Unknown(
                        RealtimeEnvelope(
                            type = "parse_error",
                            message = it.message,
                        ),
                    )
                }
            mutableEvents.tryEmit(event)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            mutableState.value = RealtimeConnectionState.Disconnected
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            mutableState.value = RealtimeConnectionState.Disconnected
            scheduleReconnect()
        }
    }
}
