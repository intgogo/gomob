package io.gomob.logging

import android.util.Log
import io.gomob.network.LogEntryDto
import io.gomob.network.LogUploadRequest
import io.gomob.network.LogsApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 日志同步全局管理器（单例）：
 *   - 监听 [LogSyncPreferences.enabledFlow]：开 → 启动 [LogcatTailer]，关 → cancel job 杀子进程
 *   - tailer 每行进 channel，buffer 5000 条（用 DROP_OLDEST 模式，断网不阻塞采集）
 *   - drainer 协程每 5 秒或 100 条 flush 一批 → POST /v1/logs/upload
 *   - 上传失败：log warn，丢弃这一批（不本地持久化，避免存储 IO + 隐私残留）
 *
 * 由 GomobApplication.onCreate 调用 [start] 启动总监听器；进程级生命周期。
 */
@Singleton
class LogSyncManager @Inject constructor(
    private val prefs: LogSyncPreferences,
    private val logsApi: LogsApi,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 当前正在跑的 tailer job；开关切换时换新的 */
    @Volatile private var tailerJob: Job? = null

    /** 单线 buffer：tailer 投，drainer 取。容量 5000 — 断网时丢老的留新的。 */
    private val buffer = Channel<LogEntryDto>(capacity = 5000)

    /** 必须由 [io.gomob.scan.GomobApplication.onCreate] 调一次 — 启动开关监听 + drainer */
    fun start() {
        // 监听开关：true → 启 tailer，false → cancel + drain 残留 + 等下次 true
        scope.launch {
            prefs.enabledFlow.collectLatest { enabled ->
                tailerJob?.cancel()
                tailerJob = null
                if (enabled) {
                    Log.i(TAG, "日志同步开启 — 启动 LogcatTailer")
                    tailerJob = launchTailer()
                } else {
                    Log.i(TAG, "日志同步关闭")
                }
            }
        }
        // drainer：每 5 秒 / 100 条上传一批
        scope.launch { drain() }
    }

    private fun launchTailer(): Job = scope.launch {
        val tailer = LogcatTailer()
        try {
            tailer.lines().collectLatest { entry ->
                // trySend 不阻塞；buffer 满时（容量 5000）走 onUndeliveredElement → 这里干脆丢老的
                if (!buffer.trySend(entry).isSuccess) {
                    // buffer 满 → 取出最老的腾位置
                    buffer.tryReceive().getOrNull()
                    buffer.trySend(entry)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "LogcatTailer 异常退出: ${e.message}")
        }
    }

    private suspend fun drain() {
        val pending = ArrayList<LogEntryDto>(BATCH_MAX)
        while (scope.isActive) {
            // 攒一批：要么够 BATCH_MAX 条，要么超过 FLUSH_INTERVAL_MS
            val deadline = System.currentTimeMillis() + FLUSH_INTERVAL_MS
            while (pending.size < BATCH_MAX && System.currentTimeMillis() < deadline) {
                val remain = (deadline - System.currentTimeMillis()).coerceAtLeast(50L)
                val recv = buffer.tryReceive().getOrNull()
                if (recv != null) {
                    pending.add(recv)
                } else {
                    delay(remain.coerceAtMost(200L))
                }
            }
            if (pending.isEmpty()) continue
            val batch = pending.toList()
            pending.clear()
            uploadBatch(batch)
        }
    }

    private suspend fun uploadBatch(batch: List<LogEntryDto>) {
        try {
            val resp = logsApi.upload(LogUploadRequest(batch))
            if (resp.code != 0) {
                Log.w(TAG, "上传被拒 code=${resp.code} msg=${resp.message}")
            }
        } catch (e: Throwable) {
            // 网络 / 认证失败：丢这一批 — 不重试不持久化，避免雪崩 + 隐私残留
            Log.w(TAG, "上传失败 (丢弃 ${batch.size} 条): ${e.message}")
        }
    }

    companion object {
        private const val TAG = "LogSyncManager"
        private const val BATCH_MAX = 100
        private const val FLUSH_INTERVAL_MS = 5_000L
    }
}
