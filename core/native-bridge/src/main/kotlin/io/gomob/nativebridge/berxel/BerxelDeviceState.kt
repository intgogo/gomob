package io.gomob.nativebridge.berxel

/**
 * Berxel iHawk 设备生命周期的 UI 友好状态。
 *
 * 把 SDK 的回调 + 异步开流过程归并成一个 sealed 状态机；feature 层只依赖此处的类型，
 * 不直接 import com.berxel.* —— 让 SDK 形态变化时只波及 core:native-bridge 一层。
 */
sealed interface BerxelDeviceState {
    /** 还没启动过 SDK，或已主动 stop。 */
    data object Idle : BerxelDeviceState

    /** SDK Context 加载中、正在枚举 USB 设备。 */
    data object Initializing : BerxelDeviceState

    /** 没找到任何 iHawk 接入。USB 拔出后也回到这里。 */
    data object NoDevice : BerxelDeviceState

    /** 正在请求 USB 权限（系统弹窗已弹出，用户没点）。 */
    data object WaitingPermission : BerxelDeviceState

    /** USB 已开，正在初始化彩色 + 深度流。 */
    data object Opening : BerxelDeviceState

    /** 设备已开 + 流已启动；reader 线程在跑。 */
    data class Streaming(val info: BerxelDeviceInfo) : BerxelDeviceState

    /** 出错了（SDK 失败、流读不到帧、设备拔出等都映射到这里）。 */
    data class Error(val reason: String) : BerxelDeviceState
}

/**
 * Berxel 设备元信息 —— 透传 SDK 的设备 + 版本 + 流规格。
 *
 * SDK 侧 `getCurrentFrameMode(...)` 返回的分辨率/像素类型/fps 会被原样塞进来。
 */
data class BerxelDeviceInfo(
    val vendorId: Int,
    val productId: Int,
    val serialNumber: String,
    val deviceAddress: String,
    val firmwareVersion: String,
    val sdkVersion: String,
    val colorMode: BerxelStreamSpec?,
    val depthMode: BerxelStreamSpec?,
)

/** 单条流（彩色 / 深度 / IR）的当前模式描述。 */
data class BerxelStreamSpec(
    val width: Int,
    val height: Int,
    val fps: Int,
    /** SDK PixelType 枚举名（如 BERXEL_HAWK_PIXEL_TYPE_DEP_16BIT_12I_4D），string 而非 enum 避免泄漏 SDK 类型 */
    val pixelType: String,
)

/** 给 UI 看的实时帧统计 —— reader 线程每帧更新一次。 */
data class BerxelFrameStat(
    /** 累计接收的帧序号（来自 SDK Frame.getFrameIndex()） */
    val frameIndex: Int,
    /** SDK 报的当前测量帧率（int fps，Frame.getFps()） */
    val measuredFps: Int,
    /** 帧时间戳（SDK 内部时基，单位 us，仅用于比较两帧之间的同步性） */
    val timestampUs: Long,
    /** 接收时刻（System.elapsedRealtime()，给 UI 算"上次帧距今多久"） */
    val receivedAtElapsedMs: Long,
    val width: Int,
    val height: Int,
)

/**
 * iHawk 设备开关 / 数值类控制项的当前快照（UI 双向绑定用）。
 *
 * 所有字段都是"我们这一侧记录的应用值"，不是从 SDK 读回来的（SDK 没有 getter）。
 * 默认值 = SDK 出厂默认 / startStreams 后的状态（参考 BerxelService.applyDefaultControls）。
 */
data class BerxelDeviceControls(
    /** Depth 重投影到 Color 像素坐标（registration on）；off=原始 depth。 */
    val registrationEnable: Boolean = false,
    /** 投射器（IR 投射）on/off。off 时 depth 没数据。 */
    val depthEmitterOn: Boolean = true,
    /** 双流镜像（左右翻转）。 */
    val streamMirror: Boolean = false,
    /** Depth 自动曝光。 */
    val depthAutoExposure: Boolean = true,
    /** Depth 边缘优化（去边缘抖动）。 */
    val depthEdgeOptimization: Boolean = false,
    /** Depth 基础去噪（SDK 内置）。 */
    val depthDenoise: Boolean = true,
    /** Depth 温度补偿（变温环境下保精度）。 */
    val depthTemperatureCompensation: Boolean = true,
    /** Color 自动曝光。 */
    val colorAutoExposure: Boolean = true,
    /** Color 手动 exposure（仅 colorAutoExposure=false 时生效）。 */
    val colorExposureUs: Int = 0,
    /** Color 手动 gain（仅 colorAutoExposure=false 时生效）。 */
    val colorGain: Int = 0,
    /** Color 画质 0..100，0=不写入 SDK。 */
    val colorQuality: Int = 0,
)
