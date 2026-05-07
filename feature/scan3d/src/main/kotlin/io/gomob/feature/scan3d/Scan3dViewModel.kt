package io.gomob.feature.scan3d

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.nativebridge.berxel.BerxelDeviceInfo
import io.gomob.nativebridge.berxel.BerxelDeviceState
import io.gomob.nativebridge.berxel.BerxelService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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

/**
 * 3D 主页 VM —— **不**主动启动 BerxelService。
 *
 * 设计变更（2026-05-07）：
 *  - 早期 init 块调 `berxel.start()` 一进 3D tab 就常驻打开相机 → 费电费热
 *  - 现在改成"点进深度相机详情页才打开" → [DepthCameraViewModel] 负责 start/stop
 *  - 主页只展示已知信息缓存（[BerxelService.lastKnownInfo]），不显示实时帧率
 */
@HiltViewModel
class Scan3dViewModel @Inject constructor(
    private val berxel: BerxelService,
) : ViewModel() {

    val uiState: StateFlow<Scan3dDeviceUiState> = combine(
        berxel.state,
        berxel.lastKnownInfo,
    ) { state, info -> Scan3dDeviceUiState(state, info) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Scan3dDeviceUiState())
}
