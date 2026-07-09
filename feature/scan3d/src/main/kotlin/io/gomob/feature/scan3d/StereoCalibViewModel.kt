package io.gomob.feature.scan3d

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gomob.model.ColorFrame
import io.gomob.model.DepthFrame
import io.gomob.nativebridge.camera.CameraSource
import io.gomob.nativebridge.camera.CameraSourceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * HLSD8 ↔ depth(eYs3D RS-D550) 双相机标定采集 VM —— 属深度相机页（Color↔Depth 标定子页），不在 VIN 拓印页。
 *
 * 标定输入对：**HLSD8 全分辨率彩色**（13MP，预览那路降采样会糊掉 ChArUco 标记）+ **eYs3D L'**（矫正左目，与深度同坐标系）。
 * 整个标定会话**关 IR 投射器**→ L' 出干净灰度（散斑会盖住标记无法解码）；离页恢复散斑（正常测深需要）。
 * 落 `externalFiles/vin_calib/calib_*`（rgb1300.jpg + lprime.jpg + depth.yuv + meta.json），adb pull 后离线 harness 标定。
 * 详见 `docs/architecture/08-vin-rectify-design.md §9.2` / `tests/harness/vin_calib/`。
 */
@HiltViewModel
class StereoCalibViewModel @Inject constructor(
    provider: CameraSourceProvider,
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val source: CameraSource = provider.active()       // eYs3D 深度相机（出 depth + L'）
    private val rgbSource: CameraSource? = provider.auxRgb()   // HLSD8 13MP 彩色（独立第二颗）
    val hasRgb: Boolean = rgbSource != null

    private val _hlsd8Preview = MutableStateFlow<Bitmap?>(null)
    val hlsd8Preview: StateFlow<Bitmap?> = _hlsd8Preview.asStateFlow()
    private val _lprimePreview = MutableStateFlow<Bitmap?>(null) // 无散斑 L'（标定能否检角点靠看它）
    val lprimePreview: StateFlow<Bitmap?> = _lprimePreview.asStateFlow()

    private val _calibCount = MutableStateFlow(0)
    val calibCount: StateFlow<Int> = _calibCount.asStateFlow()
    private val _msg = MutableStateFlow<String?>(null)
    val msg: StateFlow<String?> = _msg.asStateFlow()
    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    @Volatile private var latestColor: ColorFrame? = null   // HLSD8（降采样预览帧；存盘用全分辨率 MJPEG）
    @Volatile private var latestLPrime: ColorFrame? = null  // eYs3D L'
    @Volatile private var latestDepth: DepthFrame? = null
    @Volatile private var irOff = false

    init {
        rgbSource?.acquire() // 先点亮 HLSD8（补光），再开 eYs3D（与 VIN/深度页同序）
        source.acquire()
        // HLSD8 预览（标定靠它检角点；这里只为取景看板，存盘走全分辨率）
        rgbSource?.let { rgb ->
            viewModelScope.launch {
                var last = 0L
                rgb.colorFrames.collect { f ->
                    latestColor = f
                    val now = SystemClock.elapsedRealtime()
                    if (now - last < PREVIEW_MS) return@collect
                    last = now
                    withContext(Dispatchers.Default) { FrameRenderer.colorRgb24ToBitmap(f) }?.let { _hlsd8Preview.value = it }
                }
            }
        }
        // eYs3D L' 预览 + 首帧关 IR（等流起来再写寄存器才生效）
        viewModelScope.launch {
            var last = 0L
            source.colorFrames.collect { f ->
                latestLPrime = f
                if (!irOff) {
                    source.setIrProjector(false) // 关散斑 → L' 干净
                    irOff = true
                }
                val now = SystemClock.elapsedRealtime()
                if (now - last < PREVIEW_MS) return@collect
                last = now
                withContext(Dispatchers.Default) { FrameRenderer.colorRgb24ToBitmap(f) }?.let { _lprimePreview.value = it }
            }
        }
        viewModelScope.launch { source.depthFrames.collect { latestDepth = it } }
    }

    /** 采一组标定对（HLSD8 全分辨率 + 无散斑 L' + depth）。对准 ChArUco 板多姿态/倾角各采一张。 */
    fun captureCalib() {
        if (_capturing.value) return
        if (rgbSource == null) { _msg.value = "无 HLSD8，双相机标定不可用"; return }
        val hlsd8 = latestColor
        val lprime = latestLPrime
        val depth = latestDepth
        if (hlsd8 == null || lprime == null) { _msg.value = "尚无 HLSD8/L' 帧，等两路相机在流再采"; return }
        _capturing.value = true
        viewModelScope.launch {
            try {
                val seq = _calibCount.value + 1
                val dir = withContext(Dispatchers.IO) { saveCalibCapture(hlsd8, lprime, depth, seq) }
                _calibCount.value = seq
                _msg.value = "已采 $seq 张（换姿态/倾角/远近继续）"
                Log.i(TAG, "calib saved seq=$seq dir=${dir.absolutePath}")
            } catch (e: Exception) {
                _msg.value = "采集失败：${e.message}"; Log.e(TAG, "calib failed", e)
            } finally {
                _capturing.value = false
            }
        }
    }

    private fun Bitmap.toJpeg(q: Int): ByteArray =
        ByteArrayOutputStream().use { out -> compress(CompressFormat.JPEG, q, out); out.toByteArray() }

    private fun DepthFrame.toU16LeBytes(): ByteArray {
        val b = data.duplicate().apply { rewind() }
        val a = ByteArray(minOf(width * height * 2, b.remaining())); b.get(a); return a
    }

    private fun saveCalibCapture(hlsd8: ColorFrame, lprime: ColorFrame, depth: DepthFrame?, seq: Int): File {
        val ts = System.currentTimeMillis()
        val root = File(appContext.getExternalFilesDir(null), CALIB_ROOT).apply { mkdirs() }
        val dir = File(root, "calib_%03d_%d".format(seq, ts)).apply { mkdirs() }
        // HLSD8 存全分辨率 MJPEG（标定需清晰 ChArUco；预览降采样到 ~1280 会糊掉标记）。拿不到回退降采样。
        val full = rgbSource?.latestFullColorJpeg()
        if (full != null) File(dir, "rgb1300.jpg").writeBytes(full)
        else FrameRenderer.colorRgb24ToBitmap(hlsd8)?.let { File(dir, "rgb1300.jpg").writeBytes(it.toJpeg(95)) }
        FrameRenderer.colorRgb24ToBitmap(lprime)?.let { File(dir, "lprime.jpg").writeBytes(it.toJpeg(95)) }
        if (depth != null) File(dir, "depth.yuv").writeBytes(depth.toU16LeBytes())
        val depthMeta = if (depth != null)
            ""","depth":{"w":${depth.width},"h":${depth.height},"fx":${depth.intrinsics.fx},"fy":${depth.intrinsics.fy},"cx":${depth.intrinsics.cx},"cy":${depth.intrinsics.cy},"unit":"u16_le_mm"}"""
        else ""
        File(dir, "meta.json").writeText(
            """
            {
              "seq": $seq,
              "ts": $ts,
              "deviceId": "${android.os.Build.MODEL}",
              "kind": "vin_stereo_calib",
              "ir_projector": "off",
              "hlsd8": {"pixelType": "${hlsd8.pixelType}", "w": ${hlsd8.width}, "h": ${hlsd8.height}, "fx": ${hlsd8.intrinsics.fx}, "fy": ${hlsd8.intrinsics.fy}, "cx": ${hlsd8.intrinsics.cx}, "cy": ${hlsd8.intrinsics.cy}},
              "lprime": {"pixelType": "${lprime.pixelType}", "w": ${lprime.width}, "h": ${lprime.height}, "fx": ${lprime.intrinsics.fx}, "fy": ${lprime.intrinsics.fy}, "cx": ${lprime.intrinsics.cx}, "cy": ${lprime.intrinsics.cy}}$depthMeta
            }
            """.trimIndent(),
        )
        return dir
    }

    override fun onCleared() {
        super.onCleared()
        if (irOff) runCatching { source.setIrProjector(true) } // 离页恢复散斑（正常测深需要）
        source.release()
        rgbSource?.release()
    }

    companion object {
        private const val TAG = "StereoCalibVM"
        private const val PREVIEW_MS = 40L
        private const val CALIB_ROOT = "vin_calib"
    }
}
