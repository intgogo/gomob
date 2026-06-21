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
    val measurement: VehicleMeasurement,
    val ground: GroundPlane,
)

/** 车辆外廓测量 + GB7258 合规（M9.6，服务端 measure.go 算后经 done 事件推来；mm）。 */
data class VehicleMeasurement(
    val lengthMm: Float,
    val widthMm: Float,
    val heightMm: Float,
    val valid: Boolean,
    val compliant: Boolean,
    val violations: List<String>,
)

/**
 * 地面平面（服务端 ground.go RANSAC 拟合，经 done 事件推来）：nx*x+ny*y+nz*z+d=0，
 * 法向单位向量指向点云主体一侧(=“上”)。端侧用作视角预设的"上"方向基准（设备世界系 Z 非真竖直）。
 * valid=false 时端侧回退世界 +Z。
 */
data class GroundPlane(
    val nx: Float,
    val ny: Float,
    val nz: Float,
    val d: Float,
    val valid: Boolean,
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
                measurement = VehicleMeasurement(
                    lengthMm = it.lengthMm,
                    widthMm = it.widthMm,
                    heightMm = it.heightMm,
                    valid = it.measureValid,
                    compliant = it.compliant,
                    violations = it.violations,
                ),
                ground = GroundPlane(
                    nx = it.groundNx, ny = it.groundNy, nz = it.groundNz,
                    d = it.groundD, valid = it.groundValid,
                ),
            )
        }

    /** 确保实时通道已连接（扫描页进场时调，保证能收到 laser.points/status/done 推送）。 */
    fun ensureRealtimeConnected() = socket.connect()

    /** 起一次扫描（请求驱动；服务端探活两单元后开始采集，立即返回 capturing）。vehicleTypeId=车型编号（可空）。 */
    suspend fun start(
        align: String = "site",
        siteJson: String? = null,
        keepRatio: Float? = null,
        inspectionId: Long? = null,
        vehicleTypeId: Int? = null,
    ): LaserStartResult {
        val resp = api.start(LaserScanStartRequest(
            align = align, siteJson = siteJson, keepRatio = keepRatio, inspectionId = inspectionId, vehicleTypeId = vehicleTypeId,
        ))
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

    /** 下载一朵 PCD 并解析为渲染数据；当 PCD 带 rgb 字段时保留每点颜色。 */
    suspend fun downloadCloudRenderData(scanId: Long, name: String): LaserCloudRenderData =
        parsePcdBinaryRenderData(downloadCloudFile(scanId, name).readBytes())

    /** 下载一朵单元 PCD（XYZI），解析为 (xyz 扁平 mm, 每点 h_angle°)。供"圈框→看每点采集角"。 */
    suspend fun downloadCloudWithAngles(scanId: Long, name: String): CloudWithAngles =
        parsePcdBinaryWithAngles(downloadCloudFile(scanId, name).readBytes())

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

    // --- 持久车位框（M9.11 / M10.2 按镜头）：用户在各镜头点云空间圈 3D 框 → 每次扫描裁框内测量 ---

    /** 取某单元(a|b)的车位框；未设置返回 null。a 框在世界系、b 框在 unitB 设备系。 */
    suspend fun getCropBox(unit: String): ScanCropBox? {
        val r = api.getCropBox(unit)
        val box = r.box
        return if (r.set && box != null) box.toDomain() else null
    }

    /** 保存/覆盖某单元车位框（服务端持久化，非设备写）。 */
    suspend fun saveCropBox(unit: String, box: ScanCropBox) {
        api.putCropBox(unit, box.toNetwork())
    }

    /** 用候选框裁某次扫描指定镜头点云(a→unitA / b→unitB)并测量，供拖框实时预览（不落库）。 */
    suspend fun cropPreview(scanId: Long, unit: String, box: ScanCropBox): CropPreviewResult {
        val r = api.cropPreview(scanId, unit, box.toNetwork())
        return CropPreviewResult(
            totalPoints = r.totalPoints,
            inPoints = r.inPoints,
            lengthMm = r.measurement.lengthMm,
            widthMm = r.measurement.widthMm,
            heightMm = r.measurement.heightMm,
            bodyPts = r.measurement.bodyPts,
            bodyRatio = r.measurement.bodyRatio,
            valid = r.measurement.valid,
        )
    }
}

/**
 * 世界系定向裁剪框(OBB)，mm。center=框心；up=朝上单位向量(地面法向，可翻转/微调)；
 * yawDeg=绕 up 旋转(footprint 朝向/车头)；half=[右半宽,前半长,上半高]。
 */
data class ScanCropBox(
    val center: FloatArray,
    val up: FloatArray,
    val yawDeg: Float,
    val half: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScanCropBox) return false
        return center.contentEquals(other.center) && up.contentEquals(other.up) &&
            yawDeg == other.yawDeg && half.contentEquals(other.half)
    }
    override fun hashCode(): Int {
        var r = center.contentHashCode(); r = 31 * r + up.contentHashCode()
        r = 31 * r + yawDeg.hashCode(); r = 31 * r + half.contentHashCode(); return r
    }
}

/** 拖框预览结果：框内点数 + 框内测量（mm）。 */
data class CropPreviewResult(
    val totalPoints: Int,
    val inPoints: Int,
    val lengthMm: Float,
    val widthMm: Float,
    val heightMm: Float,
    val bodyPts: Int,
    val bodyRatio: Float,
    val valid: Boolean,
)

private fun io.gomob.network.LaserCropBox.toDomain() = ScanCropBox(
    center = center.toFloatArray(), up = up.toFloatArray(), yawDeg = yawDeg, half = half.toFloatArray(),
)

private fun ScanCropBox.toNetwork() = io.gomob.network.LaserCropBox(
    center = center.toList(), up = up.toList(), yawDeg = yawDeg, half = half.toList(),
)

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
    val scanAngle: Double? = null,
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
        scanAngle = control.scanAngle ?: (control.scanStopAngle - control.scanStartAngle),
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
    scanStopAngle = scanStopAngle, scanAngle = scanAngle,
    watchingAngle = watchingAngle, lidarFilterGhost = lidarFilterGhost,
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

/** 单元云解析结果：xyz 扁平 [x,y,z,...] mm + 每点 h_angle°（融合云无角度时 angles 为空）。 */
data class CloudWithAngles(val xyz: FloatArray, val angles: FloatArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CloudWithAngles) return false
        return xyz.contentEquals(other.xyz) && angles.contentEquals(other.angles)
    }
    override fun hashCode(): Int = 31 * xyz.contentHashCode() + angles.contentHashCode()
}

/** 点云渲染数据：xyz 扁平 [x,y,z,...] mm；rgb 为可选 0xRRGGBB；angles 为每点采集 h_angle°。 */
data class LaserCloudRenderData(
    val xyz: FloatArray,
    val rgb: IntArray? = null,
    val angles: FloatArray = FloatArray(0),
) {
    val pointCount: Int get() = xyz.size / 3
    val hasColor: Boolean get() = rgb != null && rgb.size == pointCount
    val hasAngles: Boolean get() = angles.size == pointCount

    companion object {
        val Empty = LaserCloudRenderData(FloatArray(0), null, FloatArray(0))
    }
}

/**
 * 解析 server 端 EncodePCDBinary{,XYZI} 产物（DATA binary，小端 float32）。与 server internal/laser/pcd.go 对偶。
 * 支持 FIELDS "x y z"、"x y z intensity"、"x y z rgb"、"x y z rgb intensity"。
 */
internal fun parsePcdBinary(data: ByteArray): FloatArray = parsePcdBinaryFull(data).xyz

/** 同 parsePcdBinary，但额外返回每点 intensity(=h_angle°)；融合云无 intensity 时 angles 为空。 */
internal fun parsePcdBinaryWithAngles(data: ByteArray): CloudWithAngles {
    val r = parsePcdBinaryFull(data)
    return CloudWithAngles(r.xyz, r.angles)
}

/** 同 parsePcdBinary，但额外返回可选每点 rgb(0xRRGGBB)。 */
internal fun parsePcdBinaryRenderData(data: ByteArray): LaserCloudRenderData {
    val r = parsePcdBinaryFull(data)
    return LaserCloudRenderData(r.xyz, r.rgb, r.angles)
}

// 单帧 PCD 点数硬上界：防恶意/损坏头声明天量点数导致 Int 溢出或 OOM。
// 5000 万远超单帧激光点云物理量级，留足余量同时保证 points*3 与字节数计算不溢出 Int。
private const val MAX_PCD_POINTS = 50_000_000

private data class ParsedPcdCloud(
    val xyz: FloatArray,
    val angles: FloatArray,
    val rgb: IntArray?,
)

private fun parsePcdBinaryFull(data: ByteArray): ParsedPcdCloud {
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
    val fieldNames = fields.split(Regex("\\s+")).filter { it.isNotBlank() }
    require(fieldNames.isNotEmpty()) { "缺 FIELDS" }
    require(fieldNames.distinct().size == fieldNames.size) { "FIELDS 有重复字段：$fields" }
    val xIndex = fieldNames.indexOf("x")
    val yIndex = fieldNames.indexOf("y")
    val zIndex = fieldNames.indexOf("z")
    require(xIndex >= 0 && yIndex >= 0 && zIndex >= 0) { "FIELDS 缺 x/y/z：$fields" }
    val intensityIndex = fieldNames.indexOf("intensity")
    val rgbIndex = fieldNames.indexOf("rgb")
    val unsupported = fieldNames.filter { it !in setOf("x", "y", "z", "intensity", "rgb") }
    require(unsupported.isEmpty()) { "不支持的 FIELDS ${unsupported.joinToString()}，得 \"$fields\"" }
    if (intensityIndex >= 0) {
        require(fieldNames == listOf("x", "y", "z", "intensity") ||
            fieldNames == listOf("x", "y", "z", "rgb", "intensity") ||
            fieldNames == listOf("x", "y", "z", "intensity", "rgb")) {
            "intensity 仅支持 x y z intensity / x y z rgb intensity，得 \"$fields\""
        }
    } else {
        require(fieldNames == listOf("x", "y", "z") ||
            fieldNames == listOf("x", "y", "z", "rgb")) {
            "仅支持 x y z / x y z rgb，得 \"$fields\""
        }
    }
    require(dataMode == "binary") { "仅支持 DATA binary，得 \"$dataMode\"" }
    require(points >= 0) { "缺 POINTS" }
    // POINTS 上界：恶意/损坏头声明天量点数会让下面 FloatArray(points*3) / 字节数计算溢出 Int → 负数或
    // OOM。单帧激光点云物理上不可能上亿点，给 5000 万硬上界足够余量；超界直接拒绝而非崩溃。
    require(points <= MAX_PCD_POINTS) { "POINTS=$points 超上界 $MAX_PCD_POINTS" }
    val perPt = fieldNames.size
    // 用 Long 计算字节数，避免 points*perPt*4 在 Int 域溢出后绕回造成 require 误通过。
    val need = points.toLong() * perPt * 4L
    require(n - idx >= need) { "二进制主体不足：期望 $need 字节，剩 ${n - idx}" }
    val bb = ByteBuffer.wrap(data, idx, need.toInt()).order(ByteOrder.LITTLE_ENDIAN)
    val xyz = FloatArray(points * 3)
    val angles = if (intensityIndex >= 0) FloatArray(points) else FloatArray(0)
    val rgb = if (rgbIndex >= 0) IntArray(points) else null
    for (i in 0 until points) {
        var x = 0f
        var y = 0f
        var z = 0f
        var h = 0f
        var c = 0
        for (f in fieldNames) {
            val raw = bb.int
            when (f) {
                "x" -> x = java.lang.Float.intBitsToFloat(raw)
                "y" -> y = java.lang.Float.intBitsToFloat(raw)
                "z" -> z = java.lang.Float.intBitsToFloat(raw)
                "intensity" -> h = java.lang.Float.intBitsToFloat(raw)
                "rgb" -> c = raw and 0x00ff_ffff
            }
        }
        xyz[3 * i] = x
        xyz[3 * i + 1] = y
        xyz[3 * i + 2] = z
        if (intensityIndex >= 0) angles[i] = h
        if (rgb != null) rgb[i] = c
    }
    return ParsedPcdCloud(xyz, angles, rgb)
}

private fun indexOfNewline(data: ByteArray, from: Int): Int {
    var i = from
    while (i < data.size) {
        if (data[i] == '\n'.code.toByte()) return i
        i++
    }
    return -1
}
