package io.gomob.feature.scan3d

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.scan.LaserScanRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
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

    private val _fusedCloud = MutableStateFlow(FloatArray(0))
    val fusedCloud: StateFlow<FloatArray> = _fusedCloud.asStateFlow()

    private val _unitACloud = MutableStateFlow(FloatArray(0))
    val unitACloud: StateFlow<FloatArray> = _unitACloud.asStateFlow()

    private val _unitBCloud = MutableStateFlow(FloatArray(0))
    val unitBCloud: StateFlow<FloatArray> = _unitBCloud.asStateFlow()

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

    private var sessionKey: String? = null
    private var scanId: Long? = null
    private var aFrames = 0
    private var bFrames = 0

    init {
        // 进场即连实时通道，保证起扫前已能收推送。
        repo.ensureRealtimeConnected()

        repo.pointFrames
            .onEach { f ->
                if (f.sessionKey != sessionKey) return@onEach
                // 节流：snapshot 是 O(当前点数)，每帧 emit 会 O(n²) + 过频重组。每 EMIT_EVERY 帧推一次预览，
                // 采集结束（laser.status fusing）再补最终帧。
                when (f.unit) {
                    0 -> { accA.add(f.points); if (++aFrames % EMIT_EVERY == 0) _unitACloud.value = accA.snapshot() }
                    1 -> { accB.add(f.points); if (++bFrames % EMIT_EVERY == 0) _unitBCloud.value = accB.snapshot() }
                }
            }
            .launchIn(viewModelScope)

        repo.statusUpdates
            .onEach { s ->
                if (s.sessionKey != sessionKey) return@onEach
                when (s.state) {
                    "scanning" -> _state.value = LaserScanState.Scanning
                    "fusing" -> {
                        // 采集结束：补推最终两镜头预览帧（节流可能漏掉尾帧）。
                        _unitACloud.value = accA.snapshot()
                        _unitBCloud.value = accB.snapshot()
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
                val id = scanId ?: return@onEach
                // 融合云不走实时（百万点），完成后下载 PCD 取整云。
                try {
                    _fusedCloud.value = repo.downloadCloudPoints(id, "fused")
                } catch (e: Throwable) {
                    Log.w(TAG, "下载融合 PCD 失败: ${e.message}")
                }
                _state.value = LaserScanState.Completed(
                    points = d.points,
                    ptsA = d.ptsA,
                    ptsB = d.ptsB,
                    alignMethod = d.alignMethod,
                    measurement = d.measurement,
                    ground = d.ground,
                )
            }
            .launchIn(viewModelScope)
    }

    /** 起一次扫描。 */
    fun start() {
        if (_state.value == LaserScanState.Connecting || _state.value == LaserScanState.Scanning) return
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
                _state.value = LaserScanState.Error("起扫失败: ${e.message}")
            }
        }
    }

    /** 停止/取消当前扫描。 */
    fun stop() {
        val id = scanId ?: run { resetToIdle(); return }
        viewModelScope.launch {
            try {
                repo.stop(id)
            } catch (e: Throwable) {
                Log.w(TAG, "停止失败: ${e.message}")
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
    fun restart() = resetToIdle()

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
        accA.clear(); accB.clear()
        aFrames = 0; bFrames = 0
        _unitACloud.value = FloatArray(0)
        _unitBCloud.value = FloatArray(0)
        _fusedCloud.value = FloatArray(0)
    }

    private companion object {
        const val TAG = "LaserScanVM"
        const val EMIT_EVERY = 2 // 实时预览推送节流：每 2 帧 snapshot 一次（更贴近实时直渲）
    }
}

/** 容量倍增的线程安全 float 累积器（采集中两线程并发 add；snapshot 拷贝当前有效区间）。 */
internal class FloatCloudAccumulator {
    private var buf = FloatArray(4096)
    private var size = 0

    @Synchronized
    fun add(src: FloatArray) {
        ensure(size + src.size)
        System.arraycopy(src, 0, buf, size, src.size)
        size += src.size
    }

    @Synchronized
    fun snapshot(): FloatArray = buf.copyOf(size)

    @Synchronized
    fun clear() {
        size = 0
    }

    private fun ensure(n: Int) {
        if (n <= buf.size) return
        var c = buf.size * 2
        while (c < n) c *= 2
        buf = buf.copyOf(c)
    }
}
