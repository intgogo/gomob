package io.gomob.feature.scan3d.stream

/**
 * 相机 6DoF 位姿 (CamToWorld)。约定: CV 相机系 (看 +Z / +X 右 / +Y 下); 四元数 (x,y,z,w); 平移米。
 * tracking=false 表示 VIO 当前未跟踪 —— gorob 边缘会退到纯 frame-to-model ICP。
 */
data class Pose6(
    val qx: Double, val qy: Double, val qz: Double, val qw: Double,
    val tx: Double, val ty: Double, val tz: Double,
    val tracking: Boolean,
)

/**
 * 位姿来源 (用户拍板 2026-06-02: 手机 ARCore VIO 出位姿作先验, gorob ICP 精修)。
 * 默认 [IdentityPoseProvider] 出单位位姿(未跟踪) —— 此时全靠 gorob ICP 定位 (起步可用, 漂移大时接 ARCore)。
 *
 * ★ 接缝: 真集成时实现 ArCorePoseProvider, 把 ARCore Frame.camera.pose 换算到 CV 约定 (ARCore 是
 *   OpenGL 约定看 -Z/+Y上, 需绕 X 轴 180° 翻 → 与 sim/dump_rgbd.py CAM_FLIP 同源), 按 timestamp 取最近位姿。
 */
interface PoseProvider {
    fun poseFor(timestampUs: Long): Pose6
}

object IdentityPoseProvider : PoseProvider {
    override fun poseFor(timestampUs: Long): Pose6 = Pose6(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, false)
}
