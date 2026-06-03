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
    val streamFlagMode: BerxelStreamFlagProfile,
    val requestedProfileId: String,
    val colorMode: BerxelStreamSpec?,
    val depthMode: BerxelStreamSpec?,
)

/** Berxel SDK 的 stream flag 模式；每个 flag 下才有各自可选的 frame mode。 */
enum class BerxelStreamFlagProfile {
    SINGULAR,
    MIX,
    MIX_HD,
    MIX_QVGA,
}

/** 目标帧模式：分辨率必须和 fps 一起作为一个选择单元。 */
data class BerxelStreamTarget(
    val width: Int,
    val height: Int,
    val fps: Int,
) {
    init {
        require(width > 0 && height > 0) { "stream target resolution must be positive" }
        require(fps > 0) { "stream target fps must be positive" }
    }
}

/**
 * 一组可应用到 Berxel SDK 的流配置。
 *
 * [flagProfile] 先决定 SDK 处在哪个模式族；[color] / [depth] 再在该模式族下选择各自
 * 的分辨率 + fps。这样不会把 "1280×800" 当成孤立档位，避免漏掉同分辨率下不同 fps。
 */
data class BerxelStreamProfile(
    val id: String,
    val flagProfile: BerxelStreamFlagProfile,
    val color: BerxelStreamTarget?,
    val depth: BerxelStreamTarget?,
) {
    init {
        require(id.isNotBlank()) { "stream profile id must not be blank" }
        require(color != null || depth != null) { "stream profile must enable at least one stream" }
    }
}

/** P100R3 当前暴露给 UI 的双流 profile；每个 profile 都是 flag + 分辨率 + fps 的完整组合。 */
object BerxelStreamProfiles {
    val QVGA: List<BerxelStreamProfile> = listOf(15, 30, 45).map { fps ->
        BerxelStreamProfile(
            id = "mix_qvga_$fps",
            flagProfile = BerxelStreamFlagProfile.MIX_QVGA,
            color = BerxelStreamTarget(width = 640, height = 400, fps = fps),
            depth = BerxelStreamTarget(width = 320, height = 200, fps = fps),
        )
    }

    val STANDARD: List<BerxelStreamProfile> = listOf(5, 10, 15, 20, 25, 30, 45).map { fps ->
        BerxelStreamProfile(
            id = "mix_640_$fps",
            flagProfile = BerxelStreamFlagProfile.MIX,
            color = BerxelStreamTarget(width = 640, height = 400, fps = fps),
            depth = BerxelStreamTarget(width = 640, height = 400, fps = fps),
        )
    }

    val HD: List<BerxelStreamProfile> = listOf(5, 10, 15, 20, 25, 30, 45).map { fps ->
        BerxelStreamProfile(
            id = "mix_hd_$fps",
            flagProfile = BerxelStreamFlagProfile.MIX_HD,
            color = BerxelStreamTarget(width = 1280, height = 800, fps = fps),
            depth = BerxelStreamTarget(width = 1280, height = 800, fps = fps),
        )
    }

    val QVGA_15: BerxelStreamProfile = QVGA.requireFps(15)
    val QVGA_30: BerxelStreamProfile = QVGA.requireFps(30)
    val QVGA_45: BerxelStreamProfile = QVGA.requireFps(45)
    val STANDARD_5: BerxelStreamProfile = STANDARD.requireFps(5)
    val STANDARD_10: BerxelStreamProfile = STANDARD.requireFps(10)
    val STANDARD_15: BerxelStreamProfile = STANDARD.requireFps(15)
    val STANDARD_20: BerxelStreamProfile = STANDARD.requireFps(20)
    val STANDARD_25: BerxelStreamProfile = STANDARD.requireFps(25)
    val STANDARD_30: BerxelStreamProfile = STANDARD.requireFps(30)
    val STANDARD_45: BerxelStreamProfile = STANDARD.requireFps(45)
    val HD_5: BerxelStreamProfile = HD.requireFps(5)
    val HD_10: BerxelStreamProfile = HD.requireFps(10)
    val HD_15: BerxelStreamProfile = HD.requireFps(15)
    val HD_20: BerxelStreamProfile = HD.requireFps(20)
    val HD_25: BerxelStreamProfile = HD.requireFps(25)
    val HD_30: BerxelStreamProfile = HD.requireFps(30)
    val HD_45: BerxelStreamProfile = HD.requireFps(45)

    val NATIVE_REWRITE_640_400_45 = BerxelStreamProfile(
        id = "native_rewrite_640x400_45",
        flagProfile = BerxelStreamFlagProfile.SINGULAR,
        color = null,
        depth = BerxelStreamTarget(width = 640, height = 400, fps = 45),
    )

    val DUAL: List<BerxelStreamProfile> = listOf(
        QVGA,
        STANDARD,
        HD,
    ).flatten()

    /** 手机 OTG / USB2 / 外置 HUB 默认用 MIX 640×400@25；HD 档保留给用户手动验证。 */
    val DEFAULT: BerxelStreamProfile = STANDARD_25

    fun fromName(name: String): BerxelStreamProfile? {
        val normalized = name.lowercase().replace("_", "").replace("-", "")
        return when (normalized) {
            "qvga", "qvga15", "mixqvga15" -> QVGA_15
            "standard5", "std5", "mix6405" -> STANDARD_5
            "standard10", "std10", "mix64010" -> STANDARD_10
            "standard", "standard15", "std15", "mix64015" -> STANDARD_15
            "hd", "hd5", "mixhd5" -> HD_5
            "hd10", "mixhd10" -> HD_10
            "hd15", "mixhd15" -> HD_15
            else -> profileByPattern(normalized)
        }
    }

    private fun profileByPattern(name: String): BerxelStreamProfile? {
        return when {
            name.startsWith("hd") -> HD.byFps(name.removePrefix("hd").toIntOrNull())
            name.startsWith("std") -> STANDARD.byFps(name.removePrefix("std").toIntOrNull())
            name.startsWith("standard") -> STANDARD.byFps(name.removePrefix("standard").toIntOrNull())
            name.startsWith("qvga") -> QVGA.byFps(name.removePrefix("qvga").toIntOrNull())
            name.startsWith("mixhd") -> HD.byFps(name.removePrefix("mixhd").toIntOrNull())
            name.startsWith("mix640") -> STANDARD.byFps(name.removePrefix("mix640").toIntOrNull())
            name.startsWith("mixqvga") -> QVGA.byFps(name.removePrefix("mixqvga").toIntOrNull())
            else -> DUAL.firstOrNull { it.id.replace("_", "") == name }
        }
    }

    private fun List<BerxelStreamProfile>.requireFps(fps: Int): BerxelStreamProfile {
        return first { it.depth?.fps == fps || it.color?.fps == fps }
    }

    private fun List<BerxelStreamProfile>.byFps(fps: Int?): BerxelStreamProfile? {
        if (fps == null) return null
        return firstOrNull { it.depth?.fps == fps || it.color?.fps == fps }
    }
}

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
    /** 当前实际接收帧率；SDK getFps() 为 0 时由 service 按到帧间隔测量。 */
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
