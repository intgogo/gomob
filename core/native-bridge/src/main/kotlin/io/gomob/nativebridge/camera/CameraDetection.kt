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

    /**
     * 当前连接的**深度主相机**型号（第一个命中的深度源）；无 → [CameraModel.Unknown]。
     * ★ 只认深度源（Berxel/eYs3D），跳过 HLSD8 RGB：HLSD8 与深度模组同时插着时不能把它误判成主相机。
     * 辅助 RGB 相机用 [detectAuxRgb] 单独检测，二者可并存（RGBD 双相机机型）。
     */
    fun detect(usbManager: UsbManager): CameraModel {
        val depthModels = usbManager.deviceList.values
            .map { CameraModel.fromUsbIds(it.vendorId, it.productId) }
            .filter { it.isSupported && it.isDepthSource }
        // ★ eYs3D(RS-D550)是当前重建主线深度相机:Berxel 与 eYs3D 同时插着时优先 eYs3D。
        //   只插 Berxel 时仍返 Berxel(行为不变);只插 eYs3D 时返 eYs3D。
        return depthModels.firstOrNull { it is CameraModel.Eys3d }
            ?: depthModels.firstOrNull()
            ?: CameraModel.Unknown(0, 0)
    }

    /**
     * 当前连接的**辅助 RGB 相机**（HLSD8）；无 → null。
     * 与 [detect] 的深度主相机相互独立：RGBD 机型上两颗 USB 相机并存，各自取流再做正射图配准。
     */
    fun detectAuxRgb(usbManager: UsbManager): CameraModel? =
        usbManager.deviceList.values
            .map { CameraModel.fromUsbIds(it.vendorId, it.productId) }
            .firstOrNull { it.isSupported && !it.isDepthSource }

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
