package io.gomob.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.common.net.Ipv4AddressDraft
import io.gomob.data.auth.AuthRepository
import io.gomob.data.auth.TokenStore
import io.gomob.network.ApiException
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

data class LoginUiState(
    // dev seed 账号 — DB 里 shenhm / shenhm123 真实存在;真上线前由注册流程替换
    val username: String = "shenhm",
    val password: String = "shenhm123",
    val rememberMe: Boolean = true,
    val loading: Boolean = false,
    val errorMessage: String? = null,
    val loggedIn: Boolean = false,
    /** 当前已保存的服务端地址 (DiagnosticStrip 展示) */
    val endpoint: ServerEndpoint = ServerEndpoint(
        ServerEndpointStore.DEFAULT_IP, ServerEndpointStore.DEFAULT_PORT,
    ),
    /** 后台对当前 endpoint 的探活结果 (driving DiagnosticStrip 状态点) */
    val connectivity: ConnectivityStatus = ConnectivityStatus.Unknown,
    /** 非 null 表示编辑面板打开 */
    val editor: EndpointEditorState? = null,
)

sealed interface ConnectivityStatus {
    data object Unknown : ConnectivityStatus
    data object Probing : ConnectivityStatus
    data class Ok(val latencyMs: Long) : ConnectivityStatus
    data class Failed(val reason: String) : ConnectivityStatus
}

data class EndpointEditorState(
    val draftIp: Ipv4AddressDraft,
    val draftPort: String,
    /** 内嵌 "测试" 按钮的状态 */
    val testing: Boolean = false,
    val testResult: ConnectivityStatus = ConnectivityStatus.Unknown,
    val saving: Boolean = false,
    val validationError: String? = null,
)

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepo: AuthRepository,
    private val endpointStore: ServerEndpointStore,
    private val tokenStore: TokenStore,
) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    init {
        // 跟随 store 变化更新 endpoint + 自动重新 ping
        viewModelScope.launch {
            endpointStore.endpointFlow.collectLatest { ep ->
                _state.update { it.copy(endpoint = ep, connectivity = ConnectivityStatus.Probing) }
                val result = probeEndpoint()
                _state.update { it.copy(connectivity = result) }
            }
        }
    }

    fun setUsername(v: String) = _state.update { it.copy(username = v, errorMessage = null) }
    fun setPassword(v: String) = _state.update { it.copy(password = v, errorMessage = null) }
    fun setRemember(v: Boolean) = _state.update { it.copy(rememberMe = v) }

    /**
     * 调试通道：写入假 token 跳过登录鉴权，立即进入主 App。
     *
     * Why: 开发服务端没起 / 跨网段不可达时，硬件相关功能（Berxel / 标定 / 扫描）
     * 的验证不应被登录鉴权阻塞。通过登录页 DEV badge 长按触发，**不向用户暴露**。
     * 真上线前 release 包应剥掉此入口。
     */
    fun devBypassLogin() {
        viewModelScope.launch {
            tokenStore.save(access = "dev-bypass-access", refresh = "dev-bypass-refresh")
            _state.update { it.copy(loggedIn = true, errorMessage = null) }
        }
    }

    fun submit() {
        val s = _state.value
        if (s.username.isBlank() || s.password.isBlank()) {
            _state.update { it.copy(errorMessage = "请输入账号和密码") }
            return
        }
        _state.update { it.copy(loading = true, errorMessage = null) }
        viewModelScope.launch {
            try {
                authRepo.login(s.username.trim(), s.password)
                _state.update { it.copy(loading = false, loggedIn = true) }
            } catch (e: ApiException) {
                _state.update { it.copy(loading = false, errorMessage = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, errorMessage = "网络异常: ${e.message ?: "未知"}") }
            }
        }
    }

    // ─── 端点编辑面板 ──────────────────────────────────────────────────────

    fun openEndpointEditor() {
        val ep = _state.value.endpoint
        _state.update {
            it.copy(
                editor = EndpointEditorState(
                    draftIp = Ipv4AddressDraft.from(ep.ip),
                    draftPort = ep.port.toString(),
                ),
            )
        }
    }

    fun closeEndpointEditor() {
        _state.update { it.copy(editor = null) }
    }

    fun setDraftIp(v: Ipv4AddressDraft) = updateEditor {
        it.copy(draftIp = v, validationError = null, testResult = ConnectivityStatus.Unknown)
    }

    fun setDraftPort(v: String) = updateEditor { it.copy(draftPort = v.filter { ch -> ch.isDigit() }.take(5), validationError = null, testResult = ConnectivityStatus.Unknown) }

    /** "测试连接" —— 拿草稿值临时 ping，不写库 */
    fun testDraft() {
        val ed = _state.value.editor ?: return
        val parsed = parseDraft(ed) ?: return
        updateEditor { it.copy(testing = true, testResult = ConnectivityStatus.Probing) }
        viewModelScope.launch {
            val result = probeEndpoint(parsed)
            updateEditor { it.copy(testing = false, testResult = result) }
        }
    }

    /** "保存" —— 写库 + 关面板；触发 init 里 collectLatest 自动重新 ping */
    fun saveDraft() {
        val ed = _state.value.editor ?: return
        val parsed = parseDraft(ed) ?: return
        updateEditor { it.copy(saving = true) }
        viewModelScope.launch {
            endpointStore.set(parsed.ip, parsed.port)
            _state.update { it.copy(editor = null) }
        }
    }

    private fun parseDraft(ed: EndpointEditorState): ServerEndpoint? {
        val ip = ed.draftIp.normalizedOrNull()
        val port = ed.draftPort.toIntOrNull()
        if (ip == null) {
            updateEditor { it.copy(validationError = ed.draftIp.validationError("网关 IP")) }
            return null
        }
        if (port == null || port !in 1..65535) {
            updateEditor { it.copy(validationError = "端口需在 1-65535") }
            return null
        }
        return ServerEndpoint(ip, port)
    }

    private inline fun updateEditor(block: (EndpointEditorState) -> EndpointEditorState) {
        _state.update { st -> st.editor?.let { st.copy(editor = block(it)) } ?: st }
    }

    /**
     * 探活：直接对指定 endpoint 拼 URL 调 /healthz，不依赖 OkHttp 拦截器
     * (拦截器读 store 当前值，而 testDraft 测的是"草稿值")。
     */
    private suspend fun probeEndpoint(ep: ServerEndpoint = _state.value.endpoint): ConnectivityStatus {
        return try {
            var code = 0
            val elapsed = measureTimeMillis {
                withTimeout(PROBE_TIMEOUT_MS) {
                    code = io.gomob.network.HealthProbe.ping(ep)
                }
            }
            if (code in 200..299) ConnectivityStatus.Ok(elapsed)
            else ConnectivityStatus.Failed("HTTP $code")
        } catch (_: TimeoutCancellationException) {
            ConnectivityStatus.Failed("超时")
        } catch (e: Exception) {
            ConnectivityStatus.Failed(shortReason(e))
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
