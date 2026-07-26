package io.gomob.feature.scan3d

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.scan.BundleIntrinsics
import io.gomob.data.scan.RgbdShot
import io.gomob.data.scan.Scan3dBundleUploader
import io.gomob.data.scan.ScanFusionRepository
import io.gomob.model.ColorFrame
import io.gomob.model.DepthFrame
import io.gomob.nativebridge.camera.CameraSource
import io.gomob.nativebridge.camera.CameraSourceProvider
import io.gomob.nativebridge.camera.CameraSourceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.ByteOrder
import javax.inject.Inject

/** 车辆 8 个方位（环绕一圈），与终态产品交互一致。 */
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

/** 车辆外廓扫描总状态机（采集 → 上传 → 云端融合 → 回看）。 */
sealed interface VehicleScanState {
    /** 默认态：环绕采集中（用户在各方位拍 RGBD）。 */
    data object Capturing : VehicleScanState
    /** 打包 + 分块上传 bundle 中。 */
    data object Uploading : VehicleScanState
    /** 已上传，等云端 `scan.fusion_done` 推送。 */
    data object Fusing : VehicleScanState
    /** 融合完成，GLB 已下到本地可回看。 */
    data class Completed(
        val glbFile: File,
        val vertices: Int,
        val triangles: Int,
        val frameCount: Int,
    ) : VehicleScanState
    data class Error(val msg: String) : VehicleScanState
}

/**
 * 车辆外廓扫描 VM —— 真 8 角度多视角 RGBD 采集，串 04b 端云融合主线。
 *
 * 链路：CameraSource 真帧 → 每方位抓配对 color+depth 存 [RgbdShot]（color 缩放到 depth 分辨率，
 * 共用 depth 内参，approx 对齐待 M2 registration 标定）→ 完成打 bundle 上传 kind=scan3d_bundle
 * → 服务端融合 worker → `scan.fusion_done` → 下载结果 GLB → Filament 回看。
 *
 * 与端侧 TSDF（[Scan3dRecordingViewModel]）的关系：本流程走云端高精度多视角融合（主线），
 * 当方位点云预览用本帧深度反投影提供即时"真"反馈，不在端侧做 TSDF 累积。
 */
@HiltViewModel
class VehicleContourScanViewModel @Inject constructor(
    provider: CameraSourceProvider,
    private val bundleUploader: Scan3dBundleUploader,
    private val fusionRepo: ScanFusionRepository,
) : ViewModel() {

    private val source: CameraSource = provider.active()

    private val _state = MutableStateFlow<VehicleScanState>(VehicleScanState.Capturing)
    val state: StateFlow<VehicleScanState> = _state.asStateFlow()

    /** 每方位已拍张数（长度 8，与 [VehicleAngleDefs] 对齐）。 */
    private val _shotCounts = MutableStateFlow(List(VehicleAngleDefs.size) { 0 })
    val shotCounts: StateFlow<List<Int>> = _shotCounts.asStateFlow()

    private val _activeAngle = MutableStateFlow(0)
    val activeAngle: StateFlow<Int> = _activeAngle.asStateFlow()

    private val _colorPreview = MutableStateFlow<Bitmap?>(null)
    val colorPreview: StateFlow<Bitmap?> = _colorPreview.asStateFlow()

    private val _depthPreview = MutableStateFlow<Bitmap?>(null)
    val depthPreview: StateFlow<Bitmap?> = _depthPreview.asStateFlow()

    /** 最近一次采集方位的点云（扁平 [x,y,z...] mm，相机系），喂 PointCloud3dView。 */
    private val _pointCloudPreview = MutableStateFlow(FloatArray(0))
    val pointCloudPreview: StateFlow<FloatArray> = _pointCloudPreview.asStateFlow()

    private val _capturing = MutableStateFlow(false)
    val capturing: StateFlow<Boolean> = _capturing.asStateFlow()

    /** 最近一次采集尝试时 color/depth 的时间戳差(us),供 UI/调试观测同步质量;-1 = 尚无。 */
    private val _lastSyncDeltaUs = MutableStateFlow(-1L)
    val lastSyncDeltaUs: StateFlow<Long> = _lastSyncDeltaUs.asStateFlow()

    val deviceState: StateFlow<CameraSourceState> = source.sourceState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraSourceState.Idle)

    // 最近帧快照（采集时配对取用）
    @Volatile private var latestColor: ColorFrame? = null
    @Volatile private var latestDepth: DepthFrame? = null

    /** 本会话累积的已对齐 RGBD shots（含每张所属方位，用于 UI 统计 / 打包）。 */
    private val shots = mutableListOf<CapturedShot>()
    private var bundleIntrinsics: BundleIntrinsics? = null

    private var fusionJob: Job? = null
    private var captureJob: Job? = null

    init {
        source.acquire()
        fusionRepo.ensureRealtimeConnected()

        viewModelScope.launch {
            var n = 0
            source.colorFrames.collect { frame ->
                latestColor = frame
                if (n++ % PREVIEW_DECIMATION == 0) {
                    _colorPreview.value = withContext(Dispatchers.Default) {
                        FrameRenderer.colorToBitmap(frame)
                    }
                }
            }
        }
        viewModelScope.launch {
            var n = 0
            source.depthFrames.collect { frame ->
                latestDepth = frame
                if (n++ % PREVIEW_DECIMATION == 0) {
                    _depthPreview.value = withContext(Dispatchers.Default) {
                        FrameRenderer.depth16ToBitmap(frame)
                    }
                }
            }
        }
    }

    fun selectAngle(index: Int) {
        if (index in VehicleAngleDefs.indices) _activeAngle.value = index
    }

    /** 在当前方位拍一张：抓最近 color+depth → 对齐成 RgbdShot 累积 + 出点云预览。 */
    fun capture() {
        if (_state.value != VehicleScanState.Capturing) return
        if (_capturing.value) return
        val color = latestColor
        val depth = latestDepth
        if (color == null || depth == null) {
            Log.w(TAG, "采集跳过：尚无 color/depth 帧")
            return
        }
        // 时间戳门控:color/depth 必须落在同一同步窗口内才配对,否则是时间错配帧——
        // 配准/融合会把不同瞬间的纹理贴到几何上(运动 → 鬼影/偏移)。超阈值软丢弃(不进 Error 态,
        // 留在采集态供用户稳住设备重试)并上报 delta,绝不静默用错配帧毒化 bundle。
        val deltaUs = kotlin.math.abs(color.timestampUs - depth.timestampUs)
        if (deltaUs > PAIR_SYNC_TOLERANCE_US) {
            Log.w(TAG, "采集丢弃：color/depth 时间错配 Δ=${deltaUs}us > ${PAIR_SYNC_TOLERANCE_US}us")
            _lastSyncDeltaUs.value = deltaUs
            return
        }
        _lastSyncDeltaUs.value = deltaUs
        _capturing.value = true
        captureJob = viewModelScope.launch {
            try {
                val angle = _activeAngle.value
                val shot = withContext(Dispatchers.Default) { buildShot(color, depth, angle) }
                shots.add(shot)
                if (bundleIntrinsics == null) bundleIntrinsics = shot.intrinsics
                _shotCounts.value = _shotCounts.value.toMutableList().also { it[angle] = it[angle] + 1 }
                _pointCloudPreview.value = withContext(Dispatchers.Default) {
                    backProject(shot.rgbd.depth16, shot.intrinsics)
                }
                Log.i(TAG, "方位 $angle 采集成功，累计 ${shots.size} 张")
            } catch (e: Throwable) {
                Log.w(TAG, "采集失败: ${e.message}")
                _state.value = VehicleScanState.Error("采集失败: ${e.message}")
            } finally {
                _capturing.value = false
            }
        }
    }

    /** 撤销当前方位最近一张。 */
    fun undo() {
        val angle = _activeAngle.value
        val idx = shots.indexOfLast { it.angle == angle }
        if (idx < 0) return
        shots.removeAt(idx).rgbd.rgb.recycle()
        _shotCounts.value = _shotCounts.value.toMutableList()
            .also { it[angle] = (it[angle] - 1).coerceAtLeast(0) }
    }

    /** 完成采集：打 bundle 上传 → 等融合 → 下 GLB。 */
    fun finishAndUpload() {
        if (_state.value != VehicleScanState.Capturing) return
        val captured = shots.toList()
        val intr = bundleIntrinsics
        if (captured.size < MIN_SHOTS || intr == null) {
            _state.value = VehicleScanState.Error("至少需采集 $MIN_SHOTS 张有效 RGBD 才能融合（当前 ${captured.size}）")
            return
        }
        val sessionId = "scan-${System.currentTimeMillis()}"
        _state.value = VehicleScanState.Uploading
        fusionJob = viewModelScope.launch {
            try {
                // 先挂起监听本次 session 的融合完成事件，再上传——避免 fusion_done 在订阅前到达丢失
                // （事件流 replay=0）。实际云端融合远慢于上传，竞态窗口极小，但 async-before 更正确。
                val awaitFusion = async {
                    withTimeoutOrNull(FUSION_TIMEOUT_MS) {
                        fusionRepo.fusionEvents.first { it.sessionKey == sessionId }
                    }
                }
                bundleUploader.upload(captured.map { it.rgbd }, intr, sessionId)
                Log.i(TAG, "bundle 上传完成 session=$sessionId frames=${captured.size}")
                _state.value = VehicleScanState.Fusing

                val result = awaitFusion.await()
                if (result == null) {
                    _state.value = VehicleScanState.Error("融合超时未返回（${FUSION_TIMEOUT_MS / 1000}s）")
                    return@launch
                }
                val glb = fusionRepo.downloadResultGlb(sessionId)
                _state.value = VehicleScanState.Completed(
                    glbFile = glb,
                    vertices = result.vertices,
                    triangles = result.triangles,
                    frameCount = result.frameCount,
                )
                Log.i(TAG, "融合回看就绪 v=${result.vertices} f=${result.triangles} glb=${glb.length()}B")
            } catch (e: Throwable) {
                Log.w(TAG, "上传/融合失败: ${e.message}")
                _state.value = VehicleScanState.Error("上传/融合失败: ${e.message}")
            }
        }
    }

    /** 重新开始一次扫描（清空 shots，回采集态）。 */
    fun restart() {
        fusionJob?.cancel()
        fusionJob = null
        captureJob?.cancel()
        captureJob = null
        _capturing.value = false
        shots.forEach { it.rgbd.rgb.recycle() }
        shots.clear()
        bundleIntrinsics = null
        _shotCounts.value = List(VehicleAngleDefs.size) { 0 }
        _pointCloudPreview.value = FloatArray(0)
        _state.value = VehicleScanState.Capturing
    }

    override fun onCleared() {
        super.onCleared()
        source.release()
        shots.forEach { runCatching { it.rgbd.rgb.recycle() } }
        shots.clear()
    }

    /** 把一对 color+depth 对齐成 bundle 契约的 [RgbdShot]（color 缩放到 depth 分辨率）。 */
    private fun buildShot(color: ColorFrame, depth: DepthFrame, angle: Int): CapturedShot {
        val w = depth.width
        val h = depth.height
        // color → Bitmap → 缩放到 depth 分辨率。
        // TODO(M2): 现仅 resize 不做真配准，texture 受 color/depth 基线视差影响有偏移；
        //   终态接 SDK registration / 外参标定后逐像素对齐（registeredToColor=true）。
        val colorBmp = FrameRenderer.colorToBitmap(color)
            ?: throw IllegalStateException("color 帧解码失败")
        val scaled = if (colorBmp.width == w && colorBmp.height == h) {
            colorBmp
        } else {
            Bitmap.createScaledBitmap(colorBmp, w, h, true).also {
                if (it !== colorBmp) colorBmp.recycle()
            }
        }
        // depth 裸字节（16bit LE mm），原样拷出。bulk get 与 order 无关（逐字节 memcpy），
        // 显式 LITTLE_ENDIAN 仅对齐 FrameRenderer 习惯并表明契约意图。**严格校验帧完整**：
        // 字节不足直接抛（capture 捕获→Error），绝不静默零填充毒化融合。
        val depthBytes = ByteArray(w * h * 2)
        depth.data.duplicate().order(ByteOrder.LITTLE_ENDIAN).apply {
            rewind()
            require(remaining() >= depthBytes.size) { "depth 帧不完整: ${remaining()} < ${depthBytes.size}" }
            get(depthBytes, 0, depthBytes.size)
        }
        // confidence（可选）。同样严格校验,不足即抛。
        val confBytes = depth.confidence?.let { buf ->
            ByteArray(w * h).also { b ->
                buf.duplicate().apply {
                    rewind()
                    require(remaining() >= b.size) { "conf 帧不完整: ${remaining()} < ${b.size}" }
                    get(b, 0, b.size)
                }
            }
        }
        val intr = BundleIntrinsics(
            width = w, height = h,
            fx = depth.intrinsics.fx, fy = depth.intrinsics.fy,
            cx = depth.intrinsics.cx, cy = depth.intrinsics.cy,
        )
        return CapturedShot(
            angle = angle,
            rgbd = RgbdShot(rgb = scaled, depth16 = depthBytes, confidence = confBytes, width = w, height = h),
            intrinsics = intr,
        )
    }

    /** 深度图反投影成点云（mm，相机系），抽稀后供实时预览。 */
    private fun backProject(depth16: ByteArray, intr: BundleIntrinsics): FloatArray {
        val w = intr.width
        val h = intr.height
        if (intr.fx <= 0.0 || intr.fy <= 0.0) return FloatArray(0)
        val out = ArrayList<Float>(w * h / (PREVIEW_STEP * PREVIEW_STEP) * 3)
        var v = 0
        while (v < h) {
            var u = 0
            while (u < w) {
                val i = (v * w + u) * 2
                val z = (depth16[i].toInt() and 0xFF) or ((depth16[i + 1].toInt() and 0xFF) shl 8)
                if (z in MIN_DEPTH_MM..MAX_DEPTH_MM) {
                    val zf = z.toFloat()
                    out.add(((u - intr.cx) * zf / intr.fx).toFloat())
                    out.add(((v - intr.cy) * zf / intr.fy).toFloat())
                    out.add(zf)
                }
                u += PREVIEW_STEP
            }
            v += PREVIEW_STEP
        }
        return out.toFloatArray()
    }

    private data class CapturedShot(
        val angle: Int,
        val rgbd: RgbdShot,
        val intrinsics: BundleIntrinsics,
    )

    companion object {
        private const val TAG = "VehicleContourScanVM"
        private const val PREVIEW_DECIMATION = 4
        private const val PREVIEW_STEP = 3
        private const val MIN_DEPTH_MM = 200
        private const val MAX_DEPTH_MM = 8000
        private const val MIN_SHOTS = 2
        private const val FUSION_TIMEOUT_MS = 180_000L

        /**
         * color/depth 配对同步容差(us)。Berxel MIX 模式 color@30fps + depth@45fps,
         * 单帧间隔约 22~33ms;取 ~半个 depth 周期(15ms)作为同一瞬间的判定窗口,
         * 超出即视为时间错配帧丢弃。
         */
        private const val PAIR_SYNC_TOLERANCE_US = 15_000L
    }
}
