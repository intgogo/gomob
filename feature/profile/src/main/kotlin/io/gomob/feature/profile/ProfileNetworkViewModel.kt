package io.gomob.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.network.HealthProbe
import io.gomob.network.ServerEndpoint
import io.gomob.network.ServerEndpointStore
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject
import kotlin.system.measureTimeMillis

/**
 * "网络设置"子页 VM。
 *
 * 与 [io.gomob.feature.auth.LoginViewModel] 共享同一个 [ServerEndpointStore] —— 在登录页改的
 * 服务端地址，进 App 后这页能看到；反过来也是。SSOT 唯一一份。
 */
data class ProfileNetworkUiState(
    val savedEndpoint: ServerEndpoint = ServerEndpoint(
        ServerEndpointStore.DEFAULT_IP, ServerEndpointStore.DEFAULT_PORT,
    ),
    val draftIp: String = ServerEndpointStore.DEFAULT_IP,
    val draftPort: String = ServerEndpointStore.DEFAULT_PORT.toString(),
    val testing: Boolean = false,
    val saving: Boolean = false,
    val testResult: ProbeStatus = ProbeStatus.Unknown,
    val savedToast: String? = null,
    val validationError: String? = null,
)

sealed interface ProbeStatus {
    data object Unknown : ProbeStatus
    data object Probing : ProbeStatus
    data class Ok(val latencyMs: Long) : ProbeStatus
    data class Failed(val reason: String) : ProbeStatus
}

@HiltViewModel
class ProfileNetworkViewModel @Inject constructor(
    private val store: ServerEndpointStore,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileNetworkUiState())
    val state: StateFlow<ProfileNetworkUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 用户在别处保存或首次启动时同步一份
            store.endpointFlow.collectLatest { ep ->
                _state.update {
                    // 草稿与已存值同步 —— 除非用户正在编辑（draft 与 saved 不一致就不覆盖）
                    val draftMatchesSaved = it.draftIp == it.savedEndpoint.ip &&
                        it.draftPort == it.savedEndpoint.port.toString()
                    it.copy(
                        savedEndpoint = ep,
                        draftIp = if (draftMatchesSaved) ep.ip else it.draftIp,
                        draftPort = if (draftMatchesSaved) ep.port.toString() else it.draftPort,
                    )
                }
            }
        }
    }

    fun setDraftIp(v: String) = _state.update {
        it.copy(draftIp = v.trim(), validationError = null, testResult = ProbeStatus.Unknown, savedToast = null)
    }

    fun setDraftPort(v: String) = _state.update {
        it.copy(
            draftPort = v.filter { ch -> ch.isDigit() }.take(5),
            validationError = null,
            testResult = ProbeStatus.Unknown,
            savedToast = null,
        )
    }

    fun test() {
        val ep = parseDraft() ?: return
        _state.update { it.copy(testing = true, testResult = ProbeStatus.Probing) }
        viewModelScope.launch {
            val result = probe(ep)
            _state.update { it.copy(testing = false, testResult = result) }
        }
    }

    fun save() {
        val ep = parseDraft() ?: return
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            store.set(ep.ip, ep.port)
            _state.update { it.copy(saving = false, savedToast = "已保存 ${ep.display()}") }
        }
    }

    fun testAndSave() {
        val ep = parseDraft() ?: return
        _state.update { it.copy(testing = true, saving = true, testResult = ProbeStatus.Probing) }
        viewModelScope.launch {
            val result = probe(ep)
            // 不强制 ping 通才能保存 —— 用户可能先配地址，服务端晚一点才起来
            store.set(ep.ip, ep.port)
            _state.update {
                it.copy(
                    testing = false,
                    saving = false,
                    testResult = result,
                    savedToast = "已保存 ${ep.display()}",
                )
            }
        }
    }

    private fun parseDraft(): ServerEndpoint? {
        val s = _state.value
        if (s.draftIp.isBlank()) {
            _state.update { it.copy(validationError = "请输入网关 IP") }
            return null
        }
        val port = s.draftPort.toIntOrNull()
        if (port == null || port !in 1..65535) {
            _state.update { it.copy(validationError = "端口需在 1-65535") }
            return null
        }
        return ServerEndpoint(s.draftIp, port)
    }

    private suspend fun probe(ep: ServerEndpoint): ProbeStatus {
        return try {
            var code = 0
            val elapsed = measureTimeMillis {
                withTimeout(PROBE_TIMEOUT_MS) {
                    code = HealthProbe.ping(ep)
                }
            }
            if (code in 200..299) ProbeStatus.Ok(elapsed)
            else ProbeStatus.Failed("HTTP $code")
        } catch (_: TimeoutCancellationException) {
            ProbeStatus.Failed("超时")
        } catch (e: Exception) {
            ProbeStatus.Failed(shortReason(e))
        }
    }

    private fun shortReason(e: Throwable): String {
        val msg = e.message.orEmpty()
        return when {
            "ECONNREFUSED" in msg || "Connection refused" in msg -> "拒绝连接"
            "EHOSTUNREACH" in msg || "No route" in msg -> "主机不可达"
            "ENETUNREACH" in msg -> "网络不可达"
            "Failed to connect" in msg -> "连接失败"
            "timeout" in msg.lowercase() -> "超时"
            "Unable to resolve" in msg -> "DNS 解析失败"
            else -> e.javaClass.simpleName.removeSuffix("Exception").ifBlank { "网络错误" }
        }
    }

    private companion object {
        const val PROBE_TIMEOUT_MS = 3_000L
    }
}
