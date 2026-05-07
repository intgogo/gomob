package io.gomob.feature.scan3d

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gomob.nativebridge.NativeBridge
import io.gomob.nativebridge.berxel.BerxelService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 三维外廓扫描录制状态机。
 *
 * - [Idle]：未开始 / 上一次完成已取消
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
 * 三维外廓扫描 VM。
 *
 * 生命周期：
 *   - 进 RecordingScreen → 用户点"开始" → start()
 *   - 帧流：berxel.depthFrames.collect → NativeBridge.scanSessionIngest 每帧
 *   - 用户点"停止" / 60s 自动判停 → stop()  →  Finalizing → Marching Tetrahedra → Completed
 *   - 离开 Screen → onCleared() 清场（含未 finalize 的 session 强制 close）
 *
 * 不在 init 启动 SDK — DepthCameraViewModel 已 start() 过；本 VM 只 collect 帧不管 SDK 启停。
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

    @Volatile private var sessionHandle: Long = 0L
    private var ingestJob: Job? = null
    private var startedAtMs: Long = 0L
    private var framesIngested: Int = 0
    private var keyframesCount: Int = 0

    fun start() {
        // 已在 Recording / Finalizing 时不重复启动
        if (_state.value is ScanRecordingState.Recording ||
            _state.value is ScanRecordingState.Finalizing) {
            return
        }
        startedAtMs = System.currentTimeMillis()
        framesIngested = 0
        keyframesCount = 0

        try {
            sessionHandle = NativeBridge.scanSessionCreate(
                /*voxelSizeMm=*/2.0f,
                /*gridExtentMm=*/400.0f,
            )
        } catch (e: Throwable) {
            _state.value = ScanRecordingState.Error("scanSessionCreate 失败: ${e.message}")
            return
        }
        _state.value = ScanRecordingState.Recording(0, 0, 0L)
        Log.i(TAG, "扫描会话已建立 handle=$sessionHandle")

        ingestJob = viewModelScope.launch {
            berxel.depthFrames.collect { frame ->
                val intr = frame.intrinsics
                val pose = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f) // identity
                try {
                    val kf = withContext(Dispatchers.Default) {
                        NativeBridge.scanSessionIngest(
                            sessionHandle,
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

    fun stop() {
        val cur = _state.value
        val ingested = when (cur) {
            is ScanRecordingState.Recording -> cur.framesIngested
            else -> return
        }
        ingestJob?.cancel()
        ingestJob = null
        _state.value = ScanRecordingState.Finalizing(ingested)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sessionId = "scan-${System.currentTimeMillis()}"
                val outDir = File(ctx.filesDir, "scans/$sessionId").apply { mkdirs() }
                val stats = NativeBridge.scanSessionFinalize(sessionHandle, outDir.absolutePath)
                NativeBridge.scanSessionClose(sessionHandle)
                sessionHandle = 0L
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
                if (sessionHandle != 0L) {
                    runCatching { NativeBridge.scanSessionClose(sessionHandle) }
                    sessionHandle = 0L
                }
            }
        }
    }

    fun reset() {
        ingestJob?.cancel()
        ingestJob = null
        if (sessionHandle != 0L) {
            runCatching { NativeBridge.scanSessionClose(sessionHandle) }
            sessionHandle = 0L
        }
        framesIngested = 0
        keyframesCount = 0
        _state.value = ScanRecordingState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        reset()
    }

    companion object {
        private const val TAG = "Scan3dRecordingVM"
    }
}
