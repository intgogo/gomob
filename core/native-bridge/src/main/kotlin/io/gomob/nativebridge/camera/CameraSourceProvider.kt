package io.gomob.nativebridge.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gomob.nativebridge.berxel.BerxelService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 双相机取流源路由（M6.8b ⑤）。按当前插着的 USB 相机选活动 [CameraSource]：
 * - eYs3D(0x3438:0x0206) → [Eys3dCameraService]
 * - Berxel 或未识别     → [BerxelService]（既有路径，默认回落，**不退化**）
 *
 * 两源都 @Singleton，由 Hilt 注入；本 provider 不持有状态，[active] 每次按实时枚举判型。
 * 跨相机 VM（Scan3dRecordingViewModel 等）在 init 里取一次 [active] 持有整段会话。
 *
 * ★ 默认回落 Berxel 是关键不变量：没插 eYs3D 时行为与改造前**逐位一致**。
 */
@Singleton
class CameraSourceProvider @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val berxel: BerxelService,
    private val eys3d: Eys3dCameraService,
    private val hlsd8: Hlsd8CameraService,
) {
    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            @Suppress("DEPRECATION")
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
            detachDevice(device)
        }
    }

    init {
        val filter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(usbDetachReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(usbDetachReceiver, filter)
        }
    }

    /** 当前活动**深度主相机**取流源。eYs3D 在场走 eYs3D，否则（Berxel/无）走 Berxel。 */
    fun active(): CameraSource = when (detectModel()) {
        is CameraModel.Eys3d -> eys3d
        else -> berxel
    }

    /**
     * 当前**辅助 RGB 相机**取流源（HLSD8）；未插着则 null。
     * 与 [active] 的深度主相机相互独立、可并行 acquire：RGBD 机型同时取深度 + 真彩，做正射图配准。
     */
    fun auxRgb(): CameraSource? {
        val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        return if (CameraDetection.detectAuxRgb(usb) is CameraModel.Hlsd8) hlsd8 else null
    }

    /** VIN 固定使用 RS-D550 深度源，不能在未插设备时静默绑定到 Berxel。 */
    fun vinDepth(): CameraSource = eys3d

    /** VIN 固定使用 HLSD8 彩色源；未插设备由服务自身报告 NoDevice 并等待热插拔。 */
    fun vinRgb(): CameraSource = hlsd8

    /**
     * 缓存 USB attach intent 携带的授权设备实例，并在页面已有消费者时立即开流。
     * 部分 OEM 的 [UsbManager.deviceList] 实例没有同等权限，不能丢掉 intent 中的对象。
     */
    fun attachAuthorizedDevice(device: UsbDevice) {
        when (CameraModel.fromUsbIds(device.vendorId, device.productId)) {
            is CameraModel.Eys3d -> eys3d.attachAuthorizedDevice(device)
            is CameraModel.Hlsd8 -> hlsd8.attachAuthorizedDevice(device)
            is CameraModel.Berxel -> berxel.attachAuthorizedDevice(device)
            else -> Unit
        }
    }

    /** 物理拔线立即失效对应会话，不能等 30 秒 watchdog 或继续持有旧 fd。 */
    fun detachDevice(device: UsbDevice) {
        when (CameraModel.fromUsbIds(device.vendorId, device.productId)) {
            is CameraModel.Eys3d -> eys3d.detachDevice(device)
            is CameraModel.Hlsd8 -> hlsd8.detachDevice(device)
            else -> Unit
        }
    }

    /** 当前识别到的深度主相机型号（供 UI 显型号 / 路由判定）。 */
    fun detectModel(): CameraModel {
        val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        return CameraDetection.detect(usb)
    }
}
