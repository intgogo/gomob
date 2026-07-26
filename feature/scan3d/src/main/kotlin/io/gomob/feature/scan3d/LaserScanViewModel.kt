package io.gomob.feature.scan3d

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.scan.LaserDoneResult
import io.gomob.data.scan.LaserCloudRenderData
import io.gomob.data.scan.LaserPointFrame
import io.gomob.data.scan.LaserScanInfo
import io.gomob.data.scan.LaserScanRepository
import io.gomob.data.scan.LaserScanResult
import io.gomob.data.scan.LaserStartResult
import io.gomob.data.scan.LaserStatusUpdate
import io.gomob.data.scan.MeasuredCloudArtifact
import io.gomob.data.scan.enforceMeasuredArtifactContract
import io.gomob.data.scan.enforceProductionEligibilityContract
import io.gomob.data.scan.withoutVerifiedConclusion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.floor

internal const val LASER_LIVE_PREVIEW_POINT_BUDGET = 131_072
internal const val LASER_PRIMARY_RENDER_POINT_BUDGET = 262_144
internal const val LASER_FUSED_RENDER_POINT_BUDGET = LASER_PRIMARY_RENDER_POINT_BUDGET
internal const val LASER_UNIT_RENDER_POINT_BUDGET = 65_536
internal const val LASER_MEASURED_VERIFY_POINT_BUDGET = 1

/** 把网络与实时边界抽出，轮询竞态可用纯 Kotlin fake 验证。 */
internal interface LaserScanDataSource {
    val pointFrames: Flow<LaserPointFrame>
    val statusUpdates: Flow<LaserStatusUpdate>
    val doneEvents: Flow<LaserDoneResult>

    fun ensureRealtimeConnected()
    suspend fun active(): LaserScanInfo?
    suspend fun latest(): LaserScanInfo?
    suspend fun start(): LaserStartResult
    suspend fun stop(scanId: Long): String
    suspend fun status(scanId: Long): LaserScanInfo
    suspend fun downloadCloudRenderData(
        scanId: Long,
        name: String,
        expectedArtifact: MeasuredCloudArtifact? = null,
    ): LaserCloudRenderData
    suspend fun downloadActiveCloudRenderData(name: String): LaserCloudRenderData
    suspend fun deviceCommand(unit: String, cmd: String)
}

internal interface LaserScanLogger {
    fun info(message: String)
    fun warn(message: String)
}

private object NoOpLaserScanLogger : LaserScanLogger {
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
}

private object AndroidLaserScanLogger : LaserScanLogger {
    override fun info(message: String) {
        Log.i("LaserScanVM", message)
    }

    override fun warn(message: String) {
        Log.w("LaserScanVM", message)
    }
}

private class RepositoryLaserScanDataSource(
    private val repository: LaserScanRepository,
) : LaserScanDataSource {
    override val pointFrames: Flow<LaserPointFrame> = repository.pointFrames
    override val statusUpdates: Flow<LaserStatusUpdate> = repository.statusUpdates
    override val doneEvents: Flow<LaserDoneResult> = repository.doneEvents

    override fun ensureRealtimeConnected() = repository.ensureRealtimeConnected()
    override suspend fun active(): LaserScanInfo? = repository.active()
    override suspend fun latest(): LaserScanInfo? = repository.latestInfo()
    override suspend fun start(): LaserStartResult = repository.start()
    override suspend fun stop(scanId: Long): String = repository.stop(scanId)
    override suspend fun status(scanId: Long): LaserScanInfo = repository.status(scanId)
    override suspend fun downloadCloudRenderData(
        scanId: Long,
        name: String,
        expectedArtifact: MeasuredCloudArtifact?,
    ): LaserCloudRenderData =
        repository.downloadCloudRenderData(
            scanId = scanId,
            name = name,
            maxPoints = when (name) {
                "unit_a", "unit_b" -> LASER_UNIT_RENDER_POINT_BUDGET
                // measured 只用于服务端已做全量 SHA 校验后的客户端内容身份复核，不进入渲染状态。
                "measured" -> LASER_MEASURED_VERIFY_POINT_BUDGET
                else -> LASER_PRIMARY_RENDER_POINT_BUDGET
            },
            expectedArtifact = expectedArtifact,
        )
    override suspend fun downloadActiveCloudRenderData(name: String): LaserCloudRenderData =
        repository.downloadActiveCloudRenderData(name, LASER_LIVE_PREVIEW_POINT_BUDGET)
    override suspend fun deviceCommand(unit: String, cmd: String) = repository.deviceCommand(unit, cmd)
}

/**
 * 激光车辆外廓扫描 ViewModel（M8' 瘦客户端）：只发指令 + 收 ws 流 + 下载结果，连接/采集/融合全在服务端。
 *
 * - start()：POST /v1/scans/laser → 监听 laser.points 增量喂两镜头点云、laser.status 推进状态机、
 *   scan.fusion_done(kind=laser) 下载融合 PCD。
 * - stop()：POST .../stop（设备 SCAN_STOP + 协作取消采集）。
 */
@HiltViewModel
class LaserScanViewModel internal constructor(
    private val repo: LaserScanDataSource,
    private val doneDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val statusPollIntervalMs: Long = STATUS_POLL_INTERVAL_MS,
    pointDispatcher: CoroutineDispatcher? = null,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime,
    private val logger: LaserScanLogger = NoOpLaserScanLogger,
) : ViewModel() {

    @Inject constructor(repository: LaserScanRepository) : this(
        repo = RepositoryLaserScanDataSource(repository),
        logger = AndroidLaserScanLogger,
    )

    // 复用主干现有 Connecting 视觉表达进场恢复，避免为恢复过程引入新的 UI 状态。
    private val _state = MutableStateFlow<LaserScanState>(LaserScanState.Connecting)
    val state: StateFlow<LaserScanState> = _state.asStateFlow()

    private val _fusedCloud = MutableStateFlow(LaserCloudRenderData.Empty)
    val fusedCloud: StateFlow<LaserCloudRenderData> = _fusedCloud.asStateFlow()

    private val _unitACloud = MutableStateFlow(LaserCloudRenderData.Empty)
    val unitACloud: StateFlow<LaserCloudRenderData> = _unitACloud.asStateFlow()

    private val _unitBCloud = MutableStateFlow(LaserCloudRenderData.Empty)
    val unitBCloud: StateFlow<LaserCloudRenderData> = _unitBCloud.asStateFlow()

    private val _stopping = MutableStateFlow(false)
    val stopping: StateFlow<Boolean> = _stopping.asStateFlow()

    private val accA = BoundedVoxelCloudSampler(LASER_LIVE_PREVIEW_POINT_BUDGET)
    private val accB = BoundedVoxelCloudSampler(LASER_LIVE_PREVIEW_POINT_BUDGET)
    private val activeRestoreBacklogA = BoundedVoxelCloudSampler(LASER_LIVE_PREVIEW_POINT_BUDGET)
    private val activeRestoreBacklogB = BoundedVoxelCloudSampler(LASER_LIVE_PREVIEW_POINT_BUDGET)
    private val pointStateLock = Any()
    private var restoringActiveA = false
    private var restoringActiveB = false
    private val ownedPointIngestDispatcher = if (pointDispatcher == null) {
        Executors.newSingleThreadExecutor { r ->
            Thread(r, "LaserPointIngest").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    } else {
        null
    }
    private val pointIngestDispatcher = pointDispatcher ?: requireNotNull(ownedPointIngestDispatcher)
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
    private var startJob: Job? = null
    private var restoreJob: Job? = null
    private var pendingStopCallback: (() -> Unit)? = null
    private var pendingAlignZero = false
    private var statusPollJob: Job? = null
    private val completionLock = Any()
    private var completingSessionKey: String? = null
    private val loadedFinalCloudNames = mutableSetOf<String>()
    private var lastCompletedResult: LaserScanResult? = null
    private var cloudRetryJob: Job? = null
    private var cloudRetryAttempt = 0
    private var restoreFailed = false

    init {
        // 进场即连实时通道，保证起扫前已能收推送。
        repo.ensureRealtimeConnected()

        pointIngestScope.launch {
            repo.pointFrames
                .buffer(
                    capacity = POINT_FRAME_BUFFER,
                    onBufferOverflow = BufferOverflow.DROP_OLDEST,
                )
                .collect(::ingestPointFrameIfActive)
            }

        repo.statusUpdates
            .onEach { s ->
                if (s.sessionKey != sessionKey) return@onEach
                updateReliableSourceCounts(s)
                when (s.state) {
                    "scanning" -> if ((_state.value as? LaserScanState.Error)?.activeScan != true) {
                        _state.value = LaserScanState.Scanning
                    }
                    "fusing" -> {
                        emitFinalUnitSnapshots()
                        enterProcessing()
                    }
                    "cancelled", "canceled" -> completeStopSuccess()
                    "error", "failed" -> if (!completePendingTerminalAction()) {
                        enterError("扫描出错")
                    }
                    // 正常展示仍由 doneEvents/REST 完整结果处理；安全离页只需确认服务端终态。
                    "done", "completed" -> completePendingTerminalAction()
                }
            }
            .launchIn(viewModelScope)

        repo.doneEvents
            .onEach { d ->
                if (d.result.sessionKey != sessionKey) return@onEach
                handleDoneEvent(d)
            }
            .flowOn(doneDispatcher)
            .launchIn(viewModelScope)

        restoreJob = viewModelScope.launch { restoreOnEntry() }
    }

    /** 起一次扫描。 */
    fun start() {
        if (startInFlight || stopInFlight || _state.value !is LaserScanState.Idle) return
        pendingStopCallback = null
        pendingAlignZero = false
        startInFlight = true
        resetClouds()
        _state.value = LaserScanState.Connecting
        startJob = viewModelScope.launch {
            try {
                // 只测外廓长宽高（纯 OBB 几何，与车型无关）；车型/合规/测量范围已移到网页端配置，
                // 端侧不再下发 vehicleTypeId（服务端按未选 nID=-1 处理，不套 carType 偏移）。
                val r = repo.start()
                scanId = r.scanId
                sessionKey = r.sessionKey
                startStatusPolling(r.scanId, r.sessionKey)
                // 服务端立即 capturing；真正进入 Scanning 由 laser.status "scanning" 触发。
                logger.info("激光扫描已起 scan_id=${r.scanId} session=${r.sessionKey}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val recovered = try {
                    repo.active()
                } catch (activeError: CancellationException) {
                    throw activeError
                } catch (activeError: Throwable) {
                    logger.warn("起扫响应失败后查询活动任务也失败: ${activeError.message}")
                    null
                }
                if (recovered != null) {
                    logger.warn("起扫响应不确定，已从服务端恢复 scan_id=${recovered.scanId}")
                    restoreActiveScan(recovered)
                } else {
                    enterError(startFailureMessage(e))
                }
            } finally {
                startInFlight = false
                startJob = null
            }
        }
    }

    /** 停止/取消当前扫描。 */
    fun stop() = requestStop(action = "停止")

    /** 返回页面前先确认服务端任务进入终态，避免离页后遗留采集。 */
    fun stopThen(onStopped: () -> Unit) = requestStop(action = "停止", onStopped = onStopped)

    /** 撤销本次扫描：正式取消任务后清空结果，并让两个扫描单元回到零位。 */
    fun undo() = requestStop(action = "撤销", alignZeroAfterStop = true)

    /** 只有服务端确认终态才清理本地身份；失败时保留会话，允许重试并继续 REST 轮询。 */
    private fun requestStop(
        action: String,
        onStopped: (() -> Unit)? = null,
        alignZeroAfterStop: Boolean = false,
    ) {
        if (onStopped != null && pendingStopCallback == null) pendingStopCallback = onStopped
        if (alignZeroAfterStop) pendingAlignZero = true
        if (stopInFlight) return
        stopInFlight = true
        _stopping.value = true
        viewModelScope.launch {
            try {
                // Connecting 时 POST 可能已在服务端起扫但响应尚未回来，必须先等扫描身份可见。
                restoreJob?.join()
                startJob?.join()
                val id = scanId
                val targetSession = sessionKey
                if (id == null || targetSession.isNullOrBlank()) {
                    completeStopSuccess()
                    return@launch
                }
                when (val status = repo.stop(id).trim().lowercase()) {
                    "cancelled", "canceled" -> {
                        if (scanId == id && sessionKey == targetSession) completeStopSuccess()
                    }
                    "done", "completed", "failed", "error" -> reconcileAfterStop(id, targetSession, status)
                    else -> {
                        reconcileAfterStop(id, targetSession, status)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                val id = scanId
                val targetSession = sessionKey
                logger.warn("$action 失败，保留当前扫描: ${e.message}")
                if (id != null && !targetSession.isNullOrBlank()) {
                    reconcileAfterStop(id, targetSession, returnedStatus = null, stopFailure = e)
                } else {
                    completeStopSuccess()
                }
            } finally {
                stopInFlight = false
                _stopping.value = false
            }
        }
    }

    private suspend fun reconcileAfterStop(
        id: Long,
        targetSession: String,
        returnedStatus: String?,
        stopFailure: Throwable? = null,
    ) {
        val info = try {
            repo.status(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            stopFailure?.addSuppressed(e)
            markStopUnconfirmed(
                id = id,
                targetSession = targetSession,
                detail = stopFailure?.message ?: e.message ?: returnedStatus.orEmpty(),
            )
            return
        }
        if (scanId != id || sessionKey != targetSession) return
        when (info.status) {
            "cancelled", "canceled" -> completeStopSuccess()
            "done", "completed" -> {
                if (completePendingTerminalAction()) return
                val result = info.result
                if (result == null) {
                    logger.warn("停止竞态读到 done，但结果字段未就绪，继续轮询")
                    ensureStatusPolling(id, targetSession)
                } else {
                    handleCompletedResult(result)
                }
            }
            "failed", "error" -> if (!completePendingTerminalAction()) {
                enterError(scanFailureMessage(info.error))
            }
            else -> markStopUnconfirmed(
                id = id,
                targetSession = targetSession,
                detail = buildString {
                    stopFailure?.message?.takeIf { it.isNotBlank() }?.let { append(it) }
                    returnedStatus?.takeIf { it.isNotBlank() }?.let {
                        if (isNotEmpty()) append("；")
                        append("停止响应=").append(it)
                    }
                    if (isNotEmpty()) append("；")
                    append("当前状态=").append(info.status)
                },
            )
        }
    }

    private fun markStopUnconfirmed(id: Long, targetSession: String, detail: String) {
        if (scanId != id || sessionKey != targetSession) return
        logger.warn("停止未确认，保留当前扫描并继续轮询: $detail")
        _state.value = LaserScanState.Error(
            msg = "停止失败，服务端任务可能仍在运行，请重试：$detail",
            activeScan = true,
        )
        ensureStatusPolling(id, targetSession)
    }

    private fun completeStopSuccess() {
        val callback = pendingStopCallback
        val alignZero = pendingAlignZero
        pendingStopCallback = null
        pendingAlignZero = false
        resetToIdle()
        callback?.invoke()
        if (alignZero) alignDevicesToZero()
    }

    /** 服务端自然进入任一终态时，兑现此前的安全返回/撤销且只执行一次。 */
    private fun completePendingTerminalAction(): Boolean {
        if (pendingStopCallback == null && !pendingAlignZero) return false
        completeStopSuccess()
        return true
    }

    private fun alignDevicesToZero() {
        viewModelScope.launch {
            try {
                repo.deviceCommand("a", "ALIGN_ZERO")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("镜头 A 归零失败: ${e.message}")
            }
            try {
                repo.deviceCommand("b", "ALIGN_ZERO")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                logger.warn("镜头 B 归零失败: ${e.message}")
            }
        }
    }

    private fun ensureStatusPolling(id: Long, targetSession: String) {
        if (scanId == id && sessionKey == targetSession && statusPollJob?.isActive != true) {
            startStatusPolling(id, targetSession)
        }
    }

    /** 重新开始（完成/出错后）。 */
    fun restart() {
        if (startInFlight || stopInFlight || (_state.value as? LaserScanState.Error)?.activeScan == true) return
        if (_state.value is LaserScanState.Error && restoreFailed) {
            restoreFailed = false
            _state.value = LaserScanState.Connecting
            restoreJob = viewModelScope.launch { restoreOnEntry() }
            return
        }
        pendingStopCallback = null
        pendingAlignZero = false
        resetToIdle()
    }

    fun retryFinalClouds() {
        val result = lastCompletedResult ?: return
        cloudRetryJob?.cancel()
        cloudRetryJob = null
        cloudRetryAttempt = 0
        synchronized(completionLock) { completingSessionKey = null }
        viewModelScope.launch { handleCompletedResult(result) }
    }

    private fun resetToIdle() {
        statusPollJob?.cancel()
        statusPollJob = null
        cloudRetryJob?.cancel()
        cloudRetryJob = null
        synchronized(completionLock) {
            _state.value = LaserScanState.Idle
            sessionKey = null
            scanId = null
            completingSessionKey = null
            loadedFinalCloudNames.clear()
        }
        lastCompletedResult = null
        cloudRetryAttempt = 0
        resetClouds()
    }

    private fun resetClouds() {
        synchronized(pointStateLock) {
            accA.release(); accB.release()
            activeRestoreBacklogA.release(); activeRestoreBacklogB.release()
            restoringActiveA = false; restoringActiveB = false
            aFrames = 0; bFrames = 0
            lastEmitAMs = 0L; lastEmitBMs = 0L
        }
        _unitACloud.value = LaserCloudRenderData.Empty
        _unitBCloud.value = LaserCloudRenderData.Empty
        _fusedCloud.value = LaserCloudRenderData.Empty
    }

    /** 与网页一致：先恢复活动扫描；确认没有活动扫描后，再恢复最近一次完成结果。 */
    private suspend fun restoreOnEntry() {
        restoreFailed = false
        val active = try {
            repo.active()
        } catch (e: Throwable) {
            logger.warn("查询活动扫描失败: ${e.message}")
            restoreFailed = true
            enterError("恢复工位扫描失败，请检查网络后重试")
            return
        }
        if (active != null) {
            restoreActiveScan(active)
            return
        }

        val latest = try {
            repo.latest()
        } catch (e: Throwable) {
            logger.warn("查询最近扫描失败: ${e.message}")
            null
        }
        val result = latest?.result
        val recoveredSession = latest?.sessionKey
        if (latest == null || result == null || recoveredSession.isNullOrBlank()) {
            _state.value = LaserScanState.Idle
            return
        }
        scanId = latest.scanId
        sessionKey = recoveredSession
        handleCompletedResult(result)
        if (_state.value is LaserScanState.Connecting) _state.value = LaserScanState.Idle
    }

    private fun restoreActiveScan(info: LaserScanInfo) {
        val recoveredSession = info.sessionKey
        if (recoveredSession.isNullOrBlank()) {
            _state.value = LaserScanState.Idle
            return
        }
        val terminal = info.status in setOf("done", "completed", "failed", "error", "cancelled", "canceled")
        resetClouds()
        if (!terminal) {
            synchronized(pointStateLock) {
                restoringActiveA = true
                restoringActiveB = true
            }
        }
        scanId = info.scanId
        sessionKey = recoveredSession
        when (info.status) {
            "capturing", "scanning", "starting", "connecting" -> _state.value = LaserScanState.Scanning
            "fusing", "processing" -> enterProcessing()
            "done", "completed" -> {
                val result = info.result
                if (result == null) {
                    enterError("恢复扫描失败：服务端结果字段不完整")
                } else {
                    viewModelScope.launch { handleCompletedResult(result) }
                }
                return
            }
            "failed", "error" -> {
                enterError(scanFailureMessage(info.error))
                return
            }
            "cancelled", "canceled" -> {
                resetToIdle()
                return
            }
            else -> _state.value = LaserScanState.Connecting
        }
        startStatusPolling(info.scanId, recoveredSession)
        restoreActiveCloud("unit_a", info.scanId, recoveredSession)
        restoreActiveCloud("unit_b", info.scanId, recoveredSession)
    }

    private fun restoreActiveCloud(name: String, id: Long, targetSession: String) {
        viewModelScope.launch {
            val restored = try {
                withContext(doneDispatcher) { repo.downloadActiveCloudRenderData(name) }
            } catch (e: Throwable) {
                logger.warn("恢复活动 $name 点云失败: ${e.message}")
                null
            }
            finishActiveRestoreIfActive(name, restored, id, targetSession)
        }
    }

    /** 快照下载期间暂存 WS 增量，完成门与发布必须保持原子性。 */
    private fun finishActiveRestoreIfActive(
        name: String,
        restored: LaserCloudRenderData?,
        id: Long,
        targetSession: String,
    ) {
        // 固定锁序：completionLock -> pointStateLock。
        synchronized(completionLock) {
            if (scanId != id || shouldIgnorePointFrameLocked(targetSession)) {
                synchronized(pointStateLock) { discardActiveRestoreLocked(name) }
                return
            }
            val merged = synchronized(pointStateLock) {
                finishActiveRestoreLocked(name, restored)
            }
            if (name == "unit_a") _unitACloud.value = merged else _unitBCloud.value = merged
        }
    }

    private fun finishActiveRestoreLocked(
        name: String,
        restored: LaserCloudRenderData?,
    ): LaserCloudRenderData {
        val acc = if (name == "unit_a") accA else accB
        val backlog = if (name == "unit_a") activeRestoreBacklogA else activeRestoreBacklogB
        if (restored != null) acc.replace(restored)
        acc.mergeSnapshot(backlog.snapshotRender())
        backlog.release()
        if (name == "unit_a") restoringActiveA = false else restoringActiveB = false
        return acc.snapshotRender()
    }

    private fun discardActiveRestoreLocked(name: String) {
        if (name == "unit_a") {
            restoringActiveA = false
            activeRestoreBacklogA.release()
        } else {
            restoringActiveB = false
            activeRestoreBacklogB.release()
        }
    }

    private fun ingestPointFrameIfActive(frame: LaserPointFrame) {
        var emitUnit: Int? = null
        var cloud = LaserCloudRenderData.Empty
        var voxelSizeMm = 0
        // 完成流程先拿 completionLock 封门，再释放 sampler；点帧使用同一锁序。
        synchronized(completionLock) {
            if (shouldIgnorePointFrameLocked(frame.sessionKey)) return
            val now = elapsedRealtimeMs()
            var buffered = false
            synchronized(pointStateLock) {
                when {
                    frame.unit == 0 && restoringActiveA -> {
                        activeRestoreBacklogA.add(frame.points, frame.hAngleDeg, frame.sourcePointCount)
                        aFrames++
                        buffered = true
                    }
                    frame.unit == 1 && restoringActiveB -> {
                        activeRestoreBacklogB.add(frame.points, frame.hAngleDeg, frame.sourcePointCount)
                        bFrames++
                        buffered = true
                    }
                    frame.unit == 0 -> {
                        accA.add(frame.points, frame.hAngleDeg, frame.sourcePointCount)
                        aFrames++
                        if (lastEmitAMs == 0L || now - lastEmitAMs >= LIVE_RENDER_INTERVAL_MS) {
                            cloud = accA.snapshotRender()
                            voxelSizeMm = accA.voxelSizeMm
                            lastEmitAMs = now
                            emitUnit = 0
                        }
                    }
                    frame.unit == 1 -> {
                        accB.add(frame.points, frame.hAngleDeg, frame.sourcePointCount)
                        bFrames++
                        if (lastEmitBMs == 0L || now - lastEmitBMs >= LIVE_RENDER_INTERVAL_MS) {
                            cloud = accB.snapshotRender()
                            voxelSizeMm = accB.voxelSizeMm
                            lastEmitBMs = now
                            emitUnit = 1
                        }
                    }
                }
            }
            if (!buffered) {
                when (emitUnit) {
                    0 -> _unitACloud.value = cloud
                    1 -> _unitBCloud.value = cloud
                }
            }
        }
        when (emitUnit) {
            0 -> logPreview("A", cloud, voxelSizeMm)
            1 -> logPreview("B", cloud, voxelSizeMm)
        }
    }

    private fun logPreview(unit: String, cloud: LaserCloudRenderData, voxelSizeMm: Int) {
        logger.info(
            "preview unit=$unit source=${cloud.sourcePointCount} " +
                "render=${cloud.renderPointCount} voxel_mm=$voxelSizeMm",
        )
    }

    private fun emitFinalUnitSnapshots() {
        val targetSession = sessionKey
        pointIngestScope.launch {
            synchronized(completionLock) {
                if (
                    targetSession != sessionKey ||
                    completingSessionKey == targetSession ||
                    _state.value is LaserScanState.Completed ||
                    _state.value is LaserScanState.Error
                ) {
                    return@synchronized
                }
                val clouds = synchronized(pointStateLock) {
                    accA.snapshotRender() to accB.snapshotRender()
                }
                _unitACloud.value = clouds.first
                _unitBCloud.value = clouds.second
            }
        }
    }

    /** 可靠状态消息在进入融合前校准一次累计值，避免最后一条 lossy 点帧丢失后点数永久偏小。 */
    private fun updateReliableSourceCounts(status: LaserStatusUpdate) {
        synchronized(pointStateLock) {
            status.sourcePointsA?.let { count ->
                accA.updateSourcePointCount(count)
                activeRestoreBacklogA.updateSourcePointCount(count)
            }
            status.sourcePointsB?.let { count ->
                accB.updateSourcePointCount(count)
                activeRestoreBacklogB.updateSourcePointCount(count)
            }
        }
    }

    private fun startStatusPolling(id: Long, targetSession: String) {
        statusPollJob?.cancel()
        statusPollJob = viewModelScope.launch {
            while (scanId == id && sessionKey == targetSession) {
                delay(statusPollIntervalMs)
                val info = try {
                    repo.status(id)
                } catch (e: Throwable) {
                    logger.warn("状态轮询失败，等待下次重试: ${e.message}")
                    continue
                }
                if (scanId != id || sessionKey != targetSession) return@launch
                if (info.sessionKey != null && info.sessionKey != targetSession) {
                    logger.warn("忽略其他会话的扫描状态: ${info.sessionKey}")
                    continue
                }
                when (info.status) {
                    "capturing", "scanning" -> if (_state.value is LaserScanState.Connecting) {
                        _state.value = LaserScanState.Scanning
                    }
                    "fusing", "processing" -> {
                        emitFinalUnitSnapshots()
                        enterProcessing()
                    }
                    "done", "completed" -> {
                        if (completePendingTerminalAction()) return@launch
                        val result = info.result
                        if (result == null) {
                            enterError("扫描完成但服务端结果字段不完整")
                        } else {
                            handleCompletedResult(result)
                        }
                        return@launch
                    }
                    "failed", "error" -> {
                        if (!completePendingTerminalAction()) enterError(scanFailureMessage(info.error))
                        return@launch
                    }
                    "cancelled", "canceled" -> {
                        completeStopSuccess()
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun handleDoneEvent(d: LaserDoneResult) {
        if (withContext(Dispatchers.Main.immediate) { completePendingTerminalAction() }) return
        val result = d.result
            .enforceMeasuredArtifactContract()
            .enforceProductionEligibilityContract()
        if (!result.hasRequiredCloudKeys()) {
            logger.warn("WS 完成事件缺少最终点云键，保留 REST 轮询等待完整结果")
            return
        }
        handleCompletedResult(result)
    }

    private suspend fun handleCompletedResult(rawResult: LaserScanResult) {
        val result = rawResult
            .enforceMeasuredArtifactContract()
            .enforceProductionEligibilityContract()
        val id = scanId ?: return
        synchronized(completionLock) {
            if (result.sessionKey != sessionKey || completingSessionKey == result.sessionKey) return
            completingSessionKey = result.sessionKey
        }
        cancelActiveRestoreForCompletion()
        lastCompletedResult = result
        // 最终只下载服务端从权威 PCD 派生的有界渲染样本；源点数仍用于完整性校验。
        val warnings = mutableListOf<String>()
        var loadedFused = -1
        var loadedMeasured = -1
        var loadedA = -1
        var loadedB = -1
        var measuredVerified = result.measuredObjectKey == null
        // 主窗口与网页保持一致：永远展示区域裁剪后的彩色 fused；measured 只做车辆结论身份校验，
        // 禁止把无色车辆子云冒充融合场景并配上 fused 点数。
        if (result.fusedObjectKey != null) {
            if (isFinalCloudLoaded("fused")) {
                loadedFused = result.points
            } else {
                try {
                    val cloud = withContext(doneDispatcher) { repo.downloadCloudRenderData(id, "fused") }
                    if (sessionKey != result.sessionKey || scanId != id) return
                    _fusedCloud.value = cloud
                    loadedFused = cloud.pointCount
                    logFinalCloud("fused", cloud)
                    if (!cloud.hasColor) warnings += "融合 PCD 缺少颜色"
                    if (loadedFused == result.points && cloud.hasColor) markFinalCloudLoaded("fused")
                } catch (e: Throwable) {
                    logger.warn("下载 fused PCD 失败: ${e.message}")
                    warnings += "融合 PCD 下载失败"
                }
            }
        }
        if (result.measuredObjectKey != null) {
            if (isFinalCloudLoaded("measured")) {
                loadedMeasured = result.measurement.measuredPoints
                measuredVerified = true
            } else {
                try {
                    val cloud = withContext(doneDispatcher) {
                        repo.downloadCloudRenderData(
                            id,
                            "measured",
                            expectedArtifact = result.measuredArtifact,
                        )
                    }
                    if (sessionKey != result.sessionKey || scanId != id) return
                    loadedMeasured = cloud.pointCount
                    logFinalCloud("measured_verify", cloud)
                    if (loadedMeasured == result.measurement.measuredPoints) {
                        markFinalCloudLoaded("measured")
                        measuredVerified = true
                    }
                } catch (e: Throwable) {
                    logger.warn("校验 measured PCD 失败: ${e.message}")
                    warnings += "车辆 PCD 校验失败"
                }
            }
        }
        if (result.unitAObjectKey != null) {
            if (isFinalCloudLoaded("unit_a")) {
                loadedA = result.ptsA
            } else {
                try {
                    val cloud = withContext(doneDispatcher) { repo.downloadCloudRenderData(id, "unit_a") }
                    if (sessionKey != result.sessionKey || scanId != id) return
                    _unitACloud.value = cloud
                    loadedA = cloud.pointCount
                    logFinalCloud("unit_a", cloud)
                    if (!cloud.hasColor) warnings += "A PCD 缺少颜色"
                    if (loadedA == result.ptsA && cloud.hasColor) markFinalCloudLoaded("unit_a")
                } catch (e: Throwable) {
                    logger.warn("下载 unitA PCD 失败: ${e.message}")
                    warnings += "A PCD 下载失败"
                }
            }
        }
        if (result.unitBObjectKey != null) {
            if (isFinalCloudLoaded("unit_b")) {
                loadedB = result.ptsB
            } else {
                try {
                    val cloud = withContext(doneDispatcher) { repo.downloadCloudRenderData(id, "unit_b") }
                    if (sessionKey != result.sessionKey || scanId != id) return
                    _unitBCloud.value = cloud
                    loadedB = cloud.pointCount
                    logFinalCloud("unit_b", cloud)
                    if (!cloud.hasColor) warnings += "B PCD 缺少颜色"
                    if (loadedB == result.ptsB && cloud.hasColor) markFinalCloudLoaded("unit_b")
                } catch (e: Throwable) {
                    logger.warn("下载 unitB PCD 失败: ${e.message}")
                    warnings += "B PCD 下载失败"
                }
            }
        }
        fun check(label: String, got: Int, expected: Int) {
            if (got >= 0 && got != expected) warnings += "$label 点数不一致 $got/$expected"
        }
        if (result.fusedObjectKey != null) check("融合", loadedFused, result.points)
        if (result.measuredObjectKey != null) check("车辆", loadedMeasured, result.measurement.measuredPoints)
        check("A", loadedA, result.ptsA)
        check("B", loadedB, result.ptsB)
        if (result.fusedObjectKey != null && result.points != result.ptsA + result.ptsB) {
            warnings += "服务端点数不守恒 ${result.ptsA}+${result.ptsB}≠${result.points}"
        }
        if (loadedFused >= 0 && loadedA >= 0 && loadedB >= 0 && loadedFused != loadedA + loadedB) {
            warnings += "PCD 点数不守恒 $loadedA+$loadedB≠$loadedFused"
        }
        val integrity = warnings.takeIf { it.isNotEmpty() }?.joinToString("；")
        if (integrity != null) logger.warn("点云完整性告警: $integrity")
        if (sessionKey != result.sessionKey || scanId != id) return
        val displayedMeasurement = if (measuredVerified) {
            result.measurement
        } else {
            result.measurement.withoutVerifiedConclusion("measured_cloud_unverified")
        }
        _state.value = LaserScanState.Completed(
            points = result.points,
            ptsA = result.ptsA,
            ptsB = result.ptsB,
            alignMethod = result.alignMethod,
            siteRevision = result.siteRevision,
            regionRevision = result.regionRevision,
            siteQualityVerified = result.siteQualityVerified,
            siteQualityOverride = result.siteQualityOverride,
            productionEligible = result.productionEligible,
            measurement = displayedMeasurement,
            ground = result.ground,
            measuredCloudVerified = result.measuredObjectKey != null && measuredVerified,
            pointIntegrityWarning = integrity,
        )
        statusPollJob?.cancel()
        statusPollJob = null
        if (integrity == null) {
            cloudRetryJob?.cancel()
            cloudRetryJob = null
            cloudRetryAttempt = 0
        } else {
            synchronized(completionLock) { completingSessionKey = null }
            scheduleCloudRetry(result)
        }
    }

    private fun isFinalCloudLoaded(name: String): Boolean = synchronized(completionLock) {
        name in loadedFinalCloudNames
    }

    /** 调用方必须已持有 completionLock。 */
    private fun shouldIgnorePointFrameLocked(targetSession: String): Boolean {
        if (targetSession != sessionKey || completingSessionKey == targetSession) return true
        return when (_state.value) {
            LaserScanState.Processing,
            is LaserScanState.Completed -> true
            is LaserScanState.Error -> !(_state.value as LaserScanState.Error).activeScan
            else -> false
        }
    }

    private fun enterProcessing() {
        synchronized(completionLock) {
            if ((_state.value as? LaserScanState.Error)?.activeScan != true) {
                _state.value = LaserScanState.Processing
            }
        }
    }

    private fun enterError(message: String) {
        synchronized(completionLock) { _state.value = LaserScanState.Error(message) }
        releaseLiveAccumulators()
    }

    private fun cancelActiveRestoreForCompletion() {
        synchronized(pointStateLock) {
            restoringActiveA = false
            restoringActiveB = false
            releaseLiveAccumulatorsLocked()
        }
    }

    private fun releaseLiveAccumulators() = synchronized(pointStateLock) {
        releaseLiveAccumulatorsLocked()
    }

    private fun releaseLiveAccumulatorsLocked() {
        accA.release()
        accB.release()
        activeRestoreBacklogA.release()
        activeRestoreBacklogB.release()
    }

    private fun logFinalCloud(name: String, cloud: LaserCloudRenderData) {
        logger.info(
            "final cloud=$name source=${cloud.sourcePointCount} render=${cloud.renderPointCount} " +
                "color=${cloud.hasColor}",
        )
    }

    private fun markFinalCloudLoaded(name: String) {
        synchronized(completionLock) { loadedFinalCloudNames += name }
    }

    private fun scheduleCloudRetry(result: LaserScanResult) {
        if (cloudRetryAttempt >= MAX_CLOUD_RETRY_ATTEMPTS || cloudRetryJob?.isActive == true) return
        val delayMs = CLOUD_RETRY_BASE_MS shl cloudRetryAttempt
        cloudRetryAttempt++
        cloudRetryJob = viewModelScope.launch {
            delay(delayMs)
            cloudRetryJob = null
            if (sessionKey != result.sessionKey || scanId == null) return@launch
            handleCompletedResult(result)
        }
    }

    private fun scanFailureMessage(error: String?): String {
        val code = error.orEmpty().lowercase()
        return when {
            "background_incompatible" in code -> "空工位背景与当前设备配置不兼容，请重新采集背景"
            "no_isolation" in code -> "未配置车辆隔离范围，请先完成区域标定或采集空工位背景"
            "raw" in code || "未标定" in error.orEmpty() -> "工位未完成外参标定，无法融合测量"
            error.isNullOrBlank() -> "扫描出错"
            else -> "扫描出错: $error"
        }
    }

    private fun startFailureMessage(e: Throwable): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            msg.contains("已有进行中的激光扫描") || msg.contains("已有扫描在进行") ->
                "已有扫描在进行，请先停止当前扫描"
            msg.contains("工位外参质量未达生产要求") ->
                "工位外参质量未达生产要求，请在网页端重新执行 ArUco 标定"
            msg.contains("尚未保存外参") ->
                "当前工位尚未保存外参，请先在网页端完成 A/B 标定"
            msg.contains("扫描区域") || msg.contains("区域标定") ->
                "当前工位尚未保存并启用扫描区域，请先在网页端完成区域标定"
            msg.contains("region_calibration_changed", ignoreCase = true) ->
                "扫描区域版本已变化，请保持工位为空并重新采集背景"
            msg.contains("legacy_fused_requires_recapture", ignoreCase = true) ->
                "旧版空工位背景仍在，但格式不兼容，请保持工位为空并重新采集"
            msg.contains("legacy_fused_unverified", ignoreCase = true) ->
                "旧背景仍在，但尚未完成兼容验证，请联系管理员"
            msg.contains("legacy_fused_", ignoreCase = true) ->
                "旧背景读取或完整性校验失败，请联系管理员恢复"
            msg.contains("background_incompatible", ignoreCase = true) || msg.contains("空工位背景") ->
                "空工位背景不可用于当前配置，请保持工位为空并重新采集"
            else -> "起扫失败: $msg"
        }
    }

    private companion object {
        const val LIVE_RENDER_INTERVAL_MS = 220L
        const val POINT_FRAME_BUFFER = 4
        const val STATUS_POLL_INTERVAL_MS = 800L
        const val CLOUD_RETRY_BASE_MS = 1_000L
        const val MAX_CLOUD_RETRY_ATTEMPTS = 3
    }

    override fun onCleared() {
        startJob?.cancel()
        restoreJob?.cancel()
        statusPollJob?.cancel()
        cloudRetryJob?.cancel()
        pointIngestScope.cancel()
        synchronized(pointStateLock) {
            accA.release(); accB.release()
            activeRestoreBacklogA.release(); activeRestoreBacklogB.release()
        }
        ownedPointIngestDispatcher?.close()
        super.onCleared()
    }
}

private fun LaserScanResult.hasRequiredCloudKeys(): Boolean {
    val unitsReady = unitAObjectKey != null && unitBObjectKey != null
    val fusedReady = alignMethod.equals("raw", ignoreCase = true) ||
        alignMethod.equals("unfused", ignoreCase = true) || fusedObjectKey != null
    val measuredReady = !measurement.valid || measuredObjectKey != null
    return unitsReady && fusedReady && measuredReady
}

/**
 * 固定容量的世界系嵌套体素采样器。服务端保留全量权威云，这里只派生端侧实时预览。
 *
 * 初始 25mm 网格容量满时整级合并到 50/100mm；每体素保留最靠近中心的点。存储按需分配，
 * 终态调用 [release] 后可由 GC 回收，内存上界与扫描时长无关。
 */
internal class BoundedVoxelCloudSampler(
    private val capacity: Int,
    private val initialVoxelSizeMm: Int = 25,
    private val maxCoordinateMm: Float = 50_000f,
) {
    private var xyz = FloatArray(0)
    private var index: VoxelPointIndex? = null
    private var retainedPoints = 0
    private var receivedPoints = 0L
    private var authoritativeSourcePoints: Long? = null
    private var currentVoxelSizeMm = initialVoxelSizeMm
    private var latestAngle: Float? = null

    init {
        require(capacity in 1..1_000_000) { "capacity 须为 1..1000000" }
        require(initialVoxelSizeMm > 0) { "initialVoxelSizeMm 必须大于 0" }
        require(maxCoordinateMm > 0f) { "maxCoordinateMm 必须大于 0" }
    }

    val sourcePointCount: Int
        @Synchronized get() = effectiveSourcePointCount()

    val renderPointCount: Int
        @Synchronized get() = retainedPoints

    val voxelSizeMm: Int
        @Synchronized get() = currentVoxelSizeMm

    val allocatedPointCapacity: Int
        @Synchronized get() = xyz.size / 3

    @Synchronized
    fun add(src: FloatArray, hAngleDeg: Float? = null, sourcePointCount: Int? = null) {
        require(src.size % 3 == 0) { "点数据长度 ${src.size} 不是 3 的倍数" }
        if (hAngleDeg != null && hAngleDeg.isFinite()) latestAngle = hAngleDeg
        var offset = 0
        while (offset < src.size) {
            receivedPoints++
            val x = src[offset]
            val y = src[offset + 1]
            val z = src[offset + 2]
            if (isRenderable(x) && isRenderable(y) && isRenderable(z)) insert(x, y, z)
            offset += 3
        }
        sourcePointCount?.takeIf { it >= 0 }?.let { total ->
            authoritativeSourcePoints = maxOf(authoritativeSourcePoints ?: 0L, total.toLong())
        }
    }

    @Synchronized
    fun updateSourcePointCount(sourcePointCount: Int) {
        if (sourcePointCount >= 0) {
            authoritativeSourcePoints = maxOf(authoritativeSourcePoints ?: 0L, sourcePointCount.toLong())
        }
    }

    @Synchronized
    fun replace(cloud: LaserCloudRenderData) {
        release()
        receivedPoints = cloud.sourcePointCount.toLong()
        authoritativeSourcePoints = cloud.sourcePointCount.toLong()
        latestAngle = cloud.latestAngleDeg
        insertCloud(cloud.xyz)
    }

    @Synchronized
    fun mergeSnapshot(cloud: LaserCloudRenderData) {
        authoritativeSourcePoints = maxOf(authoritativeSourcePoints ?: 0L, cloud.sourcePointCount.toLong())
        cloud.latestAngleDeg?.takeIf { it.isFinite() }?.let { latestAngle = it }
        insertCloud(cloud.xyz)
    }

    @Synchronized
    fun snapshotRender(): LaserCloudRenderData = LaserCloudRenderData(
        xyz = if (retainedPoints == 0) FloatArray(0) else xyz.copyOf(retainedPoints * 3),
        sourcePointCount = effectiveSourcePointCount(),
        latestAngleDeg = latestAngle,
    )

    private fun effectiveSourcePointCount(): Int = maxOf(
        authoritativeSourcePoints ?: receivedPoints,
        retainedPoints.toLong(),
    ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** 清空逻辑内容但保留本次扫描的固定存储。 */
    @Synchronized
    fun clear() {
        retainedPoints = 0
        receivedPoints = 0L
        authoritativeSourcePoints = null
        currentVoxelSizeMm = initialVoxelSizeMm
        latestAngle = null
        index?.clear()
    }

    /** 终态释放固定数组与哈希索引；下一次 add 时再按需分配。 */
    @Synchronized
    fun release() {
        clear()
        xyz = FloatArray(0)
        index = null
    }

    private fun insertCloud(cloud: FloatArray) {
        require(cloud.size % 3 == 0) { "点云坐标必须是 xyz 三元组" }
        var offset = 0
        while (offset < cloud.size) {
            val x = cloud[offset]
            val y = cloud[offset + 1]
            val z = cloud[offset + 2]
            if (isRenderable(x) && isRenderable(y) && isRenderable(z)) insert(x, y, z)
            offset += 3
        }
    }

    private fun isRenderable(value: Float): Boolean = value.isFinite() && abs(value) <= maxCoordinateMm

    private fun ensureStorage(): VoxelPointIndex {
        if (xyz.isEmpty()) xyz = FloatArray(capacity * 3)
        return index ?: VoxelPointIndex(capacity).also { index = it }
    }

    private fun insert(x: Float, y: Float, z: Float) {
        while (true) {
            val pointIndex = ensureStorage()
            val key = voxelKey(x, y, z, currentVoxelSizeMm) ?: return
            val existing = pointIndex.find(key)
            if (existing >= 0) {
                replaceIfBetter(existing, x, y, z, currentVoxelSizeMm)
                return
            }
            if (retainedPoints < capacity) {
                writePoint(retainedPoints, x, y, z)
                pointIndex.put(key, retainedPoints)
                retainedPoints++
                return
            }
            if (currentVoxelSizeMm > Int.MAX_VALUE / 2) return
            currentVoxelSizeMm *= 2
            rebuildAtCurrentVoxelSize()
        }
    }

    private fun rebuildAtCurrentVoxelSize() {
        val pointIndex = ensureStorage()
        pointIndex.clear()
        val oldCount = retainedPoints
        var write = 0
        for (read in 0 until oldCount) {
            val base = read * 3
            val x = xyz[base]
            val y = xyz[base + 1]
            val z = xyz[base + 2]
            val key = voxelKey(x, y, z, currentVoxelSizeMm) ?: continue
            val existing = pointIndex.find(key)
            if (existing >= 0) {
                replaceIfBetter(existing, x, y, z, currentVoxelSizeMm)
            } else {
                if (write != read) writePoint(write, x, y, z)
                pointIndex.put(key, write)
                write++
            }
        }
        retainedPoints = write
    }

    private fun replaceIfBetter(slot: Int, x: Float, y: Float, z: Float, voxelSizeMm: Int) {
        val base = slot * 3
        val oldX = xyz[base]
        val oldY = xyz[base + 1]
        val oldZ = xyz[base + 2]
        val newDistance = squaredDistanceToVoxelCenter(x, y, z, voxelSizeMm)
        val oldDistance = squaredDistanceToVoxelCenter(oldX, oldY, oldZ, voxelSizeMm)
        if (
            newDistance < oldDistance ||
            (newDistance == oldDistance && java.lang.Long.compareUnsigned(
                stablePointHash(x, y, z),
                stablePointHash(oldX, oldY, oldZ),
            ) < 0)
        ) {
            writePoint(slot, x, y, z)
        }
    }

    private fun writePoint(slot: Int, x: Float, y: Float, z: Float) {
        val base = slot * 3
        xyz[base] = x
        xyz[base + 1] = y
        xyz[base + 2] = z
    }

    private fun squaredDistanceToVoxelCenter(x: Float, y: Float, z: Float, voxelSizeMm: Int): Double {
        val size = voxelSizeMm.toDouble()
        fun delta(value: Float): Double {
            val center = (floor(value / size) + 0.5) * size
            return value - center
        }
        val dx = delta(x)
        val dy = delta(y)
        val dz = delta(z)
        return dx * dx + dy * dy + dz * dz
    }

    private fun stablePointHash(x: Float, y: Float, z: Float): Long {
        var hash = java.lang.Float.floatToRawIntBits(x).toLong() and 0xffff_ffffL
        hash = mix64(hash xor (java.lang.Float.floatToRawIntBits(y).toLong() shl 1))
        return mix64(hash xor (java.lang.Float.floatToRawIntBits(z).toLong() shl 2))
    }

    private fun voxelKey(x: Float, y: Float, z: Float, voxelSizeMm: Int): Long? {
        val size = voxelSizeMm.toDouble()
        val ix = floor(x / size).toInt()
        val iy = floor(y / size).toInt()
        val iz = floor(z / size).toInt()
        if (ix !in VOXEL_AXIS_MIN..VOXEL_AXIS_MAX ||
            iy !in VOXEL_AXIS_MIN..VOXEL_AXIS_MAX ||
            iz !in VOXEL_AXIS_MIN..VOXEL_AXIS_MAX
        ) return null
        return ((ix.toLong() and VOXEL_AXIS_MASK) shl 42) or
            ((iy.toLong() and VOXEL_AXIS_MASK) shl 21) or
            (iz.toLong() and VOXEL_AXIS_MASK)
    }

    private class VoxelPointIndex(capacity: Int) {
        private val tableSize = nextPowerOfTwo(capacity * 2)
        private val mask = tableSize - 1
        private val keys = LongArray(tableSize)
        private val values = IntArray(tableSize) { EMPTY }

        fun clear() = java.util.Arrays.fill(values, EMPTY)

        fun find(key: Long): Int {
            var slot = mix64(key).toInt() and mask
            while (true) {
                val value = values[slot]
                if (value == EMPTY) return EMPTY
                if (keys[slot] == key) return value
                slot = (slot + 1) and mask
            }
        }

        fun put(key: Long, value: Int) {
            var slot = mix64(key).toInt() and mask
            while (values[slot] != EMPTY) {
                if (keys[slot] == key) {
                    values[slot] = value
                    return
                }
                slot = (slot + 1) and mask
            }
            keys[slot] = key
            values[slot] = value
        }

        companion object {
            private const val EMPTY = -1

            private fun nextPowerOfTwo(value: Int): Int {
                var result = 1
                while (result < value) result = result shl 1
                return result
            }
        }
    }

    private companion object {
        const val VOXEL_AXIS_BITS = 21
        const val VOXEL_AXIS_MASK = (1L shl VOXEL_AXIS_BITS) - 1L
        const val VOXEL_AXIS_MIN = -(1 shl (VOXEL_AXIS_BITS - 1))
        const val VOXEL_AXIS_MAX = (1 shl (VOXEL_AXIS_BITS - 1)) - 1

        fun mix64(value: Long): Long {
            var mixed = value + -7046029254386353131L
            mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
            mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
            return mixed xor (mixed ushr 31)
        }
    }
}
