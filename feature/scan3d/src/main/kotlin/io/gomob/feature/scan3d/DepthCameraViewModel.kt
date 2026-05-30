package io.gomob.feature.scan3d

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.nativebridge.berxel.BerxelDeviceControls
import io.gomob.nativebridge.berxel.BerxelDeviceState
import io.gomob.nativebridge.berxel.BerxelFrameStat
import io.gomob.nativebridge.berxel.BerxelService
import io.gomob.nativebridge.berxel.BerxelStreamProfile
import io.gomob.nativebridge.berxel.BerxelStreamProfiles
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
    val streamProfile: BerxelStreamProfile = BerxelStreamProfiles.DEFAULT,
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
        berxel.streamProfile,
    ) { values ->
        DepthCameraUiState(
            device = values[0] as BerxelDeviceState,
            color = values[1] as BerxelFrameStat?,
            depth = values[2] as BerxelFrameStat?,
            controls = values[3] as BerxelDeviceControls,
            streamProfile = values[4] as BerxelStreamProfile,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DepthCameraUiState())

    private val _colorPreview = MutableStateFlow<Bitmap?>(null)
    val colorPreview: StateFlow<Bitmap?> = _colorPreview.asStateFlow()

    private val _depthPreview = MutableStateFlow<Bitmap?>(null)
    val depthPreview: StateFlow<Bitmap?> = _depthPreview.asStateFlow()

    init {
        // 进本子页：引用计数 acquire（单一 owner 管相机生命周期，不再各自 start）。
        // 主页不常驻；与 Scan3dRecordingViewModel 切换时计数 >0 全程保持相机不抖。
        berxel.acquire()

        viewModelScope.launch {
            var counter = 0
            berxel.colorFrames.collect { frame ->
                counter++
                if (counter % PREVIEW_DECIMATION != 0) return@collect
                val bmp = withContext(Dispatchers.Default) { FrameRenderer.colorRgb24ToBitmap(frame) }
                _colorPreview.value = bmp
            }
        }
        // 真深度（16bit mm）→ turbo 伪彩，仅非 IR 模式渲染
        viewModelScope.launch {
            var counter = 0
            berxel.depthFrames.collect { frame ->
                if (_irRenderMode.value) return@collect
                counter++
                if (counter % PREVIEW_DECIMATION != 0) return@collect
                val bmp = withContext(Dispatchers.Default) { FrameRenderer.depth16ToBitmap(frame) }
                _depthPreview.value = bmp
            }
        }
        // IR/phase 帧（8bit 灰度，companion 交织出来的真实 IR 图）→ 灰度，仅 IR 模式渲染。
        // Step 4（2026-05-29）：companion 0x82 交织真深度与 IR 帧，native 分流；depth 走 depthFrames，
        // IR 走 irFrames。「切 IR」看精细 IR 图，默认看真深度。
        viewModelScope.launch {
            var counter = 0
            berxel.irFrames.collect { frame ->
                if (!_irRenderMode.value) return@collect
                counter++
                if (counter % PREVIEW_DECIMATION != 0) return@collect
                val bmp = withContext(Dispatchers.Default) { FrameRenderer.depthRawAsGrey(frame) }
                _depthPreview.value = bmp
            }
        }
    }

    // ─── 控制命令（直接转发到 BerxelService） ───
    fun setStreamProfile(profile: BerxelStreamProfile) = berxel.setStreamProfile(profile)
    fun setRegistrationEnable(on: Boolean) = berxel.setRegistrationEnable(on)
    fun setStreamMirror(on: Boolean) = berxel.setStreamMirror(on)
    fun setDepthAutoExposure(on: Boolean) = berxel.setDepthAutoExposure(on)
    fun setDepthEdgeOptimization(on: Boolean) = berxel.setDepthEdgeOptimization(on)
    fun setDepthDenoise(on: Boolean) = berxel.setDepthDenoise(on)
    fun setDepthTemperatureCompensation(on: Boolean) = berxel.setDepthTemperatureCompensation(on)
    fun setColorAutoExposure(on: Boolean) = berxel.setColorAutoExposure(on)

    // M1.6.8 debug：NATIVE_REWRITE assembler 切帧策略 toggle
    private val _strictFrameSize = MutableStateFlow(berxel.isDepthStrictFrameSize())
    val strictFrameSize: StateFlow<Boolean> = _strictFrameSize.asStateFlow()
    fun toggleStrictFrameSize() {
        val next = !_strictFrameSize.value
        berxel.setDepthStrictFrameSize(next)
        _strictFrameSize.value = next
    }

    /** IR 模式：把 depth raw bytes 当 8-bit grey 渲染。
     *  Step 4（2026-05-29）：生产路径已切到 native portable 双流，DepthFrame.data 是真 16bit mm，
     *  default 改回 false 走 turbo 伪彩（depth16ToBitmap）。IR 灰度仅留作 LIGHT_IR 散斑预览调试 toggle。 */
    private val _irRenderMode = MutableStateFlow(false)
    val irRenderMode: StateFlow<Boolean> = _irRenderMode.asStateFlow()
    fun toggleIrRenderMode() { _irRenderMode.value = !_irRenderMode.value }

    fun triggerFrameDump() = berxel.triggerDump(30)

    override fun onCleared() {
        super.onCleared()
        // 离开详情页 → 引用计数 release（归 0 且宽限期内无人 acquire 才真停，释放 USB / 省电省热）。
        // lastKnownInfo 保留在 BerxelService 里，主页继续展示
        berxel.release()
    }

    private companion object {
        const val PREVIEW_DECIMATION = 5
    }
}
