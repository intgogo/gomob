package io.gomob.feature.scan3d

import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.scan.VinRepository
import io.gomob.data.scan.VinResult
import io.gomob.model.ColorFrame
import io.gomob.nativebridge.camera.CameraSource
import io.gomob.nativebridge.camera.CameraSourceProvider
import io.gomob.nativebridge.camera.CameraSourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/** VIN 数码拓印识别状态机。 */
sealed interface VinCaptureState {
    /** 取景中（实时 RGB 预览，等用户拍照）。 */
    data object Preview : VinCaptureState
    /** 已拍照，整图上传服务端识别 + 厂家字形比对中。 */
    data object Recognizing : VinCaptureState
    data class Result(val capture: Bitmap, val result: VinResult) : VinCaptureState
    data class Error(val msg: String) : VinCaptureState
}

/**
 * VIN 数码拓印 VM —— 端采单帧 RGB → 服务端 `vin_pipeline` 出真 verdict / 字符比对。
 *
 * 本期（M7.6）只走"识别 + 厂家字形库比对 + verdict"业务链路；深度拓印图（native 正射重投影
 * RANSAC 平面拟合）是第二刀（M4.1），故只采 color 不动 depth。
 */
@HiltViewModel
class VinCaptureViewModel @Inject constructor(
    provider: CameraSourceProvider,
    private val vinRepo: VinRepository,
) : ViewModel() {

    private val source: CameraSource = provider.active()

    private val _state = MutableStateFlow<VinCaptureState>(VinCaptureState.Preview)
    val state: StateFlow<VinCaptureState> = _state.asStateFlow()

    private val _colorPreview = MutableStateFlow<Bitmap?>(null)
    val colorPreview: StateFlow<Bitmap?> = _colorPreview.asStateFlow()

    /** 车型 ID：决定拉哪套厂家字形库对照。本期默认 dev 值，可在 UI 设置。
     *  TODO(catalog): 接车型档案查询客户端后改为从工单/车型选择带入。 */
    private val _vehicleModelId = MutableStateFlow(DEFAULT_VEHICLE_MODEL_ID)
    val vehicleModelId: StateFlow<Long> = _vehicleModelId.asStateFlow()

    val deviceState: StateFlow<CameraSourceState> = source.sourceState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraSourceState.Idle)

    @Volatile private var latestColor: ColorFrame? = null
    private var captureJob: Job? = null

    init {
        source.acquire()
        viewModelScope.launch {
            var n = 0
            source.colorFrames.collect { frame ->
                latestColor = frame
                if (n++ % PREVIEW_DECIMATION == 0 && _state.value == VinCaptureState.Preview) {
                    _colorPreview.value = withContext(Dispatchers.Default) {
                        FrameRenderer.colorRgb24ToBitmap(frame)
                    }
                }
            }
        }
    }

    fun setVehicleModelId(id: Long) {
        if (id > 0) _vehicleModelId.value = id
    }

    /** 拍照识别：抓最近 color → JPEG → 服务端 vin_pipeline。 */
    fun capture() {
        if (_state.value == VinCaptureState.Recognizing) return
        val color = latestColor
        if (color == null) {
            _state.value = VinCaptureState.Error("尚无彩色帧，请确认相机已连接")
            return
        }
        _state.value = VinCaptureState.Recognizing
        captureJob = viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.Default) {
                    FrameRenderer.colorRgb24ToBitmap(color)
                } ?: throw IllegalStateException("彩色帧解码失败")
                val jpeg = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().use { out ->
                        bmp.compress(CompressFormat.JPEG, 92, out)
                        out.toByteArray()
                    }
                }
                val result = vinRepo.recognize(_vehicleModelId.value, jpeg)
                _state.value = VinCaptureState.Result(capture = bmp, result = result)
                Log.i(TAG, "VIN 识别完成 verdict=${result.verdict} vin=${result.recognizedVin} scored=${result.scored}")
            } catch (e: Throwable) {
                Log.w(TAG, "VIN 识别失败: ${e.message}")
                _state.value = VinCaptureState.Error("识别失败: ${e.message}")
            }
        }
    }

    fun retake() {
        // 取消可能仍在跑的识别协程,避免它完成后把 state 从 Preview 覆盖回 Result。
        captureJob?.cancel()
        captureJob = null
        _state.value = VinCaptureState.Preview
    }

    override fun onCleared() {
        super.onCleared()
        source.release()
    }

    companion object {
        private const val TAG = "VinCaptureVM"
        private const val PREVIEW_DECIMATION = 4
        private const val DEFAULT_VEHICLE_MODEL_ID = 10001L
    }
}
