package io.gomob.logging

import android.os.Build
import android.util.Log
import io.gomob.network.LogEntryDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Logcat 子进程 tailer — spawn `logcat` 拉自己进程的日志，按行解析成 [LogEntryDto] 流。
 *
 * 为什么走 logcat 子进程而不是 Timber Tree：我们要把 **native 端**（gomob_native tag，
 * 已通过 __android_log_print 输出到 logcat）的日志也收进来。Timber 只能拦截 Kotlin/Java
 * 端的 Log.* 调用。Logcat 子进程是普通 app 上下文允许的（不需要 root，能读自己进程的日志，
 * 由 Android 自动隔离 — 别的 app 看不到也读不出来）。
 *
 * 过滤策略：白名单 tag（gomob_native + gomob.* + 几个核心 ViewModel/Service）+ 全局 *:E 兜底
 * 错误，避免把无关 system 服务（WindowManager / SurfaceFlinger）的日志也吸进来。
 *
 * 解析：`logcat -v threadtime` 行格式
 *   `MM-DD HH:MM:SS.sss  PID  TID L Tag: msg`
 * 取 L (level) + Tag + msg。时间戳直接用 System.currentTimeMillis() (服务端只关心收到时序)。
 */
class LogcatTailer(
    private val tagFilters: List<String> = DEFAULT_TAG_FILTERS,
    private val deviceSerial: String? = null,
) {

    /**
     * 启动 logcat 子进程，每出一行 emit 一条 [LogEntryDto]。
     * 协程被 cancel 时杀掉子进程。
     */
    fun lines(): Flow<LogEntryDto> = flow {
        val cmd = mutableListOf("logcat", "-v", "threadtime")
        // 清掉已有 buffer 在启动时（避免开关来回切重复抓老日志）— 用 -T 1 拿"从现在起"的日志
        // (Android logcat -T 是 since timestamp / count；用 1 表示从最近 1 条开始，等价于 "tail -f")
        cmd.add("-T")
        cmd.add("1")
        // 加白名单 tag:Verbose
        for (t in tagFilters) cmd.add("$t:V")
        // 兜底 *:E（其它 tag 的 ERROR 也收）
        cmd.add("*:E")

        val proc = try {
            ProcessBuilder(cmd).redirectErrorStream(true).start()
        } catch (e: Throwable) {
            Log.e(TAG, "spawn logcat 失败: ${e.message}")
            return@flow
        }

        try {
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            while (coroutineContext.isActive) {
                val line = reader.readLine() ?: break
                val entry = parseLine(line) ?: continue
                emit(entry)
            }
        } finally {
            // cancel 时杀子进程 (destroyForcibly 才能立即 kill；destroy 是 SIGTERM 在某些设备上不立即生效)
            try { proc.destroyForcibly() } catch (_: Throwable) {}
        }
    }

    private fun parseLine(line: String): LogEntryDto? {
        // logcat -v threadtime 格式（两个空格分隔可能不稳定，用 split + 去空过滤）
        // 例: "05-07 02:30:15.123  1234  5678 I gomob_native: Ingest[1st] ..."
        val s = line.trim()
        if (s.isEmpty() || s.startsWith("---")) return null  // skip "--- beginning of main"
        val parts = s.split(Regex("\\s+"), limit = 6)
        if (parts.size < 6) return null
        // parts[0] date, [1] time, [2] pid, [3] tid, [4] level, [5] "tag: msg"
        val level = parts[4]
        if (level.length != 1 || level !in LEVELS) return null
        val tagAndMsg = parts[5]
        val colon = tagAndMsg.indexOf(": ")
        val tag: String
        val msg: String
        if (colon > 0) {
            tag = tagAndMsg.substring(0, colon).trim()
            msg = tagAndMsg.substring(colon + 2)
        } else {
            tag = tagAndMsg.trim()
            msg = ""
        }
        if (tag.isBlank() || msg.isBlank()) return null
        return LogEntryDto(
            ts_ms = System.currentTimeMillis(),
            level = level,
            tag = tag,
            msg = msg,
            device_serial = deviceSerial ?: defaultSerial,
        )
    }

    companion object {
        private const val TAG = "LogcatTailer"

        /** 默认抓的 tag 白名单 — 覆盖 native + 我们的 Kotlin VM/Service。 */
        val DEFAULT_TAG_FILTERS = listOf(
            "gomob_native",        // native (jni_bridge / scan_session / berxel patch)
            "BerxelService",       // SDK lifecycle + 内参 + USB 权限
            "Scan3dRecordingVM",   // 扫描录制状态机
            "DepthCameraVM",       // 深度相机详情页
            "Scan3dVM",            // 3D 主页
            "AuthRepository",
            "GomobApplication",
        )

        private val LEVELS = setOf("V", "D", "I", "W", "E", "F")

        // build SN 作 device_serial 默认；不是用户隐私（只是物理设备型号 + 序列）
        private val defaultSerial: String? by lazy {
            try { Build.MODEL + "/" + (Build.SERIAL ?: "?") } catch (_: Throwable) { null }
        }
    }
}
