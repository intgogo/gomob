package io.gomob.feature.scan3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.scan.VinRepository
import io.gomob.data.scan.VinResult
import io.gomob.model.ColorFrame
import io.gomob.model.DepthFrame
import io.gomob.nativebridge.NativeBridge
import io.gomob.nativebridge.NativeException
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
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val source: CameraSource = provider.active()

    /** HLSD8 辅助 RGB 相机（独立第二颗 USB 相机，补光灯/IR 投射器在此模块）；未插着则 null。
     *  ★ 必须先于 [source] acquire 点亮补光，否则 eYs3D mode25 彩色暖机失败 → FrameGrabber 0 帧（与 DepthCameraViewModel 同序，用户 2026-06-15 实测）。 */
    private val rgbSource: CameraSource? = provider.auxRgb()

    /** 「彩色图」横条的色源：有 HLSD8 高清 RGB（13MP）就用它，否则回落深度相机自带彩色（eYs3D L' / Berxel）。 */
    private val colorSource: CameraSource = rgbSource ?: source

    private val _state = MutableStateFlow<VinCaptureState>(VinCaptureState.Preview)
    val state: StateFlow<VinCaptureState> = _state.asStateFlow()

    private val _colorPreview = MutableStateFlow<Bitmap?>(null)
    val colorPreview: StateFlow<Bitmap?> = _colorPreview.asStateFlow()

    // 深度实时预览（turbo 伪彩，自动量程）。顶部"深度图"横条用，与"彩色图"横条并列显示真帧。
    private val _depthPreview = MutableStateFlow<Bitmap?>(null)
    val depthPreview: StateFlow<Bitmap?> = _depthPreview.asStateFlow()

    // 拓印还原图（拍照 → native 双相机正射 → 显示在"数码拓印"纸面区域）。
    private val _rubbing = MutableStateFlow<Bitmap?>(null)
    val rubbing: StateFlow<Bitmap?> = _rubbing.asStateFlow()

    // 拍照进行中（防重入 + UI 转圈）。
    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    // 拍照结果/错误一行提示（覆盖率/rms 或失败原因）。
    private val _captureMsg = MutableStateFlow<String?>(null)
    val captureMsg: StateFlow<String?> = _captureMsg.asStateFlow()

    /** 车型 ID：决定拉哪套厂家字形库对照。本期默认 dev 值，可在 UI 设置。
     *  TODO(catalog): 接车型档案查询客户端后改为从工单/车型选择带入。 */
    private val _vehicleModelId = MutableStateFlow(DEFAULT_VEHICLE_MODEL_ID)
    val vehicleModelId: StateFlow<Long> = _vehicleModelId.asStateFlow()

    val deviceState: StateFlow<CameraSourceState> = source.sourceState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraSourceState.Idle)

    // 正射拓印输入：最近一帧深度 + 最近一帧彩色（[latestColor]，首选 HLSD8 真彩，无则深度相机 L'）。
    @Volatile private var latestColor: ColorFrame? = null
    @Volatile private var latestDepth: DepthFrame? = null
    private var captureJob: Job? = null

    // 服务端还原签名 PNG：拍照成功后存，「确认」时喂 vin_pipeline OCR（原厂全保真还原图，比原始 color 更利识别）。
    // 每次新拍照 / 重拍清空，避免对陈旧图识别。
    @Volatile private var restoredSignaturePng: ByteArray? = null
    private var recognizeJob: Job? = null

    init {
        // ★ 先开 HLSD8 补光（点灯/散斑），再开 eYs3D 深度——否则主动立体无散斑 + 彩色暖机失败 → FrameGrabber 0 帧。
        rgbSource?.acquire()
        source.acquire()
        viewModelScope.launch {
            // 彩色图横条 = HLSD8 高清 RGB（无则深度相机自带彩色）。
            // 按时间节流（~25fps 上限）而非按计数丢帧——计数丢帧把预览压到 ~3fps，发闷；时间节流跟得上设备产帧。
            var lastRenderMs = 0L
            colorSource.colorFrames.collect { frame ->
                latestColor = frame
                val now = SystemClock.elapsedRealtime()
                if (_state.value != VinCaptureState.Preview) return@collect
                if (lastRenderMs != 0L && now - lastRenderMs < COLOR_PREVIEW_MIN_INTERVAL_MS) return@collect
                lastRenderMs = now
                val bmp = withContext(Dispatchers.Default) { FrameRenderer.colorRgb24ToBitmap(frame) }
                if (bmp != null) _colorPreview.value = bmp
            }
        }
        viewModelScope.launch {
            var lastRenderMs = 0L
            source.depthFrames.collect { frame ->
                latestDepth = frame  // 每帧都更新，拍照取最近一帧（不受预览节流影响）
                val now = SystemClock.elapsedRealtime()
                if (_state.value != VinCaptureState.Preview) return@collect
                if (lastRenderMs != 0L && now - lastRenderMs < DEPTH_PREVIEW_MIN_INTERVAL_MS) return@collect
                lastRenderMs = now
                val bmp = withContext(Dispatchers.Default) {
                    // maskByConfidence=false：拓印取景阶段显示完整深度，不按置信遮罩。
                    FrameRenderer.depth16ToBitmap(frame, maskByConfidence = false)
                }
                if (bmp != null) _depthPreview.value = bmp
            }
        }
    }

    fun setVehicleModelId(id: Long) {
        if (id > 0) _vehicleModelId.value = id
    }

    /**
     * 点击拍照 → native 深度正射 → 拓印还原图（显示在"数码拓印"纸面）。
     *
     * 彩色源 = **HLSD8 真彩**（[latestColor]，无则回落深度相机自带 L'）。
     * **当前 HLSD8 内参/外参用近似**（内参 ~中等视场估、外参单位阵=两机近似同位），仅供看内容 + 平移/裁剪粗对齐。
     * TODO(M6.9.5b)：ArUco 双相机标定出真 K_hlsd8 + R|t（服务端算、端侧持久化）后替换，正射代码不变。
     */
    fun capture() {
        if (_capturing.value) return
        val depth = latestDepth
        val color = latestColor
        if (depth == null || color == null) {
            _captureMsg.value = "尚无 RGBD 帧，确认相机在流再拍"
            return
        }
        _capturing.value = true
        _captureMsg.value = null
        // 新拍照作废上一张的还原签名与 OCR 结果（回取景态，等本次还原成功再供「确认」识别）。
        recognizeJob?.cancel()
        restoredSignaturePng = null
        _state.value = VinCaptureState.Preview
        captureJob = viewModelScope.launch {
            try {
                val seq = nextSeq()
                // 共用字节：深度 u16 LE + 彩色 JPEG q95（压一次，落盘与上传复用，省一遍 13MP 编码）。
                val depthBytes = withContext(Dispatchers.Default) { depth.toU16LeBytes() }
                val jpeg = withContext(Dispatchers.Default) {
                    FrameRenderer.colorRgb24ToBitmap(color)?.let { bmp ->
                        ByteArrayOutputStream().use { out ->
                            bmp.compress(CompressFormat.JPEG, 95, out); out.toByteArray()
                        }
                    }
                }

                // ① 无条件落盘原始采集（离线自测"同一 VIN 多次还原图应重合" + 上传失败可重试）。
                val dir = withContext(Dispatchers.IO) { saveRawCapture(depth, color, depthBytes, jpeg, seq) }
                Log.i(TAG, "raw capture saved seq=$seq dir=${dir.absolutePath}")

                // ② 端侧近似正射，仅作"拍到了"的即时占位预览（真还原走服务端原厂全保真管线，随后覆盖）。best-effort。
                try {
                    val isHlsd8 = color.pixelType == "HLSD8_RGB24"
                    val rgbIntr = if (isHlsd8) {
                        doubleArrayOf(
                            color.width * HLSD8_FOCAL_FACTOR, color.width * HLSD8_FOCAL_FACTOR,
                            color.width / 2.0, color.height / 2.0,
                        )
                    } else {
                        doubleArrayOf(
                            color.intrinsics.fx, color.intrinsics.fy,
                            color.intrinsics.cx, color.intrinsics.cy,
                        )
                    }
                    val ortho = withContext(Dispatchers.Default) {
                        NativeBridge.vinOrthoRectify(
                            depth.data, depth.width, depth.height,
                            doubleArrayOf(depth.intrinsics.fx, depth.intrinsics.fy,
                                depth.intrinsics.cx, depth.intrinsics.cy),
                            color.data, color.width, color.height,
                            rgbIntr,
                            IDENTITY_RT, ORTHO_CONFIG,
                        )
                    }
                    _rubbing.value = withContext(Dispatchers.Default) { FrameRenderer.orthoToBitmap(ortho) }
                } catch (e: Throwable) {
                    Log.i(TAG, "端侧预览正射跳过（不影响落盘/上传）: ${e.message}")
                }

                // ③ 上传服务端原厂全保真还原 → 权威拓印签名图，覆盖即时预览。深度内参随传，彩色内参服务端按 2× registration 自推。
                if (jpeg == null) {
                    _captureMsg.value = "已存第 $seq 张（彩色编码失败，未上传）"
                    return@launch
                }
                _captureMsg.value = "已存第 $seq 张，上传服务端还原中…"
                try {
                    val r = vinRepo.restore(
                        jpeg, depthBytes, depth.width, depth.height,
                        depth.intrinsics.fx, depth.intrinsics.fy,
                        depth.intrinsics.cx, depth.intrinsics.cy,
                        android.os.Build.MODEL,
                    )
                    val png = r.png
                    if (r.ok && png != null) {
                        withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(png, 0, png.size) }
                            ?.let { _rubbing.value = it }
                        restoredSignaturePng = png  // 供「确认」喂 vin_pipeline OCR
                        _captureMsg.value = "服务端还原 ✓ 第 $seq 张｜倾角 %.0f° 内点 %.0f%% 检出 %d（点确认识别）".format(
                            r.tiltDeg, r.inlierRate * 100, r.numDet)
                    } else {
                        _captureMsg.value =
                            "服务端判废：倾角 %.0f°>70°（请正对钢牌再拍）｜检出 %d，已存第 $seq 张".format(r.tiltDeg, r.numDet)
                    }
                } catch (e: Throwable) {
                    _captureMsg.value = "上传还原失败（已存第 $seq 张可离线重试）：${e.message}"
                    Log.w(TAG, "服务端还原失败", e)
                }
            } catch (e: Throwable) {
                _captureMsg.value = "保存失败：${e.message}"
                Log.w(TAG, "capture 保存异常", e)
            } finally {
                _capturing.value = false
            }
        }
    }

    /**
     * VIN 字符识别（OCR + 厂家字形库比对，服务端 vin_pipeline）。
     *
     * 输入 = **服务端原厂全保真还原签名 PNG**（[restoredSignaturePng]，去阴影 OCR 级二值图，比原始 color 更利识别），
     * 由「确认」按钮触发。还原未成功（未拍照 / tilt 判废 / 上传失败）时无图可识，提示先拍照，不退化喂原始 color。
     */
    fun recognize() {
        if (_state.value == VinCaptureState.Recognizing) return
        val png = restoredSignaturePng
        if (png == null) {
            _captureMsg.value = "请先拍照生成还原图，再点确认识别"
            return
        }
        _state.value = VinCaptureState.Recognizing
        recognizeJob = viewModelScope.launch {
            try {
                // vin_pipeline 服务端按 magic 字节 IMDecode，PNG/JPEG 通吃；这里直传还原签名 PNG。
                val bmp = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(png, 0, png.size) }
                    ?: throw IllegalStateException("还原签名解码失败")
                val result = vinRepo.recognize(_vehicleModelId.value, png)
                _state.value = VinCaptureState.Result(capture = bmp, result = result)
                Log.i(TAG, "VIN 识别完成 verdict=${result.verdict} vin=${result.recognizedVin} scored=${result.scored}")
            } catch (e: Throwable) {
                Log.w(TAG, "VIN 识别失败: ${e.message}")
                _state.value = VinCaptureState.Error("识别失败: ${e.message}")
            }
        }
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
     * - `depth.yuv`：深度 16bit LE（metric mm，0=无效），width×height×2 字节。
     * - `depth.rgb24`/`color.rgb24` 不存（JPEG/raw 二选一，省空间；服务端解 JPEG）。
     * - `meta.json`：deviceId + 彩色/深度尺寸 + 深度真内参（反投影必需）+ 时间戳 + 序号。
     * 落 `externalFiles/vin_captures/cap_<seq>_<ts>/`，adb pull 即可离线复现。
     */
    /** 深度帧 → u16 LE 裸字节（metric mm，0=无效），width×height×2。落盘与上传共用。 */
    private fun DepthFrame.toU16LeBytes(): ByteArray {
        val b = data.duplicate().apply { rewind() }
        val n = width * height * 2
        val a = ByteArray(minOf(n, b.remaining())); b.get(a)
        return a
    }

    private fun saveRawCapture(
        depth: DepthFrame,
        color: ColorFrame,
        depthU16: ByteArray,
        jpeg: ByteArray?,
        seq: Int,
    ): File {
        val ts = System.currentTimeMillis()
        val root = File(appContext.getExternalFilesDir(null), CAPTURE_ROOT).apply { mkdirs() }
        val dir = File(root, "cap_%03d_%d".format(seq, ts)).apply { mkdirs() }

        File(dir, "depth.yuv").writeBytes(depthU16)
        if (jpeg != null) File(dir, "rgb1300.jpg").writeBytes(jpeg)
        File(dir, "meta.json").writeText(
            """
            {
              "seq": $seq,
              "ts": $ts,
              "deviceId": "${android.os.Build.MODEL}",
              "colorPixelType": "${color.pixelType}",
              "color": {"w": ${color.width}, "h": ${color.height}, "fx": ${color.intrinsics.fx}, "fy": ${color.intrinsics.fy}, "cx": ${color.intrinsics.cx}, "cy": ${color.intrinsics.cy}},
              "depth": {"w": ${depth.width}, "h": ${depth.height}, "fx": ${depth.intrinsics.fx}, "fy": ${depth.intrinsics.fy}, "cx": ${depth.intrinsics.cx}, "cy": ${depth.intrinsics.cy}, "unit": "u16_le_mm"}
            }
            """.trimIndent(),
        )
        return dir
    }

    /** 重拍：清掉拓印图、还原签名、OCR 结果与提示，回到取景。 */
    fun retake() {
        captureJob?.cancel()
        captureJob = null
        recognizeJob?.cancel()
        recognizeJob = null
        restoredSignaturePng = null
        _rubbing.value = null
        _captureMsg.value = null
        _state.value = VinCaptureState.Preview
    }

    override fun onCleared() {
        super.onCleared()
        source.release()
        rgbSource?.release()
    }

    companion object {
        private const val TAG = "VinCaptureVM"
        // 预览渲染时间节流：depth ~30fps、color ~25fps（解码体量大）；与 DepthCameraViewModel 同口径。
        private const val DEPTH_PREVIEW_MIN_INTERVAL_MS = 33L
        private const val COLOR_PREVIEW_MIN_INTERVAL_MS = 40L
        private const val DEFAULT_VEHICLE_MODEL_ID = 10001L
        // 原始采集落盘根目录（externalFiles 下，adb pull 取走做服务端还原离线自测）。
        private const val CAPTURE_ROOT = "vin_captures"

        // R|t = 单位阵：L' 与深度同一矫正左目精确成立；HLSD8 是近似（两机近似同位），待 ArUco 标定换真 R|t。
        private val IDENTITY_RT = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f)
        // HLSD8 内参未标定时的焦距估：fx≈fy≈宽×此系数。HLSD8 是窄视场（整块 VIN 钢牌填满画面），
        // 实测 ~6.5（fx≈8320@1280宽, HFOV≈8.8°）；离线复现真机 dump 验证整串 VIN 正确充满还原图。待 ArUco 标定替换。
        private const val HLSD8_FOCAL_FACTOR = 6.5
        // 正射配置 [pixel_size_mm, out_w, out_h, plane_dist_thresh_mm, ransac_iter, min_inlier_ratio,
        //          roi_cx, roi_cy, roi_w, roi_h]。0.2mm/px × 1024×512 ≈ 205×102mm 视场，覆盖 VIN 字带。
        // ROI=中心 50%×50%：只用图像中间部位深度拟合平面 + 定输出中心，避背景污染、对准中央目标。
        private val ORTHO_CONFIG =
            floatArrayOf(0.2f, 1024f, 512f, 3f, 200f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f)
    }
}
