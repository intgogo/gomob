package io.gomob.network

import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

/**
 * 端侧日志同步接口。把 logcat 抓的批量日志 POST 到服务端 /v1/logs/upload，
 * 服务端按 user_id + 日期落 jsonl，开发期 ./scripts/tail-user-logs.sh <user_id>
 * tail -f 看实时。
 *
 * 走 AuthInterceptor 自动带 Bearer token；服务端从 X-Gomob-User-Id (gateway 注入)
 * 知道是哪个用户的日志。
 */
interface LogsApi {
    @POST("v1/logs/upload")
    suspend fun upload(@Body req: LogUploadRequest): Envelope<LogUploadResponse>
}

@Serializable
data class LogUploadRequest(
    val entries: List<LogEntryDto>,
)

@Serializable
data class LogEntryDto(
    /** 端侧观测时间戳（毫秒 Unix epoch） */
    val ts_ms: Long,
    /** V/D/I/W/E/F；从 logcat -v time 解析的级别 */
    val level: String,
    /** logcat tag，如 "gomob_native"、"Scan3dRecordingVM"、"BerxelService" */
    val tag: String,
    /** 单行消息 */
    val msg: String,
    /** 设备 SN（可选，便于区分多机） */
    val device_serial: String? = null,
)

@Serializable
data class LogUploadResponse(
    val accepted: Int,
)
