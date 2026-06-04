package io.gomob.data.scan

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gomob.network.ApiException
import io.gomob.network.LaserCalibParams
import io.gomob.network.LaserControlSettings
import io.gomob.network.LaserDeviceCommandRequest
import io.gomob.network.LaserDeviceInfo
import io.gomob.network.LaserDeviceStatus
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
    suspend fun start(align: String = "none", keepRatio: Float? = null, inspectionId: Long? = null): LaserStartResult {
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

    // --- 设备控制面板（原厂功能键；unit="a"|"b"）---

    /** 单元实时状态。 */
    suspend fun deviceStatus(unit: String): DeviceStatusInfo = api.deviceStatus(unit).toDomain()

    /** 单元设备信息 + 当前扫描设置 + 当前标定。 */
    suspend fun deviceInfo(unit: String): DeviceFullInfo = api.deviceInfo(unit).toDomain()

    /** 直接设备命令：ALIGN_ZERO|SCAN_WATCH|SCAN_STOP|CLEAR_ERROR|SOFT_REBOOT。 */
    suspend fun deviceCommand(unit: String, cmd: String) {
        api.deviceCommand(unit, LaserDeviceCommandRequest(cmd))
    }

    /** 下发扫描运动设置。 */
    suspend fun updateScanSettings(unit: String, s: ScanSettings) {
        api.deviceScanSettings(unit, s.toNetwork())
    }

    /** 下发标定参数（破坏性：覆写设备存储标定）。 */
    suspend fun updateCalib(unit: String, c: DeviceCalib) {
        api.deviceCalib(unit, c.toNetwork())
    }
}

// --- feature 层可见设备控制类型（剥离 core:network）---

/** 单元实时状态（错误码 [errorCode] 是位掩码，UI 自行解位）。 */
data class DeviceStatusInfo(
    val ip: String,
    val online: Boolean,
    val state: String,
    val scanMsg: String,
    val uptimeSec: Double,
    val encoderOnline: Boolean,
    val lidarOnline: Boolean,
    val cameraOnline: Boolean,
    val controlOnline: Boolean,
    val latestAngle: Double,
    val zeroDegs: Double,
    val angleDegs: Double,
    val errorCode: Long,
    val tempre: Double,
)

/** 单元设备信息 + 当前扫描设置 + 当前标定（设备信息只读；后两者可编辑回写）。 */
data class DeviceFullInfo(
    val model: String,
    val sn: String,
    val hwver: String,
    val swver: String,
    val networkType: String,
    val network: String,
    val lidarModel: String,
    val lidarPort: Int,
    val lidarValidZone: List<Double>,
    val cameraModel: String,
    val cameraWidth: Int,
    val cameraHeight: Int,
    val cameraCaptureFps: Double,
    val encoderResolution: Int,
    val scanSettings: ScanSettings,
    val calib: DeviceCalib,
)

/** 扫描运动设置（可编辑）。 */
data class ScanSettings(
    val scanSpeed: Double,
    val zeroSpeed: Double,
    val scanStartAngle: Double,
    val scanStopAngle: Double,
    val watchingAngle: Double,
    val lidarFilterGhost: Double,
    val lidarFilterZone: List<Double>,
    val cameraFps: Double,
)

/** 标定参数（可编辑；quat=[w,x,y,z]、offset=[x,y,z] 米、intrinsic=[fx,fy,cx,cy]、distortion=[k1,k2,p1,p2,k3]）。 */
data class DeviceCalib(
    val lidarRotQuat: List<Double>,
    val lidarCorrQuat: List<Double>,
    val lidarCorrOffset: List<Double>,
    val cameraRotQuat: List<Double>,
    val cameraCorrQuat: List<Double>,
    val cameraCorrOffset: List<Double>,
    val cameraIntrinsic: List<Double>,
    val cameraDistortion: List<Double>,
    val b2wQuat: List<Double>,
    val b2wOffset: List<Double>,
    val b2wScale: Double,
)

private fun LaserDeviceStatus.toDomain() = DeviceStatusInfo(
    ip = ip, online = online, state = state, scanMsg = scanMsg, uptimeSec = uptime,
    encoderOnline = encoderOnline, lidarOnline = lidarOnline, cameraOnline = cameraOnline,
    controlOnline = controlOnline, latestAngle = latestAngle, zeroDegs = zeroDegs,
    angleDegs = angleDegs, errorCode = errorCode, tempre = tempre,
)

private fun LaserDeviceInfo.toDomain() = DeviceFullInfo(
    model = model, sn = sn, hwver = hwver, swver = swver, networkType = networkType, network = network,
    lidarModel = lidarModel, lidarPort = lidarPort, lidarValidZone = lidarValidZone,
    cameraModel = cameraModel, cameraWidth = cameraWidth, cameraHeight = cameraHeight,
    cameraCaptureFps = cameraCaptureFps, encoderResolution = encoderResolution,
    scanSettings = ScanSettings(
        scanSpeed = control.scanSpeed, zeroSpeed = control.zeroSpeed,
        scanStartAngle = control.scanStartAngle, scanStopAngle = control.scanStopAngle,
        watchingAngle = control.watchingAngle, lidarFilterGhost = control.lidarFilterGhost,
        lidarFilterZone = control.lidarFilterZone, cameraFps = control.cameraFps,
    ),
    calib = DeviceCalib(
        lidarRotQuat = calib.lidar.rotQuat, lidarCorrQuat = calib.lidar.corrQuat, lidarCorrOffset = calib.lidar.corrOffset,
        cameraRotQuat = calib.camera.rotQuat, cameraCorrQuat = calib.camera.corrQuat, cameraCorrOffset = calib.camera.corrOffset,
        cameraIntrinsic = calib.camera.intrinsic, cameraDistortion = calib.camera.distortion,
        b2wQuat = calib.body2world.quat, b2wOffset = calib.body2world.offset, b2wScale = calib.body2world.scale,
    ),
)

private fun ScanSettings.toNetwork() = LaserControlSettings(
    scanSpeed = scanSpeed, zeroSpeed = zeroSpeed, scanStartAngle = scanStartAngle,
    scanStopAngle = scanStopAngle, watchingAngle = watchingAngle, lidarFilterGhost = lidarFilterGhost,
    lidarFilterZone = lidarFilterZone, cameraFps = cameraFps,
)

private fun DeviceCalib.toNetwork() = LaserCalibParams(
    lidar = io.gomob.network.LaserLidarCalib(rotQuat = lidarRotQuat, corrQuat = lidarCorrQuat, corrOffset = lidarCorrOffset),
    camera = io.gomob.network.LaserCameraCalib(
        rotQuat = cameraRotQuat, corrQuat = cameraCorrQuat, corrOffset = cameraCorrOffset,
        intrinsic = cameraIntrinsic, distortion = cameraDistortion,
    ),
    body2world = io.gomob.network.LaserBody2World(quat = b2wQuat, offset = b2wOffset, scale = b2wScale),
)

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
