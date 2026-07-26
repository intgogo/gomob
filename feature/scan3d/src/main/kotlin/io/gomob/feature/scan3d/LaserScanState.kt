package io.gomob.feature.scan3d

import io.gomob.data.scan.GroundPlane
import io.gomob.data.scan.VehicleMeasurement

/**
 * 激光双单元车辆外廓扫描状态机（M8' 瘦客户端）。
 *
 * 与 RGBD（VehicleScanState）不同：无 Uploading（采集在服务端，端侧不传帧）。
 * Idle → Connecting（已 POST，服务端探活/起采集）→ Scanning（实时点流入）→
 * Processing（服务端融合中）→ Completed | Error。
 */
sealed interface LaserScanState {
    data object Idle : LaserScanState
    data object Connecting : LaserScanState
    data object Scanning : LaserScanState
    data object Processing : LaserScanState
    data class Completed(
        val points: Int,
        val ptsA: Int,
        val ptsB: Int,
        val alignMethod: String,
        val siteRevision: String? = null,
        val regionRevision: String? = null,
        val siteQualityVerified: Boolean = false,
        val siteQualityOverride: Boolean = false,
        val productionEligible: Boolean = false,
        val measurement: VehicleMeasurement,
        val ground: GroundPlane,
        /** measured PCD 内容身份已通过校验；主视图始终是 fused，不由本字段选择显示云。 */
        val measuredCloudVerified: Boolean = false,
        val pointIntegrityWarning: String? = null,
    ) : LaserScanState
    data class Error(
        val msg: String,
        /** true 表示服务端任务仍可能在运行，离页前必须再次确认停止。 */
        val activeScan: Boolean = false,
    ) : LaserScanState
}
