package io.gomob.nativebridge.camera

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * USB 相机自动识别（M6.8b）。枚举 UsbManager 设备列表，按 vid:pid 判型。
 * 纯枚举：不开流、不请求权限、不持有设备——只回答「现在插了哪种相机」。
 *
 * 路由用法（feature:scan3d）：[detect] → [CameraModel]；Berxel 走既有 BerxelService（零改）、
 * eYs3D 走独立 CameraStack。Berxel 双节点(master 0x0603 + companion 0x3558)任一在即识别为 Berxel。
 */
object CameraDetection {

    /** 当前连接的受支持相机型号（第一个命中）；无受支持设备 → [CameraModel.Unknown]。 */
    fun detect(usbManager: UsbManager): CameraModel {
        for (d in usbManager.deviceList.values) {
            val m = CameraModel.fromUsbIds(d.vendorId, d.productId)
            if (m.isSupported) return m
        }
        return CameraModel.Unknown(0, 0)
    }

    /** 所有受支持相机 USB 节点（供权限请求/fd 获取白名单）。 */
    fun supportedDevices(usbManager: UsbManager): List<UsbDevice> =
        usbManager.deviceList.values.filter { CameraModel.isRecognized(it.vendorId, it.productId) }

    /**
     * 某型号的主节点 UsbDevice（eYs3D 单节点 0x3438；Berxel 取 master 0x0603）。无 → null。
     * eYs3D 用它向 UsbManager 请求权限 + 取 usbfs fd 喂 [CameraStack.start]。
     */
    fun primaryNode(usbManager: UsbManager, model: CameraModel): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == model.vendorId && it.productId == model.productId
        }
}
