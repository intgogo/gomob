package io.gomob.realtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealtimeEnvelopeParser @Inject constructor(
    private val json: Json,
) {
    fun parse(text: String): RealtimeEnvelope = json.decodeFromString(RealtimeEnvelope.serializer(), text)

    fun encode(envelope: RealtimeEnvelope): String = json.encodeToString(RealtimeEnvelope.serializer(), envelope)

    fun toEvent(envelope: RealtimeEnvelope): RealtimeEvent = when (envelope.type) {
        "hello" -> envelope.payload?.decode<HelloPayload>()?.let {
            RealtimeEvent.Hello(
                userId = it.userId,
                role = it.role,
                serverTs = it.serverTs,
            )
        } ?: RealtimeEvent.Unknown(envelope)
        "msg.delivered" -> envelope.payload?.decode<DeliveredPayload>()?.let {
            RealtimeEvent.MessageDelivered(
                clientMsgId = it.clientMsgId,
                conversationId = it.conversationId,
                serverSeq = it.serverSeq,
                messageId = it.messageId,
                createdAt = it.createdAt,
            )
        } ?: RealtimeEvent.Unknown(envelope)
        "msg.recv" -> envelope.payload?.decode<RecvPayload>()?.let {
            RealtimeEvent.MessageReceived(
                messageId = it.messageId,
                conversationId = it.conversationId,
                serverSeq = it.serverSeq,
                senderId = it.senderId,
                kind = it.kind,
                content = it.content,
                clientMsgId = it.clientMsgId,
                createdAt = it.createdAt,
            )
        } ?: RealtimeEvent.Unknown(envelope)
        "msg.transcript.updated" -> envelope.payload?.decode<TranscriptUpdatedPayload>()?.let {
            RealtimeEvent.TranscriptUpdated(
                messageId = it.messageId,
                conversationId = it.conversationId,
                serverSeq = it.serverSeq,
                kind = it.kind,
                content = it.content,
                updatedAt = it.updatedAt,
            )
        } ?: RealtimeEvent.Unknown(envelope)
        "msg.recall" -> envelope.payload?.decode<RecallPayload>()?.let {
            RealtimeEvent.MessageRecalled(
                messageId = it.messageId,
                conversationId = it.conversationId,
                serverSeq = it.serverSeq,
                recalledBy = it.recalledBy,
                deletedAt = it.deletedAt,
            )
        } ?: RealtimeEvent.Unknown(envelope)
        "scan.fusion_done" -> envelope.payload?.decode<FusionDonePayload>()?.let {
            // 同 topic 按 kind 区分激光/RGBD（M8'）。
            if (it.kind == "laser") {
                RealtimeEvent.LaserScanDone(
                    jobId = it.jobId,
                    sessionKey = it.sessionKey,
                    fusedObjectKey = it.resultObjectKey,
                    unitAObjectKey = it.unitAObjectKey ?: "",
                    unitBObjectKey = it.unitBObjectKey ?: "",
                    measuredObjectKey = it.measuredObjectKey,
                    measuredArtifact = it.measuredArtifact,
                    points = it.points,
                    ptsA = it.ptsA,
                    ptsB = it.ptsB,
                    alignMethod = it.alignMethod ?: "",
                    siteRevision = it.siteRevision,
                    regionRevision = it.regionRevision,
                    siteQualityVerified = it.siteQualityVerified,
                    siteQualityOverride = it.siteQualityOverride,
                    productionEligible = it.productionEligible,
                    lengthMm = it.lengthMm,
                    widthMm = it.widthMm,
                    heightMm = it.heightMm,
                    measureValid = it.measureValid,
                    complianceDetermined = it.complianceDetermined,
                    complianceReason = it.complianceReason,
                    compliant = it.compliant,
                    violations = it.violations,
                    measMode = it.measMode,
                    measureReason = it.measureReason ?: it.legacyMeasureReason,
                    backgroundCaptured = it.backgroundCaptured,
                    backgroundSet = it.backgroundSet,
                    backgroundCompatible = it.backgroundCompatible,
                    backgroundReason = it.backgroundReason,
                    backgroundRevisionId = it.backgroundRevisionId,
                    backgroundSchema = it.backgroundSchema,
                    foregroundPoints = it.foregroundPoints,
                    measuredPoints = it.measuredPoints,
                    numAxles = it.numAxles,
                    wheelbasesMm = it.wheelbasesMm,
                    totalWheelbaseMm = it.totalWheelbaseMm,
                    frontOverhangMm = it.frontOverhangMm,
                    rearOverhangMm = it.rearOverhangMm,
                    axleValid = it.axleValid,
                    hasCargoBox = it.hasCargoBox,
                    boxOuterLengthMm = it.boxOuterLengthMm,
                    boxOuterWidthMm = it.boxOuterWidthMm,
                    boxDepthMm = it.boxDepthMm,
                    boxInnerWidthMm = it.boxInnerWidthMm,
                    overlay = it.overlay,
                    groundNx = it.groundNx,
                    groundNy = it.groundNy,
                    groundNz = it.groundNz,
                    groundD = it.groundD,
                    groundValid = it.groundValid,
                )
            } else {
                RealtimeEvent.ScanFusionDone(
                    jobId = it.jobId,
                    sessionKey = it.sessionKey,
                    resultObjectKey = it.resultObjectKey,
                    vertices = it.vertices,
                    triangles = it.triangles,
                    frameCount = it.frameCount,
                )
            }
        } ?: RealtimeEvent.Unknown(envelope)
        "laser.points" -> envelope.payload?.decode<LaserPointsPayload>()?.let {
            RealtimeEvent.LaserPoints(
                sessionKey = it.sessionKey,
                unit = it.unit,
                points = it.points,
                hAngleDeg = it.hAngleDeg,
                sourcePointCount = it.sourcePointCount,
            )
        } ?: RealtimeEvent.Unknown(envelope)
        "laser.status" -> envelope.payload?.decode<LaserStatusPayload>()?.let {
            RealtimeEvent.LaserStatus(
                sessionKey = it.sessionKey,
                state = it.state,
                framesA = it.framesA,
                framesB = it.framesB,
                sourcePointsA = it.sourcePointsA,
                sourcePointsB = it.sourcePointsB,
            )
        } ?: RealtimeEvent.Unknown(envelope)
        "error" -> RealtimeEvent.Error(
            code = envelope.code ?: 50001,
            message = envelope.message ?: "实时通道错误",
            inReplyTo = envelope.payload?.decode<ErrorPayload>()?.inReplyTo,
        )
        else -> RealtimeEvent.Unknown(envelope)
    }

    private inline fun <reified T> JsonElement.decode(): T = json.decodeFromJsonElement(this)
}

@Serializable
private data class HelloPayload(
    @SerialName("user_id") val userId: Long,
    val role: String,
    @SerialName("server_ts") val serverTs: Long,
)

@Serializable
private data class DeliveredPayload(
    @SerialName("client_msg_id") val clientMsgId: String? = null,
    @SerialName("conversation_id") val conversationId: Long,
    @SerialName("server_seq") val serverSeq: Long,
    @SerialName("message_id") val messageId: Long,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class RecvPayload(
    @SerialName("message_id") val messageId: Long? = null,
    @SerialName("conversation_id") val conversationId: Long,
    @SerialName("server_seq") val serverSeq: Long,
    @SerialName("sender_id") val senderId: Long,
    val kind: String,
    val content: JsonElement? = null,
    @SerialName("client_msg_id") val clientMsgId: String? = null,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
private data class TranscriptUpdatedPayload(
    @SerialName("message_id") val messageId: Long,
    @SerialName("conversation_id") val conversationId: Long,
    @SerialName("server_seq") val serverSeq: Long,
    val kind: String,
    val content: JsonElement? = null,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
private data class RecallPayload(
    @SerialName("message_id") val messageId: Long,
    @SerialName("conversation_id") val conversationId: Long,
    @SerialName("server_seq") val serverSeq: Long,
    @SerialName("recalled_by") val recalledBy: Long,
    @SerialName("deleted_at") val deletedAt: String,
)

@Serializable
private data class ErrorPayload(
    @SerialName("in_reply_to") val inReplyTo: String? = null,
)

@Serializable
private data class FusionDonePayload(
    @SerialName("job_id") val jobId: Long? = null,
    @SerialName("session_key") val sessionKey: String,
    @SerialName("result_object_key") val resultObjectKey: String,
    val vertices: Int = 0,
    val triangles: Int = 0,
    @SerialName("frame_count") val frameCount: Int = 0,
    // --- M8' 激光扩展字段（kind=laser 时填，RGBD 缺省）---
    val kind: String = "",
    @SerialName("unit_a_object_key") val unitAObjectKey: String? = null,
    @SerialName("unit_b_object_key") val unitBObjectKey: String? = null,
    @SerialName("measured_object_key") val measuredObjectKey: String? = null,
    @SerialName("measured_artifact") val measuredArtifact: io.gomob.network.LaserMeasuredCloudArtifact? = null,
    val points: Int = 0,
    @SerialName("pts_a") val ptsA: Int = 0,
    @SerialName("pts_b") val ptsB: Int = 0,
    @SerialName("align_method") val alignMethod: String? = null,
    @SerialName("site_revision") val siteRevision: String? = null,
    @SerialName("region_revision") val regionRevision: String? = null,
    @SerialName("site_quality_verified") val siteQualityVerified: Boolean = false,
    @SerialName("site_quality_override") val siteQualityOverride: Boolean = false,
    @SerialName("production_eligible") val productionEligible: Boolean = false,
    // --- M9.6 测量 + 合规（kind=laser 时填）---
    @SerialName("length_mm") val lengthMm: Float = 0f,
    @SerialName("width_mm") val widthMm: Float = 0f,
    @SerialName("height_mm") val heightMm: Float = 0f,
    @SerialName("measure_valid") val measureValid: Boolean = false,
    @SerialName("compliance_determined") val complianceDetermined: Boolean = false,
    @SerialName("compliance_reason") val complianceReason: String? = null,
    val compliant: Boolean = false,
    val violations: List<String> = emptyList(),
    @SerialName("meas_mode") val measMode: String = "",
    @SerialName("measure_reason") val measureReason: String? = null,
    @SerialName("meas_reason") val legacyMeasureReason: String? = null,
    @SerialName("background_captured") val backgroundCaptured: Boolean = false,
    @SerialName("background_set") val backgroundSet: Boolean = false,
    @SerialName("background_compatible") val backgroundCompatible: Boolean? = null,
    @SerialName("background_reason") val backgroundReason: String? = null,
    @SerialName("background_revision_id") val backgroundRevisionId: Long? = null,
    @SerialName("background_schema") val backgroundSchema: String? = null,
    @SerialName("fg_points") val foregroundPoints: Int = 0,
    @SerialName("measured_points") val measuredPoints: Int = 0,
    @SerialName("num_axles") val numAxles: Int = 0,
    @SerialName("wheelbases_mm") val wheelbasesMm: List<Float> = emptyList(),
    @SerialName("total_wheelbase_mm") val totalWheelbaseMm: Float = 0f,
    @SerialName("front_overhang_mm") val frontOverhangMm: Float = 0f,
    @SerialName("rear_overhang_mm") val rearOverhangMm: Float = 0f,
    @SerialName("axle_valid") val axleValid: Boolean = false,
    @SerialName("has_cargo_box") val hasCargoBox: Boolean = false,
    @SerialName("box_outer_length_mm") val boxOuterLengthMm: Float = 0f,
    @SerialName("box_outer_width_mm") val boxOuterWidthMm: Float = 0f,
    @SerialName("box_depth_mm") val boxDepthMm: Float = 0f,
    @SerialName("box_inner_width_mm") val boxInnerWidthMm: Float = 0f,
    val overlay: io.gomob.network.LaserVehicleOverlay? = null,
    // 地面平面（端侧视角预设的"上"方向；nx*x+ny*y+nz*z+d=0）。
    @SerialName("ground_nx") val groundNx: Float = 0f,
    @SerialName("ground_ny") val groundNy: Float = 0f,
    @SerialName("ground_nz") val groundNz: Float = 0f,
    @SerialName("ground_d") val groundD: Float = 0f,
    @SerialName("ground_valid") val groundValid: Boolean = false,
)

@Serializable
private data class LaserPointsPayload(
    @SerialName("session_key") val sessionKey: String,
    val unit: Int = 0,
    val points: FloatArray = FloatArray(0),
    @SerialName("h_angle_deg") val hAngleDeg: Float = 0f,
    @SerialName("source_points") val sourcePointCount: Int? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LaserPointsPayload) return false
        return sessionKey == other.sessionKey && unit == other.unit &&
            hAngleDeg == other.hAngleDeg && sourcePointCount == other.sourcePointCount &&
            points.contentEquals(other.points)
    }
    override fun hashCode(): Int {
        var r = sessionKey.hashCode()
        r = 31 * r + unit
        r = 31 * r + hAngleDeg.hashCode()
        r = 31 * r + (sourcePointCount ?: 0)
        r = 31 * r + points.contentHashCode()
        return r
    }
}

@Serializable
private data class LaserStatusPayload(
    @SerialName("session_key") val sessionKey: String,
    val state: String = "",
    @SerialName("frames_a") val framesA: Int = 0,
    @SerialName("frames_b") val framesB: Int = 0,
    @SerialName("source_points_a") val sourcePointsA: Int? = null,
    @SerialName("source_points_b") val sourcePointsB: Int? = null,
)
