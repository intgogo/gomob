package io.gomob.nativebridge.camera

/** Gomob 自有相机型号。
 *
 * RS-D550/HLSD8 的枚举、权限、profile 和 fd 生命周期由 vin-capture AAR 持有，
 * 不在 gomob 的模型或 native registry 中重复定义。
 */
sealed class CameraModel(
    val vendorId: Int,
    val productId: Int,
    val deviceTypeLabel: String,
    val fdCount: Int,
) {
    /** Berxel iHawk P100R3：master 0x0603:0x001f + companion 0x3558:0x1012 双节点。 */
    data object Berxel : CameraModel(0x0603, 0x001f, "Berxel iHawk P100R3", 2)

    /** 未识别设备（不走 gomob 相机栈）。 */
    data class Unknown(val vid: Int, val pid: Int) : CameraModel(vid, pid, "未知相机", 0)

    val isSupported: Boolean get() = this is Berxel

    companion object {
        const val BERXEL_COMPANION_VID = 0x3558
        const val BERXEL_COMPANION_PID = 0x1012

        /** Berxel 的 master/companion 任一节点都归同一设备。 */
        fun fromUsbIds(vid: Int, pid: Int): CameraModel = when {
            vid == Berxel.vendorId && pid == Berxel.productId -> Berxel
            vid == BERXEL_COMPANION_VID && pid == BERXEL_COMPANION_PID -> Berxel
            else -> Unknown(vid, pid)
        }

        fun isRecognized(vid: Int, pid: Int): Boolean = fromUsbIds(vid, pid).isSupported
    }
}
