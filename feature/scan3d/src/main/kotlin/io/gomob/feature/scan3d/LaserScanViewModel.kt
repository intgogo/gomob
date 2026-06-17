package io.gomob.feature.scan3d

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.scan.LaserDoneResult
import io.gomob.data.scan.LaserCloudRenderData
import io.gomob.data.scan.LaserScanRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import javax.inject.Inject

/**
 * 激光车辆外廓扫描 ViewModel（M8' 瘦客户端）：只发指令 + 收 ws 流 + 下载结果，连接/采集/融合全在服务端。
 *
 * - start()：POST /v1/scans/laser → 监听 laser.points 增量喂两镜头点云、laser.status 推进状态机、
 *   scan.fusion_done(kind=laser) 下载融合 PCD。
 * - stop()：POST .../stop（设备 SCAN_STOP + 协作取消采集）。
 */
@HiltViewModel
class LaserScanViewModel @Inject constructor(
    private val repo: LaserScanRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<LaserScanState>(LaserScanState.Idle)
    val state: StateFlow<LaserScanState> = _state.asStateFlow()

    private val _fusedCloud = MutableStateFlow(LaserCloudRenderData.Empty)
    val fusedCloud: StateFlow<LaserCloudRenderData> = _fusedCloud.asStateFlow()

    private val _unitACloud = MutableStateFlow(LaserCloudRenderData.Empty)
    val unitACloud: StateFlow<LaserCloudRenderData> = _unitACloud.asStateFlow()

    private val _unitBCloud = MutableStateFlow(LaserCloudRenderData.Empty)
    val unitBCloud: StateFlow<LaserCloudRenderData> = _unitBCloud.asStateFlow()

    // 当前选中车型（控制栏车型下拉；随扫描下发服务端套 carType 偏移/合规/记录）。默认常规货车。
    private val _vehicleType = MutableStateFlow(io.gomob.data.scan.VehicleTypeCatalog.default)
    val vehicleType: StateFlow<io.gomob.data.scan.VehicleType> = _vehicleType.asStateFlow()

    fun selectVehicleType(t: io.gomob.data.scan.VehicleType) { _vehicleType.value = t }

    // 按单元车位框（M10.2）：a 框在世界系(融合/镜头A)、b 框在 unitB 设备系。各自独立持久化。
    // 缓存供 UI 显示「已圈选」与编辑器/漫游进场预填。
    private val _boxA = MutableStateFlow<io.gomob.data.scan.ScanCropBox?>(null)
    val boxA: StateFlow<io.gomob.data.scan.ScanCropBox?> = _boxA.asStateFlow()
    private val _boxB = MutableStateFlow<io.gomob.data.scan.ScanCropBox?>(null)
    val boxB: StateFlow<io.gomob.data.scan.ScanCropBox?> = _boxB.asStateFlow()

    private val accA = FloatCloudAccumulator()
    private val accB = FloatCloudAccumulator()
    private val pointStateLock = Any()
    private val pointIngestExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "LaserPointIngest").apply { isDaemon = true }
    }
    private val pointIngestDispatcher = pointIngestExecutor.asCoroutineDispatcher()
    private val pointIngestScope = CoroutineScope(SupervisorJob() + pointIngestDispatcher)

    @Volatile
    private var sessionKey: String? = null
    @Volatile
    private var scanId: Long? = null
    private var aFrames = 0
    private var bFrames = 0
    private var lastEmitAMs = 0L
    private var lastEmitBMs = 0L
    private var startInFlight = false
    private var stopInFlight = false

    init {
        // 进场即连实时通道，保证起扫前已能收推送。
        repo.ensureRealtimeConnected()

        pointIngestScope.launch {
            repo.pointFrames
                .buffer(capacity = POINT_FRAME_BUFFER)
                .collect { f ->
                    if (f.sessionKey != sessionKey) return@collect
                    ingestPointFrame(f.unit, f.points, f.hAngleDeg)
                }
            }

        repo.statusUpdates
            .onEach { s ->
                if (s.sessionKey != sessionKey) return@onEach
                when (s.state) {
                    "scanning" -> _state.value = LaserScanState.Scanning
                    "fusing" -> {
                        emitFinalUnitSnapshots()
                        _state.value = LaserScanState.Processing
                    }
                    "cancelled" -> resetToIdle()
                    "error" -> _state.value = LaserScanState.Error("扫描出错")
                    // "done" 由 doneEvents 处理（带三朵 PCD key）
                }
            }
            .launchIn(viewModelScope)

        repo.doneEvents
            .onEach { d ->
                if (d.sessionKey != sessionKey) return@onEach
                handleDoneEvent(d)
            }
            .flowOn(Dispatchers.IO)
            .launchIn(viewModelScope)
    }

    /** 起一次扫描。 */
    fun start() {
        if (startInFlight || _state.value !is LaserScanState.Idle) return
        startInFlight = true
        resetClouds()
        _state.value = LaserScanState.Connecting
        viewModelScope.launch {
            try {
                val r = repo.start(vehicleTypeId = _vehicleType.value.id)
                scanId = r.scanId
                sessionKey = r.sessionKey
                // 服务端立即 capturing；真正进入 Scanning 由 laser.status "scanning" 触发。
                Log.i(TAG, "激光扫描已起 scan_id=${r.scanId} session=${r.sessionKey}")
            } catch (e: Throwable) {
                _state.value = LaserScanState.Error(startFailureMessage(e))
            } finally {
                startInFlight = false
            }
        }
    }

    /** 停止/取消当前扫描。 */
    fun stop() {
        if (stopInFlight) return
        val id = scanId ?: run { resetToIdle(); return }
        stopInFlight = true
        viewModelScope.launch {
            try {
                repo.stop(id)
            } catch (e: Throwable) {
                Log.w(TAG, "停止失败: ${e.message}")
            } finally {
                stopInFlight = false
            }
            resetToIdle()
        }
    }

    /**
     * 撤销本次扫描：停掉进行中的采集 → 清空两镜头/融合点云 + 复位状态 → 两单元镜头归零（ALIGN_ZERO）。
     * 对照相机页「撤销」语义，但激光是整次扫描级撤销（非单帧）。
     */
    fun undo() {
        val id = scanId
        viewModelScope.launch {
            if (id != null) {
                try { repo.stop(id) } catch (e: Throwable) { Log.w(TAG, "撤销-停止失败: ${e.message}") }
            }
            resetToIdle() // 清空点云 + 回 Idle
            // 镜头归零：两单元各发 ALIGN_ZERO（电机回零）。失败不阻断（仅记日志）。
            try { repo.deviceCommand("a", "ALIGN_ZERO") } catch (e: Throwable) { Log.w(TAG, "镜头A归零失败: ${e.message}") }
            try { repo.deviceCommand("b", "ALIGN_ZERO") } catch (e: Throwable) { Log.w(TAG, "镜头B归零失败: ${e.message}") }
        }
    }

    /** 重新开始（完成/出错后）。 */
    fun restart() {
        if (startInFlight) return
        resetToIdle()
    }

    // --- 按单元持久车位框（M9.11 / M10.2）：用户在各镜头点云空间圈 3D 框 → 每次扫描裁框内测量 ---

    /** 拖框预览：用候选框裁当前已完成扫描指定镜头点云(a→unitA / b→unitB)并测量。无 scanId / 失败回 null。 */
    suspend fun cropPreview(unit: String, box: io.gomob.data.scan.ScanCropBox): io.gomob.data.scan.CropPreviewResult? {
        val id = scanId ?: return null
        return runCatching { repo.cropPreview(id, unit, box) }.getOrElse {
            Log.w(TAG, "拖框预览失败($unit): ${it.message}"); null
        }
    }

    /** 保存/覆盖某单元车位框（服务端持久化，下次扫描自动裁框内测量）。成功后更新本地缓存。 */
    fun saveCropBox(unit: String, box: io.gomob.data.scan.ScanCropBox, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching { repo.saveCropBox(unit, box) }.isSuccess
            if (ok) { if (unit == "b") _boxB.value = box else _boxA.value = box }
            else Log.w(TAG, "保存车位框失败($unit)")
            onDone(ok)
        }
    }

    /** 取某单元已保存的车位框（编辑器/漫游进场预填）。失败/未设置回 null。 */
    suspend fun loadCropBox(unit: String): io.gomob.data.scan.ScanCropBox? =
        runCatching { repo.getCropBox(unit) }.getOrNull()

    /** 预载 A/B 两单元已存框到缓存（进场刷新「已圈选」状态）。失败静默回退。 */
    fun refreshCropBoxes() {
        viewModelScope.launch {
            _boxA.value = runCatching { repo.getCropBox("a") }.getOrNull()
            _boxB.value = runCatching { repo.getCropBox("b") }.getOrNull()
        }
    }

    private fun resetToIdle() {
        _state.value = LaserScanState.Idle
        sessionKey = null
        scanId = null
        resetClouds()
    }

    private fun resetClouds() {
        synchronized(pointStateLock) {
            accA.clear(); accB.clear()
            aFrames = 0; bFrames = 0
            lastEmitAMs = 0L; lastEmitBMs = 0L
        }
        _unitACloud.value = LaserCloudRenderData.Empty
        _unitBCloud.value = LaserCloudRenderData.Empty
        _fusedCloud.value = LaserCloudRenderData.Empty
    }

    private fun ingestPointFrame(unit: Int, points: FloatArray, hAngleDeg: Float) {
        var emitUnit: Int? = null
        var cloud = LaserCloudRenderData.Empty
        val now = SystemClock.elapsedRealtime()
        synchronized(pointStateLock) {
            when (unit) {
                0 -> {
                    accA.add(points, hAngleDeg)
                    aFrames++
                    if (lastEmitAMs == 0L || now - lastEmitAMs >= LIVE_RENDER_INTERVAL_MS) {
                        cloud = accA.snapshotRender()
                        lastEmitAMs = now
                        emitUnit = 0
                    }
                }
                1 -> {
                    accB.add(points, hAngleDeg)
                    bFrames++
                    if (lastEmitBMs == 0L || now - lastEmitBMs >= LIVE_RENDER_INTERVAL_MS) {
                        cloud = accB.snapshotRender()
                        lastEmitBMs = now
                        emitUnit = 1
                    }
                }
            }
        }
        when (emitUnit) {
            0 -> _unitACloud.value = cloud
            1 -> _unitBCloud.value = cloud
        }
    }

    private fun emitFinalUnitSnapshots() {
        val targetSession = sessionKey
        pointIngestScope.launch {
            val clouds = synchronized(pointStateLock) {
                accA.snapshotRender() to accB.snapshotRender()
            }
            if (targetSession != sessionKey || _state.value is LaserScanState.Completed) return@launch
            _unitACloud.value = clouds.first
            _unitBCloud.value = clouds.second
        }
    }

    private suspend fun handleDoneEvent(d: LaserDoneResult) {
        val id = scanId ?: return
        // 融合 + 两单元云完成后都下载权威整云：实时增量流只作采集中预览；最终显示用服务端完整 PCD。
        val warnings = mutableListOf<String>()
        var loadedFused = -1
        var loadedA = -1
        var loadedB = -1
        try {
            val cloud = repo.downloadCloudRenderData(id, "fused")
            if (sessionKey != d.sessionKey || scanId != id) return
            _fusedCloud.value = cloud
            loadedFused = cloud.pointCount
        } catch (e: Throwable) {
            Log.w(TAG, "下载融合 PCD 失败: ${e.message}")
            warnings += "融合 PCD 下载失败"
        }
        try {
            val cloud = repo.downloadCloudRenderData(id, "unit_a")
            if (sessionKey != d.sessionKey || scanId != id) return
            _unitACloud.value = cloud
            loadedA = cloud.pointCount
        } catch (e: Throwable) {
            Log.w(TAG, "下载 unitA PCD 失败: ${e.message}")
            warnings += "A PCD 下载失败"
        }
        try {
            val cloud = repo.downloadCloudRenderData(id, "unit_b")
            if (sessionKey != d.sessionKey || scanId != id) return
            _unitBCloud.value = cloud
            loadedB = cloud.pointCount
        } catch (e: Throwable) {
            Log.w(TAG, "下载 unitB PCD 失败: ${e.message}")
            warnings += "B PCD 下载失败"
        }
        fun check(label: String, got: Int, expected: Int) {
            if (got >= 0 && got != expected) warnings += "$label 点数不一致 $got/$expected"
        }
        check("融合", loadedFused, d.points)
        check("A", loadedA, d.ptsA)
        check("B", loadedB, d.ptsB)
        val integrity = warnings.takeIf { it.isNotEmpty() }?.joinToString("；")
        if (integrity != null) Log.w(TAG, "点云完整性告警: $integrity")
        if (sessionKey != d.sessionKey || scanId != id) return
        _state.value = LaserScanState.Completed(
            points = d.points,
            ptsA = d.ptsA,
            ptsB = d.ptsB,
            alignMethod = d.alignMethod,
            measurement = d.measurement,
            ground = d.ground,
            pointIntegrityWarning = integrity,
        )
    }

    private fun startFailureMessage(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        return if (msg.contains("已有进行中的激光扫描") || msg.contains("已有扫描在进行")) {
            "已有扫描在进行，请先停止当前扫描"
        } else {
            "起扫失败: $msg"
        }
    }

    private companion object {
        const val TAG = "LaserScanVM"
        const val LIVE_RENDER_INTERVAL_MS = 220L
        const val POINT_FRAME_BUFFER = 1024
    }

    override fun onCleared() {
        pointIngestScope.cancel()
        pointIngestDispatcher.close()
        super.onCleared()
    }
}

/** 容量倍增的线程安全 float 累积器（采集中两线程并发 add；snapshot 拷贝当前有效区间）。 */
internal class FloatCloudAccumulator {
    private var buf = FloatArray(4096)
    private var angleBuf = FloatArray(1366)
    private var size = 0
    private var hasAngles = false

    @Synchronized
    fun add(src: FloatArray, hAngleDeg: Float? = null) {
        ensure(size + src.size)
        System.arraycopy(src, 0, buf, size, src.size)
        if (hAngleDeg != null) {
            val fromPoint = size / 3
            val points = src.size / 3
            ensureAngles(fromPoint + points)
            java.util.Arrays.fill(angleBuf, fromPoint, fromPoint + points, hAngleDeg)
            hasAngles = true
        }
        size += src.size
    }

    @Synchronized
    fun snapshot(): FloatArray = buf.copyOf(size)

    @Synchronized
    fun snapshotRender(): LaserCloudRenderData {
        val xyz = buf.copyOf(size)
        val angles = if (hasAngles) angleBuf.copyOf(size / 3) else FloatArray(0)
        return LaserCloudRenderData(xyz, angles = angles)
    }

    @Synchronized
    fun clear() {
        size = 0
        hasAngles = false
    }

    private fun ensure(n: Int) {
        if (n <= buf.size) return
        var c = buf.size * 2
        while (c < n) c *= 2
        buf = buf.copyOf(c)
    }

    private fun ensureAngles(n: Int) {
        if (n <= angleBuf.size) return
        var c = angleBuf.size * 2
        while (c < n) c *= 2
        angleBuf = angleBuf.copyOf(c)
    }
}
