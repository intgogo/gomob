package io.gomob.feature.scan3d

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.nativebridge.berxel.BerxelDeviceControls
import io.gomob.nativebridge.berxel.BerxelDeviceState
import io.gomob.nativebridge.berxel.BerxelFrameStat
import io.gomob.nativebridge.berxel.BerxelService
import io.gomob.nativebridge.berxel.BerxelStackBackend
import io.gomob.nativebridge.berxel.BerxelStreamProfile
import io.gomob.nativebridge.berxel.BerxelStreamProfiles
import io.gomob.nativebridge.camera.CameraSource
import io.gomob.nativebridge.camera.CameraSourceProvider
import io.gomob.nativebridge.camera.CameraSourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** 厂商无关的预览帧统计（双相机通用，从 [io.gomob.model.ColorFrame]/[io.gomob.model.DepthFrame] 派生）。 */
data class PreviewStat(
    val frameIndex: Int,
    val width: Int,
    val height: Int,
    val measuredFps: Int,
    val stale: Boolean = false,
)

data class DepthCameraUiState(
    // ─── 中性面（双相机通用，主页预览 / 状态条用） ───
    val sourceState: CameraSourceState = CameraSourceState.Idle,
    val label: String = "",
    val isBerxel: Boolean = true,
    val colorStat: PreviewStat? = null,
    val depthStat: PreviewStat? = null,
    // VIN 的 HLSD8 预览由 vin-capture AAR 管理；本页只展示 Berxel。
    val hasRgb: Boolean = false,
    val rgbLabel: String = "",
    val rgbStat: PreviewStat? = null,
    // ─── Berxel 专属（仅 Berxel 在场时填真值，否则默认；三级子页 + Berxel 选择器消费） ───
    val device: BerxelDeviceState = BerxelDeviceState.Idle,
    val color: BerxelFrameStat? = null,
    val depth: BerxelFrameStat? = null,
    val controls: BerxelDeviceControls = BerxelDeviceControls(),
    val streamProfile: BerxelStreamProfile = BerxelStreamProfiles.DEFAULT,
    val backend: BerxelStackBackend = BerxelStackBackend.NATIVE_REWRITE,
)

/**
 * 深度相机详情子页 VM —— 进本页才启动相机，离开就停。
 *
 * 设计：注入 [CameraSourceProvider]，init 取一次 Berxel [CameraSource] 持有整段会话。
 * 预览从 [CameraSource.colorFrames]/[CameraSource.depthFrames] 收；Berxel 专属能力（采集后端 / 流模式 / IR /
 * 成像控制 / 帧统计 / 标定）经向下转型暴露。
 *
 * 生命周期由 [CameraSource] 引用计数单一管控：本 VM 与 [Scan3dRecordingViewModel] 都走 acquire/release，
 * `onCleared` 才 release()，所以三级页之间跳转不会断流。
 */
@HiltViewModel
class DepthCameraViewModel @Inject constructor(
    provider: CameraSourceProvider,
) : ViewModel() {

    /** 整段会话持有的活动取流源（按进页时插着的相机判型）。 */
    private val source: CameraSource = provider.active()

    private val berxel: BerxelService = source as BerxelService
    private val isBerxel = true

    // 中性帧统计：从两源同构的 core:model 帧派生（fps 用到达间隔 EMA 平滑）。
    private val _colorStat = MutableStateFlow<PreviewStat?>(null)
    private val _depthStat = MutableStateFlow<PreviewStat?>(null)

    /** Berxel 专属状态打包；eYs3D 时为默认常量流（不订阅任何 Berxel 流）。 */
    private val berxelBundle: Flow<BerxelBundle> = berxel?.let { b ->
        combine(
            b.state, b.colorStat, b.depthStat, b.controls, b.streamProfile, b.backendMode,
        ) { values ->
            BerxelBundle(
                device = values[0] as BerxelDeviceState,
                color = values[1] as BerxelFrameStat?,
                depth = values[2] as BerxelFrameStat?,
                controls = values[3] as BerxelDeviceControls,
                streamProfile = values[4] as BerxelStreamProfile,
                backend = values[5] as BerxelStackBackend,
            )
        }
    } ?: flowOf(BerxelBundle())

    val uiState: StateFlow<DepthCameraUiState> = combine(
        source.sourceState, _colorStat, _depthStat, berxelBundle,
    ) { srcState, colorStat, depthStat, bundle ->
        val effectiveColorStat = if (isBerxel && bundle.color?.visualStale == true) {
            PreviewStat(
                frameIndex = bundle.color.frameIndex,
                width = bundle.color.width,
                height = bundle.color.height,
                measuredFps = 0,
                stale = true,
            )
        } else {
            colorStat
        }
        DepthCameraUiState(
            sourceState = srcState,
            label = source.deviceLabel,
            isBerxel = isBerxel,
            colorStat = effectiveColorStat,
            depthStat = depthStat,
            hasRgb = false,
            rgbLabel = "",
            rgbStat = null,
            device = bundle.device,
            color = bundle.color,
            depth = bundle.depth,
            controls = bundle.controls,
            streamProfile = bundle.streamProfile,
            backend = bundle.backend,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DepthCameraUiState())

    private val _colorPreview = MutableStateFlow<Bitmap?>(null)
    val colorPreview: StateFlow<Bitmap?> = _colorPreview.asStateFlow()

    private val _depthPreview = MutableStateFlow<Bitmap?>(null)
    val depthPreview: StateFlow<Bitmap?> = _depthPreview.asStateFlow()

    /** 兼容旧 UI 状态字段；VIN AAR 页面自行管理 HLSD8 预览。 */
    private val _rgbPreview = MutableStateFlow<Bitmap?>(null)
    val rgbPreview: StateFlow<Bitmap?> = _rgbPreview.asStateFlow()

    @Volatile private var sourcesAcquired = false

    init {
        // COLOR：两源同发 RGB24 ColorFrame，FrameRenderer 直接渲染；渲染节流上限 ~10fps（解码体量大）。
        viewModelScope.launch {
            var lastRenderMs = 0L
            var lastArriveMs = 0L
            var emaMs = 0.0
            source.colorFrames.collect { frame ->
                val now = SystemClock.elapsedRealtime()
                emaMs = updateEma(emaMs, lastArriveMs, now)
                lastArriveMs = now
                _colorStat.value = PreviewStat(frame.frameIndex, frame.width, frame.height, emaMs.toFps())
                if (lastRenderMs != 0L && now - lastRenderMs < COLOR_PREVIEW_MIN_INTERVAL_MS) return@collect
                lastRenderMs = now
                val bmp = withContext(Dispatchers.Default) { FrameRenderer.colorToBitmap(frame) }
                if (bmp != null) _colorPreview.value = bmp
            }
        }
        // DEPTH：两源同发 16bit mm DepthFrame → turbo 伪彩（IR 模式仅 Berxel，渲染让位给 irFrames）。
        viewModelScope.launch {
            var lastRenderMs = 0L
            var lastArriveMs = 0L
            var emaMs = 0.0
            source.depthFrames.collect { frame ->
                val now = SystemClock.elapsedRealtime()
                emaMs = updateEma(emaMs, lastArriveMs, now)
                lastArriveMs = now
                _depthStat.value = PreviewStat(frame.frameIndex, frame.width, frame.height, emaMs.toFps())
                if (_irRenderMode.value) return@collect
                if (lastRenderMs != 0L && now - lastRenderMs < DEPTH_PREVIEW_MIN_INTERVAL_MS) return@collect
                lastRenderMs = now
                val bmp = withContext(Dispatchers.Default) {
                    FrameRenderer.depth16ToBitmap(frame, maskByConfidence = false)
                }
                if (bmp != null) _depthPreview.value = bmp
            }
        }
        // IR/phase 灰度帧（仅 Berxel companion 交织出来）：IR 模式下渲染，让 depth 框看精细 IR 图。
        berxel?.let { b ->
            viewModelScope.launch {
                var lastRenderMs = 0L
                b.irFrames.collect { frame ->
                    if (!_irRenderMode.value) return@collect
                    val now = SystemClock.elapsedRealtime()
                    if (lastRenderMs != 0L && now - lastRenderMs < DEPTH_PREVIEW_MIN_INTERVAL_MS) return@collect
                    lastRenderMs = now
                    val bmp = withContext(Dispatchers.Default) { FrameRenderer.depthRawAsGrey(frame) }
                    if (bmp != null) _depthPreview.value = bmp
                }
            }
        }
    }

    /**
     * HyperOS / Android 15 会先检查 CAMERA runtime permission，再允许 UVC USB 权限弹窗。
     * 因此不能在 VM init 里立刻 acquire；必须等页面拿到 CAMERA 后再启动 USB 权限链。
     */
    fun setCameraPermissionGranted(granted: Boolean) {
        if (granted) acquireSourcesIfNeeded() else releaseSourcesIfNeeded()
    }

    private fun acquireSourcesIfNeeded() {
        if (sourcesAcquired) return
        sourcesAcquired = true
        source.acquire()
    }

    private fun releaseSourcesIfNeeded() {
        if (!sourcesAcquired) return
        sourcesAcquired = false
        source.release()
    }

    // ─── 控制命令（仅 Berxel 有意义，null-safe 转发；eYs3D 下 UI 不暴露这些入口） ───
    fun setBackendMode(backend: BerxelStackBackend) { berxel?.setBackendModeForDebug(backend) }
    fun setStreamProfile(profile: BerxelStreamProfile) { berxel?.setStreamProfile(profile) }
    fun setRegistrationEnable(on: Boolean) { berxel?.setRegistrationEnable(on) }
    fun setStreamMirror(on: Boolean) { berxel?.setStreamMirror(on) }
    fun setDepthAutoExposure(on: Boolean) { berxel?.setDepthAutoExposure(on) }
    fun setDepthEdgeOptimization(on: Boolean) { berxel?.setDepthEdgeOptimization(on) }
    fun setDepthDenoise(on: Boolean) { berxel?.setDepthDenoise(on) }
    fun setDepthTemperatureCompensation(on: Boolean) { berxel?.setDepthTemperatureCompensation(on) }
    fun setColorAutoExposure(on: Boolean) { berxel?.setColorAutoExposure(on) }

    // M1.6.8 debug：NATIVE_REWRITE assembler 切帧策略 toggle（Berxel 专属）。
    private val _strictFrameSize = MutableStateFlow(berxel?.isDepthStrictFrameSize() ?: false)
    val strictFrameSize: StateFlow<Boolean> = _strictFrameSize.asStateFlow()
    fun toggleStrictFrameSize() {
        val next = !_strictFrameSize.value
        berxel?.setDepthStrictFrameSize(next)
        _strictFrameSize.value = next
    }

    /** IR 模式：把 depth raw bytes 当 8-bit grey 渲染（Berxel 专属调试 toggle）。 */
    private val _irRenderMode = MutableStateFlow(false)
    val irRenderMode: StateFlow<Boolean> = _irRenderMode.asStateFlow()
    fun toggleIrRenderMode() { _irRenderMode.value = !_irRenderMode.value }

    fun triggerFrameDump() { berxel?.triggerDump(30) }

    override fun onCleared() {
        super.onCleared()
        // 离开详情页 → 引用计数 release（归 0 且宽限期内无人 acquire 才真停）。
        releaseSourcesIfNeeded()
    }

    private data class BerxelBundle(
        val device: BerxelDeviceState = BerxelDeviceState.Idle,
        val color: BerxelFrameStat? = null,
        val depth: BerxelFrameStat? = null,
        val controls: BerxelDeviceControls = BerxelDeviceControls(),
        val streamProfile: BerxelStreamProfile = BerxelStreamProfiles.DEFAULT,
        val backend: BerxelStackBackend = BerxelStackBackend.NATIVE_REWRITE,
    )

    private companion object {
        // 显示节流上限 ~30fps（depth）/ ~25fps（color 解码体量大，只取最新帧）。
        const val DEPTH_PREVIEW_MIN_INTERVAL_MS = 33L
        const val COLOR_PREVIEW_MIN_INTERVAL_MS = 40L
    }
}

/** 到达间隔 EMA（alpha=0.3）；首帧或时钟回退时保持原值。 */
private fun updateEma(prev: Double, lastArriveMs: Long, now: Long): Double {
    if (lastArriveMs == 0L) return prev
    val dt = (now - lastArriveMs).toDouble()
    if (dt <= 0.0) return prev
    return if (prev == 0.0) dt else prev * 0.7 + dt * 0.3
}

private fun Double.toFps(): Int = if (this > 0.0) (1000.0 / this).toInt().coerceIn(0, 120) else 0
