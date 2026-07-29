package io.gomob.nativebridge.camera

import android.app.PendingIntent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build

/** USB 权限结果由系统广播值与 UsbManager 当前真值共同裁决。 */
internal fun isUsbPermissionGranted(rawGranted: Boolean, managerGranted: Boolean): Boolean =
    rawGranted || managerGranted

/** Android 12+ 必须可变，UsbManager 才能补入设备与授权结果；Intent 已限制到本应用包。 */
internal fun usbPermissionPendingIntentFlags(sdkInt: Int): Int =
    PendingIntent.FLAG_CANCEL_CURRENT or
        if (sdkInt >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0

/** 把热插拔代际纳入 PendingIntent identity，旧请求结果不能冒充当前设备。 */
internal fun usbPermissionRequestCode(deviceName: String, generation: Long): Int {
    val generationHash = (generation xor (generation ushr 32)).toInt()
    return 31 * deviceName.hashCode() + generationHash
}

/**
 * 只在当前枚举节点范围内复用授权对象，避免热插拔后继续打开旧 UsbDevice。
 * 某些 OEM 的枚举对象权限状态落后，因此同一节点下仍保留系统广播携带的授权对象。
 */
internal fun resolveCurrentUsbDevice(
    usbManager: UsbManager,
    model: CameraModel,
    preferredDevice: UsbDevice?,
    authorizedDevice: UsbDevice?,
): UsbDevice? {
    val enumerated = CameraDetection.primaryNode(usbManager, model)
    val currentAnchor = enumerated ?: preferredDevice ?: return null
    val candidates = listOfNotNull(enumerated, preferredDevice, authorizedDevice)
        .filter { it.isSameUsbNode(currentAnchor) }

    return candidates.firstOrNull(usbManager::hasPermission)
        ?: preferredDevice?.takeIf { it.isSameUsbNode(currentAnchor) }
        ?: enumerated
}

internal fun UsbDevice.isSameUsbNode(other: UsbDevice): Boolean =
    deviceName == other.deviceName && vendorId == other.vendorId && productId == other.productId
