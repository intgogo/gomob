package io.gomob.nativebridge.camera

import io.gomob.nativebridge.NativeBridge
import java.nio.ByteBuffer

/** native `SessionState` 的 Kotlin 对应；数值顺序必须与 camera_session.h 保持一致。 */
internal enum class NativeCameraSessionState(val rawValue: Long) {
    Idle(0),
    Starting(1),
    Streaming(2),
    Error(3),
    Stopped(4),
    Unknown(-1),
    ;

    val isTerminal: Boolean get() = this == Error || this == Stopped

    companion object {
        fun fromRawValue(rawValue: Long): NativeCameraSessionState =
            entries.firstOrNull { it != Unknown && it.rawValue == rawValue } ?: Unknown
    }
}

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
class CameraStack internal constructor(
    private val nativeApi: CameraNativeApi = JniCameraNativeApi,
) {
    /** 裸 native 指针的读取、JNI 调用与 stop/delete 必须在同一临界区，禁止在途 poll 遭释放。 */
    private val callLock = Any()
    private var handle: Long = 0L

    @Volatile
    var model: CameraModel = CameraModel.Unknown(0, 0)
        private set

    val isOpen: Boolean get() = synchronized(callLock) { handle != 0L }

    /**
     * 打开相机。fds 长度须等于 [CameraModel.fdCount]（eYs3D 1 / Berxel 2）。
     * configJson 为 driver 特定配置（可空数组）。成功返回 true。
     */
    fun start(model: CameraModel, fds: IntArray, configJson: ByteArray = ByteArray(0)): Boolean {
        synchronized(callLock) {
            if (handle != 0L) return false  // 单会话，先 stop
            if (!model.isSupported) return false
            val h = nativeApi.openByFds(model.vendorId, model.productId, fds, configJson)
            if (h == 0L) return false
            handle = h
            this.model = model
            return true
        }
    }

    /** 取最新 depthMm 帧写进 buffer（容量需 >= w*h*2）。返回字节数；无新帧 0；buffer 不足 -1；未开 -1。 */
    fun pollDepthMm(buffer: ByteBuffer, outInfo: LongArray): Int {
        synchronized(callLock) {
            val h = handle
            return if (h == 0L) -1 else nativeApi.pollDepthMm(h, buffer, outInfo)
        }
    }

    /** 取最新 color 帧字节（consume-once，无新帧/未开 null）。 */
    fun pollColor(): ByteArray? {
        synchronized(callLock) {
            val h = handle
            return if (h == 0L) null else nativeApi.pollColor(h)
        }
    }

    /** 取最新 color 帧，outInfo=[width,height,serial,native hostNs]。 */
    fun pollColor(outInfo: LongArray): ByteArray? {
        require(outInfo.size >= 4) { "outInfo 至少 4 项" }
        synchronized(callLock) {
            val h = handle
            return if (h == 0L) null else nativeApi.pollColorWithInfo(h, outInfo)
        }
    }

    /** 会话统计 [colorFrames, depthFrames, dropped, errors, state]。 */
    fun stats(): LongArray {
        synchronized(callLock) {
            val h = handle
            return if (h == 0L) LongArray(5) else nativeApi.stats(h)
        }
    }

    /**
     * 返回 native 会话状态。句柄已经关闭时明确视为 [NativeCameraSessionState.Stopped]；
     * stats 缺项或 native 返回未来版本数值时为 [NativeCameraSessionState.Unknown]。
     */
    internal fun sessionState(): NativeCameraSessionState {
        synchronized(callLock) {
            val h = handle
            if (h == 0L) return NativeCameraSessionState.Stopped
            val stats = nativeApi.stats(h)
            return stats.getOrNull(4)
                ?.let(NativeCameraSessionState::fromRawValue)
                ?: NativeCameraSessionState.Unknown
        }
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
        synchronized(callLock) {
            val h = handle
            return if (h == 0L) false
            else nativeApi.setControls(h, confThr, temporal, spatial, ae, gain, irCurrent)
        }
    }

    /** 停止 + 释放（唯一释放点）。 */
    fun stop() {
        synchronized(callLock) {
            val h = handle
            if (h != 0L) {
                handle = 0L
                nativeApi.stop(h)
            }
        }
    }

    companion object {
        /** 不开流即取设备能力 JSON（供 UI 显型号/档位）。 */
        fun capabilitiesJson(model: CameraModel): String =
            NativeBridge.cameraCapabilitiesJson(model.vendorId, model.productId)
    }
}

internal interface CameraNativeApi {
    fun openByFds(vid: Int, pid: Int, fds: IntArray, configJson: ByteArray): Long
    fun pollDepthMm(handle: Long, buffer: ByteBuffer, outInfo: LongArray): Int
    fun pollColor(handle: Long): ByteArray?
    fun pollColorWithInfo(handle: Long, outInfo: LongArray): ByteArray?
    fun stats(handle: Long): LongArray
    fun setControls(
        handle: Long,
        confThr: Float,
        temporal: Int,
        spatial: Int,
        ae: Int,
        gain: Int,
        irCurrent: Int,
    ): Boolean
    fun stop(handle: Long)
}

private object JniCameraNativeApi : CameraNativeApi {
    override fun openByFds(vid: Int, pid: Int, fds: IntArray, configJson: ByteArray): Long =
        NativeBridge.cameraOpenByFds(vid, pid, fds, configJson)

    override fun pollDepthMm(handle: Long, buffer: ByteBuffer, outInfo: LongArray): Int =
        NativeBridge.cameraPollDepthMm(handle, buffer, outInfo)

    override fun pollColor(handle: Long): ByteArray? = NativeBridge.cameraPollColor(handle)

    override fun pollColorWithInfo(handle: Long, outInfo: LongArray): ByteArray? =
        NativeBridge.cameraPollColorWithInfo(handle, outInfo)

    override fun stats(handle: Long): LongArray = NativeBridge.cameraStats(handle)

    override fun setControls(
        handle: Long,
        confThr: Float,
        temporal: Int,
        spatial: Int,
        ae: Int,
        gain: Int,
        irCurrent: Int,
    ): Boolean = NativeBridge.cameraSetControls(
        handle,
        confThr,
        temporal,
        spatial,
        ae,
        gain,
        irCurrent,
    )

    override fun stop(handle: Long) = NativeBridge.cameraStop(handle)
}
