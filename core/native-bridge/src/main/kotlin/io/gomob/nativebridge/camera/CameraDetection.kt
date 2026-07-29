package io.gomob.nativebridge.camera

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/** Gomob Berxel 设备识别；VIN 的 RS-D550/HLSD8 由 AAR 管理。 */
object CameraDetection {
    /** 当前连接的 Berxel 型号；无设备返回 Unknown。 */
    fun detect(usbManager: UsbManager): CameraModel =
        usbManager.deviceList.values
            .map { CameraModel.fromUsbIds(it.vendorId, it.productId) }
            .firstOrNull { it is CameraModel.Berxel }
            ?: CameraModel.Unknown(0, 0)

    /** 所有 Berxel USB 节点（供权限请求/fd 获取白名单）。 */
    fun supportedDevices(usbManager: UsbManager): List<UsbDevice> =
        usbManager.deviceList.values.filter {
            CameraModel.fromUsbIds(it.vendorId, it.productId) is CameraModel.Berxel
        }

    /** Berxel master 节点。 */
    fun primaryNode(usbManager: UsbManager, model: CameraModel): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == model.vendorId && it.productId == model.productId
        }
}
