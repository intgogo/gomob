package io.gomob.realtime

import android.util.Log
import io.gomob.network.ServerEndpointStore
import io.gomob.network.TokenProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
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
    private companion object {
        const val TAG = "RealtimeSocketClient"
        const val INBOUND_TEXT_BUFFER = 512
        const val EVENT_BUFFER = 512
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: WebSocket? = null
    private var reconnect = false
    private var attempt = 0

    private val mutableState = MutableStateFlow(RealtimeConnectionState.Disconnected)
    val state: StateFlow<RealtimeConnectionState> = mutableState

    private val inboundTexts = Channel<String>(capacity = INBOUND_TEXT_BUFFER)

    private val mutableEvents = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<RealtimeEvent> = mutableEvents

    init {
        scope.launch {
            for (text in inboundTexts) {
                mutableEvents.emit(parseEvent(text))
            }
        }
    }

    fun connect() {
        reconnect = true
        if (socket != null && mutableState.value != RealtimeConnectionState.Disconnected) {
            return
        }
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
            Log.w(TAG, "实时通道未连接：缺少 access token")
            return
        }
        val endpoint = endpointStore.current()
        val url = HttpUrl.Builder()
            .scheme("http")
            .host(endpoint.ip)
            .port(endpoint.port)
            .addPathSegments("v1/ws")
            .addQueryParameter("token", token)
            .build()
        mutableState.value = RealtimeConnectionState.Connecting
        Log.i(TAG, "实时通道连接中 endpoint=${endpoint.display()}")
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
            Log.i(TAG, "实时通道已连接")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!inboundTexts.trySend(text).isSuccess) {
                Log.w(TAG, "实时帧接收队列已满，丢弃一帧")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            mutableState.value = RealtimeConnectionState.Disconnected
            Log.i(TAG, "实时通道已断开 code=$code reason=$reason")
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            mutableState.value = RealtimeConnectionState.Disconnected
            Log.w(TAG, "实时通道异常: ${t.message}")
            scheduleReconnect()
        }
    }

    private fun parseEvent(text: String): RealtimeEvent =
        runCatching { parser.toEvent(parser.parse(text)) }
            .getOrElse {
                Log.w(TAG, "实时帧解析失败: ${it.message}")
                RealtimeEvent.Unknown(
                    RealtimeEnvelope(
                        type = "parse_error",
                        message = it.message,
                    ),
                )
            }
}
