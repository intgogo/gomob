package io.gomob.realtime

import android.util.Log
import io.gomob.network.ServerEndpointStore
import io.gomob.network.TokenProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
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

    // 单线程 confinement：socket / reconnect 的读写全部 post 到这条独立线程，
    // connect/disconnect 从任意线程调用、WebSocketListener 回调从 OkHttp 线程触发，
    // 但对连接状态字段的实际访问只发生在 controlDispatcher 上，杜绝数据竞争。
    private val controlDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "realtime-socket-control").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + controlDispatcher)

    // 仅在 controlDispatcher 线程上访问。
    private var socket: WebSocket? = null
    private var reconnect = false

    // 重连尝试计数 —— scheduleReconnect 与 onOpen 可能在不同线程时序触发，用原子量。
    private val attempt = AtomicInteger(0)

    private val mutableState = MutableStateFlow(RealtimeConnectionState.Disconnected)
    val state: StateFlow<RealtimeConnectionState> = mutableState

    private val inboundTexts = Channel<String>(capacity = INBOUND_TEXT_BUFFER)

    private val mutableEvents = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = EVENT_BUFFER)
    val events: SharedFlow<RealtimeEvent> = mutableEvents

    init {
        // 事件解析消费跑在 IO，不占 controlDispatcher。
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            for (text in inboundTexts) {
                mutableEvents.emit(parseEvent(text))
            }
        }
    }

    fun connect() {
        scope.launch {
            reconnect = true
            if (socket != null && mutableState.value != RealtimeConnectionState.Disconnected) {
                return@launch
            }
            openSocket()
        }
    }

    fun disconnect() {
        scope.launch {
            reconnect = false
            socket?.close(1000, "client disconnect")
            socket = null
            mutableState.value = RealtimeConnectionState.Disconnected
        }
    }

    fun send(envelope: RealtimeEnvelope): Boolean {
        val text = parser.encode(envelope)
        // socket?.send 自身线程安全（OkHttp 内部加锁）；这里读 socket 引用可能稍旧，
        // 但 send 失败返回 false 由上层处理，不引入状态字段竞争。
        return socket?.send(text) == true
    }

    /** 必须在 controlDispatcher 线程上调用。 */
    private fun openSocket() {
        val token = tokenProvider.currentAccessToken()
        if (token.isNullOrBlank()) {
            mutableState.value = RealtimeConnectionState.Disconnected
            Log.w(TAG, "实时通道未连接：缺少 access token")
            return
        }
        val endpoint = endpointStore.current()
        val url = HttpUrl.Builder()
            // OkHttp WebSocket 用 http/https scheme（内部升级 ws/wss），跟随 endpoint.tls。
            .scheme(endpoint.httpScheme)
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

    /** 必须在 controlDispatcher 线程上调用（经 scope.launch 入队）。 */
    private fun scheduleReconnect() {
        if (!reconnect) return
        val delayMs = reconnectPolicy.delayMillis(attempt.getAndIncrement())
        scope.launch {
            delay(delayMs)
            if (reconnect) openSocket()
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt.set(0)
            scope.launch {
                mutableState.value = RealtimeConnectionState.Connected
            }
            Log.i(TAG, "实时通道已连接")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!inboundTexts.trySend(text).isSuccess) {
                Log.w(TAG, "实时帧接收队列已满，丢弃一帧")
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            scope.launch {
                socket = null
                mutableState.value = RealtimeConnectionState.Disconnected
                Log.i(TAG, "实时通道已断开 code=$code reason=$reason")
                scheduleReconnect()
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scope.launch {
                socket = null
                mutableState.value = RealtimeConnectionState.Disconnected
                Log.w(TAG, "实时通道异常: ${t.message}")
                scheduleReconnect()
            }
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
