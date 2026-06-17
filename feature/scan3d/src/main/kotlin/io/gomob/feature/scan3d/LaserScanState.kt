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
        val measurement: VehicleMeasurement,
        val ground: GroundPlane,
        val pointIntegrityWarning: String? = null,
    ) : LaserScanState
    data class Error(val msg: String) : LaserScanState
}
