package io.gomob.feature.scan3d

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.scan.LaserLatestScan
import io.gomob.data.scan.LaserScanRepository
import io.gomob.nativebridge.berxel.BerxelDeviceInfo
import io.gomob.nativebridge.berxel.BerxelDeviceState
import io.gomob.nativebridge.berxel.BerxelService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 3D 主页 UI 状态。
 *
 * - [state] 当前 SDK 状态（如果用户没进过详情页 → 一直是 Idle）
 * - [lastKnownInfo] 历次连接拿到的设备信息缓存；用户进过一次详情页就能填上 SN / FW / SDK
 */
data class Scan3dDeviceUiState(
    val state: BerxelDeviceState = BerxelDeviceState.Idle,
    val lastKnownInfo: BerxelDeviceInfo? = null,
)

/** 最近扫描只允许呈现服务端可证明的四种状态。 */
sealed interface LatestScanUiState {
    data object Loading : LatestScanUiState
    data object Empty : LatestScanUiState
    data class Ready(val scan: LaserLatestScan) : LatestScanUiState
    data object Error : LatestScanUiState
}

/** 把设备状态与最近扫描 I/O 收口，ViewModel 可用纯 Kotlin fake 验证并发刷新。 */
internal interface Scan3dDataSource {
    val deviceState: StateFlow<BerxelDeviceState>
    val lastKnownInfo: StateFlow<BerxelDeviceInfo?>

    suspend fun latestScan(): LaserLatestScan?
}

private class RepositoryScan3dDataSource(
    berxel: BerxelService,
    private val scans: LaserScanRepository,
) : Scan3dDataSource {
    override val deviceState: StateFlow<BerxelDeviceState> = berxel.state
    override val lastKnownInfo: StateFlow<BerxelDeviceInfo?> = berxel.lastKnownInfo

    override suspend fun latestScan(): LaserLatestScan? = scans.latest()
}

/**
 * 3D 主页 VM —— **不**主动启动 BerxelService。
 *
 * 设计变更（2026-05-07）：
 *  - 早期 init 块调 `berxel.start()` 一进 3D tab 就常驻打开相机 → 费电费热
 *  - 现在改成"点进深度相机详情页才打开" → [DepthCameraViewModel] 负责 start/stop
 *  - 主页只展示已知信息缓存（[BerxelService.lastKnownInfo]），不显示实时帧率
 */
@HiltViewModel
class Scan3dViewModel internal constructor(
    private val source: Scan3dDataSource,
) : ViewModel() {

    @Inject
    constructor(
        berxel: BerxelService,
        scans: LaserScanRepository,
    ) : this(RepositoryScan3dDataSource(berxel, scans))

    private val _latestScan = MutableStateFlow<LatestScanUiState>(LatestScanUiState.Loading)
    val latestScan: StateFlow<LatestScanUiState> = _latestScan.asStateFlow()

    private var latestLoadJob: Job? = null
    private var latestLoadGeneration = 0L

    val uiState: StateFlow<Scan3dDeviceUiState> = combine(
        source.deviceState,
        source.lastKnownInfo,
    ) { state, info -> Scan3dDeviceUiState(state, info) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Scan3dDeviceUiState())

    /** 每次根页进场刷新；新请求会取消旧请求，避免慢响应覆盖更新结果。 */
    fun refreshLatestScan() {
        val generation = ++latestLoadGeneration
        latestLoadJob?.cancel()
        _latestScan.value = LatestScanUiState.Loading
        latestLoadJob = viewModelScope.launch {
            try {
                val latest = source.latestScan()
                if (generation != latestLoadGeneration) return@launch
                _latestScan.value = latest?.let { LatestScanUiState.Ready(it) } ?: LatestScanUiState.Empty
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                if (generation == latestLoadGeneration) {
                    _latestScan.value = LatestScanUiState.Error
                }
            } finally {
                if (generation == latestLoadGeneration) {
                    latestLoadJob = null
                }
            }
        }
    }
}
