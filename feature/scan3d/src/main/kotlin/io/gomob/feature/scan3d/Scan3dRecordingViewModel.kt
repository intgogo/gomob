package io.gomob.feature.scan3d

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gomob.nativebridge.NativeBridge
import io.gomob.nativebridge.berxel.BerxelDeviceState
import io.gomob.nativebridge.berxel.BerxelService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 三维外廓扫描录制状态机。
 *
 * - [Idle]：进 Screen 即此状态，SDK 已 start 出预览，等用户点"开始"
 * - [Recording]：正在喂深度帧到 native session（每帧 framesIngested++，关键帧来自 native 计数）
 * - [Finalizing]：用户/自动停止后，native 跑 Marching Tetrahedra + 写文件中（耗时）
 * - [Completed]：finalize 出 mesh，UI 显示统计 + outDir 路径
 * - [Error]：native 抛 NativeException 或 finalize 失败
 */
sealed interface ScanRecordingState {
    object Idle : ScanRecordingState
    data class Recording(
        val framesIngested: Int,
        val keyframes: Int,
        val elapsedMs: Long,
    ) : ScanRecordingState
    data class Finalizing(val framesIngested: Int) : ScanRecordingState
    data class Completed(
        val sessionId: String,
        val outDir: String,
        val vertexCount: Int,
        val triangleCount: Int,
        val keyframes: Int,
        val durationMs: Long,
    ) : ScanRecordingState
    data class Error(val msg: String) : ScanRecordingState
}

/**
 * 三维外廓扫描 VM —— 自包含 SDK 生命周期 + 实时预览 + native scanSession 三段。
 *
 * 生命周期：
 *   - VM 创建（进 Screen）→ init.start() 启动 BerxelService（已 streaming 时幂等）+ collect
 *     colorFrames/depthFrames 出 Bitmap 给 UI 预览
 *   - 用户点"开始" → start()：建 native session 并把 depthFrames 喂进去
 *   - 用户点"停止" → stop() → Finalizing → Marching Tetrahedra → Completed
 *   - VM cleared（退栈）→ onCleared() 关 native session + 停 SDK 释放 USB
 *
 * 不嵌套：本 VM 与 [DepthCameraViewModel] 都做 init.start / onCleared.stop；为避免嵌套
 * 入栈时 Recording 关 SDK 让上游 DepthCamera VM 看到空流，**不再从 DepthCameraScreen 提供
 * 进入 Recording 的入口**（DepthCameraScreen 的 emphasis NavRow 已删，3D 主页 ActionTile 01
 * 直接进 Recording）。
 *
 * pose7：第一阶段统一传 identity，让 native ICP 自估。后续接 IMU 时可在每帧给"IMU 估计的姿态"
 * 作为 ICP 初值（提高鲁棒性）。
 */
@HiltViewModel
class Scan3dRecordingViewModel @Inject constructor(
    private val berxel: BerxelService,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<ScanRecordingState>(ScanRecordingState.Idle)
    val state: StateFlow<ScanRecordingState> = _state.asStateFlow()

    /** TSDF 累积体的实时预览点云：扁平 [x0,y0,z0, x1,y1,z1, ...]，单位 mm，世界系。
     *  start() 后定时 peek，stop() / reset() 清空。 */
    private val _pointCloudPreview = MutableStateFlow<FloatArray>(FloatArray(0))
    val pointCloudPreview: StateFlow<FloatArray> = _pointCloudPreview.asStateFlow()

    private val _colorPreview = MutableStateFlow<Bitmap?>(null)
    val colorPreview: StateFlow<Bitmap?> = _colorPreview.asStateFlow()

    private val _depthPreview = MutableStateFlow<Bitmap?>(null)
    val depthPreview: StateFlow<Bitmap?> = _depthPreview.asStateFlow()

    /** SDK 设备状态 — UI 用来判定开始按钮是否可用 */
    val deviceState: StateFlow<BerxelDeviceState> = berxel.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BerxelDeviceState.Idle)

    @Volatile private var sessionHandle: Long = 0L
    private var ingestJob: Job? = null
    private var previewJob: Job? = null
    private var startedAtMs: Long = 0L
    private var framesIngested: Int = 0
    private var keyframesCount: Int = 0

    init {
        // 进入扫描页即启动 SDK（幂等）+ collect Color/Depth 预览帧（横排小窗给用户看实时画面）
        berxel.start()

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

    /** 用户点"开始扫描" — 建 native session + 启动 ingest 协程 */
    fun start() {
        if (_state.value is ScanRecordingState.Recording ||
            _state.value is ScanRecordingState.Finalizing) {
            return
        }
        startedAtMs = System.currentTimeMillis()
        framesIngested = 0
        keyframesCount = 0

        try {
            // 5mm voxel × 1200mm extent → 240³ = 13.8M voxel × 8B = ~110MB；端侧 LOG-AN10 8GB RAM 可承受
            // gridCenterZ=600mm — 让 grid 覆盖 z[0, 1200]mm，覆盖手持 25cm-1m+ 全场景
            // (实测真机日志：avg depth 可能 800mm+，grid 必须够大才能让 TSDF 表面 voxel 落进 (-1,+1) sdf 范围)
            sessionHandle = NativeBridge.scanSessionCreate(
                /*voxelSizeMm=*/5.0f,
                /*gridExtentMm=*/1200.0f,
                /*gridCenterZMm=*/600.0f,
            )
        } catch (e: Throwable) {
            _state.value = ScanRecordingState.Error("scanSessionCreate 失败: ${e.message}")
            return
        }
        _state.value = ScanRecordingState.Recording(0, 0, 0L)
        Log.i(TAG, "扫描会话已建立 handle=$sessionHandle")

        // 实时点云预览协程：每 500ms peek 一次，UI 端 Canvas 画 2D top-view
        previewJob = viewModelScope.launch {
            while (isActive) {
                delay(PREVIEW_PEEK_INTERVAL_MS)
                val h = sessionHandle
                if (h == 0L) break
                try {
                    val pts = withContext(Dispatchers.Default) {
                        NativeBridge.scanSessionPeekVertices(h, MAX_PREVIEW_VERTICES)
                    }
                    _pointCloudPreview.value = pts
                } catch (e: Throwable) {
                    Log.w(TAG, "peek vertices 失败: ${e.message}")
                }
            }
        }

        ingestJob = viewModelScope.launch {
            berxel.depthFrames.collect { frame ->
                // 每次进入 native 前 snapshot handle；stop() 把 handle 置 0 后这里跳过
                val h = sessionHandle
                if (h == 0L) return@collect
                val intr = frame.intrinsics
                val pose = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f) // identity
                try {
                    val kf = withContext(Dispatchers.Default) {
                        NativeBridge.scanSessionIngest(
                            h,
                            frame.data, frame.width, frame.height,
                            doubleArrayOf(intr.fx, intr.fy, intr.cx, intr.cy),
                            pose,
                        )
                    }
                    framesIngested++
                    keyframesCount = kf
                    val cur = _state.value
                    if (cur is ScanRecordingState.Recording) {
                        _state.value = ScanRecordingState.Recording(
                            framesIngested = framesIngested,
                            keyframes = keyframesCount,
                            elapsedMs = System.currentTimeMillis() - startedAtMs,
                        )
                    }
                } catch (e: Throwable) {
                    // 单帧 ICP 退化 / 数据异常不中断录制 — 继续下一帧
                    Log.w(TAG, "ingest 单帧失败: ${e.message}")
                }
            }
        }
    }

    /**
     * 停止录制：
     *   1. **先把 sessionHandle 置 0** — ingest collect 协程下一帧 snapshot 看到 0 直接 return
     *   2. cancelAndJoin ingest 协程 — 等当前正在 native 中的 ingest 调用返回后协程退出
     *   3. 此时再 finalize / close 那个 local handle，不会与 ingest 抢 native 对象
     *
     * 修闪退：之前 `ingestJob.cancel()` 不阻塞，紧接 finalize 在同一 sessionHandle 上跑 →
     * native 不是线程安全 → SIGSEGV → app 闪退。
     */
    fun stop() {
        val cur = _state.value
        val ingested = when (cur) {
            is ScanRecordingState.Recording -> cur.framesIngested
            else -> return
        }
        val handle = sessionHandle
        if (handle == 0L) return
        sessionHandle = 0L  // 阻断后续 ingest 进 native
        _state.value = ScanRecordingState.Finalizing(ingested)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                ingestJob?.cancelAndJoin()  // 等 ingest 协程真正退出（含 native 调用返回）
                ingestJob = null
                previewJob?.cancelAndJoin()
                previewJob = null

                val sessionId = "scan-${System.currentTimeMillis()}"
                val outDir = File(ctx.filesDir, "scans/$sessionId").apply { mkdirs() }
                val stats = NativeBridge.scanSessionFinalize(handle, outDir.absolutePath)
                NativeBridge.scanSessionClose(handle)
                _state.value = ScanRecordingState.Completed(
                    sessionId = sessionId,
                    outDir = outDir.absolutePath,
                    vertexCount = stats[0],
                    triangleCount = stats[1],
                    keyframes = stats[2],
                    durationMs = System.currentTimeMillis() - startedAtMs,
                )
                Log.i(TAG, "扫描完成 v=${stats[0]} f=${stats[1]} kf=${stats[2]} → $outDir")
            } catch (e: Throwable) {
                _state.value = ScanRecordingState.Error("finalize 失败: ${e.message}")
                runCatching { NativeBridge.scanSessionClose(handle) }
            }
        }
    }

    fun reset() {
        val handle = sessionHandle
        sessionHandle = 0L
        ingestJob?.cancel()
        ingestJob = null
        previewJob?.cancel()
        previewJob = null
        if (handle != 0L) runCatching { NativeBridge.scanSessionClose(handle) }
        framesIngested = 0
        keyframesCount = 0
        _pointCloudPreview.value = FloatArray(0)
        _state.value = ScanRecordingState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        val handle = sessionHandle
        sessionHandle = 0L
        val ij = ingestJob; ingestJob = null
        val pj = previewJob; previewJob = null
        if (handle != 0L || ij?.isActive == true || pj?.isActive == true) {
            runCatching {
                runBlocking(NonCancellable) {
                    ij?.cancelAndJoin()
                    pj?.cancelAndJoin()
                    if (handle != 0L) NativeBridge.scanSessionClose(handle)
                }
            }
        }
        berxel.stop()
    }

    companion object {
        private const val TAG = "Scan3dRecordingVM"
        private const val PREVIEW_PEEK_INTERVAL_MS = 500L
        private const val MAX_PREVIEW_VERTICES = 5000
        private const val PREVIEW_DECIMATION = 5
    }
}
