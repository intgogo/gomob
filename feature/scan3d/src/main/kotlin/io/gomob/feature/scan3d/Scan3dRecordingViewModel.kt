package io.gomob.feature.scan3d

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gomob.nativebridge.NativeBridge
import io.gomob.nativebridge.camera.CameraSource
import io.gomob.nativebridge.camera.CameraSourceProvider
import io.gomob.nativebridge.camera.CameraSourceState
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
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
        /** 首帧 native ingest 还没回来；UI 用来显示"等待首帧"避免误以为卡死。 */
        val awaitingFirstFrame: Boolean = true,
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
 *   - VM 创建（进 Screen）→ init.acquire() 引用计数 +1 拉起 BerxelService + collect
 *     colorFrames/depthFrames 出 Bitmap 给 UI 预览
 *   - 用户点"开始" → start()：建 native session 并把 depthFrames 喂进去
 *   - 用户点"停止" → stop() → Finalizing → Marching Tetrahedra → Completed
 *   - VM cleared（退栈）→ onCleared() 关 native session + source.release() 引用计数 -1
 *
 * 相机生命周期由 [BerxelService] 引用计数单一管控：本 VM 与 [DepthCameraViewModel] 都走
 * acquire/release，导航在两页间切换时计数全程 >0，相机不再被旧 VM 的 stop 抢关（修掉历史"双 VM
 * 抢相机"）。3D 主页 ActionTile 01 直接进 Recording。
 *
 * pose7：第一阶段统一传 identity，让 native ICP 自估。后续接 IMU 时可在每帧给"IMU 估计的姿态"
 * 作为 ICP 初值（提高鲁棒性）。
 */
@HiltViewModel
class Scan3dRecordingViewModel @Inject constructor(
    provider: CameraSourceProvider,
    @ApplicationContext private val ctx: Context,
) : ViewModel() {

    /** 进场时按当前插着的相机选活动取流源（eYs3D→Eys3dCameraService / 否则 Berxel，不退化）。
     *  整段会话持有同一个 source；中途换相机是 device-gated 边缘场景，暂不处理。 */
    private val source: CameraSource = provider.active()

    private val _state = MutableStateFlow<ScanRecordingState>(ScanRecordingState.Idle)
    val state: StateFlow<ScanRecordingState> = _state.asStateFlow()

    /** TSDF 累积体的实时预览点云：扁平 [x0,y0,z0, x1,y1,z1, ...]，单位 mm，世界系。
     *  start() 后定时 peek，stop() / reset() 清空。 */
    private val _pointCloudPreview = MutableStateFlow<FloatArray>(FloatArray(0))
    val pointCloudPreview: StateFlow<FloatArray> = _pointCloudPreview.asStateFlow()

    /** finalize 完成后 native 提取的 mesh 数据快照 — UI 在 Completed 状态下用 lit material 渲染。
     *  必须在 scanSessionClose 之前从 native 拉走（close 后 last_mesh 释放）。 */
    private val _meshPreview = MutableStateFlow<ScanMeshData?>(null)
    val meshPreview: StateFlow<ScanMeshData?> = _meshPreview.asStateFlow()

    private val _colorPreview = MutableStateFlow<Bitmap?>(null)
    val colorPreview: StateFlow<Bitmap?> = _colorPreview.asStateFlow()

    private val _depthPreview = MutableStateFlow<Bitmap?>(null)
    val depthPreview: StateFlow<Bitmap?> = _depthPreview.asStateFlow()

    /** 相机设备状态（中性）— UI 用来判定开始按钮是否可用 */
    val deviceState: StateFlow<CameraSourceState> = source.sourceState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CameraSourceState.Idle)

    @Volatile private var sessionHandle: Long = 0L
    private var ingestJob: Job? = null
    private var previewJob: Job? = null
    private var tickerJob: Job? = null
    private var startedAtMs: Long = 0L
    private var framesIngested: Int = 0
    private var keyframesCount: Int = 0
    @Volatile private var firstFrameReceived: Boolean = false

    /**
     * native scan_session 单线程 dispatcher — ingest / peek / finalize / close 全部走这一个线程，
     * 避免多核并发撞 native 数据竞争（scan_session.cpp 注释 "native 不是线程安全" 已说明）。
     * VM cleared 时 close。
     */
    private val nativeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scan-session-native").apply { isDaemon = true }
    }
    private val nativeDispatcher = nativeExecutor.asCoroutineDispatcher()

    init {
        // 进入扫描页：引用计数 acquire（单一 owner 管相机），+ collect Color/Depth 预览帧
        source.acquire()

        viewModelScope.launch {
            var counter = 0
            source.colorFrames.collect { frame ->
                counter++
                if (counter % PREVIEW_DECIMATION != 0) return@collect
                val bmp = withContext(Dispatchers.Default) { FrameRenderer.colorToBitmap(frame) }
                _colorPreview.value = bmp
            }
        }
        viewModelScope.launch {
            var counter = 0
            source.depthFrames.collect { frame ->
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
        firstFrameReceived = false

        try {
            // P100R3 默认参数（覆盖手持 25-150cm + 桌面贴近 20-50cm 全场景）：
            //   voxel=6mm × extent=1500mm → 250³ ≈ 16M voxel × 8B = 125MB，端侧能扛
            //   gridCenterZ=750mm 让 grid 覆盖 z[0, 1500]mm —— 关键调整：
            //     之前 1000mm 让 grid 起点 z=250mm，用户贴近物体 (实测 20cm) 时 seed=207mm 不在
            //     grid 内（log "Ingest[1st] WARN foreground depth 207mm 不在 grid z[250,1750]mm
            //     内"），TSDF 完全没积分到物体；750mm 让 grid 起点 z=0，覆盖整个相机前方有效区。
            //     [0, 200] 段是 IsDepthValid 滤死区（P100R3 spec 0.2m 硬下限），grid 浪费 ~13%
            //     可接受。
            //   6mm voxel 对应 truncation=24mm，匹配 P100R3 精度 ≤1% @ 1-2m 的真实噪声底；
            //   4mm voxel 反而比传感器精度还细 → 噪声拉锯式刷新表面。
            //
            // TODO 阶段 2 引入"扫描预设" UI（桌面贴近 / 中件 / 大件），按 preset 选 voxel/extent/centerZ。
            sessionHandle = NativeBridge.scanSessionCreate(
                /*voxelSizeMm=*/6.0f,
                /*gridExtentMm=*/1500.0f,
                /*gridCenterZMm=*/750.0f,
            )
        } catch (e: Throwable) {
            _state.value = ScanRecordingState.Error("scanSessionCreate 失败: ${e.message}")
            return
        }
        _state.value = ScanRecordingState.Recording(0, 0, 0L, awaitingFirstFrame = true)
        Log.i(TAG, "扫描会话已建立 handle=$sessionHandle")

        // 时长独立 1Hz ticker — 与 ingest 帧节奏解耦，wall clock 走时长。
        // 修 bug：之前 elapsedMs 只在 ingest 一帧返回后才写，首帧 native 冷启动 (TSDF 64MB
        // 首次 page fault + ProjectToPointCloud 冷启) 几百 ms~秒级 → 用户看到时长卡 0
        // 然后突然跳一下，误以为录制没工作。
        tickerJob = viewModelScope.launch {
            while (isActive) {
                delay(TICKER_INTERVAL_MS)
                val cur = _state.value
                if (cur is ScanRecordingState.Recording) {
                    _state.value = cur.copy(elapsedMs = System.currentTimeMillis() - startedAtMs)
                } else {
                    break
                }
            }
        }

        // 实时点云预览协程：每 500ms peek 一次；走 nativeDispatcher 与 ingest 串行。
        previewJob = viewModelScope.launch {
            while (isActive) {
                delay(PREVIEW_PEEK_INTERVAL_MS)
                val h = sessionHandle
                if (h == 0L) break
                if (!firstFrameReceived) continue  // 首帧没到 peek 必空，省一次 native 调用
                try {
                    val pts = withContext(nativeDispatcher) {
                        if (sessionHandle == 0L) FloatArray(0)
                        else NativeBridge.scanSessionPeekVertices(h, MAX_PREVIEW_VERTICES)
                    }
                    _pointCloudPreview.value = pts
                } catch (e: Throwable) {
                    Log.w(TAG, "peek vertices 失败: ${e.message}")
                }
            }
        }

        ingestJob = viewModelScope.launch {
            var collectCount = 0
            source.depthFrames.collect { frame ->
                collectCount++
                // 每次进入 native 前 snapshot handle；stop() 把 handle 置 0 后这里跳过
                val h = sessionHandle
                if (h == 0L) return@collect
                val intr = frame.intrinsics
                val pose = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f) // identity
                try {
                    val nativeStart = System.currentTimeMillis()
                    val kf = withContext(nativeDispatcher) {
                        if (sessionHandle == 0L) -1
                        // 传 frame.confidence(可能 null):native 按置信软加权 TSDF + 加权 ICP,
                        // 弱回波/散斑弱像素降权,density-first 稠密输入也收敛到干净表面(M1.6.19)
                        else NativeBridge.scanSessionIngest(
                            h,
                            frame.data, frame.width, frame.height,
                            doubleArrayOf(intr.fx, intr.fy, intr.cx, intr.cy),
                            pose,
                            frame.confidence,
                        )
                    }
                    val nativeMs = System.currentTimeMillis() - nativeStart
                    // 只在异常慢时告警 — 正常每帧 ~250ms，超 800ms 必有问题
                    if (nativeMs > 800) {
                        Log.w(TAG, "ingest native #$collectCount took ${nativeMs}ms (慢) kf=$kf")
                    }
                    if (kf < 0) return@collect
                    framesIngested++
                    keyframesCount = kf
                    firstFrameReceived = true
                    val cur = _state.value
                    if (cur is ScanRecordingState.Recording) {
                        _state.value = cur.copy(
                            framesIngested = framesIngested,
                            keyframes = keyframesCount,
                            elapsedMs = System.currentTimeMillis() - startedAtMs,
                            awaitingFirstFrame = false,
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
                tickerJob?.cancelAndJoin()
                tickerJob = null

                val sessionId = "scan-${System.currentTimeMillis()}"
                val outDir = File(ctx.filesDir, "scans/$sessionId").apply { mkdirs() }
                // finalize → 拉 mesh → close 全部串行在 nativeDispatcher 上（单线程，无重叠）。
                // 必须在 close 之前拉走 mesh：close 后 ScanSession.last_mesh 已释放。
                val (stats, mesh) = withContext(nativeDispatcher) {
                    val s = NativeBridge.scanSessionFinalize(handle, outDir.absolutePath)
                    val mv = NativeBridge.scanSessionMeshVertices(handle)
                    val mn = NativeBridge.scanSessionMeshNormals(handle)
                    val mi = NativeBridge.scanSessionMeshIndices(handle)
                    NativeBridge.scanSessionClose(handle)
                    val m = if (mv.isNotEmpty() && mi.isNotEmpty() && mn.size == mv.size) {
                        ScanMeshData(vertices = mv, normals = mn, indices = mi)
                    } else null
                    s to m
                }
                _meshPreview.value = mesh
                _state.value = ScanRecordingState.Completed(
                    sessionId = sessionId,
                    outDir = outDir.absolutePath,
                    vertexCount = stats[0],
                    triangleCount = stats[1],
                    keyframes = stats[2],
                    durationMs = System.currentTimeMillis() - startedAtMs,
                )
                Log.i(TAG, "扫描完成 v=${stats[0]} f=${stats[1]} kf=${stats[2]} mesh=${mesh != null} → $outDir")
            } catch (e: Throwable) {
                _state.value = ScanRecordingState.Error("finalize 失败: ${e.message}")
                runCatching {
                    withContext(nativeDispatcher) { NativeBridge.scanSessionClose(handle) }
                }
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
        tickerJob?.cancel()
        tickerJob = null
        if (handle != 0L) {
            // close 也走 nativeDispatcher，保证 ingest 协程即使还在 native 中也能等它退出
            viewModelScope.launch {
                runCatching { withContext(nativeDispatcher) { NativeBridge.scanSessionClose(handle) } }
            }
        }
        framesIngested = 0
        keyframesCount = 0
        firstFrameReceived = false
        _pointCloudPreview.value = FloatArray(0)
        _meshPreview.value = null
        _state.value = ScanRecordingState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        val handle = sessionHandle
        sessionHandle = 0L
        val ij = ingestJob; ingestJob = null
        val pj = previewJob; previewJob = null
        val tj = tickerJob; tickerJob = null
        val executor = nativeExecutor
        val dispatcher = nativeDispatcher

        // native 释放派到 process-lifetime cleanup scope 跑，主线程立即返回避免 ANR。
        // 之前 onCleared 里 runBlocking(NonCancellable) 在主线程等 native 调用返回：
        //   - ingest 一帧 ~250-800ms（JNI 不响应 coroutine cancel）
        //   - preview 排在同一 single-thread nativeDispatcher 后面再等一轮
        //   - finalize（Marching Tetrahedra）几秒到十几秒
        // 累积主线程阻塞 → 用户体感"按返回 app 卡死"乃至 ANR。cleanup scope 与 VM 解耦，
        // VM 销毁后仍能跑完释放。
        cleanupScope.launch {
            runCatching {
                ij?.cancelAndJoin()
                pj?.cancelAndJoin()
                tj?.cancelAndJoin()
                if (handle != 0L) {
                    withContext(dispatcher) { NativeBridge.scanSessionClose(handle) }
                }
            }
            executor.shutdown()
        }
        source.release()
    }

    companion object {
        private const val TAG = "Scan3dRecordingVM"
        private const val PREVIEW_PEEK_INTERVAL_MS = 500L
        // SessionPeekVertices 上限。提到 50000 让 native 端 stride_by_count（按 max_vertices×64
        // 反推的内存兜底 stride）在 N=250 (extent=1500/voxel=6mm) 时降到 1，与 stride_by_physics
        // (8mm 物理间距 → voxel=6mm 时 stride=1) 对齐。50000 是 cap 不是常态——实际 nearSurf 数
        // 量只有几百到几千（取决于物体大小），FloatArray 拷贝量上界也只有 50000×3×4=600KB，可控。
        private const val MAX_PREVIEW_VERTICES = 50000
        private const val PREVIEW_DECIMATION = 5
        /** 时长 ticker 周期：250ms — 用户体感"流畅滚动"的下限。 */
        private const val TICKER_INTERVAL_MS = 250L

        /**
         * Process-lifetime cleanup scope —— VM 销毁后仍能跑完 native 释放，主线程不阻塞。
         * SupervisorJob：单个 VM cleanup 失败不影响其它；Default 调度器够用，cleanup 主要是
         * 等 nativeDispatcher 上的 native 调用结束。
         */
        private val cleanupScope = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineName("scan3d-cleanup")
        )
    }
}
