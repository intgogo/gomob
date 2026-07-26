package io.gomob.realtime

import io.gomob.network.LaserVehicleOverlay
import io.gomob.network.LaserMeasuredCloudArtifact
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class RealtimeEnvelope(
    val type: String,
    val payload: JsonElement? = null,
    @SerialName("frame_seq") val frameSeq: Long? = null,
    val code: Int? = null,
    val message: String? = null,
)

sealed interface RealtimeEvent {
    data class Hello(val userId: Long, val role: String, val serverTs: Long) : RealtimeEvent
    data class MessageDelivered(
        val clientMsgId: String?,
        val conversationId: Long,
        val serverSeq: Long,
        val messageId: Long,
        val createdAt: String,
    ) : RealtimeEvent
    data class MessageReceived(
        val messageId: Long?,
        val conversationId: Long,
        val serverSeq: Long,
        val senderId: Long,
        val kind: String,
        val content: JsonElement?,
        val clientMsgId: String?,
        val createdAt: String,
    ) : RealtimeEvent
    data class TranscriptUpdated(
        val messageId: Long,
        val conversationId: Long,
        val serverSeq: Long,
        val kind: String,
        val content: JsonElement?,
        val updatedAt: String,
    ) : RealtimeEvent
    data class MessageRecalled(
        val messageId: Long,
        val conversationId: Long,
        val serverSeq: Long,
        val recalledBy: Long,
        val deletedAt: String,
    ) : RealtimeEvent
    /** 云端多视角融合完成（scan.fusion_done）。端侧据 [sessionKey] 关联本次扫描，拉 [resultObjectKey] 的 GLB 回看。 */
    data class ScanFusionDone(
        val jobId: Long?,
        val sessionKey: String,
        val resultObjectKey: String,
        val vertices: Int,
        val triangles: Int,
        val frameCount: Int,
    ) : RealtimeEvent
    /**
     * 激光双单元扫描融合完成（scan.fusion_done，kind=laser，M8'）。与 RGBD 共 topic、按 kind 区分。
     * 三朵 PCD 经 [fusedObjectKey]/[unitAObjectKey]/[unitBObjectKey] 走 laserworker 下载端点取回。
     */
    data class LaserScanDone(
        val jobId: Long?,
        val sessionKey: String,
        val fusedObjectKey: String,
        val unitAObjectKey: String,
        val unitBObjectKey: String,
        val points: Int,
        val ptsA: Int,
        val ptsB: Int,
        val alignMethod: String,
        val measuredObjectKey: String? = null,
        val measuredArtifact: LaserMeasuredCloudArtifact? = null,
        val siteRevision: String? = null,
        val regionRevision: String? = null,
        val siteQualityVerified: Boolean = false,
        val siteQualityOverride: Boolean = false,
        val productionEligible: Boolean = false,
        // 融合后外廓测量 + GB7258 合规（M9.6，服务端 measure.go 推来；mm）。
        val lengthMm: Float,
        val widthMm: Float,
        val heightMm: Float,
        val measureValid: Boolean,
        val complianceDetermined: Boolean = false,
        val complianceReason: String? = null,
        val compliant: Boolean,
        val violations: List<String>,
        // 测量输入域与不可用原因；端侧只展示服务端结论，不重算外廓。
        val measMode: String = "",
        val measureReason: String? = null,
        val backgroundCaptured: Boolean = false,
        val backgroundSet: Boolean = false,
        val backgroundCompatible: Boolean? = null,
        val backgroundReason: String? = null,
        val backgroundRevisionId: Long? = null,
        val backgroundSchema: String? = null,
        val foregroundPoints: Int = 0,
        val measuredPoints: Int = 0,
        // 轴距 / 前后悬。
        val numAxles: Int = 0,
        val wheelbasesMm: List<Float> = emptyList(),
        val totalWheelbaseMm: Float = 0f,
        val frontOverhangMm: Float = 0f,
        val rearOverhangMm: Float = 0f,
        val axleValid: Boolean = false,
        // 货箱。
        val hasCargoBox: Boolean = false,
        val boxOuterLengthMm: Float = 0f,
        val boxOuterWidthMm: Float = 0f,
        val boxDepthMm: Float = 0f,
        val boxInnerWidthMm: Float = 0f,
        // 与测量同源的世界系车体框 / 货箱框 / 轴线。
        val overlay: LaserVehicleOverlay? = null,
        // 地面平面（视角预设的"上"方向基准；nx*x+ny*y+nz*z+d=0，法向指向点云主体侧）。
        val groundNx: Float = 0f,
        val groundNy: Float = 0f,
        val groundNz: Float = 0f,
        val groundD: Float = 0f,
        val groundValid: Boolean = false,
    ) : RealtimeEvent
    /** 激光采集中的增量点（laser.points，M8'）。unit: 0=unitA, 1=unitB。[points] 扁平 [x,y,z,...] mm。 */
    data class LaserPoints(
        val sessionKey: String,
        val unit: Int,
        val points: FloatArray,
        val hAngleDeg: Float,
        /** 服务端 canonical region 裁剪后的该单元累计源点数；旧服务端缺失时为 null。 */
        val sourcePointCount: Int? = null,
    ) : RealtimeEvent {
        // FloatArray 需自定义 equals/hashCode（data class 默认按引用比较）。
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is LaserPoints) return false
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
    /** 激光扫描状态机变更（laser.status，M8'）。state: scanning|fusing|done|error|cancelled。 */
    data class LaserStatus(
        val sessionKey: String,
        val state: String,
        val framesA: Int,
        val framesB: Int,
        /** 可靠状态通道携带的服务端分单元累计源点数，用于校准末帧丢失。 */
        val sourcePointsA: Int? = null,
        val sourcePointsB: Int? = null,
    ) : RealtimeEvent
    data class Error(val code: Int, val message: String, val inReplyTo: String?) : RealtimeEvent
    data class Unknown(val envelope: RealtimeEnvelope) : RealtimeEvent
}

enum class RealtimeConnectionState {
    Disconnected,
    Connecting,
    Connected,
}
