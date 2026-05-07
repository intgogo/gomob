package io.gomob.feature.scan3d

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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

data class Scan3dDeviceUiState(
    val device: BerxelDeviceState = BerxelDeviceState.Idle,
    val color: BerxelFrameStat? = null,
    val depth: BerxelFrameStat? = null,
)

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

    private val _colorPreview = MutableStateFlow<Bitmap?>(null)
    val colorPreview: StateFlow<Bitmap?> = _colorPreview.asStateFlow()

    private val _depthPreview = MutableStateFlow<Bitmap?>(null)
    val depthPreview: StateFlow<Bitmap?> = _depthPreview.asStateFlow()

    init {
        berxel.start()

        // 订阅帧流 → 降采样 → 转 Bitmap → emit。
        // 30 fps 全转 Bitmap 太重（每帧 256K 像素 IntArray 填充 + Bitmap 创建 ~10-50ms）；
        // 取每 PREVIEW_DECIMATION 帧渲一次 → 6 fps 预览，用户看着也够流畅。
        viewModelScope.launch {
            var counter = 0
            berxel.colorFrames.collect { frame ->
                counter++
                if (counter % PREVIEW_DECIMATION != 0) return@collect
                val bmp = withContext(Dispatchers.Default) {
                    FrameRenderer.colorRgb24ToBitmap(frame)
                }
                _colorPreview.value = bmp
            }
        }
        viewModelScope.launch {
            var counter = 0
            berxel.depthFrames.collect { frame ->
                counter++
                if (counter % PREVIEW_DECIMATION != 0) return@collect
                val bmp = withContext(Dispatchers.Default) {
                    FrameRenderer.depth16ToBitmap(frame)
                }
                _depthPreview.value = bmp
            }
        }
    }

    fun retry() = berxel.start()
    fun stop() = berxel.stop()

    override fun onCleared() {
        super.onCleared()
        // 不在这里 stop —— 让 service 跨 VM 生存，下次进 Scan3dRoute 直接复用已开的 device
        // 真要释放设备由用户手动按"停止"或 App 退出（GomobApplication.onTerminate / 进程死亡）
    }

    private companion object {
        /** 每 N 帧采样一次预览：30 fps / 5 = 6 fps Bitmap 渲染 */
        const val PREVIEW_DECIMATION = 5
    }
}
