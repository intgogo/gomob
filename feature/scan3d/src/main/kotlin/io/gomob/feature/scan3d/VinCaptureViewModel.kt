package io.gomob.feature.scan3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.JsonWriter
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.scan.VinRepository
import io.gomob.data.scan.VinCropImage
import io.gomob.data.scan.VinRecognitionResult
import io.gomob.data.scan.VinPreviewCalibrationKey
import io.gomob.data.scan.VinRestoreOutcome
import io.gomob.data.scan.VinRestoreRejectReason
import io.gomob.model.ColorFrame
import io.gomob.model.DepthFrame
import io.gomob.model.DepthSampleFormat
import io.gomob.nativebridge.camera.CameraSource
import io.gomob.nativebridge.camera.CameraSourceProvider
import io.gomob.nativebridge.camera.CameraSourceState
import io.gomob.nativebridge.camera.hlsd8PreviewSampleSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringWriter
import javax.inject.Inject

/** VIN 数码拓印识别状态机。 */
sealed interface VinCaptureState {
    /** 取景中（实时 RGB 预览，等用户拍照）。 */
    data object Preview : VinCaptureState
    /** 已拍照，权威正射图正在进行外部 OCR。 */
    data object Recognizing : VinCaptureState
    data class Result(
        val result: VinRecognitionResult,
        val crops: List<VinCharacterCropPreview>,
    ) : VinCaptureState
    data class Error(val msg: String) : VinCaptureState
}

/** Compose 只持有已解码的小尺寸单字符图，原始 WebP 仍留在领域结果中。 */
data class VinCharacterCropPreview(
    val position: Int,
    val character: String,
    val confidence: Double,
    val bitmap: Bitmap,
)

/** 服务端权威正射还原状态。 */
sealed interface VinRestoreState {
    data object Preview : VinRestoreState
    data object Processing : VinRestoreState
    data object Ready : VinRestoreState
    data class Rejected(val msg: String) : VinRestoreState
    data class Error(val msg: String) : VinRestoreState
}

/** 深度预览空间配准状态；标定不可用时必须明确显示原始深度，禁止伪装已对齐。 */
sealed interface VinPreviewAlignmentState {
    data object WaitingForRig : VinPreviewAlignmentState
    data object Loading : VinPreviewAlignmentState
    data class Ready(val sha256: String, val version: Int) : VinPreviewAlignmentState
    data class Unavailable(val message: String) : VinPreviewAlignmentState
}

private data class RenderedVinPreview(
    val color: Bitmap,
    val depth: Bitmap,
    val projected: VinProjectedDepth?,
    val colorTimestampUs: Long,
    val depthTimestampUs: Long,
)

internal const val VIN_HLSD8_CAPTURE_W = 4160
internal const val VIN_HLSD8_CAPTURE_H = 832
internal const val VIN_RUBBING_PREVIEW_MAX_W = 1600

/** 原厂 4425px 宽用户还原图只为屏幕解码到可见分辨率，原始字节仍完整保留给 OCR。 */
internal fun vinRubbingPreviewSampleSize(srcWidth: Int, maxWidth: Int): Int {
    if (srcWidth <= 0 || maxWidth <= 0) return 1
    var sample = 1
    while (srcWidth / sample > maxWidth) sample *= 2
    return sample
}

/** 把服务端结构化判废转换成手机端可执行的重拍提示。 */
internal fun vinRestoreRejectMessage(result: VinRestoreOutcome, seq: Int): String =
    when (result.rejectReason) {
        VinRestoreRejectReason.TiltTooLarge ->
            "判废：承印面过斜 %.0f°（>70°），请正对钢牌再拍｜已存第 $seq 张".format(result.tiltDeg)
        VinRestoreRejectReason.VinNotDetected ->
            "未检测到完整 VIN，请让 17 位字符全部进入红框、对焦清晰后重拍｜已存第 $seq 张"
        VinRestoreRejectReason.RgbdOutOfSync ->
            "判废：RGBD 回调时间差 %.1fms，请稳住设备重拍｜已存第 $seq 张".format(
                result.syncDeltaUs / 1000.0,
            )
        VinRestoreRejectReason.TextAnchorUnreliable ->
            "未稳定锚定完整 17 位 VIN，请确保整串字符完整入框、清晰无遮挡并减少反光后重拍｜已存第 $seq 张"
        VinRestoreRejectReason.CalibrationUnavailable ->
            "当前相机的 VIN 标定尚未在网页端发布，已保留第 $seq 张原始采集"
        is VinRestoreRejectReason.Unknown, null ->
            "判废：当前采集无法稳定还原，请调整取景后重拍｜已存第 $seq 张"
    }

/** VINCreator 工厂几何契约检查；不满足时只留原始采集，禁止送进错误标定。 */
internal fun vinCaptureInputError(
    pixelType: String,
    encodedWidth: Int,
    encodedHeight: Int,
    depthDeviceSerial: String?,
    colorDeviceSerial: String?,
    depthSampleFormat: DepthSampleFormat = DepthSampleFormat.DISPARITY_X8_U16,
): String? = when {
    pixelType != "HLSD8_MJPEG" ->
        "未连接 HLSD8 彩色相机，当前画面不能用于 VIN 工厂正射"
    encodedWidth != VIN_HLSD8_CAPTURE_W || encodedHeight != VIN_HLSD8_CAPTURE_H ->
        "HLSD8 当前为 ${encodedWidth}×${encodedHeight}，必须使用原厂 ${VIN_HLSD8_CAPTURE_W}×${VIN_HLSD8_CAPTURE_H} 采集档"
    depthDeviceSerial.isNullOrBlank() ->
        "未读取到深度相机序列号，无法选择逐设备标定"
    colorDeviceSerial.isNullOrBlank() ->
        "未读取到 HLSD8 彩色相机序列号，无法选择双相机标定"
    depthSampleFormat != DepthSampleFormat.DISPARITY_X8_U16 ->
        "RS-D550 深度流不是原厂 mode25 视差格式，禁止送入 VIN 工厂还原"
    else -> null
}

internal data class VinCaptureReadiness(
    val ready: Boolean,
    val message: String,
)

/** 快门只在两颗物理相机都收到真实首帧后开放，避免把开流失败延迟成泛化 RGBD 超时。 */
internal fun vinCaptureReadiness(
    hasDedicatedColorSource: Boolean,
    colorFrameReady: Boolean,
    depthFrameReady: Boolean,
    colorState: CameraSourceState,
    depthState: CameraSourceState,
): VinCaptureReadiness {
    if (!hasDedicatedColorSource) {
        return VinCaptureReadiness(false, "未连接 HLSD8 彩色相机，VIN 拍照不可用")
    }
    cameraReadinessIssue("HLSD8 彩色相机", colorState)?.let {
        return VinCaptureReadiness(false, it)
    }
    if (!colorFrameReady) {
        return VinCaptureReadiness(false, "正在等待 HLSD8 彩色首帧…")
    }
    cameraReadinessIssue("RS-D550 深度相机", depthState)?.let {
        return VinCaptureReadiness(false, it)
    }
    if (!depthFrameReady) {
        return VinCaptureReadiness(false, "正在等待 RS-D550 深度首帧…")
    }
    return VinCaptureReadiness(true, "RGBD 双路已就绪")
}

private fun cameraReadinessIssue(label: String, state: CameraSourceState): String? = when (state) {
    CameraSourceState.Idle -> "$label 尚未启动"
    CameraSourceState.NoDevice -> "未检测到 $label"
    CameraSourceState.WaitingPermission -> "正在等待 $label USB 权限"
    CameraSourceState.Opening -> "正在打开 $label…"
    is CameraSourceState.Error -> "${label}异常：${state.message}"
    is CameraSourceState.Streaming -> null
}

/**
 * VIN 数码拓印 VM —— 同步消费真实 RGBD，服务端完成权威正射，随后调用外部算法输出纯 OCR 结果。
 */
@HiltViewModel
class VinCaptureViewModel @Inject constructor(
    provider: CameraSourceProvider,
    private val vinRepo: VinRepository,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    // VIN 是固定双相机 rig，不能在页面创建瞬间因设备未枚举而静默绑定到 Berxel。
    private val source: CameraSource = provider.vinDepth()

    /** HLSD8 辅助 RGB 相机（独立第二颗 USB 相机）；未插着时服务保持 NoDevice 并等待热插拔。
     *  ★ 必须先于 [source] acquire 点亮补光，否则 eYs3D mode25 彩色暖机失败 → FrameGrabber 0 帧（与 DepthCameraViewModel 同序，用户 2026-06-15 实测）。 */
    private val rgbSource: CameraSource = provider.vinRgb()

    /** VIN 彩色源只能是 HLSD8；禁止回落深度相机内置低分辨率彩色并套错标定。 */
    private val colorSource: CameraSource = rgbSource

    private val _state = MutableStateFlow<VinCaptureState>(VinCaptureState.Preview)
    val state: StateFlow<VinCaptureState> = _state.asStateFlow()

    private val _colorPreview = MutableStateFlow<Bitmap?>(null)
    val colorPreview: StateFlow<Bitmap?> = _colorPreview.asStateFlow()

    // 深度实时预览（turbo 伪彩，自动量程）。顶部"深度图"横条用，与"彩色图"横条并列显示真帧。
    private val _depthPreview = MutableStateFlow<Bitmap?>(null)
    val depthPreview: StateFlow<Bitmap?> = _depthPreview.asStateFlow()

    private val _previewAlignment = MutableStateFlow<VinPreviewAlignmentState>(VinPreviewAlignmentState.WaitingForRig)
    val previewAlignment: StateFlow<VinPreviewAlignmentState> = _previewAlignment.asStateFlow()

    private val _captureQuality = MutableStateFlow<VinCaptureQuality>(VinCaptureQuality.Waiting)
    internal val captureQuality: StateFlow<VinCaptureQuality> = _captureQuality.asStateFlow()

    private val _autoCaptureDecision = MutableStateFlow<VinAutoCaptureDecision>(VinAutoCaptureDecision.Waiting)
    internal val autoCaptureDecision: StateFlow<VinAutoCaptureDecision> = _autoCaptureDecision.asStateFlow()

    private val _depthRoiMetrics = MutableStateFlow<VinDepthRoiMetrics?>(null)
    internal val depthRoiMetrics: StateFlow<VinDepthRoiMetrics?> = _depthRoiMetrics.asStateFlow()

    // 服务端权威拓印还原图。
    private val _rubbing = MutableStateFlow<Bitmap?>(null)
    val rubbing: StateFlow<Bitmap?> = _rubbing.asStateFlow()

    private val _restoreState = MutableStateFlow<VinRestoreState>(VinRestoreState.Preview)
    val restoreState: StateFlow<VinRestoreState> = _restoreState.asStateFlow()

    // 拍照进行中（防重入 + UI 转圈）。
    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    // 拍照结果/错误一行提示（覆盖率/rms 或失败原因）。
    private val _captureMsg = MutableStateFlow<String?>(null)
    val captureMsg: StateFlow<String?> = _captureMsg.asStateFlow()

    private val _colorFrameReady = MutableStateFlow(false)
    private val _depthFrameReady = MutableStateFlow(false)

    internal val captureReadiness: StateFlow<VinCaptureReadiness> = combine(
        colorSource.sourceState,
        source.sourceState,
        _colorFrameReady,
        _depthFrameReady,
    ) { colorState, depthState, colorFrameReady, depthFrameReady ->
        vinCaptureReadiness(
            hasDedicatedColorSource = true,
            colorFrameReady = colorFrameReady,
            depthFrameReady = depthFrameReady,
            colorState = colorState,
            depthState = depthState,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        vinCaptureReadiness(
            hasDedicatedColorSource = true,
            colorFrameReady = false,
            depthFrameReady = false,
            colorState = colorSource.sourceState.value,
            depthState = source.sourceState.value,
        ),
    )

    val deviceState: StateFlow<CameraSourceState> = source.sourceState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraSourceState.Idle)

    // 两颗独立 USB 相机按同一 host 单调时钟做 burst 配对；回调时间不是曝光时间。
    private val framePairer = VinRgbdPairer(
        maxDeltaUs = VIN_PAIR_MAX_CALLBACK_DELTA_US,
        maxAgeUs = VIN_PAIR_MAX_AGE_US,
    )
    private val autoCaptureWorkflow = VinAutoCaptureWorkflow()
    private var captureJob: Job? = null
    private var captureGeneration = 0L

    // 服务端原厂 4425×600 用户正射 PNG：拍照成功后完整保留，「识别」时发给外部 VIN OCR。
    // 每次新拍照 / 重拍清空，避免对陈旧图识别。
    @Volatile private var restoredRubbingPng: ByteArray? = null
    private var recognizeJob: Job? = null
    private var recognizeGeneration = 0L
    @Volatile private var cameraPermissionGranted = false
    @Volatile private var rgbSourceAcquired = false
    @Volatile private var sourceAcquired = false
    private var sourceStartupJob: Job? = null
    private var sourceStartupGeneration = 0L
    private var previewCalibrationKey: VinPreviewCalibrationKey? = null
    private var previewCalibrationJob: Job? = null
    private var previewProjector: VinPreviewProjector? = null
    private var previewRenderJob: Job? = null
    private var previewRenderJobGeneration = 0L
    private var previewRenderRequested = false
    private var previewRenderGeneration = 0L
    private var lastRenderedPreviewPair: Pair<Long, Long>? = null
    private var previewProjectionLogCounter = 0
    @Volatile private var previewRoi: VinPreviewRoi? = null
    @Volatile private var previewSessionMinTimestampUs = Long.MIN_VALUE
    private var previewOutputWidth = 0
    private var previewOutputHeight = 0

    init {
        viewModelScope.launch {
            colorSource.sourceState.collect { state ->
                if (state !is CameraSourceState.Streaming) {
                    _colorFrameReady.value = false
                    _captureQuality.value = VinCaptureQuality.Waiting
                    resetAutoCaptureIfPreviewInterrupted()
                }
            }
        }
        viewModelScope.launch {
            source.sourceState.collect { state ->
                if (state !is CameraSourceState.Streaming) {
                    _depthFrameReady.value = false
                    _captureQuality.value = VinCaptureQuality.Waiting
                    resetAutoCaptureIfPreviewInterrupted()
                }
            }
        }
        viewModelScope.launch {
            colorSource.colorFrames.collect { frame ->
                if (!rgbSourceAcquired || frame.timestampUs <= previewSessionMinTimestampUs) return@collect
                _colorFrameReady.value = true
                framePairer.offerColor(frame)
                requestPreviewRender()
            }
        }
        viewModelScope.launch {
            source.depthFrames.collect { frame ->
                if (!sourceAcquired || frame.timestampUs <= previewSessionMinTimestampUs) return@collect
                _depthFrameReady.value = true
                framePairer.offerDepth(frame)
                requestPreviewRender()
            }
        }
    }

    /** 两路预览必须消费同一个最近邻帧对，避免设备移动时空间配准正确但画面时序仍错位。 */
    private fun requestPreviewRender(force: Boolean = false) {
        if (!isPreviewRenderAllowed()) return
        if (force) {
            previewRenderGeneration++
            lastRenderedPreviewPair = null
        }
        previewRenderRequested = true
        if (previewRenderJob?.isActive == true) return
        val jobGeneration = ++previewRenderJobGeneration
        previewRenderJob = viewModelScope.launch {
            try {
                while (previewRenderRequested) {
                    previewRenderRequested = false
                    if (!isPreviewRenderAllowed()) break
                    val pair = framePairer.snapshot() ?: break
                    ensurePreviewCalibration(pair.depth.width, pair.depth.height, pair.color.encodedWidth, pair.color.encodedHeight)
                    val pairIdentity = pair.color.timestampUs to pair.depth.timestampUs
                    if (pairIdentity == lastRenderedPreviewPair) continue
                    val renderGeneration = previewRenderGeneration
                    val projector = previewProjector
                    val roi = previewRoi
                    val startedAtMs = SystemClock.elapsedRealtime()
                    val rendered = withContext(Dispatchers.Default) {
                        val colorBitmap = FrameRenderer.colorToBitmap(pair.color) ?: return@withContext null
                        val projected = projector?.project(pair.depth, colorBitmap.width, colorBitmap.height, roi)
                        val depthBitmap = if (projected != null) {
                            FrameRenderer.projectedDepthToBitmap(projected)
                        } else {
                            FrameRenderer.depth16ToBitmap(pair.depth, maskByConfidence = false)
                        } ?: return@withContext null
                        RenderedVinPreview(
                            color = colorBitmap,
                            depth = depthBitmap,
                            projected = projected,
                            colorTimestampUs = pair.color.timestampUs,
                            depthTimestampUs = pair.depth.timestampUs,
                        )
                    } ?: continue
                    if (renderGeneration != previewRenderGeneration) continue
                    if (!isPreviewRenderAllowed()) continue
                    _colorPreview.value = rendered.color
                    _depthPreview.value = rendered.depth
                    previewOutputWidth = rendered.color.width
                    previewOutputHeight = rendered.color.height
                    val roiMetrics = rendered.projected?.roiMetrics
                    val captureQuality = vinCaptureQuality(roiMetrics)
                    _depthRoiMetrics.value = roiMetrics
                    _captureQuality.value = captureQuality
                    lastRenderedPreviewPair = rendered.colorTimestampUs to rendered.depthTimestampUs
                    rendered.projected?.let { projected ->
                        previewProjectionLogCounter++
                        if (previewProjectionLogCounter % PREVIEW_PROJECTION_LOG_INTERVAL == 1) {
                            Log.i(
                                TAG,
                                "aligned preview renderMs=${SystemClock.elapsedRealtime() - startedAtMs} " +
                                    "valid=${projected.validDepthPoints} inView=${projected.pointsInColorView} " +
                                    "coverage=${projected.coveredPixels.toDouble() / (projected.width * projected.height)} " +
                                    "roiCoverage=${projected.roiMetrics?.coverageRatio} " +
                                    "roiProjectedPointRatio=${projected.roiMetrics?.projectedPointRatio} " +
                                    "roiDistanceP10Mm=${projected.roiMetrics?.distanceP10Mm} " +
                                    "roiDistanceMedianMm=${projected.roiMetrics?.distanceMedianMm} " +
                                    "roiFarEnough=${projected.roiMetrics?.farEnoughRatio}",
                            )
                        }
                    }
                    observeAutoCapture(rendered, captureQuality)
                }
            } finally {
                if (jobGeneration == previewRenderJobGeneration) {
                    previewRenderJob = null
                    if (previewRenderRequested) requestPreviewRender()
                }
            }
        }
    }

    private fun isPreviewRenderAllowed(): Boolean =
        !_capturing.value &&
            _state.value == VinCaptureState.Preview &&
            _restoreState.value !is VinRestoreState.Ready &&
            (rgbSourceAcquired || sourceAcquired)

    internal fun setPreviewRoi(roi: VinPreviewRoi) {
        if (!roi.isValid || previewRoi == roi) return
        previewRoi = roi
        _depthRoiMetrics.value = null
        _captureQuality.value = VinCaptureQuality.Waiting
        resetAutoCaptureWorkflow()
        requestPreviewRender(force = true)
    }

    private fun observeAutoCapture(rendered: RenderedVinPreview, quality: VinCaptureQuality) {
        if (
            _restoreState.value !is VinRestoreState.Preview ||
            _state.value != VinCaptureState.Preview ||
            _capturing.value ||
            !captureReadiness.value.ready
        ) {
            return
        }
        val decision = autoCaptureWorkflow.observe(
            VinAutoCaptureObservation(
                colorTimestampUs = rendered.colorTimestampUs,
                depthTimestampUs = rendered.depthTimestampUs,
                quality = quality,
            ),
        )
        _autoCaptureDecision.value = decision
        when (decision) {
            is VinAutoCaptureDecision.Stabilizing -> Log.d(
                TAG,
                "auto capture stabilizing frames=${decision.readyFrames}/$VIN_AUTO_CAPTURE_MIN_READY_FRAMES " +
                    "durationMs=${decision.stableDurationUs / 1_000.0} " +
                    "distanceMm=${(quality as VinCaptureQuality.Ready).metrics.distanceMedianMm}",
            )
            VinAutoCaptureDecision.Trigger -> {
                Log.i(TAG, "auto capture stable, requesting one capture")
                requestCapture(VinCaptureOrigin.Auto)
            }
            VinAutoCaptureDecision.Triggered,
            VinAutoCaptureDecision.Waiting -> Unit
        }
    }

    private fun resetAutoCaptureIfPreviewInterrupted() {
        if (_restoreState.value is VinRestoreState.Preview && !_capturing.value) {
            resetAutoCaptureWorkflow()
        }
    }

    private fun resetAutoCaptureWorkflow() {
        autoCaptureWorkflow.reset()
        _autoCaptureDecision.value = VinAutoCaptureDecision.Waiting
    }

    private fun rearmAutoCaptureAfterTransientQualityFailure() {
        autoCaptureWorkflow.rearmAfterTransientQualityFailure()
        _autoCaptureDecision.value = VinAutoCaptureDecision.Waiting
    }

    private fun lockAutoCaptureAfterFailure() {
        autoCaptureWorkflow.lockAfterCaptureFailure()
        _autoCaptureDecision.value = VinAutoCaptureDecision.Triggered
    }

    private fun ensurePreviewCalibration(depthWidth: Int, depthHeight: Int, colorWidth: Int, colorHeight: Int) {
        val depthSerial = source.deviceSerial?.trim()?.takeIf(String::isNotEmpty)
        val colorSerial = rgbSource.deviceSerial?.trim()?.takeIf(String::isNotEmpty)
        if (depthSerial == null || colorSerial == null || colorWidth <= 0 || colorHeight <= 0) {
            if (previewCalibrationKey == null) _previewAlignment.value = VinPreviewAlignmentState.WaitingForRig
            return
        }
        val key = VinPreviewCalibrationKey(
            depthSerial = depthSerial,
            colorSerial = colorSerial,
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            colorWidth = colorWidth,
            colorHeight = colorHeight,
        )
        if (previewCalibrationKey == key) return

        previewCalibrationKey = key
        previewCalibrationJob?.cancel()
        previewProjector = null
        _previewAlignment.value = VinPreviewAlignmentState.Loading
        previewCalibrationJob = viewModelScope.launch {
            try {
                val calibration = vinRepo.previewCalibration(key)
                if (previewCalibrationKey != key) return@launch
                previewProjector = VinPreviewProjector(calibration)
                _previewAlignment.value = VinPreviewAlignmentState.Ready(
                    sha256 = calibration.calibrationSha256,
                    version = calibration.calibrationVersion,
                )
                Log.i(
                    TAG,
                    "VIN preview calibration ready key=$key sha=${calibration.calibrationSha256} " +
                        "version=${calibration.calibrationVersion}",
                )
                requestPreviewRender(force = true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (previewCalibrationKey == key) {
                    val message = error.message?.takeIf(String::isNotBlank) ?: "服务端未返回可用标定"
                    _previewAlignment.value = VinPreviewAlignmentState.Unavailable(message)
                    Log.w(TAG, "VIN preview calibration unavailable key=$key: $message")
                    requestPreviewRender(force = true)
                }
            } finally {
                if (previewCalibrationKey == key) previewCalibrationJob = null
            }
        }
    }

    /** Android 15 对 UVC 设备先校验 CAMERA；页面授权完成前禁止启动 USB 权限链。 */
    fun setCameraPermissionGranted(granted: Boolean) {
        cameraPermissionGranted = granted
        if (granted) {
            if (_captureMsg.value == CAMERA_PERMISSION_MESSAGE) _captureMsg.value = null
            if (_restoreState.value !is VinRestoreState.Ready) acquireSourcesIfNeeded()
        } else {
            _captureMsg.value = CAMERA_PERMISSION_MESSAGE
            _colorFrameReady.value = false
            _depthFrameReady.value = false
            _depthRoiMetrics.value = null
            _captureQuality.value = VinCaptureQuality.Waiting
            resetAutoCaptureIfPreviewInterrupted()
            releaseSourcesIfNeeded()
        }
    }

    @Synchronized
    private fun acquireSourcesIfNeeded() {
        if (!cameraPermissionGranted || rgbSourceAcquired || sourceAcquired || sourceStartupJob?.isActive == true) return

        previewSessionMinTimestampUs = framePairer.nowUs()
        framePairer.clear()
        val sessionMinTimestampUs = previewSessionMinTimestampUs
        // VINCreator 真机顺序：HLSD8 全分辨率预览首帧 → 80ms 电源沉降 → RS-D550。
        // 两个 acquire 都会异步开流，连续调用并不表示串行；并发初始化会让 mode25 偶发 0 帧。
        rgbSourceAcquired = true
        rgbSource.acquire()
        val generation = ++sourceStartupGeneration
        sourceStartupJob = viewModelScope.launch {
            try {
                val rgbReady = withTimeoutOrNull(RGB_READY_TIMEOUT_MS) {
                    rgbSource.colorFrames.first { it.timestampUs > sessionMinTimestampUs }
                }
                if (!cameraPermissionGranted || generation != sourceStartupGeneration) return@launch
                if (rgbReady == null) {
                    Log.e(TAG, "HLSD8 首帧等待超时，继续启动深度以暴露真实设备状态")
                } else {
                    delay(RGB_TO_DEPTH_SETTLE_MS)
                }
                if (cameraPermissionGranted && generation == sourceStartupGeneration) acquireDepthSource()
            } finally {
                synchronized(this@VinCaptureViewModel) {
                    if (generation == sourceStartupGeneration) sourceStartupJob = null
                }
            }
        }
    }

    @Synchronized
    private fun releaseSourcesIfNeeded() {
        sourceStartupGeneration++
        sourceStartupJob?.cancel()
        sourceStartupJob = null
        if (sourceAcquired) {
            sourceAcquired = false
            source.release()
        }
        if (rgbSourceAcquired) {
            rgbSourceAcquired = false
            rgbSource.release()
        }
    }

    /** 成功结果一旦形成，先封住旧帧和预览任务，再释放双相机，最后才向 UI 发布 Ready。 */
    private fun suspendSourcesAfterSuccessfulRestore(
        capturedColorPreview: Bitmap,
        capturedDepthPreview: Bitmap,
        capturedRoiMetrics: VinDepthRoiMetrics,
    ) {
        previewRenderGeneration++
        previewRenderRequested = false
        previewRenderJob?.cancel()
        lastRenderedPreviewPair = null
        framePairer.clear()
        previewSessionMinTimestampUs = framePairer.nowUs()
        _colorFrameReady.value = false
        _depthFrameReady.value = false
        _captureQuality.value = VinCaptureQuality.Waiting
        _depthRoiMetrics.value = capturedRoiMetrics
        _colorPreview.value = capturedColorPreview
        _depthPreview.value = capturedDepthPreview
        previewOutputWidth = capturedColorPreview.width
        previewOutputHeight = capturedColorPreview.height
        releaseSourcesIfNeeded()
        Log.i(TAG, "规范化还原成功，本页面 VIN 双相机 lease 已释放")
    }

    /** 新一轮扫描不得继承上一轮任何帧、质量状态或预览 Bitmap。 */
    private fun resetPreviewForNewScan() {
        previewRenderGeneration++
        previewRenderRequested = false
        previewRenderJob?.cancel()
        lastRenderedPreviewPair = null
        framePairer.clear()
        previewSessionMinTimestampUs = framePairer.nowUs()
        _colorFrameReady.value = false
        _depthFrameReady.value = false
        _depthRoiMetrics.value = null
        _captureQuality.value = VinCaptureQuality.Waiting
        resetAutoCaptureWorkflow()
        _colorPreview.value = null
        _depthPreview.value = null
        previewOutputWidth = 0
        previewOutputHeight = 0
    }

    /** 手动快门与自动稳定门最终都收口到同一个防重入口。 */
    fun capture() {
        requestCapture(VinCaptureOrigin.Manual)
    }

    /** 保存原始同步 RGBD，并上传服务端生成唯一权威规范化还原图。 */
    private fun requestCapture(origin: VinCaptureOrigin) {
        if (_capturing.value || _state.value != VinCaptureState.Preview) return
        val restoreState = _restoreState.value
        val restoreAllowsCapture = restoreState is VinRestoreState.Preview ||
            (origin == VinCaptureOrigin.Manual &&
                (restoreState is VinRestoreState.Rejected || restoreState is VinRestoreState.Error))
        if (!restoreAllowsCapture) return
        val readiness = captureReadiness.value
        if (!readiness.ready) {
            _captureMsg.value = readiness.message
            return
        }
        val previewQuality = _captureQuality.value
        val captureProjector = previewProjector
        val captureRoi = previewRoi
        val capturePreviewWidth = previewOutputWidth
        val capturePreviewHeight = previewOutputHeight
        if (
            previewQuality !is VinCaptureQuality.Ready ||
            captureProjector == null || captureRoi == null ||
            capturePreviewWidth <= 0 || capturePreviewHeight <= 0
        ) {
            _captureMsg.value = vinCaptureGuidance(previewQuality)
            return
        }
        if (!autoCaptureWorkflow.tryStartCapture(origin)) return
        _autoCaptureDecision.value = VinAutoCaptureDecision.Triggered
        Log.i(TAG, "capture request accepted origin=$origin")
        val generation = ++captureGeneration
        captureJob?.cancel()
        recognizeGeneration++
        recognizeJob?.cancel()
        recognizeJob = null
        _capturing.value = true
        _captureMsg.value = if (origin == VinCaptureOrigin.Auto) {
            "取景稳定，正在自动采集同步 RGBD…"
        } else {
            "正在采集同步 RGBD…"
        }
        // 新拍照作废上一张的还原图与 OCR 结果（回取景态，等本次还原成功再供「确认」识别）。
        restoredRubbingPng = null
        _rubbing.value = null
        _restoreState.value = VinRestoreState.Processing
        _state.value = VinCaptureState.Preview
        val clickTimestampUs = framePairer.nowUs()
        captureJob = viewModelScope.launch {
            try {
                var selectedBurst: VinRgbdBurstResult? = null
                var selectedProjection: VinProjectedDepth? = null
                var selectedAttempt = 0
                var attemptWatermarkUs = clickTimestampUs
                var lastBurst: VinRgbdBurstResult? = null
                var lastRejectedMetrics: VinDepthRoiMetrics? = null
                for (attempt in 1..VIN_CAPTURE_MAX_ATTEMPTS) {
                    if (!isCurrentCapture(generation)) return@launch
                    _captureMsg.value = "正在采集同步 RGBD（$attempt/$VIN_CAPTURE_MAX_ATTEMPTS）…"
                    val burst = framePairer.awaitBurst(
                        minTimestampUs = attemptWatermarkUs,
                        skipColorFrames = VIN_CAPTURE_SKIP_COLOR_FRAMES,
                        minColorFrames = VIN_CAPTURE_MIN_COLOR_FRAMES,
                        minDepthFrames = VIN_CAPTURE_MIN_DEPTH_FRAMES,
                        timeoutMs = VIN_CAPTURE_ATTEMPT_TIMEOUT_MS,
                    )
                    lastBurst = burst
                    Log.i(
                        TAG,
                        "capture burst attempt=$attempt waitUs=${framePairer.nowUs() - attemptWatermarkUs} " +
                            "colors=${burst.colorCount} depths=${burst.depthCount} " +
                            "bestCallbackDeltaUs=${burst.bestDeltaUs} timedOut=${burst.timedOut}",
                    )
                    val candidatePair = burst.pair
                    if (candidatePair != null) {
                        val burstProjection = withContext(Dispatchers.Default) {
                            captureProjector.project(
                                frame = candidatePair.depth,
                                outputWidth = capturePreviewWidth,
                                outputHeight = capturePreviewHeight,
                                roi = captureRoi,
                            )
                        }
                        val burstQuality = vinCaptureQuality(burstProjection?.roiMetrics)
                        _depthRoiMetrics.value = burstProjection?.roiMetrics
                        _captureQuality.value = burstQuality
                        if (burstQuality is VinCaptureQuality.Ready) {
                            selectedBurst = burst
                            selectedProjection = burstProjection
                            selectedAttempt = attempt
                            break
                        }
                        lastRejectedMetrics = burstProjection?.roiMetrics
                        Log.i(
                            TAG,
                            "capture attempt=$attempt blocked by ROI depth quality " +
                                "coverage=${lastRejectedMetrics?.coverageRatio} " +
                                "projectedPointRatio=${lastRejectedMetrics?.projectedPointRatio} " +
                                "distanceP10Mm=${lastRejectedMetrics?.distanceP10Mm} " +
                                "distanceMedianMm=${lastRejectedMetrics?.distanceMedianMm} " +
                                "farEnough=${lastRejectedMetrics?.farEnoughRatio}",
                        )
                    }
                    attemptWatermarkUs = framePairer.nowUs()
                }
                val burst = selectedBurst
                val pair = burst?.pair
                if (burst == null || pair == null) {
                    if (lastRejectedMetrics != null) {
                        _captureMsg.value = vinCaptureGuidance(vinCaptureQuality(lastRejectedMetrics))
                        _restoreState.value = VinRestoreState.Preview
                        rearmAutoCaptureAfterTransientQualityFailure()
                        return@launch
                    }
                    val last = lastBurst
                    val detail = when {
                        last == null -> "未收到候选帧"
                        last.colorCount < VIN_CAPTURE_MIN_COLOR_FRAMES ||
                            last.depthCount < VIN_CAPTURE_MIN_DEPTH_FRAMES ->
                            "末轮仅收到彩色 ${last.colorCount}/$VIN_CAPTURE_MIN_COLOR_FRAMES、深度 ${last.depthCount}/$VIN_CAPTURE_MIN_DEPTH_FRAMES"
                        last.bestDeltaUs != null ->
                            "末轮最佳回调差 %.1fms，超过 %.1fms 门限".format(
                                last.bestDeltaUs / 1000.0,
                                VIN_PAIR_MAX_CALLBACK_DELTA_US / 1000.0,
                            )
                        else -> "末轮没有可比较的帧"
                    }
                    val msg = "连续 $VIN_CAPTURE_MAX_ATTEMPTS 轮未取得可用 RGBD 帧对：$detail；请检查双路预览后重拍"
                    _captureMsg.value = msg
                    _restoreState.value = VinRestoreState.Error(msg)
                    lockAutoCaptureAfterFailure()
                    return@launch
                }
                val depth = pair.depth
                val color = pair.color
                val capturedDepthProjection = requireNotNull(selectedProjection)
                val callbackDeltaUs = pair.timestampDeltaUs
                val depthDeviceSerial = source.deviceSerial
                val colorDeviceSerial = rgbSource.deviceSerial
                Log.i(
                    TAG,
                    "capture pair ready attempt=$selectedAttempt totalWaitUs=${framePairer.nowUs() - clickTimestampUs} " +
                        "callbackDeltaUs=$callbackDeltaUs colors=${burst.colorCount} depths=${burst.depthCount} " +
                        "color#${color.frameIndex} depth#${depth.frameIndex}",
                )
                val seq = nextSeq()
                // 共用字节：深度 u16 LE + 彩色 JPEG q95（压一次，落盘与上传复用，省一遍 13MP 编码）。
                val depthDisparityBytes = withContext(Dispatchers.Default) { depth.toRawDisparityU16LeBytes() }
                val jpeg = color.encodedJpeg ?: withContext(Dispatchers.Default) {
                    FrameRenderer.colorToBitmap(color)?.let { bmp ->
                        ByteArrayOutputStream().use { out ->
                            bmp.compress(CompressFormat.JPEG, 95, out); out.toByteArray()
                        }
                    }
                }

                // ① 无条件落盘原始采集（离线自测"同一 VIN 多次还原图应重合" + 上传失败可重试）。
                val dir = withContext(Dispatchers.IO) {
                    saveRawCapture(
                        depth = depth,
                        color = color,
                        depthDisparityU16 = depthDisparityBytes,
                        jpeg = jpeg,
                        seq = seq,
                        callbackDeltaUs = callbackDeltaUs,
                        captureAttempt = selectedAttempt,
                        colorCandidateCount = burst.colorCount,
                        depthCandidateCount = burst.depthCount,
                        depthDeviceSerial = depthDeviceSerial,
                        colorDeviceSerial = colorDeviceSerial,
                    )
                }
                Log.i(
                    TAG,
                    "raw capture saved seq=$seq callbackDeltaUs=$callbackDeltaUs dir=${dir.absolutePath}",
                )

                // ② 上传服务端还原。手机端不运行标定或近似正射，避免假结果、额外内存和上传延迟。
                if (!isCurrentCapture(generation)) return@launch
                if (jpeg == null) {
                    val msg = "已存第 $seq 张（彩色编码失败，未上传）"
                    _captureMsg.value = msg
                    _restoreState.value = VinRestoreState.Error(msg)
                    lockAutoCaptureAfterFailure()
                    return@launch
                }
                vinCaptureInputError(
                    pixelType = color.pixelType,
                    encodedWidth = color.encodedWidth,
                    encodedHeight = color.encodedHeight,
                    depthDeviceSerial = depthDeviceSerial,
                    colorDeviceSerial = colorDeviceSerial,
                    depthSampleFormat = depth.sampleFormat,
                )?.let { reason ->
                    val msg = "已存第 $seq 张，未上传：$reason"
                    _captureMsg.value = msg
                    _restoreState.value = VinRestoreState.Error(msg)
                    lockAutoCaptureAfterFailure()
                    return@launch
                }
                _captureMsg.value = "正在生成规范化还原图…"
                try {
                    val r = vinRepo.restore(
                        jpeg, depthDisparityBytes, depth.width, depth.height,
                        depth.intrinsics.fx, depth.intrinsics.fy,
                        depth.intrinsics.cx, depth.intrinsics.cy,
                        requireNotNull(depthDeviceSerial),
                        requireNotNull(colorDeviceSerial),
                        color.encodedWidth,
                        color.encodedHeight,
                        color.timestampUs,
                        depth.timestampUs,
                    )
                    if (!isCurrentCapture(generation)) return@launch
                    val png = r.png
                    val decodedRubbing = if (r.ok && png != null) {
                        withContext(Dispatchers.Default) { decodeRubbingPreview(png) }
                            ?: throw IllegalStateException("服务端还原图解码失败")
                    } else {
                        null
                    }
                    withContext(Dispatchers.IO) { saveRestoreAudit(dir, r) }
                    if (r.ok && png != null) {
                        val restored = requireNotNull(decodedRubbing)
                        if (!isCurrentCapture(generation)) return@launch
                        val capturedPreviews = withContext(Dispatchers.Default) {
                            decodeCapturedColorPreview(
                                jpeg = jpeg,
                                encodedWidth = color.encodedWidth,
                                previewMaxWidth = capturePreviewWidth,
                            ) to
                                FrameRenderer.projectedDepthToBitmap(capturedDepthProjection)
                        }
                        val capturedColorPreview = requireNotNull(capturedPreviews.first) {
                            "本次拍摄彩色帧预览解码失败"
                        }
                        val capturedRoiMetrics = requireNotNull(capturedDepthProjection.roiMetrics) {
                            "本次拍摄深度帧缺少 ROI 指标"
                        }
                        restoredRubbingPng = png  // 供「识别」发送外部 VIN OCR
                        Log.i(
                            TAG,
                            "restore preview source=${r.width}x${r.height} display=${restored.width}x${restored.height} " +
                                "calibrationSha256=${r.calibrationSha256} calibrationVersion=${r.calibrationVersion}",
                        )
                        suspendSourcesAfterSuccessfulRestore(
                            capturedColorPreview = capturedColorPreview,
                            capturedDepthPreview = capturedPreviews.second,
                            capturedRoiMetrics = capturedRoiMetrics,
                        )
                        _rubbing.value = restored
                        _captureMsg.value = "还原完成，正在自动识别…"
                        _restoreState.value = VinRestoreState.Ready
                        if (autoCaptureWorkflow.onRestoreSuccess()) {
                            Log.i(TAG, "restore success, starting recognition exactly once")
                            startRecognition()
                        }
                    } else {
                        val msg = vinRestoreRejectMessage(r, seq)
                        Log.i(
                            TAG,
                            "服务端判废 reason=${r.rejectReason} anchor=${r.textAnchor} logId=${r.logId} " +
                                "calibrationSha256=${r.calibrationSha256} calibrationVersion=${r.calibrationVersion}",
                        )
                        _captureMsg.value = msg
                        _restoreState.value = VinRestoreState.Rejected(msg)
                        lockAutoCaptureAfterFailure()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    if (isCurrentCapture(generation)) {
                        val msg = "上传还原失败（已存第 $seq 张可离线重试）：${e.message}"
                        _captureMsg.value = msg
                        _restoreState.value = VinRestoreState.Error(msg)
                        lockAutoCaptureAfterFailure()
                        Log.w(TAG, "服务端还原失败", e)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isCurrentCapture(generation)) {
                    val msg = "保存失败：${e.message}"
                    _captureMsg.value = msg
                    _restoreState.value = VinRestoreState.Error(msg)
                    lockAutoCaptureAfterFailure()
                    Log.w(TAG, "capture 保存异常", e)
                }
            } finally {
                if (isCurrentCapture(generation)) {
                    captureJob = null
                    _capturing.value = false
                    requestPreviewRender(force = true)
                }
            }
        }
    }

    /**
     * VIN 字符识别（服务端代理调用外部 OCR）。
     *
     * 输入 = **服务端原厂式彩色正射 PNG**（[restoredRubbingPng]），
     * 由「确认」按钮触发。还原未成功（未拍照 / tilt 判废 / 上传失败）时无图可识，提示先拍照，不退化喂原始 color。
     */
    fun recognize() {
        if (_state.value is VinCaptureState.Recognizing || _state.value is VinCaptureState.Result) return
        if (_restoreState.value !is VinRestoreState.Ready) {
            _captureMsg.value = "请先完成服务端权威还原，再识别"
            return
        }
        val png = restoredRubbingPng
        if (png == null) {
            _captureMsg.value = "请先拍照生成还原图，再点确认识别"
            return
        }
        if (!autoCaptureWorkflow.tryStartRecognition()) return
        startRecognition(png)
    }

    private fun startRecognition(png: ByteArray = requireNotNull(restoredRubbingPng)) {
        val generation = ++recognizeGeneration
        recognizeJob?.cancel()
        _state.value = VinCaptureState.Recognizing
        recognizeJob = viewModelScope.launch {
            try {
                val result = vinRepo.recognize(png)
                val crops = withContext(Dispatchers.Default) {
                    decodeRecognitionCrops(result)
                } ?: throw IllegalStateException("算法单字符切割图解码失败")
                if (isCurrentRecognition(generation)) {
                    autoCaptureWorkflow.finishRecognition(success = true)
                    _state.value = VinCaptureState.Result(result = result, crops = crops)
                    Log.i(
                        TAG,
                        "VIN 识别完成 provider=${result.provider} characters=${result.characterCount} " +
                            "characterCrops=${crops.size} inferMs=${result.inferMs}",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (isCurrentRecognition(generation)) {
                    autoCaptureWorkflow.finishRecognition(success = false)
                    Log.w(TAG, "VIN 识别失败: ${e.message}")
                    _state.value = VinCaptureState.Error("识别失败: ${e.message}")
                }
            } finally {
                if (isCurrentRecognition(generation)) recognizeJob = null
            }
        }
    }

    private fun decodeRubbingPreview(encoded: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        val sample = vinRubbingPreviewSampleSize(bounds.outWidth, VIN_RUBBING_PREVIEW_MAX_W)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
    }

    /** 结果态严格解码本次实际上传的 JPEG，禁止用拍照前实时预览冒充所选彩色帧。 */
    private fun decodeCapturedColorPreview(
        jpeg: ByteArray,
        encodedWidth: Int,
        previewMaxWidth: Int,
    ): Bitmap? {
        val options = BitmapFactory.Options().apply {
            inSampleSize = hlsd8PreviewSampleSize(encodedWidth, previewMaxWidth)
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
    }

    private fun decodeRecognitionCrops(result: VinRecognitionResult): List<VinCharacterCropPreview>? =
        result.characterCrops.map { crop ->
            val bitmap = decodeRecognitionCrop(crop.image) ?: return null
            VinCharacterCropPreview(
                position = crop.position,
                character = crop.character,
                confidence = crop.confidence,
                bitmap = bitmap,
            )
        }

    private fun decodeRecognitionCrop(crop: VinCropImage): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(crop.bytes, 0, crop.bytes.size, bounds)
        if (bounds.outWidth != crop.width || bounds.outHeight != crop.height) return null
        val sample = vinRubbingPreviewSampleSize(bounds.outWidth, VIN_RUBBING_PREVIEW_MAX_W)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return BitmapFactory.decodeByteArray(crop.bytes, 0, crop.bytes.size, options)
    }

    /** 拍照序号（按已存在的 cap_ 目录数续号，跨进程不重置）。 */
    private var captureSeq = -1

    @Synchronized
    private fun nextSeq(): Int {
        if (captureSeq < 0) {
            val root = File(appContext.getExternalFilesDir(null), CAPTURE_ROOT)
            captureSeq = root.listFiles()?.count { it.isDirectory && it.name.startsWith("cap_") } ?: 0
        }
        captureSeq += 1
        return captureSeq
    }

    /**
     * 原始采集落盘 = 服务端原厂全保真还原管线的输入契约（对齐 VINCreator `*_rgb1300.jpg` + `*_depth.yuv`）：
     * - `rgb1300.jpg`：HLSD8 真彩 JPEG（q95）。彩色源若为 L'（无 HLSD8）也存，meta 标 pixelType。
     * - `depth.yuv`：RS-D550 mode25 原始 16bit LE 视差（数值=真实视差×8，0=无效），width×height×2 字节。
     * - `depth.rgb24`/`color.rgb24` 不存（JPEG/raw 二选一，省空间；服务端解 JPEG）。
     * - `meta.json`：物理相机序列号 + 手机型号 + 彩色编码/预览尺寸 + 深度内参 + 时间戳 + 序号。
     * 落 `externalFiles/vin_captures/cap_<seq>_<ts>/`，adb pull 即可离线复现。
     */
    /** mode25 原始视差帧 → u16 LE 裸字节（数值=真实视差×8，0=无效）。落盘与上传共用。 */
    private fun DepthFrame.toRawDisparityU16LeBytes(): ByteArray {
        val b = data.duplicate().apply { rewind() }
        val expectedLong = width.toLong() * height.toLong() * 2L
        require(width > 0 && height > 0 && expectedLong <= Int.MAX_VALUE) {
            "深度帧尺寸非法: ${width}×$height"
        }
        val expected = expectedLong.toInt()
        require(b.remaining() == expected) {
            "深度帧字节数 ${b.remaining()} != ${width}×${height}×2=$expected"
        }
        return ByteArray(expected).also { b.get(it) }
    }

    private fun saveRawCapture(
        depth: DepthFrame,
        color: ColorFrame,
        depthDisparityU16: ByteArray,
        jpeg: ByteArray?,
        seq: Int,
        callbackDeltaUs: Long,
        captureAttempt: Int,
        colorCandidateCount: Int,
        depthCandidateCount: Int,
        depthDeviceSerial: String?,
        colorDeviceSerial: String?,
    ): File {
        val ts = System.currentTimeMillis()
        val root = File(appContext.getExternalFilesDir(null), CAPTURE_ROOT)
        if (!root.isDirectory && !root.mkdirs()) throw IOException("无法创建 VIN 采集目录")
        root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith(".cap_") && it.name.endsWith(".tmp") }
            ?.forEach(File::deleteRecursively)

        val meta = buildRawCaptureMetadata(
            depth = depth,
            color = color,
            seq = seq,
            ts = ts,
            callbackDeltaUs = callbackDeltaUs,
            captureAttempt = captureAttempt,
            colorCandidateCount = colorCandidateCount,
            depthCandidateCount = depthCandidateCount,
            depthDeviceSerial = depthDeviceSerial,
            colorDeviceSerial = colorDeviceSerial,
        )
        val requiredBytes = depthDisparityU16.size.toLong() +
            (jpeg?.size?.toLong() ?: 0L) + meta.toByteArray(Charsets.UTF_8).size
        if (root.usableSpace < requiredBytes) {
            throw IOException("VIN 采集存储空间不足：需要 $requiredBytes 字节，可用 ${root.usableSpace} 字节")
        }

        val finalDir = File(root, "cap_%03d_%d".format(seq, ts))
        val tempDir = File(root, ".${finalDir.name}.tmp")
        if (finalDir.exists() || !tempDir.mkdir()) throw IOException("无法创建 VIN 临时采集目录")
        try {
            File(tempDir, "depth.yuv").writeBytes(depthDisparityU16)
            if (jpeg != null) File(tempDir, "rgb1300.jpg").writeBytes(jpeg)
            File(tempDir, "meta.json").writeText(meta)
            if (!tempDir.renameTo(finalDir)) throw IOException("无法提交 VIN 完整采集目录")
            return finalDir
        } catch (error: Throwable) {
            tempDir.deleteRecursively()
            throw error
        }
    }

    private fun buildRawCaptureMetadata(
        depth: DepthFrame,
        color: ColorFrame,
        seq: Int,
        ts: Long,
        callbackDeltaUs: Long,
        captureAttempt: Int,
        colorCandidateCount: Int,
        depthCandidateCount: Int,
        depthDeviceSerial: String?,
        colorDeviceSerial: String?,
    ): String = StringWriter().use { output ->
        JsonWriter(output).use { json ->
            json.beginObject()
            json.name("seq").value(seq.toLong())
            json.name("ts").value(ts)
            json.name("deviceSerial").value(depthDeviceSerial)
            json.name("depthDeviceSerial").value(depthDeviceSerial)
            json.name("colorDeviceSerial").value(colorDeviceSerial)
            json.name("phoneModel").value(android.os.Build.MODEL)
            json.name("colorPixelType").value(color.pixelType)
            json.name("sync").beginObject()
            json.name("timestampKind").value("host_callback_monotonic")
            json.name("colorTimestampUs").value(color.timestampUs)
            json.name("depthTimestampUs").value(depth.timestampUs)
            json.name("callbackDeltaUs").value(callbackDeltaUs)
            json.name("captureAttempt").value(captureAttempt.toLong())
            json.name("colorCandidateCount").value(colorCandidateCount.toLong())
            json.name("depthCandidateCount").value(depthCandidateCount.toLong())
            json.endObject()
            json.name("color").beginObject()
            json.name("previewW").value(color.width.toLong())
            json.name("previewH").value(color.height.toLong())
            json.name("encodedW").value(color.encodedWidth.toLong())
            json.name("encodedH").value(color.encodedHeight.toLong())
            json.name("fx").value(color.intrinsics.fx)
            json.name("fy").value(color.intrinsics.fy)
            json.name("cx").value(color.intrinsics.cx)
            json.name("cy").value(color.intrinsics.cy)
            json.endObject()
            json.name("depth").beginObject()
            json.name("w").value(depth.width.toLong())
            json.name("h").value(depth.height.toLong())
            json.name("fx").value(depth.intrinsics.fx)
            json.name("fy").value(depth.intrinsics.fy)
            json.name("cx").value(depth.intrinsics.cx)
            json.name("cy").value(depth.intrinsics.cy)
            json.name("sampleFormat").value(depth.sampleFormat.name)
            json.endObject()
            json.endObject()
        }
        output.toString()
    }

    /** 保存服务端裁决与标定身份；`restore.json` 存在才表示该采集已形成可审计还原结果。 */
    private fun saveRestoreAudit(dir: File, result: VinRestoreOutcome) {
        val restored = File(dir, "restored.png")
        val restoredTmp = File(dir, "restored.png.tmp")
        val audit = File(dir, "restore.json")
        val auditTmp = File(dir, "restore.json.tmp")
        try {
            result.png?.let { png ->
                restoredTmp.writeBytes(png)
                if (!restoredTmp.renameTo(restored)) {
                    throw IOException("无法提交 restored.png")
                }
            }
            val payload = StringWriter().use { output ->
                JsonWriter(output).use { json ->
                    json.beginObject()
                    json.name("received_at_ms").value(System.currentTimeMillis())
                    json.name("ok").value(result.ok)
                    json.name("log_id").value(result.logId)
                    json.name("depth_device_id").value(result.depthDeviceId)
                    json.name("color_device_id").value(result.colorDeviceId)
                    json.name("calibration_sha256").value(result.calibrationSha256)
                    json.name("calibration_version").value(result.calibrationVersion.toLong())
                    json.name("width").value(result.width.toLong())
                    json.name("height").value(result.height.toLong())
                    json.name("tilt_deg").value(result.tiltDeg)
                    json.name("width_mm").value(result.widthMm)
                    json.name("height_mm").value(result.heightMm)
                    json.name("inlier_rate").value(result.inlierRate)
                    json.name("rms").value(result.rms)
                    json.name("med_z").value(result.medZ)
                    json.name("num_det").value(result.numDet.toLong())
                    json.name("sync_delta_us").value(result.syncDeltaUs)
                    json.name("reject_reason").value(result.rejectReason.toWireValue())
                    result.textAnchor?.let { anchor ->
                        json.name("text_anchor").beginObject()
                        json.name("count").value(anchor.count.toLong())
                        json.name("candidate_count").value(anchor.candidateCount.toLong())
                        json.name("pitch_px").value(anchor.pitchPx)
                        json.name("rms_px").value(anchor.rmsPx)
                        json.name("mean_score").value(anchor.meanScore)
                        json.name("height_px").value(anchor.heightPx)
                        json.name("rotation_deg").value(anchor.rotationDeg)
                        json.name("scale").value(anchor.scale)
                        json.endObject()
                    }
                    if (result.png != null) json.name("result_file").value(restored.name)
                    json.endObject()
                }
                output.toString()
            }
            auditTmp.writeText(payload)
            if (!auditTmp.renameTo(audit)) {
                throw IOException("无法提交 restore.json")
            }
        } catch (error: Throwable) {
            restoredTmp.delete()
            auditTmp.delete()
            restored.delete()
            audit.delete()
            throw error
        }
    }

    private fun VinRestoreRejectReason?.toWireValue(): String = when (this) {
        VinRestoreRejectReason.TiltTooLarge -> "tilt_too_large"
        VinRestoreRejectReason.VinNotDetected -> "vin_not_detected"
        VinRestoreRejectReason.RgbdOutOfSync -> "rgbd_out_of_sync"
        VinRestoreRejectReason.TextAnchorUnreliable -> "text_anchor_unreliable"
        VinRestoreRejectReason.CalibrationUnavailable -> "calibration_unavailable"
        is VinRestoreRejectReason.Unknown -> raw
        null -> ""
    }

    /** 重拍：清掉拓印图、OCR 结果与提示，回到取景。 */
    fun retake() {
        val invalidatedGeneration = ++captureGeneration
        val cancelledCapture = captureJob
        cancelledCapture?.cancel()
        resetPreviewForNewScan()
        recognizeGeneration++
        recognizeJob?.cancel()
        recognizeJob = null
        restoredRubbingPng = null
        _rubbing.value = null
        _restoreState.value = VinRestoreState.Preview
        _captureMsg.value = null
        _state.value = VinCaptureState.Preview
        acquireSourcesIfNeeded()
        Log.i(
            TAG,
            if (cameraPermissionGranted) {
                "开始新一轮 VIN 扫描，已请求重新 acquire 双相机"
            } else {
                "开始新一轮 VIN 扫描，等待相机权限后 acquire"
            },
        )
        if (cancelledCapture == null || cancelledCapture.isCompleted) {
            captureJob = null
            _capturing.value = false
            requestPreviewRender(force = true)
        } else {
            // 旧落盘/网络任务退出前保持快门忙，避免两次大图处理并行；generation 防止旧任务回写新状态。
            _capturing.value = true
            viewModelScope.launch {
                cancelledCapture.join()
                if (isCurrentCapture(invalidatedGeneration) && captureJob === cancelledCapture) {
                    captureJob = null
                    _capturing.value = false
                    requestPreviewRender(force = true)
                }
            }
        }
    }

    private fun isCurrentCapture(generation: Long): Boolean = captureGeneration == generation

    private fun isCurrentRecognition(generation: Long): Boolean = recognizeGeneration == generation

    @Synchronized
    private fun acquireDepthSource() {
        if (!cameraPermissionGranted || sourceAcquired) return
        sourceAcquired = true
        source.acquire()
    }

    override fun onCleared() {
        releaseSourcesIfNeeded()
        super.onCleared()
    }

    companion object {
        private const val TAG = "VinCaptureVM"
        private const val PREVIEW_PROJECTION_LOG_INTERVAL = 30
        private const val RGB_READY_TIMEOUT_MS = 10_000L
        private const val RGB_TO_DEPTH_SETTLE_MS = 80L
        private const val CAMERA_PERMISSION_MESSAGE = "需要相机权限才能使用 VIN RGBD 相机"
        // VINCreator 没有硬触发：快门后跳过 3 张 HLSD8，再至少收 3 张彩色和 3 张深度。
        // 100ms 只是回调完成时间门，不冒充曝光级同步；终态仍需 PTS/SCR 或同步光学事件证明。
        private const val VIN_PAIR_MAX_CALLBACK_DELTA_US = 100_000L
        // 快门只能消费仍在流动的近帧，防止相机掉线后重复使用陈旧配对。
        private const val VIN_PAIR_MAX_AGE_US = 250_000L
        private const val VIN_CAPTURE_SKIP_COLOR_FRAMES = 3
        private const val VIN_CAPTURE_MIN_COLOR_FRAMES = 3
        private const val VIN_CAPTURE_MIN_DEPTH_FRAMES = 3
        private const val VIN_CAPTURE_ATTEMPT_TIMEOUT_MS = 5_000L
        private const val VIN_CAPTURE_MAX_ATTEMPTS = 6
        // 原始采集落盘根目录（externalFiles 下，adb pull 取走做服务端还原离线自测）。
        private const val CAPTURE_ROOT = "vin_captures"
    }
}
