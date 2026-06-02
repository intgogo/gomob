package io.gomob.nativebridge.camera

import io.gomob.nativebridge.NativeBridge
import java.nio.ByteBuffer

/**
 * 厂商无关相机会话编排（M6.8b）。经 [NativeBridge.cameraOpenByFds] 走 native CameraRegistry 分发，
 * 两相机出同一 metric depthMm 契约。
 *
 * 用法：上层（BerxelService / feature:scan3d）枚举到 USB 设备 → [CameraModel.fromUsbIds] 判型 →
 * 拿对应数量的 usbfs fd → [start]；之后 [pollDepthMm]/[pollColor] 取帧，[stop] 释放。
 *
 * ★ 现仅 eYs3D 走此 stack；Berxel 维持既有 BerxelNativeStack（NATIVE_REWRITE，完全不退化）。
 * Berxel adapter 接好后（TODO M6.8b step3），Berxel 也可走此统一栈。
 *
 * 句柄生命周期与 native 一致：单会话，[start] 后到 [stop] 前句柄有效；[stop] 是唯一释放点。
 */
class CameraStack {
    @Volatile
    private var handle: Long = 0L

    @Volatile
    var model: CameraModel = CameraModel.Unknown(0, 0)
        private set

    val isOpen: Boolean get() = handle != 0L

    /**
     * 打开相机。fds 长度须等于 [CameraModel.fdCount]（eYs3D 1 / Berxel 2）。
     * configJson 为 driver 特定配置（可空数组）。成功返回 true。
     */
    fun start(model: CameraModel, fds: IntArray, configJson: ByteArray = ByteArray(0)): Boolean {
        if (handle != 0L) return false  // 单会话，先 stop
        if (!model.isSupported) return false
        val h = NativeBridge.cameraOpenByFds(model.vendorId, model.productId, fds, configJson)
        if (h == 0L) return false
        handle = h
        this.model = model
        return true
    }

    /** 取最新 depthMm 帧写进 buffer（容量需 >= w*h*2）。返回字节数；无新帧 0；buffer 不足 -1；未开 -1。 */
    fun pollDepthMm(buffer: ByteBuffer, outInfo: LongArray): Int {
        val h = handle
        return if (h == 0L) -1 else NativeBridge.cameraPollDepthMm(h, buffer, outInfo)
    }

    /** 取最新 color 帧字节（consume-once，无新帧/未开 null）。 */
    fun pollColor(): ByteArray? {
        val h = handle
        return if (h == 0L) null else NativeBridge.cameraPollColor(h)
    }

    /** 会话统计 [colorFrames, depthFrames, dropped, errors, state]。 */
    fun stats(): LongArray {
        val h = handle
        return if (h == 0L) LongArray(5) else NativeBridge.cameraStats(h)
    }

    /** 语义深度控制（负值=不改）。返回是否生效。 */
    fun setControls(
        confThr: Float = -1f,
        temporal: Int = -1,
        spatial: Int = -1,
        ae: Int = -1,
        gain: Int = -1,
        irCurrent: Int = -1,
    ): Boolean {
        val h = handle
        return if (h == 0L) false
        else NativeBridge.cameraSetControls(h, confThr, temporal, spatial, ae, gain, irCurrent)
    }

    /** 停止 + 释放（唯一释放点）。 */
    fun stop() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            NativeBridge.cameraStop(h)
        }
    }

    companion object {
        /** 不开流即取设备能力 JSON（供 UI 显型号/档位）。 */
        fun capabilitiesJson(model: CameraModel): String =
            NativeBridge.cameraCapabilitiesJson(model.vendorId, model.productId)
    }
}
