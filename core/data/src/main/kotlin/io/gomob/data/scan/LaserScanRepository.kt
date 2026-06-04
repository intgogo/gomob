package io.gomob.data.scan

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gomob.network.ApiException
import io.gomob.network.LaserScanApi
import io.gomob.network.LaserScanStartRequest
import io.gomob.realtime.RealtimeEvent
import io.gomob.realtime.RealtimeSocketClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

// --- feature 层可见契约（剥离 core:realtime / core:network 类型）---

/** 起扫返回。 */
data class LaserStartResult(val scanId: Long, val sessionKey: String, val status: String)

/** 采集中的增量点帧（unit 0=A,1=B；points 扁平 [x,y,z,...] mm）。 */
data class LaserPointFrame(val sessionKey: String, val unit: Int, val points: FloatArray, val hAngleDeg: Float) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LaserPointFrame) return false
        return sessionKey == other.sessionKey && unit == other.unit &&
            hAngleDeg == other.hAngleDeg && points.contentEquals(other.points)
    }
    override fun hashCode(): Int {
        var r = sessionKey.hashCode(); r = 31 * r + unit
        r = 31 * r + hAngleDeg.hashCode(); r = 31 * r + points.contentHashCode(); return r
    }
}

/** 状态机变更。 */
data class LaserStatusUpdate(val sessionKey: String, val state: String, val framesA: Int, val framesB: Int)

/** 融合完成（三朵 PCD object key + 统计）。 */
data class LaserDoneResult(
    val sessionKey: String,
    val fusedObjectKey: String,
    val unitAObjectKey: String,
    val unitBObjectKey: String,
    val points: Int,
    val ptsA: Int,
    val ptsB: Int,
    val alignMethod: String,
)

/**
 * 激光双单元车辆外廓扫描仓库（M8'）：把实时 laser.points/status + scan.fusion_done(kind=laser) 事件，
 * 与 REST 起停/状态/PCD 下载收口给 feature 层。feature:scan3d 不直接依赖 core:realtime / core:network。
 *
 * 瘦客户端：连接 / 采集 / 融合全在服务端，本仓库只发指令 + 收流 + 下载结果。
 */
@Singleton
class LaserScanRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val socket: RealtimeSocketClient,
    private val api: LaserScanApi,
) {
    /** 采集中增量点流（未按 session 过滤；调用方自行匹配 sessionKey）。 */
    val pointFrames: Flow<LaserPointFrame> =
        socket.events.filterIsInstance<RealtimeEvent.LaserPoints>().map {
            LaserPointFrame(it.sessionKey, it.unit, it.points, it.hAngleDeg)
        }

    /** 状态机变更流。 */
    val statusUpdates: Flow<LaserStatusUpdate> =
        socket.events.filterIsInstance<RealtimeEvent.LaserStatus>().map {
            LaserStatusUpdate(it.sessionKey, it.state, it.framesA, it.framesB)
        }

    /** 融合完成事件流。 */
    val doneEvents: Flow<LaserDoneResult> =
        socket.events.filterIsInstance<RealtimeEvent.LaserScanDone>().map {
            LaserDoneResult(
                sessionKey = it.sessionKey,
                fusedObjectKey = it.fusedObjectKey,
                unitAObjectKey = it.unitAObjectKey,
                unitBObjectKey = it.unitBObjectKey,
                points = it.points,
                ptsA = it.ptsA,
                ptsB = it.ptsB,
                alignMethod = it.alignMethod,
            )
        }

    /** 确保实时通道已连接（扫描页进场时调，保证能收到 laser.points/status/done 推送）。 */
    fun ensureRealtimeConnected() = socket.connect()

    /** 起一次扫描（请求驱动；服务端探活两单元后开始采集，立即返回 capturing）。 */
    suspend fun start(align: String = "icp", keepRatio: Float? = null, inspectionId: Long? = null): LaserStartResult {
        val resp = api.start(LaserScanStartRequest(align = align, keepRatio = keepRatio, inspectionId = inspectionId))
        return LaserStartResult(resp.scanId, resp.sessionKey, resp.status)
    }

    /** 停止/取消扫描（设备 SCAN_STOP + 协作取消 cgo 采集）。 */
    suspend fun stop(scanId: Long): String = api.stop(scanId).status

    /** 查状态（断线重连兜底）。返回 (status, 三朵 PCD object key 若就绪)。 */
    suspend fun status(scanId: Long): LaserScanInfo {
        val r = api.status(scanId)
        return LaserScanInfo(
            scanId = r.scanId,
            status = r.status,
            alignMethod = r.alignMethod,
            points = r.points,
            fusedObjectKey = r.resultObjectKey,
            unitAObjectKey = r.unitAObjectKey,
            unitBObjectKey = r.unitBObjectKey,
            error = r.error,
        )
    }

    /** 下载一朵 PCD 到本地缓存。name ∈ fused|unit_a|unit_b。 */
    suspend fun downloadCloudFile(scanId: Long, name: String): File {
        val body = api.downloadCloud(scanId, name)
        val dir = File(context.cacheDir, "laser_scans").apply { mkdirs() }
        val out = File(dir, "${scanId}_$name.pcd")
        try {
            body.byteStream().use { input -> out.outputStream().use { input.copyTo(it) } }
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        if (out.length() <= 0L) {
            out.delete()
            throw ApiException(50001, 500, "PCD 为空")
        }
        return out
    }

    /** 下载一朵 PCD 并解析为扁平 [x,y,z,...] mm，直接喂 PointCloud3dView。 */
    suspend fun downloadCloudPoints(scanId: Long, name: String): FloatArray =
        parsePcdBinary(downloadCloudFile(scanId, name).readBytes())
}

/** 扫描状态视图（断线重连兜底用）。 */
data class LaserScanInfo(
    val scanId: Long,
    val status: String,
    val alignMethod: String?,
    val points: Int?,
    val fusedObjectKey: String?,
    val unitAObjectKey: String?,
    val unitBObjectKey: String?,
    val error: String?,
)

/**
 * 解析 server 端 EncodePCDBinary 产物（FIELDS x y z / SIZE 4 / TYPE F / DATA binary，小端 float32）。
 * 仅支持该编码器形态（非通用 PCD 解析器）。与 server internal/laser/pcd.go 对偶。
 */
internal fun parsePcdBinary(data: ByteArray): FloatArray {
    // 找头部 "DATA binary\n" 的结束位置 + POINTS 数。
    var points = -1
    var fields = ""
    var dataMode = ""
    var idx = 0
    val n = data.size
    while (idx < n) {
        val lineEnd = indexOfNewline(data, idx)
        if (lineEnd < 0) throw IllegalArgumentException("PCD 头未见 DATA")
        val line = String(data, idx, lineEnd - idx, Charsets.US_ASCII).trimEnd('\r')
        when {
            line.startsWith("FIELDS") -> fields = line.removePrefix("FIELDS").trim()
            line.startsWith("POINTS") -> points = line.removePrefix("POINTS").trim().toInt()
            line.startsWith("DATA") -> dataMode = line.removePrefix("DATA").trim()
        }
        idx = lineEnd + 1
        if (dataMode.isNotEmpty()) break
    }
    require(fields == "x y z") { "仅支持 FIELDS x y z，得 \"$fields\"" }
    require(dataMode == "binary") { "仅支持 DATA binary，得 \"$dataMode\"" }
    require(points >= 0) { "缺 POINTS" }
    val floats = points * 3
    val need = floats * 4
    require(n - idx >= need) { "二进制主体不足：期望 $need 字节，剩 ${n - idx}" }
    val bb = ByteBuffer.wrap(data, idx, need).order(ByteOrder.LITTLE_ENDIAN)
    val out = FloatArray(floats)
    for (i in 0 until floats) out[i] = bb.float
    return out
}

private fun indexOfNewline(data: ByteArray, from: Int): Int {
    var i = from
    while (i < data.size) {
        if (data[i] == '\n'.code.toByte()) return i
        i++
    }
    return -1
}
