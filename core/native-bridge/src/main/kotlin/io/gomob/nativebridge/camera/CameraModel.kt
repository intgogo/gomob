package io.gomob.nativebridge.camera

/**
 * 受支持相机型号（双相机自动识别真理源，M6.8b）。
 *
 * vendorId/productId 用于 native CameraRegistry 分发；deviceTypeLabel 给 UI 显型号；
 * fdCount = 该相机需从 UsbManager 拿几个 usbfs fd（eYs3D 单节点 1，Berxel 双节点 master+companion 2）。
 * 真正的 driver 选择交给 native registry（单一真理源）；此处只做「UI 标签 + fd 数 + 走哪条 stack」轻判定。
 */
sealed class CameraModel(
    val vendorId: Int,
    val productId: Int,
    val deviceTypeLabel: String,
    val fdCount: Int,
    /**
     * 是否为「深度源」。深度模组（Berxel/eYs3D）= true，是扫描主相机；
     * HLSD8 是【独立第二颗 RGB 相机】（无深度）= false，作辅助 RGB 源（正射图高分辨率来源）。
     * 路由用此区分主深度相机与辅助 RGB：[CameraDetection.detect] 只选深度源，RGB 经 [CameraDetection.detectAuxRgb]。
     */
    val isDepthSource: Boolean = true,
) {
    /** Berxel iHawk P100R3：master 0x0603:0x001f + companion 0x3558:0x1012 双节点。 */
    data object Berxel : CameraModel(0x0603, 0x001f, "Berxel iHawk P100R3", 2)

    /** eYs3D / Etron RS-D550(ROSIE4)：单节点 0x3438:0x0206。L'+depth（mode25），不出 13MP 真彩。 */
    data object Eys3d : CameraModel(0x3438, 0x0206, "eYs3D RS-D550", 1)

    /**
     * HLSD8 RGB 相机：Image+ / Sonix 0x0C45:0x6366，13MP 真彩（约 4160 宽 MJPEG）。
     * 与 eYs3D 深度模组**物理独立**的第二颗 USB 相机；VINCreator 正射图的高分辨率 RGB 来源。
     */
    data object Hlsd8 : CameraModel(0x0C45, 0x6366, "Image+ HLSD8 (RGB)", 1, isDepthSource = false)

    /** 未识别设备（不走相机栈）。 */
    data class Unknown(val vid: Int, val pid: Int) : CameraModel(vid, pid, "未知相机", 0, isDepthSource = false)

    val isSupported: Boolean get() = this !is Unknown

    companion object {
        // Berxel companion 节点 VID:PID（与 master 同属 Berxel 设备）。
        const val BERXEL_COMPANION_VID = 0x3558
        const val BERXEL_COMPANION_PID = 0x1012

        /** 按 USB vid:pid 判型。Berxel 的 master/companion 任一节点都归 Berxel。 */
        fun fromUsbIds(vid: Int, pid: Int): CameraModel = when {
            vid == Berxel.vendorId && pid == Berxel.productId -> Berxel
            vid == BERXEL_COMPANION_VID && pid == BERXEL_COMPANION_PID -> Berxel
            vid == Eys3d.vendorId && pid == Eys3d.productId -> Eys3d
            vid == Hlsd8.vendorId && pid == Hlsd8.productId -> Hlsd8
            else -> Unknown(vid, pid)
        }

        /** 该 vid:pid 是否为受支持相机（供 USB 枚举/权限白名单判定）。 */
        fun isRecognized(vid: Int, pid: Int): Boolean = fromUsbIds(vid, pid).isSupported
    }
}
