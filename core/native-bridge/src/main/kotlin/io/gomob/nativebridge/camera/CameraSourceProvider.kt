package io.gomob.nativebridge.camera

import android.content.Context
import android.hardware.usb.UsbManager
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
) {
    /** 当前活动取流源。eYs3D 在场走 eYs3D，否则（Berxel/无）走 Berxel。 */
    fun active(): CameraSource = when (detectModel()) {
        is CameraModel.Eys3d -> eys3d
        else -> berxel
    }

    /** 当前识别到的相机型号（供 UI 显型号 / 路由判定）。 */
    fun detectModel(): CameraModel {
        val usb = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        return CameraDetection.detect(usb)
    }
}
