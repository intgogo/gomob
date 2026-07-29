package io.gomob.nativebridge.camera

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gomob.nativebridge.berxel.BerxelService
import javax.inject.Inject
import javax.inject.Singleton

/** Gomob 自有 Berxel 取流入口。
 *
 * RS-D550 + HLSD8 已迁移到 vin-capture AAR；本类不再注入或转发第二套驱动。
 */
@Singleton
class CameraSourceProvider @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val berxel: BerxelService,
) {
    /** 外廓录制只使用 Berxel；VIN/RS-D550/HLSD8 由 AAR 的公开会话负责。 */
    fun active(): CameraSource = berxel

    /**
     * 缓存 USB attach intent 携带的授权设备实例，并在页面已有消费者时立即开流。
     * 部分 OEM 的 [UsbManager.deviceList] 实例没有同等权限，不能丢掉 intent 中的对象。
     */
    fun attachAuthorizedDevice(device: UsbDevice) {
        if (CameraModel.fromUsbIds(device.vendorId, device.productId) is CameraModel.Berxel) {
            berxel.attachAuthorizedDevice(device)
        }
    }

    /** 当前识别到的 Berxel 型号；无设备时返回 Unknown。 */
    fun detectModel(): CameraModel = CameraDetection.detect(
        appContext.getSystemService(Context.USB_SERVICE) as UsbManager,
    )
}
