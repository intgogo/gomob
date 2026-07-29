package io.gomob.feature.scan3d

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.scan.CalibrationFileException
import io.gomob.data.scan.CalibrationFileProvider
import io.gomob.data.scan.DefaultCalibrationFileProvider
import io.gomob.data.scan.LocalCalibrationFile
import io.gomob.data.scan.RawRgbdShot
import io.gomob.data.scan.RgbdSourceProfile
import io.gomob.data.scan.Scan3dBundleUploader
import io.gomob.data.scan.ScanFusionRepository
import io.gomob.model.ColorFrame
import io.gomob.model.DepthFrame
import io.gomob.model.DepthSampleFormat
import io.gomob.nativebridge.camera.CameraSource
import io.gomob.nativebridge.camera.CameraSourceProvider
import io.gomob.nativebridge.camera.CameraSourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteOrder
import javax.inject.Inject

data class VehicleAngleDef(val label: String, val name: String, val deg: Float)

val VehicleAngleDefs: List<VehicleAngleDef> = listOf(
    VehicleAngleDef("前", "正前", 0f),
    VehicleAngleDef("右前", "右前 45°", 45f),
    VehicleAngleDef("右", "正右", 90f),
    VehicleAngleDef("右后", "右后 45°", 135f),
    VehicleAngleDef("后", "正后", 180f),
    VehicleAngleDef("左后", "左后 45°", 225f),
    VehicleAngleDef("左", "正左", 270f),
    VehicleAngleDef("左前", "左前 45°", 315f),
)

sealed interface VehicleScanState {
    data object Capturing : VehicleScanState
    data object Uploading : VehicleScanState
    data object Fusing : VehicleScanState
    data class Completed(
        val glbFile: File,
        val vertices: Int,
        val triangles: Int,
        val frameCount: Int,
    ) : VehicleScanState
    data class Error(val msg: String, val requiresStoragePermission: Boolean = false) : VehicleScanState
}

/** VIN 多视角采集：RS-D550 原始 disparity + HLSD8 原始 JPEG + 自包含 calibration.bin。 */
@HiltViewModel
class VehicleContourScanViewModel @Inject constructor(
    provider: CameraSourceProvider,
    private val bundleUploader: Scan3dBundleUploader,
    private val fusionRepo: ScanFusionRepository,
    private val calibrationFileProvider: CalibrationFileProvider,
) : ViewModel() {

    private val depthSource: CameraSource = provider.vinDepth()
    private val colorSource: CameraSource = provider.vinRgb()
    private val _state = MutableStateFlow<VehicleScanState>(VehicleScanState.Capturing)
    val state: StateFlow<VehicleScanState> = _state.asStateFlow()
    private val _shotCounts = MutableStateFlow(List(VehicleAngleDefs.size) { 0 })
    val shotCounts: StateFlow<List<Int>> = _shotCounts.asStateFlow()
    private val _activeAngle = MutableStateFlow(0)
    val activeAngle: StateFlow<Int> = _activeAngle.asStateFlow()
    private val _colorPreview = MutableStateFlow<Bitmap?>(null)
    val colorPreview: StateFlow<Bitmap?> = _colorPreview.asStateFlow()
    private val _depthPreview = MutableStateFlow<Bitmap?>(null)
    val depthPreview: StateFlow<Bitmap?> = _depthPreview.asStateFlow()
    private val _pointCloudPreview = MutableStateFlow(FloatArray(0))
    val pointCloudPreview: StateFlow<FloatArray> = _pointCloudPreview.asStateFlow()
    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()
    private val _lastSyncDeltaUs = MutableStateFlow(-1L)
    val lastSyncDeltaUs: StateFlow<Long> = _lastSyncDeltaUs.asStateFlow()
    private val _calibrationReady = MutableStateFlow(false)
    val calibrationReady: StateFlow<Boolean> = _calibrationReady.asStateFlow()

    val deviceState: StateFlow<CameraSourceState> = combine(
        depthSource.sourceState,
        colorSource.sourceState,
    ) { depth, color ->
        when {
            depth is CameraSourceState.Error -> depth
            color is CameraSourceState.Error -> color
            depth is CameraSourceState.NoDevice -> depth
            color is CameraSourceState.NoDevice -> color
            depth is CameraSourceState.Streaming && color is CameraSourceState.Streaming -> depth
            depth is CameraSourceState.Streaming -> CameraSourceState.Opening
            else -> depth
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraSourceState.Idle)

    @Volatile private var latestColor: ColorFrame? = null
    @Volatile private var latestDepth: DepthFrame? = null
    private val shots = mutableListOf<CapturedShot>()
    private var calibration: LocalCalibrationFile? = null
    private var sourceProfile: RgbdSourceProfile? = null
    private var sessionBinding: SessionBinding? = null
    private var acquired = false
    private var fusionJob: Job? = null
    private var captureJob: Job? = null
    private var bindingJob: Job? = null

    init {
        if (!calibrationFileProvider.hasExternalStorageAccess()) {
            _state.value = VehicleScanState.Error(storagePermissionMessage(), true)
        } else {
            acquireSources()
            fusionRepo.ensureRealtimeConnected()
        }
        viewModelScope.launch {
            var n = 0
            colorSource.colorFrames.collect { frame ->
                latestColor = frame
                if (sessionBinding != null && !matchesLockedColor(frame)) invalidateSession("HLSD8 彩色 profile 或设备 ID 已变化")
                if (n++ % PREVIEW_DECIMATION == 0) _colorPreview.value = withContext(Dispatchers.Default) { FrameRenderer.colorToBitmap(frame) }
                scheduleSessionBinding()
            }
        }
        viewModelScope.launch {
            var n = 0
            depthSource.depthFrames.collect { frame ->
                latestDepth = frame
                if (sessionBinding != null && !matchesLockedDepth(frame)) invalidateSession("RS-D550 深度 profile 或设备 ID 已变化")
                if (n++ % PREVIEW_DECIMATION == 0) _depthPreview.value = withContext(Dispatchers.Default) { FrameRenderer.depth16ToBitmap(frame) }
                scheduleSessionBinding()
            }
        }
        viewModelScope.launch {
            combine(depthSource.sourceState, colorSource.sourceState) { d, c -> d to c }.collect { (depth, color) ->
                if (sessionBinding != null && (depth is CameraSourceState.NoDevice || color is CameraSourceState.NoDevice || depth is CameraSourceState.Error || color is CameraSourceState.Error)) {
                    invalidateSession("相机断开或流异常，当前扫描会话已废弃，请重新加载标定后开始")
                }
            }
        }
    }

    private fun acquireSources() {
        if (acquired) return
        depthSource.acquire(); colorSource.acquire(); acquired = true
    }

    /** 两路进入稳定 profile 后立即加载标定，快门只允许使用已锁定会话。 */
    private fun scheduleSessionBinding() {
        if (_state.value != VehicleScanState.Capturing || sessionBinding != null || bindingJob?.isActive == true) return
        if (latestColor == null || latestDepth == null || !calibrationFileProvider.hasExternalStorageAccess()) return
        bindingJob = viewModelScope.launch {
            try {
                val color = latestColor ?: return@launch
                val depth = latestDepth ?: return@launch
                withContext(Dispatchers.Default) { ensureSessionBinding(color, depth) }
                _calibrationReady.value = true
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w(TAG, "VIN 标定绑定失败", e)
                clearShots()
                _calibrationReady.value = false
                _state.value = VehicleScanState.Error(
                    e.message ?: "VIN 标定文件不可用",
                    requiresStoragePermission = !calibrationFileProvider.hasExternalStorageAccess(),
                )
            }
        }
    }

    fun selectAngle(index: Int) { if (index in VehicleAngleDefs.indices) _activeAngle.value = index }

    fun capture() {
        if (_state.value != VehicleScanState.Capturing || _capturing.value) return
        if (!calibrationFileProvider.hasExternalStorageAccess()) {
            _state.value = VehicleScanState.Error(storagePermissionMessage(), true); return
        }
        if (!_calibrationReady.value || sessionBinding == null) {
            _state.value = VehicleScanState.Error("两路相机尚未完成 VIN 标定绑定，请等待首帧或重新扫描")
            return
        }
        val color = latestColor
        val depth = latestDepth
        if (color == null || depth == null) { Log.w(TAG, "采集跳过：尚无 RGB/Depth 帧"); return }
        val deltaUs = kotlin.math.abs(color.timestampUs - depth.timestampUs)
        _lastSyncDeltaUs.value = deltaUs
        if (deltaUs > PAIR_SYNC_TOLERANCE_US) return
        val angle = _activeAngle.value
        _capturing.value = true
        captureJob = viewModelScope.launch {
            try {
                val shot = withContext(Dispatchers.Default) { buildShot(color, depth, angle) }
                shots.add(shot)
                _shotCounts.value = _shotCounts.value.toMutableList().also { it[angle] = it[angle] + 1 }
                // 原始 mode25 disparity 没有 App 侧共享毫米内参；点云预览不伪造配准结果。
                _pointCloudPreview.value = FloatArray(0)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w(TAG, "采集失败", e)
                if (e is CalibrationFileException) {
                    invalidateSession(
                        e.message ?: "标定文件不可用",
                        requiresStoragePermission = !calibrationFileProvider.hasExternalStorageAccess(),
                    )
                } else {
                    _state.value = VehicleScanState.Error("采集失败：${e.message}")
                }
            } finally { _capturing.value = false }
        }
    }

    fun undo() {
        if (_capturing.value || _state.value != VehicleScanState.Capturing) return
        val angle = _activeAngle.value
        val index = shots.indexOfLast { it.angle == angle }
        if (index < 0) return
        shots.removeAt(index).rgbd.rgb.recycle()
        _shotCounts.value = _shotCounts.value.toMutableList().also { it[angle] = (it[angle] - 1).coerceAtLeast(0) }
    }

    fun finishAndUpload() {
        if (_state.value != VehicleScanState.Capturing || _capturing.value) return
        val captured = shots.toList()
        val lockedCalibration = calibration
        val lockedProfile = sourceProfile
        if (captured.size < MIN_SHOTS || lockedCalibration == null || lockedProfile == null || sessionBinding == null) {
            _state.value = VehicleScanState.Error("至少需采集 $MIN_SHOTS 张且完成 VIN 标定绑定的原始 RGBD")
            return
        }
        val sessionId = "scan-${System.currentTimeMillis()}"
        _state.value = VehicleScanState.Uploading
        fusionJob = viewModelScope.launch {
            try {
                calibrationFileProvider.verifyUnchanged(lockedCalibration)
                val awaitFusion = async { withTimeoutOrNull(FUSION_TIMEOUT_MS) { fusionRepo.fusionEvents.first { it.sessionKey == sessionId } } }
                bundleUploader.upload(captured.map { it.rgbd }, lockedCalibration, lockedProfile, sessionId)
                _state.value = VehicleScanState.Fusing
                val result = awaitFusion.await() ?: run { _state.value = VehicleScanState.Error("融合超时未返回（${FUSION_TIMEOUT_MS / 1000}s）"); return@launch }
                val glb = fusionRepo.downloadResultGlb(sessionId)
                _state.value = VehicleScanState.Completed(glb, result.vertices, result.triangles, result.frameCount)
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (e is CalibrationFileException) {
                    clearShots()
                    calibration = null
                    sourceProfile = null
                    sessionBinding = null
                    _calibrationReady.value = false
                }
                _state.value = VehicleScanState.Error(
                    "上传/融合失败：${e.message}",
                    requiresStoragePermission = e is CalibrationFileException &&
                        !calibrationFileProvider.hasExternalStorageAccess(),
                )
            }
        }
    }

    fun restart() {
        fusionJob?.cancel(); fusionJob = null
        captureJob?.cancel(); captureJob = null
        bindingJob?.cancel(); bindingJob = null
        _capturing.value = false
        clearShots()
        calibration = null; sourceProfile = null; sessionBinding = null; _calibrationReady.value = false
        _shotCounts.value = List(VehicleAngleDefs.size) { 0 }
        _pointCloudPreview.value = FloatArray(0)
        if (!calibrationFileProvider.hasExternalStorageAccess()) {
            _state.value = VehicleScanState.Error(storagePermissionMessage(), true)
        } else {
            acquireSources(); fusionRepo.ensureRealtimeConnected(); _state.value = VehicleScanState.Capturing
        }
    }

    override fun onCleared() {
        bindingJob?.cancel()
        clearShots()
        if (acquired) { depthSource.release(); colorSource.release() }
    }

    private fun buildShot(color: ColorFrame, depth: DepthFrame, angle: Int): CapturedShot {
        val profile = ensureSessionBinding(color, depth)
        val localCalibration = requireNotNull(calibration)
        calibrationFileProvider.verifyUnchanged(localCalibration)
        val rgb = FrameRenderer.colorToOriginalBitmap(color)
            ?: throw IllegalStateException("HLSD8 原始 MJPEG 解码失败")
        require(rgb.width == profile.colorWidth && rgb.height == profile.colorHeight) {
            "HLSD8 原始帧尺寸 ${rgb.width}x${rgb.height} 与 profile ${profile.colorProfile} 不一致"
        }
        val depthBytes = ByteArray(depth.width * depth.height * 2)
        depth.data.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            rewind(); require(remaining() >= depthBytes.size) { "depth 帧不完整" }; get(depthBytes)
        }
        val confBytes = depth.confidence?.let { buffer ->
            ByteArray(depth.width * depth.height).also { bytes ->
                buffer.duplicate().apply { rewind(); require(remaining() >= bytes.size) { "confidence 帧不完整" }; get(bytes) }
            }
        }
        return CapturedShot(
            angle,
            RawRgbdShot(rgb, depthBytes, confBytes, color.timestampUs, depth.timestampUs),
        )
    }

    private fun ensureSessionBinding(color: ColorFrame, depth: DepthFrame): RgbdSourceProfile {
        require(color.pixelType == "HLSD8_MJPEG") { "VIN 采集必须使用 HLSD8 原始 MJPEG" }
        require(color.encodedWidth == VIN_COLOR_WIDTH && color.encodedHeight == VIN_COLOR_HEIGHT) {
            "HLSD8 当前 profile 为 ${color.encodedWidth}x${color.encodedHeight}，期望 ${VIN_COLOR_WIDTH}x${VIN_COLOR_HEIGHT}"
        }
        require(depth.sampleFormat == DepthSampleFormat.DISPARITY_X8_U16) { "RS-D550 必须输出 mode25 disparity×8" }
        require(depth.width == VIN_DEPTH_WIDTH && depth.height == VIN_DEPTH_HEIGHT) {
            "RS-D550 当前 profile 为 ${depth.width}x${depth.height}，期望 ${VIN_DEPTH_WIDTH}x${VIN_DEPTH_HEIGHT} mode25"
        }
        val depthId = DefaultCalibrationFileProvider.normalizeDepthDeviceId(depthSource.deviceSerial ?: "")
        val colorId = colorSource.deviceSerial?.let { DefaultCalibrationFileProvider.normalizeDeviceId(it) }
            ?: throw IllegalStateException("未读取到 HLSD8 彩色设备 ID")
        val profile = RgbdSourceProfile(depth.width, depth.height, "vin_creator_disparity_u16", color.encodedWidth, color.encodedHeight, depthId, colorId)
        val existing = sessionBinding
        if (existing != null && existing.profile != profile) throw IllegalStateException("设备或 profile 在扫描会话中发生变化，当前会话已废弃")
        if (existing == null) {
            val loaded = calibrationFileProvider.load(depthId)
            val currentDepthId = DefaultCalibrationFileProvider.normalizeDepthDeviceId(depthSource.deviceSerial ?: "")
            val currentColorId = DefaultCalibrationFileProvider.normalizeDeviceId(colorSource.deviceSerial ?: "")
            require(currentDepthId == depthId && currentColorId == colorId) {
                "加载标定期间设备 ID 发生变化，请重新插拔相机"
            }
            calibration = loaded
            sourceProfile = profile
            sessionBinding = SessionBinding(profile, loaded.sha256)
        }
        return profile
    }

    private fun matchesLockedColor(frame: ColorFrame): Boolean = sessionBinding?.profile?.let {
        frame.encodedWidth == it.colorWidth && frame.encodedHeight == it.colorHeight &&
            runCatching { DefaultCalibrationFileProvider.normalizeDeviceId(colorSource.deviceSerial ?: "") }
                .getOrNull() == it.colorDeviceId
    } == true

    private fun matchesLockedDepth(frame: DepthFrame): Boolean = sessionBinding?.profile?.let {
        frame.width == it.depthWidth && frame.height == it.depthHeight &&
            frame.sampleFormat == DepthSampleFormat.DISPARITY_X8_U16 &&
            runCatching { DefaultCalibrationFileProvider.normalizeDepthDeviceId(depthSource.deviceSerial ?: "") }
                .getOrNull() == it.depthDeviceId
    } == true

    private fun invalidateSession(message: String, requiresStoragePermission: Boolean = false) {
        if (_state.value == VehicleScanState.Uploading || _state.value == VehicleScanState.Fusing) return
        clearShots(); calibration = null; sourceProfile = null; sessionBinding = null
        _calibrationReady.value = false
        _shotCounts.value = List(VehicleAngleDefs.size) { 0 }
        _state.value = VehicleScanState.Error(message, requiresStoragePermission)
    }

    private fun clearShots() { shots.forEach { runCatching { it.rgbd.rgb.recycle() } }; shots.clear() }

    private data class CapturedShot(val angle: Int, val rgbd: RawRgbdShot)
    private data class SessionBinding(val profile: RgbdSourceProfile, val calibrationSha256: String)

    private fun storagePermissionMessage() = "未授予所有文件访问权限，无法读取 /storage/emulated/0/VIN/param/VIN_<Depth设备ID>.bin"

    companion object {
        private const val TAG = "VehicleContourScanVM"
        private const val PREVIEW_DECIMATION = 4
        private const val MIN_SHOTS = 2
        private const val FUSION_TIMEOUT_MS = 180_000L
        private const val PAIR_SYNC_TOLERANCE_US = 15_000L
        private const val VIN_COLOR_WIDTH = 4160
        private const val VIN_COLOR_HEIGHT = 832
        private const val VIN_DEPTH_WIDTH = 640
        private const val VIN_DEPTH_HEIGHT = 128
    }
}
