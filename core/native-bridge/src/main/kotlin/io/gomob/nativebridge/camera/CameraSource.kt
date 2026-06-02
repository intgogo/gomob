package io.gomob.nativebridge.camera

import io.gomob.model.ColorFrame
import io.gomob.model.DepthFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 厂商无关取流源的统一状态（M6.8b ⑤）。路由 / UI 共用，不暴露任何厂商细节。
 *
 * 故意做成与 BerxelDeviceState 同构但中性的小型状态机：Berxel 侧将来可由 BerxelService
 * 派生一份 [CameraSourceState]（additive，不改其行为）；eYs3D 侧由 [Eys3dCameraService] 直接产出。
 */
sealed interface CameraSourceState {
    /** 未启动（未 acquire 或已 stop）。 */
    data object Idle : CameraSourceState

    /** 已启动但当前无受支持相机插着。 */
    data object NoDevice : CameraSourceState

    /** 已弹 USB 权限框，等用户授权 / 系统异步落 grant。 */
    data object WaitingPermission : CameraSourceState

    /** 拿到 fd，正在开流。 */
    data object Opening : CameraSourceState

    /** 正在出帧。depthWidth/Height 为当前深度流分辨率。 */
    data class Streaming(
        val label: String,
        val depthWidth: Int,
        val depthHeight: Int,
    ) : CameraSourceState

    /** 故障（含原因），UI 直接展示 message。 */
    data class Error(val message: String) : CameraSourceState
}

/**
 * 厂商无关相机取流源（M6.8b ⑤）。
 *
 * feature:scan3d 路由按 [CameraModel] 选具体实现：
 * - Berxel → 既有 [io.gomob.nativebridge.berxel.BerxelService]（NATIVE_REWRITE 双流，零改、不退化）
 * - eYs3D  → [Eys3dCameraService]（libusb 单节点 + CameraStack）
 *
 * 两源出**同一** core:model 帧契约（[ColorFrame] / [DepthFrame]，depth=16bit unsigned mm，0=无效），
 * 下游预览 / 重建 / VIN 拓印不需要知道相机是哪家。
 *
 * 生命周期用引用计数：扫描页进场 [acquire]、退场 [release]；归 0 且宽限期满无人重新 acquire 才真停。
 */
interface CameraSource {
    /** UI 显示用型号标签（如 "eYs3D RS-D550"）。 */
    val deviceLabel: String

    /** 中性状态流。 */
    val sourceState: StateFlow<CameraSourceState>

    /** 彩色帧流（RGB24，已解码）。 */
    val colorFrames: SharedFlow<ColorFrame>

    /** 深度帧流（16bit mm）。 */
    val depthFrames: SharedFlow<DepthFrame>

    /** 进场引用计数 +1（0→1 拉起相机；已运行则幂等）。 */
    fun acquire()

    /** 退场引用计数 -1（归 0 且宽限期满无人 acquire 才真停，释放 USB / 省电）。 */
    fun release()
}
