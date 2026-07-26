package io.gomob.data.scan

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gomob.network.ApiException
import io.gomob.network.LaserCalibParams
import io.gomob.network.LaserControlSettings
import io.gomob.network.LaserDeviceCommandRequest
import io.gomob.network.LaserDeviceInfo
import io.gomob.network.LaserDeviceStatus
import io.gomob.network.LaserLatestScanResponse
import io.gomob.network.LaserMeasuredCloudArtifact
import io.gomob.network.LaserScanApi
import io.gomob.network.LaserScanStatusResponse
import io.gomob.network.LaserScanStartRequest
import io.gomob.network.LaserVehicleOverlay
import io.gomob.realtime.RealtimeEvent
import io.gomob.realtime.RealtimeSocketClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

// --- feature 层可见契约（剥离 core:realtime / core:network 类型）---

/** 起扫返回。 */
data class LaserStartResult(val scanId: Long, val sessionKey: String, val status: String)

/** 3D 根页可展示的最近一次真实扫描摘要。 */
data class LaserLatestScan(
    val scanId: Long,
    val status: String,
    val points: Int?,
    val backgroundCaptured: Boolean = false,
)

/** 采集中的增量点帧（unit 0=A,1=B；points 扁平 [x,y,z,...] mm）。 */
data class LaserPointFrame(
    val sessionKey: String,
    val unit: Int,
    val points: FloatArray,
    val hAngleDeg: Float,
    /** 服务端 canonical region 裁剪后的该单元累计源点数；旧服务端缺失时为 null。 */
    val sourcePointCount: Int? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LaserPointFrame) return false
        return sessionKey == other.sessionKey && unit == other.unit &&
            hAngleDeg == other.hAngleDeg && sourcePointCount == other.sourcePointCount &&
            points.contentEquals(other.points)
    }
    override fun hashCode(): Int {
        var r = sessionKey.hashCode(); r = 31 * r + unit
        r = 31 * r + hAngleDeg.hashCode(); r = 31 * r + (sourcePointCount ?: 0)
        r = 31 * r + points.contentHashCode(); return r
    }
}

/** 状态机变更；累计源点数走可靠通道，兜住最后一条 lossy 点帧被丢的情况。 */
data class LaserStatusUpdate(
    val sessionKey: String,
    val state: String,
    val framesA: Int,
    val framesB: Int,
    val sourcePointsA: Int? = null,
    val sourcePointsB: Int? = null,
)

/** WS 完成事件；[result] 与 REST 状态使用同一个完整领域模型。 */
data class LaserDoneResult(
    val jobId: Long?,
    val result: LaserScanResult,
)

/** 一次已完成激光扫描的完整客户端结果；所有几何尺寸和坐标单位均为 mm。 */
data class LaserScanResult(
    val sessionKey: String,
    val fusedObjectKey: String?,
    val unitAObjectKey: String?,
    val unitBObjectKey: String?,
    val points: Int,
    val ptsA: Int,
    val ptsB: Int,
    val alignMethod: String,
    val measuredObjectKey: String? = null,
    val measuredArtifact: MeasuredCloudArtifact? = null,
    val siteRevision: String? = null,
    val regionRevision: String? = null,
    val siteQualityVerified: Boolean = false,
    val siteQualityOverride: Boolean = false,
    val productionEligible: Boolean = false,
    val measurement: VehicleMeasurement,
    val ground: GroundPlane,
)

data class MeasuredCloudArtifact(
    val xyzSha256: String,
    val coordinateSchema: String,
    val sourcePoints: Int,
    val siteRevision: String?,
    val regionRevision: String?,
    val backgroundRevision: Long?,
    val finalBToASha256: String,
) {
    fun matches(result: LaserScanResult): Boolean =
        coordinateSchema == MEASURED_COORDINATE_SCHEMA &&
            xyzSha256.isSha256Hex() &&
            finalBToASha256.isSha256Hex() &&
            sourcePoints > 0 &&
            sourcePoints == result.measurement.measuredPoints &&
            siteRevision.nonBlankOrNull() != null &&
            siteRevision.nonBlankOrNull() == result.siteRevision.nonBlankOrNull() &&
            regionRevision.nonBlankOrNull() != null &&
            regionRevision.nonBlankOrNull() == result.regionRevision.nonBlankOrNull() &&
            backgroundRevision == result.measurement.backgroundRevisionId
}

/** 车辆外廓测量 + GB7258 合规（M9.6，服务端 measure.go 算后经 done 事件推来；mm）。 */
data class VehicleMeasurement(
    val lengthMm: Float,
    val widthMm: Float,
    val heightMm: Float,
    val valid: Boolean,
    val complianceDetermined: Boolean = false,
    val complianceReason: String? = null,
    val compliant: Boolean,
    val violations: List<String>,
    val mode: String = "",
    val reason: String? = null,
    val backgroundCaptured: Boolean = false,
    val backgroundSet: Boolean = false,
    val backgroundCompatible: Boolean? = null,
    val backgroundIncompatible: Boolean = false,
    val backgroundReason: String? = null,
    val backgroundRevisionId: Long? = null,
    val backgroundSchema: String? = null,
    val foregroundPoints: Int = 0,
    val measuredPoints: Int = 0,
    val axle: VehicleAxleMeasurement = VehicleAxleMeasurement(),
    val cargoBox: VehicleCargoBoxMeasurement = VehicleCargoBoxMeasurement(),
    val overlay: VehicleMeasurementOverlay? = null,
)

/** 清除尚未被 measured PCD 内容身份证明的车辆结论，仅保留诊断元数据。 */
fun VehicleMeasurement.withoutVerifiedConclusion(reason: String?): VehicleMeasurement = copy(
    lengthMm = 0f,
    widthMm = 0f,
    heightMm = 0f,
    valid = false,
    complianceDetermined = false,
    complianceReason = reason,
    compliant = false,
    violations = emptyList(),
    reason = reason,
    axle = VehicleAxleMeasurement(),
    cargoBox = VehicleCargoBoxMeasurement(),
    overlay = null,
)

/**
 * 有效测量必须由独立 measured PCD 承载。缺失该产物时把整组车辆几何降为无效诊断结果，
 * 禁止任何调用方误用 fused 场景云和服务端残留尺寸冒充车辆外廓。
 */
fun LaserScanResult.enforceMeasuredArtifactContract(): LaserScanResult {
    val artifactDeclared = !measuredObjectKey.isNullOrBlank() || measuredArtifact != null
    val artifactReady = !measuredObjectKey.isNullOrBlank() && measuredArtifact?.matches(this) == true
    if (artifactReady) {
        return if (measurement.valid) this else copy(
            measurement = measurement.withoutVerifiedConclusion(measurement.reason),
        )
    }
    if (!measurement.valid && !artifactDeclared) {
        return copy(measurement = measurement.withoutVerifiedConclusion(measurement.reason))
    }
    return copy(
        measuredObjectKey = null,
        measuredArtifact = null,
        measurement = measurement.withoutVerifiedConclusion(
            if (measuredObjectKey.isNullOrBlank() || measuredArtifact == null) {
                "measured_artifact_missing"
            } else {
                "measured_artifact_mismatch"
            },
        ),
    )
}

/** 未通过生产资格时只撤销合规结论，保留已验证 measured 点云上的尺寸用于联调比对。 */
fun LaserScanResult.enforceProductionEligibilityContract(): LaserScanResult {
    if (siteQualityVerified && !siteQualityOverride && productionEligible) return this
    val reason = when {
        !siteQualityVerified -> "site_quality_unverified"
        siteQualityOverride -> "site_quality_override"
        else -> "production_ineligible"
    }
    return copy(
        measurement = measurement.copy(
            complianceDetermined = false,
            complianceReason = reason,
            compliant = false,
            violations = emptyList(),
        ),
    )
}

/** 轴距、总轴距及前后悬；[valid] 为 false 时其余数值只作原始回传，不应展示为结论。 */
data class VehicleAxleMeasurement(
    val valid: Boolean = false,
    val numAxles: Int = 0,
    val wheelbasesMm: List<Float> = emptyList(),
    val totalWheelbaseMm: Float = 0f,
    val frontOverhangMm: Float = 0f,
    val rearOverhangMm: Float = 0f,
)

/** 货箱外廓；[hasBox] 为 false 表示服务端未检出可信货箱。 */
data class VehicleCargoBoxMeasurement(
    val hasBox: Boolean = false,
    val outerLengthMm: Float = 0f,
    val outerWidthMm: Float = 0f,
    val depthMm: Float = 0f,
    val innerWidthMm: Float = 0f,
)

data class MeasurementPoint3(val x: Float, val y: Float, val z: Float)

data class MeasurementLine3(val from: MeasurementPoint3, val to: MeasurementPoint3)

/** 与服务端测量同源的世界系几何，不由 App 从点云包围盒重算。 */
data class VehicleMeasurementOverlay(
    val valid: Boolean,
    val vehicleBox: List<MeasurementPoint3>,
    val hasCargoBox: Boolean,
    val cargoBox: List<MeasurementPoint3>,
    val axleLines: List<MeasurementLine3>,
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
        socket.laserPoints.map {
            LaserPointFrame(it.sessionKey, it.unit, it.points, it.hAngleDeg, it.sourcePointCount)
        }

    /** 状态机变更流。 */
    val statusUpdates: Flow<LaserStatusUpdate> =
        socket.events.filterIsInstance<RealtimeEvent.LaserStatus>().map {
            LaserStatusUpdate(
                it.sessionKey,
                it.state,
                it.framesA,
                it.framesB,
                it.sourcePointsA,
                it.sourcePointsB,
            )
        }

    /** 融合完成事件流。 */
    val doneEvents: Flow<LaserDoneResult> =
        socket.events.filterIsInstance<RealtimeEvent.LaserScanDone>().map {
            LaserDoneResult(
                jobId = it.jobId,
                result = it.toDomainResult(),
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
    suspend fun status(scanId: Long): LaserScanInfo = api.status(scanId)
        .requireValidScanIdentity(expectedScanId = scanId, endpoint = "status")
        .toDomainInfo()

    /** 页面重进时恢复当前工位的活动扫描；没有活动任务返回 null。 */
    suspend fun active(): LaserScanInfo? {
        val response = api.active()
        if (!response.active) return null
        response.requireValidScanIdentity(endpoint = "active")
        return response.toDomainInfo(statusOverride = response.resolveActiveStatus())
    }

    /** 没有活动任务时恢复当前工位最近一次完整结果；没有历史结果返回 null。 */
    suspend fun latestInfo(): LaserScanInfo? {
        val response = api.latestInfo()
        if (!response.found) return null
        response.requireValidScanIdentity(endpoint = "latest")
        return response.toDomainInfo()
    }

    /** 保留 3D 根页现有摘要契约，不改变主干界面。 */
    suspend fun latest(): LaserLatestScan? = api.latest().toDomainOrNull()

    /** 下载一朵权威完整 PCD 到本地缓存；只落磁盘，不进入 Java 堆。 */
    suspend fun downloadCloudFile(scanId: Long, name: String): File {
        val response = api.downloadCloud(scanId, name)
        val body = response.requireCloudBody("$scanId/$name")
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

    /** 下载有界点样本并解析为扁平 [x,y,z,...] mm。 */
    suspend fun downloadCloudPoints(scanId: Long, name: String): FloatArray =
        downloadCloudRenderData(scanId, name, MAX_ANDROID_RENDER_POINTS).xyz

    /** 下载服务端从权威 PCD 派生的有界渲染样本，并校验源点数与返回点数响应头。 */
    suspend fun downloadCloudRenderData(
        scanId: Long,
        name: String,
        maxPoints: Int,
        expectedArtifact: MeasuredCloudArtifact? = null,
    ): LaserCloudRenderData {
        require(maxPoints in 1..MAX_ANDROID_RENDER_POINTS) {
            "maxPoints 须为 1..$MAX_ANDROID_RENDER_POINTS"
        }
        return parseCloudResponse(
            response = api.downloadCloud(scanId, name, maxPoints),
            maxRenderPoints = maxPoints,
            requirePointHeaders = true,
            endpoint = "$scanId/$name",
            expectedArtifact = expectedArtifact,
        )
    }

    /** 下载进行中扫描的单元点云快照，供页面重进后补回已采集部分。 */
    suspend fun downloadActiveCloudRenderData(
        name: String,
        maxPoints: Int,
    ): LaserCloudRenderData {
        require(name == "unit_a" || name == "unit_b") { "活动点云仅支持 unit_a/unit_b" }
        require(maxPoints in 1..MAX_ANDROID_RENDER_POINTS) {
            "maxPoints 须为 1..$MAX_ANDROID_RENDER_POINTS"
        }
        return parseCloudResponse(
            response = api.downloadActiveCloud(name, maxPoints),
            maxRenderPoints = maxPoints,
            requirePointHeaders = true,
            endpoint = "active/$name",
        )
    }

    /** 下载一朵有界单元 PCD，解析 xyz 与每点 h_angle°。 */
    suspend fun downloadCloudWithAngles(scanId: Long, name: String): CloudWithAngles {
        val cloud = downloadCloudRenderData(scanId, name, MAX_ANDROID_RENDER_POINTS)
        return CloudWithAngles(cloud.xyz, cloud.angles)
    }

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
    val sessionKey: String?,
    val result: LaserScanResult?,
    val error: String?,
    val unitAIp: String? = null,
    val unitBIp: String? = null,
    val livePointsA: Int = 0,
    val livePointsB: Int = 0,
    val fusionAvailable: Boolean? = null,
    val regionFilter: LaserScanRegionFilter? = null,
    val siteQualityVerified: Boolean = false,
    val siteQualityOverride: Boolean = false,
    val productionEligible: Boolean = false,
)

data class LaserScanRegionFilter(
    val enabled: Boolean,
    val points: List<MeasurementPoint3>,
    val bToA: List<Float>,
)

internal fun LaserLatestScanResponse.toDomainOrNull(): LaserLatestScan? {
    if (!found) return null
    val id = checkNotNull(scanId) { "latest found=true 但缺少 scan_id" }
    check(id > 0) { "latest scan_id 必须大于 0" }
    val normalizedStatus = status?.trim()?.lowercase().orEmpty()
    check(normalizedStatus.isNotEmpty()) { "latest found=true 但缺少 status" }
    return LaserLatestScan(
        scanId = id,
        status = normalizedStatus,
        points = points,
        backgroundCaptured = backgroundCaptured,
    )
}

internal fun RealtimeEvent.LaserScanDone.toDomainResult(): LaserScanResult = LaserScanResult(
    sessionKey = sessionKey,
    fusedObjectKey = fusedObjectKey.nonBlankOrNull(),
    unitAObjectKey = unitAObjectKey.nonBlankOrNull(),
    unitBObjectKey = unitBObjectKey.nonBlankOrNull(),
    points = points,
    ptsA = ptsA,
    ptsB = ptsB,
    alignMethod = alignMethod,
    measuredObjectKey = measuredObjectKey.nonBlankOrNull(),
    measuredArtifact = measuredArtifact?.toDomain(),
    siteRevision = siteRevision.nonBlankOrNull(),
    regionRevision = regionRevision.nonBlankOrNull(),
    siteQualityVerified = siteQualityVerified,
    siteQualityOverride = siteQualityOverride,
    productionEligible = productionEligible,
    measurement = vehicleMeasurement(
        alignMethod = alignMethod,
        lengthMm = lengthMm,
        widthMm = widthMm,
        heightMm = heightMm,
        measureValid = measureValid,
        complianceDetermined = complianceDetermined,
        complianceReason = complianceReason,
        compliant = compliant,
        violations = violations,
        measMode = measMode,
        measureReason = measureReason,
        backgroundCaptured = backgroundCaptured,
        backgroundSet = backgroundSet,
        backgroundCompatible = backgroundCompatible,
        backgroundIncompatible = backgroundSet && backgroundCompatible == false,
        backgroundReason = backgroundReason,
        backgroundRevisionId = backgroundRevisionId,
        backgroundSchema = backgroundSchema,
        foregroundPoints = foregroundPoints,
        measuredPoints = measuredPoints,
        numAxles = numAxles,
        wheelbasesMm = wheelbasesMm,
        totalWheelbaseMm = totalWheelbaseMm,
        frontOverhangMm = frontOverhangMm,
        rearOverhangMm = rearOverhangMm,
        axleValid = axleValid,
        hasCargoBox = hasCargoBox,
        boxOuterLengthMm = boxOuterLengthMm,
        boxOuterWidthMm = boxOuterWidthMm,
        boxDepthMm = boxDepthMm,
        boxInnerWidthMm = boxInnerWidthMm,
        overlay = overlay?.toDomain(),
    ),
    ground = GroundPlane(
        nx = groundNx,
        ny = groundNy,
        nz = groundNz,
        d = groundD,
        valid = groundValid,
    ),
).enforceMeasuredArtifactContract().enforceProductionEligibilityContract()

internal fun LaserScanStatusResponse.toDomainInfo(statusOverride: String? = null): LaserScanInfo {
    val normalizedStatus = statusOverride?.takeIf { it.isNotBlank() }?.lowercase() ?: status.lowercase()
    val completed = normalizedStatus == "done" || normalizedStatus == "completed"
    val completedSessionKey = sessionKey
    val result = if (completed && completedSessionKey != null) {
        LaserScanResult(
            sessionKey = completedSessionKey,
            fusedObjectKey = resultObjectKey.nonBlankOrNull(),
            unitAObjectKey = unitAObjectKey.nonBlankOrNull(),
            unitBObjectKey = unitBObjectKey.nonBlankOrNull(),
            points = points ?: 0,
            ptsA = ptsA ?: 0,
            ptsB = ptsB ?: 0,
            alignMethod = alignMethod.orEmpty(),
            measuredObjectKey = measuredObjectKey.nonBlankOrNull(),
            measuredArtifact = measuredArtifact?.toDomain(),
            siteRevision = siteRevision.nonBlankOrNull(),
            regionRevision = regionRevision.nonBlankOrNull(),
            siteQualityVerified = siteQualityVerified,
            siteQualityOverride = siteQualityOverride,
            productionEligible = productionEligible,
            measurement = vehicleMeasurement(
                alignMethod = alignMethod.orEmpty(),
                lengthMm = lengthMm,
                widthMm = widthMm,
                heightMm = heightMm,
                measureValid = measureValid,
                complianceDetermined = complianceDetermined,
                complianceReason = complianceReason,
                compliant = compliant,
                violations = violations,
                measMode = measMode.orEmpty(),
                measureReason = measureReason ?: legacyMeasureReason,
                backgroundCaptured = backgroundCaptured,
                backgroundSet = backgroundSet,
                backgroundCompatible = backgroundCompatible,
                backgroundIncompatible = backgroundIncompatible || (backgroundSet && backgroundCompatible == false),
                backgroundReason = backgroundReason,
                backgroundRevisionId = backgroundRevisionId,
                backgroundSchema = backgroundSchema,
                foregroundPoints = foregroundPoints,
                measuredPoints = measuredPoints,
                numAxles = numAxles,
                wheelbasesMm = wheelbasesMm,
                totalWheelbaseMm = totalWheelbaseMm,
                frontOverhangMm = frontOverhangMm,
                rearOverhangMm = rearOverhangMm,
                axleValid = axleValid,
                hasCargoBox = hasCargoBox,
                boxOuterLengthMm = boxOuterLengthMm,
                boxOuterWidthMm = boxOuterWidthMm,
                boxDepthMm = boxDepthMm,
                boxInnerWidthMm = boxInnerWidthMm,
                overlay = overlay?.toDomain(),
            ),
            ground = GroundPlane(
                nx = groundNx,
                ny = groundNy,
                nz = groundNz,
                d = groundD,
                valid = groundValid,
            ),
        ).enforceMeasuredArtifactContract().enforceProductionEligibilityContract()
    } else {
        null
    }
    return LaserScanInfo(
        scanId = scanId,
        status = normalizedStatus,
        sessionKey = sessionKey,
        result = result,
        error = error,
        unitAIp = unitAIp.nonBlankOrNull(),
        unitBIp = unitBIp.nonBlankOrNull(),
        livePointsA = livePointsA,
        livePointsB = livePointsB,
        fusionAvailable = fusionAvailable,
        siteQualityVerified = siteQualityVerified,
        siteQualityOverride = siteQualityOverride,
        productionEligible = productionEligible,
        regionFilter = regionFilter?.let { filter ->
            LaserScanRegionFilter(
                enabled = filter.enabled,
                points = filter.points.mapNotNull { it.toPoint3OrNull() },
                bToA = filter.bToA,
            )
        },
    )
}

/** DB job 状态是终态权威；live_state 只能把采集态前推到 scanning/fusing，不能用早到的 done 越过测量阶段。 */
internal fun LaserScanStatusResponse.resolveActiveStatus(): String {
    val job = status.trim().lowercase()
    val live = liveState?.trim()?.lowercase().orEmpty()
    val terminal = setOf("done", "completed", "failed", "error", "cancelled", "canceled")
    return when {
        job in terminal -> job
        live in setOf("failed", "error", "cancelled", "canceled") -> live
        job == "fusing" || job == "processing" -> job
        live == "fusing" || live == "processing" -> live
        live == "scanning" -> live
        job.isNotEmpty() -> job
        else -> live
    }
}

internal fun LaserScanStatusResponse.requireValidScanIdentity(
    expectedScanId: Long? = null,
    endpoint: String,
): LaserScanStatusResponse {
    val valid = scanId > 0 && !sessionKey.isNullOrBlank() && status.isNotBlank() &&
        (expectedScanId == null || scanId == expectedScanId)
    if (!valid) {
        throw ApiException(
            code = 50001,
            httpStatus = 502,
            message = "$endpoint 返回的扫描身份或状态无效",
        )
    }
    return this
}

private fun vehicleMeasurement(
    alignMethod: String,
    lengthMm: Float,
    widthMm: Float,
    heightMm: Float,
    measureValid: Boolean,
    complianceDetermined: Boolean,
    complianceReason: String?,
    compliant: Boolean,
    violations: List<String>,
    measMode: String,
    measureReason: String?,
    backgroundCaptured: Boolean,
    backgroundSet: Boolean,
    backgroundCompatible: Boolean?,
    backgroundIncompatible: Boolean,
    backgroundReason: String?,
    backgroundRevisionId: Long?,
    backgroundSchema: String?,
    foregroundPoints: Int,
    measuredPoints: Int,
    numAxles: Int,
    wheelbasesMm: List<Float>,
    totalWheelbaseMm: Float,
    frontOverhangMm: Float,
    rearOverhangMm: Float,
    axleValid: Boolean,
    hasCargoBox: Boolean,
    boxOuterLengthMm: Float,
    boxOuterWidthMm: Float,
    boxDepthMm: Float,
    boxInnerWidthMm: Float,
    overlay: VehicleMeasurementOverlay?,
): VehicleMeasurement {
    val normalizedMode = measMode.trim().lowercase()
    return VehicleMeasurement(
        lengthMm = lengthMm,
        widthMm = widthMm,
        heightMm = heightMm,
        valid = measureValid,
        complianceDetermined = complianceDetermined,
        complianceReason = complianceReason,
        compliant = compliant,
        violations = violations,
        mode = normalizedMode,
        reason = resolveMeasurementReason(
            serverReason = measureReason,
            mode = normalizedMode,
            alignMethod = alignMethod,
            valid = measureValid,
            backgroundCaptured = backgroundCaptured,
            backgroundIncompatible = backgroundIncompatible,
        ),
        backgroundCaptured = backgroundCaptured,
        backgroundSet = backgroundSet,
        backgroundCompatible = backgroundCompatible,
        backgroundIncompatible = backgroundIncompatible,
        backgroundReason = backgroundReason,
        backgroundRevisionId = backgroundRevisionId,
        backgroundSchema = backgroundSchema,
        foregroundPoints = foregroundPoints,
        measuredPoints = measuredPoints,
        axle = VehicleAxleMeasurement(
            valid = axleValid,
            numAxles = numAxles,
            wheelbasesMm = wheelbasesMm,
            totalWheelbaseMm = totalWheelbaseMm,
            frontOverhangMm = frontOverhangMm,
            rearOverhangMm = rearOverhangMm,
        ),
        cargoBox = VehicleCargoBoxMeasurement(
            hasBox = hasCargoBox,
            outerLengthMm = boxOuterLengthMm,
            outerWidthMm = boxOuterWidthMm,
            depthMm = boxDepthMm,
            innerWidthMm = boxInnerWidthMm,
        ),
        overlay = overlay,
    )
}

internal fun resolveMeasurementReason(
    serverReason: String?,
    mode: String,
    alignMethod: String,
    valid: Boolean,
    backgroundCaptured: Boolean,
    backgroundIncompatible: Boolean,
): String? {
    if (valid) return null
    serverReason?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { return it }
    if (backgroundCaptured || mode == "background_captured") return "background_captured"
    if (backgroundIncompatible || mode.startsWith("background_incompatible")) return "background_incompatible"
    if (mode == "region_missing") return "region_missing"
    if (mode == "no_isolation") return "no_isolation"
    if (mode == "background_capture_failed") return "background_capture_failed"
    if (alignMethod.equals("raw", ignoreCase = true) || mode == "raw" || mode == "unfused") return "raw"
    return "measurement_invalid"
}

private fun LaserVehicleOverlay.toDomain(): VehicleMeasurementOverlay {
    val mappedVehicle = vehicleBox.mapNotNull { it.toPoint3OrNull() }
    if (!valid || vehicleBox.size != 8 || mappedVehicle.size != 8) {
        return VehicleMeasurementOverlay(
            valid = false,
            vehicleBox = emptyList(),
            hasCargoBox = false,
            cargoBox = emptyList(),
            axleLines = emptyList(),
        )
    }
    val mappedCargo = cargoBox.mapNotNull { it.toPoint3OrNull() }
    val cargoValid = hasCargoBox && cargoBox.size == 8 && mappedCargo.size == 8
    return VehicleMeasurementOverlay(
        valid = true,
        vehicleBox = mappedVehicle,
        hasCargoBox = cargoValid,
        cargoBox = if (cargoValid) mappedCargo else emptyList(),
        axleLines = axleLines.mapNotNull { line ->
            val from = line.getOrNull(0)?.toPoint3OrNull()
            val to = line.getOrNull(1)?.toPoint3OrNull()
            if (from != null && to != null) MeasurementLine3(from, to) else null
        },
    )
}

private fun List<Float>.toPoint3OrNull(): MeasurementPoint3? {
    if (size < 3 || !this[0].isFinite() || !this[1].isFinite() || !this[2].isFinite()) return null
    return MeasurementPoint3(this[0], this[1], this[2])
}

private fun LaserMeasuredCloudArtifact.toDomain() = MeasuredCloudArtifact(
    xyzSha256 = xyzSha256,
    coordinateSchema = coordinateSchema,
    sourcePoints = sourcePoints,
    siteRevision = siteRevision.nonBlankOrNull(),
    regionRevision = regionRevision.nonBlankOrNull(),
    backgroundRevision = backgroundRevision,
    finalBToASha256 = finalBToASha256,
)

private const val MEASURED_COORDINATE_SCHEMA = "unit_a_world_mm_v1"

private fun String.isSha256Hex(): Boolean = length == 64 && all { it in '0'..'9' || it in 'a'..'f' }

private fun String?.nonBlankOrNull(): String? = this?.takeIf { it.isNotBlank() }

/** 单元云解析结果：xyz 扁平 [x,y,z,...] mm + 每点 h_angle°（融合云无角度时 angles 为空）。 */
data class CloudWithAngles(val xyz: FloatArray, val angles: FloatArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CloudWithAngles) return false
        return xyz.contentEquals(other.xyz) && angles.contentEquals(other.angles)
    }
    override fun hashCode(): Int = 31 * xyz.contentHashCode() + angles.contentHashCode()
}

/** 点云渲染数据只保存端侧样本；[pointCount] 始终表示服务端权威源点数。 */
data class LaserCloudRenderData(
    val xyz: FloatArray,
    val rgb: IntArray? = null,
    val angles: FloatArray = FloatArray(0),
    val sourcePointCount: Int = xyz.size / 3,
    val latestAngleDeg: Float? = angles.lastOrNull(),
) {
    val renderPointCount: Int get() = xyz.size / 3
    val pointCount: Int get() = sourcePointCount
    val hasColor: Boolean get() = rgb != null && rgb.size == renderPointCount
    val hasAngles: Boolean get() = angles.size == renderPointCount

    init {
        require(sourcePointCount >= renderPointCount) {
            "sourcePointCount=$sourcePointCount 小于 renderPointCount=$renderPointCount"
        }
    }

    companion object {
        val Empty = LaserCloudRenderData(FloatArray(0))
    }
}

/**
 * 解析 server 端 EncodePCDBinary{,XYZI} 产物（DATA binary，小端 float32）。与 server internal/laser/pcd.go 对偶。
 * 支持 FIELDS "x y z"、"x y z intensity"、"x y z rgb"、"x y z rgb intensity"。
 */
internal fun parsePcdBinary(data: ByteArray): FloatArray =
    parsePcdBinaryFull(ByteArrayInputStream(data), MAX_ANDROID_RENDER_POINTS).xyz

/** 同 parsePcdBinary，但额外返回每点 intensity(=h_angle°)；融合云无 intensity 时 angles 为空。 */
internal fun parsePcdBinaryWithAngles(data: ByteArray): CloudWithAngles {
    val r = parsePcdBinaryFull(ByteArrayInputStream(data), MAX_ANDROID_RENDER_POINTS)
    return CloudWithAngles(r.xyz, r.angles)
}

/** 同 parsePcdBinary，但额外返回可选每点 rgb(0xRRGGBB)。 */
internal fun parsePcdBinaryRenderData(data: ByteArray): LaserCloudRenderData {
    return parsePcdBinaryRenderData(ByteArrayInputStream(data), MAX_ANDROID_RENDER_POINTS)
}

/** 流式解析 PCD；即使响应携带全量活动快照，Java 堆也只分配 [maxRenderPoints] 个点。 */
internal fun parsePcdBinaryRenderData(
    input: InputStream,
    maxRenderPoints: Int,
    expectedSourcePointCount: Int? = null,
    expectedEncodedPointCount: Int? = null,
    expectedArtifact: MeasuredCloudArtifact? = null,
): LaserCloudRenderData {
    val r = parsePcdBinaryFull(
        input = input,
        maxRenderPoints = maxRenderPoints,
        expectedSourcePointCount = expectedSourcePointCount,
        expectedEncodedPointCount = expectedEncodedPointCount,
        expectedArtifact = expectedArtifact,
    )
    return LaserCloudRenderData(
        xyz = r.xyz,
        rgb = r.rgb,
        angles = r.angles,
        sourcePointCount = r.sourcePointCount,
    )
}

/** 把 Retrofit 响应头与 PCD 头交叉校验，防止把渲染采样点数误判为权威完整点数。 */
internal fun parseCloudResponse(
    response: Response<ResponseBody>,
    maxRenderPoints: Int,
    requirePointHeaders: Boolean,
    endpoint: String,
    expectedArtifact: MeasuredCloudArtifact? = null,
): LaserCloudRenderData {
    require(maxRenderPoints in 1..MAX_ANDROID_RENDER_POINTS) {
        "maxRenderPoints 须为 1..$MAX_ANDROID_RENDER_POINTS"
    }
    val sourcePoints = response.pointHeader(HEADER_SOURCE_POINTS)
    val renderPoints = response.pointHeader(HEADER_RENDER_POINTS)
    if (requirePointHeaders && (sourcePoints == null || renderPoints == null)) {
        throw ApiException(50001, 502, "$endpoint 缺少点云源点数响应头")
    }
    if (sourcePoints != null && renderPoints != null) {
        if (sourcePoints < renderPoints) {
            throw ApiException(50001, 502, "$endpoint 源点数 $sourcePoints 小于返回点数 $renderPoints")
        }
        val expectedRender = minOf(sourcePoints, maxRenderPoints)
        if (renderPoints != expectedRender) {
            throw ApiException(
                50001,
                502,
                "$endpoint 返回点数 $renderPoints 与预算派生值 $expectedRender 不一致",
            )
        }
    }
    expectedArtifact?.let { artifact ->
        fun requireHeader(name: String, expected: String) {
            val actual = response.headers()[name]?.trim().orEmpty()
            if (actual != expected) {
                throw ApiException(50001, 502, "$endpoint $name 与 measured 清单不一致")
            }
        }
        if (sourcePoints != artifact.sourcePoints) {
            throw ApiException(50001, 502, "$endpoint 源点数与 measured 清单不一致")
        }
        requireHeader(HEADER_COORDINATE_SCHEMA, artifact.coordinateSchema)
        requireHeader(HEADER_XYZ_SHA256, artifact.xyzSha256)
        requireHeader(HEADER_FINAL_B_TO_A_SHA256, artifact.finalBToASha256)
    }
    val body = response.requireCloudBody(endpoint)
    return body.use {
        if (it.contentLength() == 0L) throw ApiException(50001, 502, "$endpoint PCD 为空")
        parsePcdBinaryRenderData(
            input = it.byteStream(),
            maxRenderPoints = maxRenderPoints,
            expectedSourcePointCount = sourcePoints,
            expectedEncodedPointCount = renderPoints,
            expectedArtifact = expectedArtifact,
        )
    }
}

private fun Response<ResponseBody>.pointHeader(name: String): Int? {
    val raw = headers()[name] ?: return null
    return raw.toIntOrNull()?.takeIf { it >= 0 }
        ?: throw ApiException(50001, 502, "$name 响应头非法: $raw")
}

private fun Response<ResponseBody>.requireCloudBody(endpoint: String): ResponseBody {
    if (!isSuccessful) {
        throw ApiException(code(), code(), "$endpoint 点云下载失败: HTTP ${code()}")
    }
    return body() ?: throw ApiException(50001, 502, "$endpoint 点云响应为空")
}

internal const val MAX_ANDROID_RENDER_POINTS = 1_000_000
private const val MAX_PCD_ENCODED_POINTS = 10_000_000
private const val MAX_CANONICAL_POINTS = 100_000_000
private const val MAX_PCD_HEADER_LINE_BYTES = 16 * 1024
private const val HEADER_SOURCE_POINTS = "X-Gomob-Source-Points"
private const val HEADER_RENDER_POINTS = "X-Gomob-Render-Points"
private const val HEADER_COORDINATE_SCHEMA = "X-Gomob-Coordinate-Schema"
private const val HEADER_XYZ_SHA256 = "X-Gomob-XYZ-SHA256"
private const val HEADER_FINAL_B_TO_A_SHA256 = "X-Gomob-Final-B-To-A-SHA256"

private data class ParsedPcdCloud(
    val xyz: FloatArray,
    val angles: FloatArray,
    val rgb: IntArray?,
    val sourcePointCount: Int,
    val coordinateSchema: String,
    val xyzSha256: String,
    val finalBToASha256: String,
)

private fun parsePcdBinaryFull(
    input: InputStream,
    maxRenderPoints: Int,
    expectedSourcePointCount: Int? = null,
    expectedEncodedPointCount: Int? = null,
    expectedArtifact: MeasuredCloudArtifact? = null,
): ParsedPcdCloud {
    require(maxRenderPoints in 1..MAX_ANDROID_RENDER_POINTS) {
        "maxRenderPoints 须为 1..$MAX_ANDROID_RENDER_POINTS"
    }
    val source = if (input is BufferedInputStream) input else BufferedInputStream(input, 64 * 1024)
    var points = -1
    var fields = ""
    var dataMode = ""
    var sourcePoints = -1
    var sizes = ""
    var types = ""
    var counts = ""
    var coordinateSchema = ""
    var xyzSha256 = ""
    var finalBToASha256 = ""
    while (dataMode.isEmpty()) {
        val line = readAsciiLine(source)
        when {
            line.startsWith("# GOMOB_SOURCE_POINTS") ->
                sourcePoints = line.removePrefix("# GOMOB_SOURCE_POINTS").trim().toInt()
            line.startsWith("# GOMOB_COORDINATE_SCHEMA") ->
                coordinateSchema = line.removePrefix("# GOMOB_COORDINATE_SCHEMA").trim()
            line.startsWith("# GOMOB_XYZ_SHA256") ->
                xyzSha256 = line.removePrefix("# GOMOB_XYZ_SHA256").trim()
            line.startsWith("# GOMOB_FINAL_B_TO_A_SHA256") ->
                finalBToASha256 = line.removePrefix("# GOMOB_FINAL_B_TO_A_SHA256").trim()
            line.startsWith("FIELDS") -> fields = line.removePrefix("FIELDS").trim()
            line.startsWith("SIZE") -> sizes = line.removePrefix("SIZE").trim()
            line.startsWith("TYPE") -> types = line.removePrefix("TYPE").trim()
            line.startsWith("COUNT") -> counts = line.removePrefix("COUNT").trim()
            line.startsWith("POINTS") -> points = line.removePrefix("POINTS").trim().toInt()
            line.startsWith("DATA") -> dataMode = line.removePrefix("DATA").trim()
        }
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
    require(points <= MAX_PCD_ENCODED_POINTS) {
        "POINTS=$points 超上界 $MAX_PCD_ENCODED_POINTS"
    }
    if (sourcePoints < 0) sourcePoints = expectedSourcePointCount ?: points
    expectedSourcePointCount?.let {
        require(sourcePoints == it) { "PCD 源点数 $sourcePoints 与响应头 $it 不一致" }
    }
    expectedEncodedPointCount?.let {
        require(points == it) { "PCD 返回点数 $points 与响应头 $it 不一致" }
    }
    require(sourcePoints in points..MAX_CANONICAL_POINTS) {
        "源点数 $sourcePoints 不在 [$points,$MAX_CANONICAL_POINTS]"
    }
    expectedArtifact?.let { artifact ->
        require(fieldNames == listOf("x", "y", "z")) { "measured PCD 必须为 FIELDS x y z" }
        require(sizes == "4 4 4" && types == "F F F" && counts == "1 1 1") {
            "measured PCD SIZE/TYPE/COUNT 非 canonical XYZ float32"
        }
        require(coordinateSchema == artifact.coordinateSchema) { "PCD coordinate schema 与 measured 清单不一致" }
        require(xyzSha256 == artifact.xyzSha256) { "PCD XYZ SHA-256 与 measured 清单不一致" }
        require(finalBToASha256 == artifact.finalBToASha256) { "PCD B→A SHA-256 与 measured 清单不一致" }
    }
    val renderPoints = minOf(points, maxRenderPoints)
    val xyz = FloatArray(renderPoints * 3)
    val angles = if (intensityIndex >= 0) FloatArray(renderPoints) else FloatArray(0)
    val rgb = if (rgbIndex >= 0) IntArray(renderPoints) else null
    val pointBytes = ByteArray(fieldNames.size * 4)
    var out = 0
    var selected = if (renderPoints > 0) stratifiedPointIndex(0, renderPoints, points) else -1
    for (i in 0 until points) {
        readFully(source, pointBytes)
        if (i != selected) continue
        var x = 0f
        var y = 0f
        var z = 0f
        var h = 0f
        var c = 0
        for ((fieldIndex, f) in fieldNames.withIndex()) {
            val raw = littleEndianInt(pointBytes, fieldIndex * 4)
            when (f) {
                "x" -> x = java.lang.Float.intBitsToFloat(raw)
                "y" -> y = java.lang.Float.intBitsToFloat(raw)
                "z" -> z = java.lang.Float.intBitsToFloat(raw)
                "intensity" -> h = java.lang.Float.intBitsToFloat(raw)
                "rgb" -> c = raw and 0x00ff_ffff
            }
        }
        xyz[3 * out] = x
        xyz[3 * out + 1] = y
        xyz[3 * out + 2] = z
        require(x.isFinite() && y.isFinite() && z.isFinite()) { "PCD 含非有限 XYZ 坐标" }
        if (intensityIndex >= 0) angles[out] = h
        if (rgb != null) rgb[out] = c
        out++
        selected = if (out < renderPoints) stratifiedPointIndex(out, renderPoints, points) else -1
    }
    require(out == renderPoints) { "PCD 采样输出 $out/$renderPoints 不完整" }
    require(source.read() < 0) { "PCD 二进制主体存在多余字节" }
    return ParsedPcdCloud(xyz, angles, rgb, sourcePoints, coordinateSchema, xyzSha256, finalBToASha256)
}

private fun readAsciiLine(input: InputStream): String {
    val out = ByteArrayOutputStream(128)
    while (out.size() <= MAX_PCD_HEADER_LINE_BYTES) {
        val value = input.read()
        if (value < 0) throw EOFException("PCD 头未见 DATA")
        if (value == '\n'.code) return out.toString(Charsets.US_ASCII.name()).trimEnd('\r')
        out.write(value)
    }
    throw IllegalArgumentException("PCD 头单行超过 $MAX_PCD_HEADER_LINE_BYTES 字节")
}

private fun readFully(input: InputStream, target: ByteArray) {
    var offset = 0
    while (offset < target.size) {
        val read = input.read(target, offset, target.size - offset)
        if (read < 0) throw EOFException("PCD 二进制主体不足")
        offset += read
    }
}

private fun littleEndianInt(data: ByteArray, offset: Int): Int =
    (data[offset].toInt() and 0xff) or
        ((data[offset + 1].toInt() and 0xff) shl 8) or
        ((data[offset + 2].toInt() and 0xff) shl 16) or
        ((data[offset + 3].toInt() and 0xff) shl 24)

private fun stratifiedPointIndex(outputIndex: Int, outputCount: Int, sourceCount: Int): Int {
    if (outputCount == sourceCount) return outputIndex
    val start = (outputIndex.toLong() * sourceCount / outputCount).toInt()
    val end = ((outputIndex + 1L) * sourceCount / outputCount).toInt()
    val width = end - start
    if (width <= 1) return start
    var seed = (outputIndex + 1L) xor (sourceCount.toLong() shl 32) xor outputCount.toLong()
    seed += -7046029254386353131L
    seed = (seed xor (seed ushr 30)) * -4658895280553007687L
    seed = (seed xor (seed ushr 27)) * -7723592293110705685L
    seed = seed xor (seed ushr 31)
    return start + java.lang.Long.remainderUnsigned(seed, width.toLong()).toInt()
}
