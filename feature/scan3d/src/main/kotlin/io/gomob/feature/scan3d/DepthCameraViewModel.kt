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
 * 深度相机详情子页 VM。
 *
 * 跟 [Scan3dViewModel] 一样订阅 BerxelService 的状态流，但**预览渲染只在本子页**做（主页
 * 已不显示预览，避免双倍 Bitmap 转换浪费）。
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
        // 不重复 berxel.start()：主页 VM 早就启动过；本子页只订阅
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

    private companion object {
        const val PREVIEW_DECIMATION = 5
    }
}
