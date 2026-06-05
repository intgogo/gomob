package io.gomob.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming

/**
 * 激光双单元车辆外廓扫描网络接口（M8'，经 gateway 反代 laserworker :18087）。
 *
 * 请求驱动：POST 起一次扫描即返回（capturing），采集中的增量点经 /v1/ws 的 laser.points 实时帧推送，
 * 完成事件 scan.fusion_done(kind=laser) 同经 ws；最终三朵 PCD 经 [downloadCloud] 流式取回。
 * 契约真理源：server internal/laser/handler.go。
 */
interface LaserScanApi {
    @POST("v1/scans/laser")
    suspend fun start(@Body req: LaserScanStartRequest): LaserScanStartResponse

    @POST("v1/scans/laser/{id}/stop")
    suspend fun stop(@Path("id") scanId: Long): LaserScanStatusResponse

    @GET("v1/scans/laser/{id}")
    suspend fun status(@Path("id") scanId: Long): LaserScanStatusResponse

    /** 流式下载一朵 PCD。name ∈ fused|unit_a|unit_b。 */
    @Streaming
    @GET("v1/scans/laser/{id}/cloud/{name}")
    suspend fun downloadCloud(
        @Path("id") scanId: Long,
        @Path("name") name: String,
    ): ResponseBody

    // --- 持久车位框（M9.11，契约 handler.go crop-box / crop-preview 端点）---

    /** 取当前装机点的车位框（未设置 set=false）。 */
    @GET("v1/scans/laser/crop-box")
    suspend fun getCropBox(): LaserCropBoxResponse

    /** 保存/覆盖车位框（服务端持久化，非设备写）。 */
    @PUT("v1/scans/laser/crop-box")
    suspend fun putCropBox(@Body box: LaserCropBox): LaserDeviceOkResponse

    /** 用候选框裁某次扫描的融合云并测量，供拖框实时预览（不落库）。 */
    @POST("v1/scans/laser/{id}/crop-preview")
    suspend fun cropPreview(
        @Path("id") scanId: Long,
        @Body box: LaserCropBox,
    ): LaserCropPreviewResponse

    // --- 设备控制面板（原厂功能键，unit=a|b 直打单元 :4000，契约 handler.go device-* 端点）---

    /** 实时状态（状态机/角度/温度/错误位/子系统在线）。 */
    @GET("v1/scans/laser/device-status")
    suspend fun deviceStatus(@Query("unit") unit: String): LaserDeviceStatus

    /** 型号/SN/固件/规格 + 当前扫描设置 + 当前标定。 */
    @GET("v1/scans/laser/device-info")
    suspend fun deviceInfo(@Query("unit") unit: String): LaserDeviceInfo

    /** 直接设备命令：ALIGN_ZERO|SCAN_WATCH|SCAN_STOP|CLEAR_ERROR|SOFT_REBOOT。 */
    @POST("v1/scans/laser/device-command")
    suspend fun deviceCommand(
        @Query("unit") unit: String,
        @Body req: LaserDeviceCommandRequest,
    ): LaserDeviceOkResponse

    /** 下发扫描运动设置。 */
    @POST("v1/scans/laser/device-scan-settings")
    suspend fun deviceScanSettings(
        @Query("unit") unit: String,
        @Body settings: LaserControlSettings,
    ): LaserDeviceOkResponse

    /** 下发标定参数（破坏性：覆写设备存储标定）。 */
    @POST("v1/scans/laser/device-calib")
    suspend fun deviceCalib(
        @Query("unit") unit: String,
        @Body calib: LaserCalibParams,
    ): LaserDeviceOkResponse
}

// --- 设备控制 DTO（镜像 server internal/laser devctl.go 的 JSON 形状）---

@Serializable
data class LaserDeviceStatus(
    val ip: String = "",
    val online: Boolean = false,
    val state: String = "",
    @SerialName("scan_msg") val scanMsg: String = "",
    val uptime: Double = 0.0,
    @SerialName("encoder_online") val encoderOnline: Boolean = false,
    @SerialName("lidar_online") val lidarOnline: Boolean = false,
    @SerialName("camera_online") val cameraOnline: Boolean = false,
    @SerialName("control_online") val controlOnline: Boolean = false,
    @SerialName("latest_angle") val latestAngle: Double = 0.0,
    @SerialName("zero_degs") val zeroDegs: Double = 0.0,
    @SerialName("angle_degs") val angleDegs: Double = 0.0,
    @SerialName("error_code") val errorCode: Long = 0,
    val tempre: Double = 0.0,
)

@Serializable
data class LaserDeviceInfo(
    val model: String = "",
    val sn: String = "",
    val hwver: String = "",
    val swver: String = "",
    @SerialName("network_type") val networkType: String = "",
    val network: String = "",
    @SerialName("lidar_model") val lidarModel: String = "",
    @SerialName("camera_model") val cameraModel: String = "",
    @SerialName("encoder_resolution") val encoderResolution: Int = 0,
    @SerialName("lidar_port") val lidarPort: Int = 0,
    @SerialName("lidar_valid_zone") val lidarValidZone: List<Double> = emptyList(),
    @SerialName("camera_width") val cameraWidth: Int = 0,
    @SerialName("camera_height") val cameraHeight: Int = 0,
    @SerialName("camera_capture_fps") val cameraCaptureFps: Double = 0.0,
    val control: LaserControlSettings = LaserControlSettings(),
    val calib: LaserCalibParams = LaserCalibParams(),
)

/** 扫描运动参数（device-info.control 读 / device-scan-settings 写，双向同型）。 */
@Serializable
data class LaserControlSettings(
    @SerialName("scan_speed") val scanSpeed: Double = 0.0,
    @SerialName("zero_speed") val zeroSpeed: Double = 0.0,
    @SerialName("scan_start_angle") val scanStartAngle: Double = 0.0,
    @SerialName("scan_stop_angle") val scanStopAngle: Double = 0.0,
    @SerialName("watching_angle") val watchingAngle: Double = 0.0,
    @SerialName("lidar_filter_ghost") val lidarFilterGhost: Double = 0.0,
    @SerialName("lidar_filter_zone") val lidarFilterZone: List<Double> = listOf(0.0, 0.0),
    @SerialName("camera_fps") val cameraFps: Double = 0.0,
)

@Serializable
data class LaserCalibParams(
    val lidar: LaserLidarCalib = LaserLidarCalib(),
    val camera: LaserCameraCalib = LaserCameraCalib(),
    val body2world: LaserBody2World = LaserBody2World(),
)

@Serializable
data class LaserLidarCalib(
    @SerialName("lidar_rot_quat") val rotQuat: List<Double> = listOf(1.0, 0.0, 0.0, 0.0),
    @SerialName("lidar_corr_quat") val corrQuat: List<Double> = listOf(1.0, 0.0, 0.0, 0.0),
    @SerialName("lidar_corr_offset") val corrOffset: List<Double> = listOf(0.0, 0.0, 0.0),
)

@Serializable
data class LaserCameraCalib(
    @SerialName("camera_rot_quat") val rotQuat: List<Double> = listOf(1.0, 0.0, 0.0, 0.0),
    @SerialName("camera_corr_quat") val corrQuat: List<Double> = listOf(1.0, 0.0, 0.0, 0.0),
    @SerialName("camera_corr_offset") val corrOffset: List<Double> = listOf(0.0, 0.0, 0.0),
    @SerialName("camera_intrinsic") val intrinsic: List<Double> = listOf(0.0, 0.0, 0.0, 0.0),
    @SerialName("camera_distortion") val distortion: List<Double> = listOf(0.0, 0.0, 0.0, 0.0, 0.0),
)

@Serializable
data class LaserBody2World(
    @SerialName("b2w_quat") val quat: List<Double> = listOf(1.0, 0.0, 0.0, 0.0),
    @SerialName("b2w_offset") val offset: List<Double> = listOf(0.0, 0.0, 0.0),
    @SerialName("b2w_scale") val scale: Double = 1.0,
)

@Serializable
data class LaserDeviceCommandRequest(val cmd: String)

@Serializable
data class LaserDeviceOkResponse(
    val ok: Boolean = false,
    @SerialName("unit_ip") val unitIp: String = "",
    val cmd: String = "",
)

@Serializable
data class LaserScanStartRequest(
    @SerialName("inspection_id") val inspectionId: Long? = null,
    @SerialName("unit_a_ip") val unitAIp: String? = null,
    @SerialName("unit_b_ip") val unitBIp: String? = null,
    // 默认 none(纯 union)：ICP 跨单元配准对固定双机位不稳，无强共同结构(空场/少特征)即发散，
    // 把 B 甩出数十米。固定基线的正解是 site 标定外参(待 laserworker 接线 SiteJSON);在此之前
    // none 给有界可渲染的 union 结果。
    val align: String = "none", // icp|none|site
    @SerialName("keep_ratio") val keepRatio: Float? = null,
    // 车型编号（逆向 JCHY 26 型，docs/16 §4.1）：服务端据此套 carType 偏移 + 按型合规 + 落库记录。
    @SerialName("vehicle_type_id") val vehicleTypeId: Int? = null,
)

@Serializable
data class LaserScanStartResponse(
    @SerialName("scan_id") val scanId: Long,
    @SerialName("session_key") val sessionKey: String,
    val status: String,
)

// --- 持久车位框 DTO（镜像 server internal/laser CropBox + handler crop-box/preview 响应）---

/** 世界系定向裁剪框(OBB)。center/half 单位 mm；up 朝上单位向量；yawDeg 绕 up 旋转(车头朝向)。 */
@Serializable
data class LaserCropBox(
    val center: List<Float> = listOf(0f, 0f, 0f),
    val up: List<Float> = listOf(0f, 0f, 1f),
    @SerialName("yaw_deg") val yawDeg: Float = 0f,
    val half: List<Float> = listOf(0f, 0f, 0f), // [右半宽, 前半长, 上半高]
)

@Serializable
data class LaserCropBoxResponse(
    @SerialName("bay_key") val bayKey: String = "",
    val set: Boolean = false,
    val box: LaserCropBox? = null,
)

/** crop-preview 响应：框内点数 + 框内测量。 */
@Serializable
data class LaserCropPreviewResponse(
    @SerialName("total_points") val totalPoints: Int = 0,
    @SerialName("in_points") val inPoints: Int = 0,
    val measurement: LaserMeasurement = LaserMeasurement(),
)

/** 框内/通用测量结果（镜像 server measure.Dimensions JSON）。 */
@Serializable
data class LaserMeasurement(
    @SerialName("length_mm") val lengthMm: Float = 0f,
    @SerialName("width_mm") val widthMm: Float = 0f,
    @SerialName("height_mm") val heightMm: Float = 0f,
    @SerialName("obb_angle_deg") val obbAngleDeg: Float = 0f,
    @SerialName("raw_pts") val rawPts: Int = 0,
    @SerialName("roi_pts") val roiPts: Int = 0,
    @SerialName("body_pts") val bodyPts: Int = 0,
    @SerialName("body_ratio") val bodyRatio: Float = 0f,
    val valid: Boolean = false,
)

/** GET 状态 / stop 的统一视图（字段随状态机渐次出现，未就绪为 null）。对齐 handler.go jobView。 */
@Serializable
data class LaserScanStatusResponse(
    @SerialName("scan_id") val scanId: Long,
    @SerialName("session_key") val sessionKey: String? = null,
    val status: String,
    val align: String? = null,
    @SerialName("align_method") val alignMethod: String? = null,
    val points: Int? = null,
    @SerialName("pts_a") val ptsA: Int? = null,
    @SerialName("pts_b") val ptsB: Int? = null,
    @SerialName("result_object_key") val resultObjectKey: String? = null,
    @SerialName("unit_a_object_key") val unitAObjectKey: String? = null,
    @SerialName("unit_b_object_key") val unitBObjectKey: String? = null,
    val error: String? = null,
)
