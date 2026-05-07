package io.gomob.feature.scan3d

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.nativebridge.berxel.BerxelDeviceControls
import io.gomob.nativebridge.berxel.BerxelDeviceState
import io.gomob.nativebridge.berxel.BerxelFrameStat
import io.gomob.nativebridge.berxel.BerxelService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class DepthCameraUiState(
    val device: BerxelDeviceState = BerxelDeviceState.Idle,
    val color: BerxelFrameStat? = null,
    val depth: BerxelFrameStat? = null,
    val controls: BerxelDeviceControls = BerxelDeviceControls(),
)

/**
 * 深度相机详情子页 VM —— 进本页才启动 SDK，离开就停。
 *
 * 设计：3D 主页 [Scan3dViewModel] 不再常驻打开相机（费电费热），改由进入详情页时启动；
 * 三级页（info / controls / calibration）也共用本 VM 的 BerxelService 单例 — 但 `onCleared`
 * 时才 stop()，所以三级页之间跳转不会断流（详情页 NavGraph 顶层未销毁本 VM）。
 */
@HiltViewModel
class DepthCameraViewModel @Inject constructor(
    private val berxel: BerxelService,
) : ViewModel() {

    val uiState: StateFlow<DepthCameraUiState> = combine(
        berxel.state,
        berxel.colorStat,
        berxel.depthStat,
        berxel.controls,
    ) { state, color, depth, controls ->
        DepthCameraUiState(state, color, depth, controls)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DepthCameraUiState())

    private val _colorPreview = MutableStateFlow<Bitmap?>(null)
    val colorPreview: StateFlow<Bitmap?> = _colorPreview.asStateFlow()

    private val _depthPreview = MutableStateFlow<Bitmap?>(null)
    val depthPreview: StateFlow<Bitmap?> = _depthPreview.asStateFlow()

    init {
        // 进本子页才启动 SDK（主页不再常驻）；幂等 — 已 Streaming 就 noop
        berxel.start()

        viewModelScope.launch {
            var counter = 0
            berxel.colorFrames.collect { frame ->
                counter++
                if (counter % PREVIEW_DECIMATION != 0) return@collect
                val bmp = withContext(Dispatchers.Default) { FrameRenderer.colorRgb24ToBitmap(frame) }
                _colorPreview.value = bmp
            }
        }
        viewModelScope.launch {
            var counter = 0
            berxel.depthFrames.collect { frame ->
                counter++
                if (counter % PREVIEW_DECIMATION != 0) return@collect
                val bmp = withContext(Dispatchers.Default) { FrameRenderer.depth16ToBitmap(frame) }
                _depthPreview.value = bmp
            }
        }
    }

    // ─── 控制命令（直接转发到 BerxelService） ───
    fun setRegistrationEnable(on: Boolean) = berxel.setRegistrationEnable(on)
    fun setStreamMirror(on: Boolean) = berxel.setStreamMirror(on)
    fun setDepthAutoExposure(on: Boolean) = berxel.setDepthAutoExposure(on)
    fun setDepthEdgeOptimization(on: Boolean) = berxel.setDepthEdgeOptimization(on)
    fun setDepthDenoise(on: Boolean) = berxel.setDepthDenoise(on)
    fun setDepthTemperatureCompensation(on: Boolean) = berxel.setDepthTemperatureCompensation(on)
    fun setColorAutoExposure(on: Boolean) = berxel.setColorAutoExposure(on)

    override fun onCleared() {
        super.onCleared()
        // 离开详情页（导航回主页 / 退出 App）→ 停 SDK 释放 USB / 省电省热
        // lastKnownInfo 保留在 BerxelService 里，主页继续展示
        berxel.stop()
    }

    private companion object {
        const val PREVIEW_DECIMATION = 5
    }
}
