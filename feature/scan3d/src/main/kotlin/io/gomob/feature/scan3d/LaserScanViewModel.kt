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
                val r = repo.start()
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

    /** 重新开始（完成/出错后）。 */
    fun restart() = resetToIdle()

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
        const val EMIT_EVERY = 8 // 实时预览推送节流：每 8 帧 snapshot 一次
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
