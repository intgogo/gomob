package io.gomob.feature.scan3d

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.nativebridge.berxel.BerxelDeviceState
import io.gomob.nativebridge.berxel.BerxelFrameStat
import io.gomob.nativebridge.berxel.BerxelService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class Scan3dDeviceUiState(
    val device: BerxelDeviceState = BerxelDeviceState.Idle,
    val color: BerxelFrameStat? = null,
    val depth: BerxelFrameStat? = null,
)

/**
 * 3D 主页 VM —— 只展示设备状态卡（SN / 流模式 / fps），不渲染预览。
 *
 * 实时双流预览搬到了 [DepthCameraViewModel] / [DepthCameraRoute] 子页 —— 主页点击设备卡
 * 进入子页才渲染 Bitmap，避免双重转换。
 */
@HiltViewModel
class Scan3dViewModel @Inject constructor(
    private val berxel: BerxelService,
) : ViewModel() {

    val uiState: StateFlow<Scan3dDeviceUiState> = combine(
        berxel.state,
        berxel.colorStat,
        berxel.depthStat,
    ) { state, color, depth -> Scan3dDeviceUiState(state, color, depth) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Scan3dDeviceUiState())

    init {
        // 进入扫描页就尝试启动 SDK；BerxelService.start() 幂等，反复进出此页不会重复打开
        berxel.start()
    }

    fun retry() = berxel.start()
    fun stop() = berxel.stop()

    override fun onCleared() {
        super.onCleared()
        // 不在这里 stop —— 让 service 跨 VM 生存，下次进 Scan3dRoute 直接复用已开的 device
        // 真要释放设备由用户手动按"停止"或 App 退出（GomobApplication.onTerminate / 进程死亡）
    }
}
