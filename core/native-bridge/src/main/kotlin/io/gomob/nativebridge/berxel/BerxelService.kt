package io.gomob.nativebridge.berxel

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.berxel.berxelInterface.api.BerxelHawkContext
import com.berxel.berxelInterface.api.BerxelHawkDevice
import com.berxel.berxelInterface.api.admitenum.BerxelHawkDeviceStatusEnum
import com.berxel.berxelInterface.api.admitenum.BerxelHawkPixelTypeEnum
import com.berxel.berxelInterface.api.admitenum.BerxelHawkStreamFlagEnum
import com.berxel.berxelInterface.api.admitenum.BerxelHawkStreamTypeEnum
import com.berxel.berxelInterface.api.admitmode.BerxelHawkCameraIntrinsic
import com.berxel.berxelInterface.api.admitmode.BerxelHawkDeviceInfo
import com.berxel.berxelInterface.api.admitmode.BerxelHawkFrame
import com.berxel.berxelInterface.api.admitmode.BerxelHawkStreamFrameMode
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gomob.model.CameraIntrinsics
import io.gomob.model.ColorFrame
import io.gomob.model.DepthFrame
import io.gomob.model.RgbdFramePair
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Berxel iHawk 设备的 Hilt @Singleton 包装 —— 整个 App 进程独占一个 SDK Context + 一台 device。
 *
 * 设计：
 * - SDK 的 `BerxelHawkContext` 自身是单例 (`getBerxelContext` 反复调返回同一对象)，
 *   配合 USB BroadcastReceiver / AsyncTask 搞 USB 权限。本类与 SDK 的单例 1:1 对齐。
 * - Reader：双流按厂商 sample 用一个 MIX reader 先试 depth 再试 color；单流 debug 才用独立 reader。
 *   谁拿到帧谁更新自己的 [BerxelFrameStat]，主线程 UI 通过 [colorStat] / [depthStat] StateFlow 订阅。
 * - 帧不出 reader 线程：SDK Frame 持有 native handle，逃出 reader 后做后续处理是 UAF 风险。
 *   后续 fusion / reconstruction 想要帧数据的话，应该在 reader 线程里立刻拷贝 ByteBuffer 出来再丢。
 *
 * Why "service" 而不是更细粒度: 双流必须共用一个 Device 实例（SDK 限制），
 * 所以"全局单例"是 SDK 自带语义不是过度抽象。
 */
@Singleton
class BerxelService @Inject constructor(
    @ApplicationContext private val appContext: Context,
    @BerxelStack private val backend: BerxelStackBackend,
    private val nativeStack: BerxelNativeStack,
) {

    private val _state = MutableStateFlow<BerxelDeviceState>(BerxelDeviceState.Idle)
    val state: StateFlow<BerxelDeviceState> = _state.asStateFlow()

    /**
     * 上一次 [BerxelDeviceState.Streaming] 时收集到的设备信息 — stop 之后**不清空**。
     *
     * 用途：3D 主页设备卡不再常驻打开 SDK，只在点进详情页才连；关掉后用本快照展示已知信息
     * （SN / FW / SDK / 流模式），让用户知道"上次插着的相机是哪台"，不需要重新连。
     *
     * 拔出 / 换设备：下次进详情页连接时自动覆盖本字段；如果用户从未连过，本字段为 null。
     */
    private val _lastKnownInfo = MutableStateFlow<BerxelDeviceInfo?>(null)
    val lastKnownInfo: StateFlow<BerxelDeviceInfo?> = _lastKnownInfo.asStateFlow()

    private val _colorStat = MutableStateFlow<BerxelFrameStat?>(null)
    val colorStat: StateFlow<BerxelFrameStat?> = _colorStat.asStateFlow()

    private val _streamProfile = MutableStateFlow(BerxelStreamProfiles.DEFAULT)
    val streamProfile: StateFlow<BerxelStreamProfile> = _streamProfile.asStateFlow()

    private val _depthStat = MutableStateFlow<BerxelFrameStat?>(null)
    val depthStat: StateFlow<BerxelFrameStat?> = _depthStat.asStateFlow()

    /**
     * Color/Depth/RgbdPair 实时帧流。
     *
     * 设计选择 SharedFlow（不是 StateFlow / Channel）的原因：
     * - StateFlow 会"覆盖未消费帧"，且只对最新值；订阅者跟不上时丢的是旧帧 — 对 30 fps
     *   预览正合适
     * - Channel 是单消费，但我们想多个订阅者（预览 UI + 重建管线 + harness 录制）
     * - extraBufferCapacity = 1 + DROP_OLDEST：reader 永不阻塞；订阅者慢就丢旧
     *
     * 帧数据所有权：emit 出去的 ByteBuffer 是 reader 线程**新分配**的 DirectByteBuffer，
     * SDK 内部 Frame 已 GC（reader 不留引用），消费方可安全长期持有。
     */
    private val _colorFrames = MutableSharedFlow<ColorFrame>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val colorFrames: SharedFlow<ColorFrame> = _colorFrames.asSharedFlow()

    private val _depthFrames = MutableSharedFlow<DepthFrame>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val depthFrames: SharedFlow<DepthFrame> = _depthFrames.asSharedFlow()

    /** IR/phase 帧预览流（companion 0x82 交织的 IR 帧，data=8-bit 灰度 w*h）。供「切 IR」渲染，
     *  与 depthFrames（真深度 16bit mm）分开。复用 DepthFrame 仅作 16/8bit 图像载体。 */
    private val _irFrames = MutableSharedFlow<DepthFrame>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val irFrames: SharedFlow<DepthFrame> = _irFrames.asSharedFlow()

    /** 同 frameIndex 配对的 RGBD pair；VIN 拓印 / fusion 用。 */
    private val _rgbdPairs = MutableSharedFlow<RgbdFramePair>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val rgbdPairs: SharedFlow<RgbdFramePair> = _rgbdPairs.asSharedFlow()

    /** 同 frameIndex 配对器：reader 线程间共享，靠 frameIndex 两边相遇就 emit pair。 */
    @Volatile private var pendingColor: ColorFrame? = null
    @Volatile private var pendingDepth: DepthFrame? = null
    private val pairLock = Any()

    /** 内参：onDeviceOpened 时从 SDK 读出，所有后续帧 emit 时塞进 ColorFrame/DepthFrame.intrinsics。 */
    @Volatile private var currentColorIntrinsics: CameraIntrinsics? = null
    @Volatile private var currentDepthIntrinsics: CameraIntrinsics? = null

    // 12.4 → mm 转换的复用缓冲（DEPTH reader 线程独占；同尺寸帧不重新分配）
    private var depthScratch: ShortArray? = null

    /**
     * 设备控制项快照。所有 set* 方法都把"应用的值"写回 [_controls]，UI 双向绑定。
     *
     * 设计：SDK 没有 getter，我们这一侧记录"我们告诉过 SDK 的最后一个值"。设备拔出 / 重连后
     * [stopInternal] 重置到默认值；下次开流 [applyDefaultControls] 把默认值同步到 SDK。
     */
    private val _controls = MutableStateFlow(BerxelDeviceControls())
    val controls: StateFlow<BerxelDeviceControls> = _controls.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var hawkContext: BerxelHawkContext? = null
    @Volatile private var device: BerxelHawkDevice? = null
    @Volatile private var deviceStatusCallbackRegistered = false
    @Volatile private var readerRunning = false
    @Volatile private var loggedFirstColorFrame = false
    @Volatile private var loggedFirstDepthFrame = false
    private var mixReader: Thread? = null
    private var colorReader: Thread? = null
    private var depthReader: Thread? = null
    private var pendingStartJob: Job? = null
    private var companionRetryJob: Job? = null
    /**
     * OEM 脏缓存 watchdog —— 当 `requestPermission` broadcast 回 false 且 3 次延迟 probe 都
     * 失败时，进 Error 状态后启动本 watchdog 后台每 8s 重新 probe。HONOR Magic OS / Xiaomi
     * HyperOS 都出现过 broadcast deny 但 system_server 异步授权完成的 race；watchdog 让 SDK
     * 不需要用户手动拔插或重启 app 就能自愈。
     */
    private var permissionWatchdogJob: Job? = null
    @Volatile private var partialNodeRetryCount = 0
    @Volatile private var lastPhysicalDisconnectAtMs = 0L
    @Volatile private var pendingUsbPermissionMode = StartupStreamMode.DUAL
    /** 当前生效的 Berxel 流配置；切档时 stop + restart。 */
    @Volatile private var activeStreamProfile: BerxelStreamProfile = BerxelStreamProfiles.DEFAULT
    @Volatile private var lastStreamStartAtMs = 0L
    @Volatile private var lastFirstFrameAtMs = 0L
    @Volatile private var lastStartedProfile: BerxelStreamProfile = BerxelStreamProfiles.DEFAULT
    @Volatile private var lastStartedMode = StartupStreamMode.DUAL
    @Volatile private var streamStartBlockedReason: String? = null
    @Volatile private var debugStartupModeOverride: StartupStreamMode? = null
    /**
     * 实验：DEPTH_ONLY 时让 setStreamFlagMode 走 MIX 路径（而不是默认 SINGULAR）。
     * 用来验证 SDK SINGULAR + DEPTH_ONLY 路径是否漏发 Sonix firmware 的关键初始化命令。
     */
    @Volatile private var debugForceMixModeForSingle: Boolean = false
    /**
     * 实验：DUAL startStreams 成功后立即注入 stopStreams(COLOR) —— 满足 firmware lockstep
     * 后立刻停掉 color BULK transfer，只留 depth 跑，看是否能绕开 25102RKBEC host kill。
     */
    @Volatile private var debugHalfStopColorAfterDual: Boolean = false
    /**
     * 实验：在 IR_STREAM 单流启动前调 enableDeviceSlaveMode(boolean)，试解开 Berxel
     * 加在 Sonix firmware 上的 master/slave 同步约束。null = 不动；true/false = 切换。
     */
    @Volatile private var debugSlaveModeOverride: Boolean? = null
    @Volatile private var stoppingAfterReaderError = false
    private val startEpoch = AtomicLong(0L)
    /**
     * 来自 USB_DEVICE_ATTACHED intent extras / requestPermission broadcast 的 UsbDevice。
     *
     * P100R3 / iHawk100RS 会枚举成两个 USB 节点；Android 的授权按 deviceName 绑定，
     * 所以这里按节点缓存，避免只记住其中一个导致 SDK 内部枚举另一个节点时读 serial 崩掉。
     */
    private val authorizedDevicesByName = ConcurrentHashMap<String, UsbDevice>()

    /**
     * 相机生命周期引用计数 —— 单例硬件资源的唯一 owner。
     *
     * 扫描页（DepthCameraViewModel / Scan3dRecordingViewModel）进场 [acquire]、退场 [release]，
     * 不再各自直接 start()/stop()。导航在两页间切换时旧 VM 的 release 会紧跟新 VM 的 acquire，
     * 计数 0→1→2→1 全程 >0，相机不抖；只有真正没人用（计数归 0 且 [CAMERA_RELEASE_GRACE_MS]
     * 宽限期内无人重新 acquire）才 stop。修掉"双 VM 抢相机"以及"插上即在根页面空跑相机"。
     */
    private val acquireCount = AtomicInteger(0)
    @Volatile private var releaseStopJob: Job? = null

    private val deviceStatusCallback = BerxelHawkContext.DeviceStatusChangedCallBack { vid, pid, status ->
        Log.i(TAG, "device status change vid=0x${vid.toString(16)} pid=0x${pid.toString(16)} -> $status")
        if (status == BerxelHawkDeviceStatusEnum.BERXEL_HAWK_DEVICE_STATUS_DISCONNECT) {
            // 物理断开时 SDK 自身也会 stop/close stream；这里再调 stopInternal 会和厂商线程抢释放。
            scope.launch { handlePhysicalDisconnect() }
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != USB_PERMISSION_ACTION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val grantedDevice = @Suppress("DEPRECATION") intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            Log.i(TAG, "USB permission broadcast granted=$granted device=${grantedDevice?.deviceName}")
            if (granted && grantedDevice != null && needsBerxelSdkUsbPermission(grantedDevice)) {
                // 拿到权限后把 device 喂回 service，再按 backend 重进对应启动路径
                authorizedDevicesByName[grantedDevice.deviceName] = grantedDevice
                resumeStartAfterPermission()
            } else {
                // HONOR Magic OS / Xiaomi HyperOS 实测：用户点了允许后，dumpsys 已经有 device_permissions，
                // 但广播仍可能回 granted=false/device=null。这里不信广播，进入延迟重试 + watchdog 流程。
                scope.launch { retryAfterUsbPermissionBroadcastDenied() }
            }
        }
    }
    @Volatile private var usbReceiverRegistered = false

    /**
     * 扫描页进场：引用计数 +1。0→1 时拉起相机；已有消费者时 start() 幂等无需重复。
     * 取消可能在途的 release 宽限停流，避免导航切换时刚 acquire 又被旧 VM 的延迟 stop 关掉。
     */
    fun acquire() {
        releaseStopJob?.cancel()
        releaseStopJob = null
        val n = acquireCount.incrementAndGet()
        Log.i(TAG, "acquire → count=$n")
        if (n == 1) start()
    }

    /**
     * 扫描页退场：引用计数 -1。归 0 后进 [CAMERA_RELEASE_GRACE_MS] 宽限期；期满仍无人 acquire
     * 才真正 stop。宽限期吸收导航切换时"旧 VM release 紧跟新 VM acquire"的瞬时归零，避免抖动。
     */
    fun release() {
        val n = acquireCount.decrementAndGet()
        Log.i(TAG, "release → count=$n")
        if (n > 0) return
        if (n < 0) acquireCount.set(0) // 容错：release 多于 acquire 时夹回 0
        releaseStopJob?.cancel()
        releaseStopJob = scope.launch {
            delay(CAMERA_RELEASE_GRACE_MS)
            if (acquireCount.get() == 0) {
                Log.i(TAG, "release 宽限期满且无消费者 → stop()")
                stop()
            } else {
                Log.i(TAG, "release 宽限期内有新消费者 acquire，保持相机运行")
            }
        }
    }

    /**
     * 启动整套生命周期：加载 SDK → 弹 USB 权限 → 开 device → 起 reader 线程。
     * 幂等：当前已 Streaming/Opening/WaitingPermission 时直接返回，不重复触发权限弹窗。
     */
    fun start() {
        // M1.6.6 feature flag — backend = NATIVE_REWRITE 时改走 BerxelNativeStack 路径，
        // 不进 SDK BerxelHawkContext。runtime override > buildConfig。
        if (backend == BerxelStackBackend.NATIVE_REWRITE) {
            startNativeRewrite()
            return
        }
        val debugMode = debugStartupModeOverride
        if (debugMode != null) {
            if (_state.value is BerxelDeviceState.Error) {
                Log.d(TAG, "start() ignored under debug stream lock mode=$debugMode current=${_state.value}")
                return
            }
            start(debugMode)
            return
        }
        start(StartupStreamMode.DUAL)
    }

    // ─── NATIVE_REWRITE 路径 ───────────────────────────────────────────────────
    // BerxelNativeStack 是端到端自实现的 libusb-1.0 stack，绕开 SDK 的 libuvc-0.0.7 + 自家 libusb。
    // 目前实测（2026-05-27 sweep）vivo PD2324 上拉不到持续流（≤100ms 内 host kill），但 DI
    // wiring 留在这里方便后续在 2510DRK44C 等 BSP 友好机器上跑通后零代码切换。

    @Volatile private var nativeRewritePullJob: Job? = null
    @Volatile private var nativeRewriteColorJob: Job? = null
    @Volatile private var nativeRewriteRunning: Boolean = false
    @Volatile private var nativeRewriteWatchdogJob: Job? = null
    @Volatile private var nativeRewriteRestartCount: Int = 0
    @Volatile private var nativeMasterStreamDebugOverride: Boolean? = null
    /** Debug：keepalive 间隔 ms override（null=默认 50；0=关闭 keepalive，验证它对 depth/color 共存的影响）。 */
    @Volatile private var nativeKeepaliveMsDebugOverride: Int? = null
    /** Debug：depth fps override（null=默认 45；可切 30/15 对照 1280 高帧率下 device 积压掉线）。 */
    @Volatile private var nativeDepthFpsDebugOverride: Int? = null
    /** ABAB 合成缓冲：拿到一帧 byte1 就缓存，下一帧来跟它 max 合并 emit。 */
    @Volatile private var abPrevB1: ByteArray? = null

    private fun startNativeRewrite() {
        if (nativeRewriteRunning) {
            if (_state.value is BerxelDeviceState.NoDevice) {
                Log.w(TAG, "startNativeRewrite: running flag stale in NoDevice，重置 NativeStack 后重启")
                nativeRewriteRunning = false
                nativeRewritePullJob?.cancel(); nativeRewritePullJob = null
                nativeRewriteColorJob?.cancel(); nativeRewriteColorJob = null
                nativeRewriteWatchdogJob?.cancel(); nativeRewriteWatchdogJob = null
                runCatching { nativeStack.stop() }
                runCatching { nativeStack.invalidateCachedConns() }
            } else {
                Log.d(TAG, "startNativeRewrite() ignored, already running state=${_state.value}")
                return
            }
        }
        when (_state.value) {
            is BerxelDeviceState.Initializing,
            is BerxelDeviceState.Opening,
            is BerxelDeviceState.Streaming -> {
                Log.d(TAG, "startNativeRewrite() ignored, current=${_state.value}")
                return
            }
            else -> Unit
        }
        val enableMasterStreamForThisStart =
            nativeMasterStreamDebugOverride ?: NATIVE_REWRITE_MASTER_STREAM_DEFAULT
        nativeStack.enableMasterStream = enableMasterStreamForThisStart
        Log.i(
            TAG,
            "★ backend=NATIVE_REWRITE — 纯 NativeStack 双流（master MJPEG 0x81 + companion depth 0x82），" +
                "弃 SDK 调用 masterRgb=$enableMasterStreamForThisStart debugOverride=$nativeMasterStreamDebugOverride",
        )
        val token = startEpoch.incrementAndGet()
        nativeRewriteRunning = true
        partialNodeRetryCount = 0
        _state.value = BerxelDeviceState.Initializing
        val job = scope.launch {
            runCatching {
                Log.i(TAG, "NATIVE_REWRITE start coroutine entered token=$token")
                // Step 4：生产 depth 路径切到 native portable 双流（startDualNative），
                // 替代旧 startNativeRewriteInternal 的 Kotlin 编排 + IR-grey 合成（P0）。
                startNativeDualDepthInternal(token)
            }.onFailure { t ->
                Log.e(TAG, "NATIVE_REWRITE start coroutine failed token=$token", t)
                nativeRewriteRunning = false
                _state.value = BerxelDeviceState.Error("NATIVE_REWRITE 启动协程异常: ${t.message}")
                runCatching { nativeStack.stop() }
                runCatching { nativeStack.invalidateCachedConns() }
            }
        }
        Log.i(TAG, "NATIVE_REWRITE start coroutine launched token=$token active=${job.isActive}")
    }

    // Step 4：native portable 双流 depth 生产路径（替代旧 startNativeRewriteInternal）。
    // XU replay / dense depth controls / UVC commit / bulk pump / 帧组装全在 C++ portable 层
    // （NativeBridge.berxelDualStart），这里只 poll 出 active 16bit mm depth 直接 emit DepthFrame，
    // 不再做 IR-grey 合成。已在小米 2510DRK44C 实机验证：valid=1.000 稠密 depth、center≈423mm、err=0。
    // master-color 在该硬件输出全 0（旧路同样），单独排查，暂 enableColor=false。
    private suspend fun startNativeDualDepthInternal(token: Long) {
        Log.i(TAG, "NATIVE dual-depth startInternal token=$token running=$nativeRewriteRunning")
        if (token != startEpoch.get() || !nativeRewriteRunning) return
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDevices = currentBerxelUsbDevices(usbManager)
        if (usbDevices.isEmpty()) {
            _state.value = BerxelDeviceState.NoDevice
            nativeRewriteRunning = false
            return
        }
        // USB 权限预检：startDualNative 内部直接 openDevice 拿 fd，未授权会 SecurityException。
        // 重装 / 首次启动 / 用户拒过一次后，主动 requestPermission 弹系统框；master/companion 任一缺权限
        // 都先请求，granted 后 receiver 以新 token 重进本路径，逐个补齐（保持 nativeRewriteRunning=true）。
        // 只查 hasPermission（不做 openDevice 探测）：HONOR Magic OS 实测，无权限时 openDevice 不抛
        // SecurityException 而是 binder 阻塞 ~68s，trial-open 会把预检卡死、永远到不了 requestPermission，
        // 弹窗出不来。故 !hasPermission 直接请求权限；脏缓存（hasPermission=false 但实际已授）由
        // requestPermission broadcast 回调 + retryAfterUsbPermissionBroadcastDenied 重试链兜底。
        val missingPermissionDevice = usbDevices.firstOrNull { !usbManager.hasPermission(it) }
        if (missingPermissionDevice != null) {
            Log.i(TAG, "NATIVE dual-depth 需要 USB 权限 ${usbLabel(missingPermissionDevice)}")
            requestUsbPermission(usbManager, missingPermissionDevice)
            return  // 等 receiver granted 回调重进；不复位 nativeRewriteRunning
        }
        _state.value = BerxelDeviceState.Opening
        // enableColor=true 开 master 视频流：vivo 上 keepalive-only 会让 master/总线 ~100ms 后 LIBUSB_ERROR_IO
        // （master+companion 同时 IO 故障），必须有活跃 master 视频流维持主控（旧 enableMasterStream=true 路径
        // 在 vivo+hub 实测 14310 reads 0 错误）。小米 keepalive-only 也行但开 color 不影响 depth。
        // 注：master color 当前输出全 0（独立排查中），但 bulk 活动本身就维持总线，depth 不受影响。
        // 传 authorizedDevicesByName：HONOR 上 deviceList 实例无权限，必须用 intent 授权实例 open。
        // RGBD 终态：master color + companion depth 双流。
        // 注：HONOR depth-only 诊断（2026-05-29）证 master color commit 反而维持 master 连接，
        // 不开 color 时 master 立刻 NO_DEVICE；HONOR 真正卡点是 master keepalive set_cur 跑不通（设备/BSP 级）。
        // enableColor 接 debug override（setNativeMasterStreamForDebug）：默认 true=RGBD 双流；
        // 诊断时可 broadcast master_rgb=false 切 depth-only，隔离「color 是否破坏 master keepalive」根因。
        val enableColor = nativeMasterStreamDebugOverride ?: NATIVE_REWRITE_MASTER_STREAM_DEFAULT
        val keepaliveMs = nativeKeepaliveMsDebugOverride ?: 50
        val depthFps = nativeDepthFpsDebugOverride ?: 45
        Log.i(TAG, "NATIVE dual-depth enableColor=$enableColor (override=$nativeMasterStreamDebugOverride) keepaliveMs=$keepaliveMs depthFps=$depthFps")
        val handle = nativeStack.startDualNative(
            usbManager,
            enableColor = enableColor,
            authorizedByName = authorizedDevicesByName,
            keepaliveMs = keepaliveMs,
            depthFps = depthFps,
        )
        if (handle == 0L) {
            _state.value = BerxelDeviceState.Error("startDualNative 失败: ${nativeStack.lastError()}")
            nativeRewriteRunning = false
            return
        }
        if (token != startEpoch.get() || !nativeRewriteRunning) {
            nativeStack.stopDualNative()
            return
        }
        val nativeProfile = nativeRewriteProfile(enableMasterStream = false)
        val depthTarget = nativeProfile.depth ?: BerxelStreamTarget(width = 640, height = 400, fps = 45)
        activeStreamProfile = nativeProfile
        _streamProfile.value = nativeProfile
        _colorStat.value = null
        _depthStat.value = null
        _state.value = BerxelDeviceState.Streaming(
            BerxelDeviceInfo(
                vendorId = BERXEL_VID,
                productId = P100R3_PRIMARY_PID,
                serialNumber = "NATIVE_REWRITE",
                deviceAddress = "",
                firmwareVersion = "",
                sdkVersion = "NATIVE_REWRITE",
                streamFlagMode = BerxelStreamFlagProfile.SINGULAR,
                requestedProfileId = nativeProfile.id,
                colorMode = null,
                depthMode = BerxelStreamSpec(
                    width = 1280,
                    height = 800,
                    fps = 45,
                    pixelType = "BERXEL_HAWK_PIXEL_TYPE_DEP_16BIT_13I_3D",
                ),
            ),
        )

        nativeRewritePullJob = scope.launch {
            // buffer 按设备【最大】depth（1280x800）分配，分辨率无关：poll 实际写 active*2 字节，
            // DepthFrame 用 outInfo 回报的真实 w/h。这样 640/1280 切换都不会出现 buffer 不够（poll 返 -1）。
            val activeW = 1280
            val activeH = 800
            val bytes = activeW * activeH * 2
            var buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val outInfo = LongArray(4)
            // 逐像素 confidence（uint8，飞点=0）：与 depth 同帧取，随 DepthFrame 一起发出供下游按 conf 取点。
            val confBytes = activeW * activeH
            var confBuffer = ByteBuffer.allocateDirect(confBytes)
            val confOutInfo = LongArray(4)
            var lastFrameNumber = -1L
            var frameIdx = 0
            // IR/phase 帧预览：8-bit 灰度 w*h，独立 buffer + dedup，emit 到 _irFrames（「切 IR」用）
            val irBytes = activeW * activeH
            var irBuffer = ByteBuffer.allocateDirect(irBytes)
            val irOutInfo = LongArray(4)
            var lastIrFrameNumber = -1L
            var irIdx = 0
            var statWindowStartMs = SystemClock.elapsedRealtime()
            var statWindowStartFrame = 0
            var measuredFps = 0
            // 存活看门狗：native pump 在 vivo 上可能 stream 后立刻 LIBUSB_ERROR_IO 死（depth_seq 不再涨，
            // poll 恒返 0）。Kotlin 侧必须检出 session 死 → 复位状态，否则卡假 Streaming 忽略所有 re-attach。
            var lastFrameMs = SystemClock.elapsedRealtime()
            var sessionDead = false
            while (isActive && nativeRewriteRunning) {
                buffer.clear()
                val n = nativeStack.dualPollDepthMm(buffer, outInfo)
                if (n <= 0) {
                    if (SystemClock.elapsedRealtime() - lastFrameMs > NATIVE_DUAL_FRAME_TIMEOUT_MS) {
                        sessionDead = true
                        break
                    }
                    delay(8); continue
                }
                val frameNumber = outInfo[2]
                if (frameNumber == lastFrameNumber) {
                    // pump 死后可能残留最后一帧 → poll 恒返同一 frameNumber；同样要走死亡看门狗，
                    // 否则 dedup 分支一直 delay(4) 永不超时 → 卡假 Streaming（"拿了几帧后死" case）。
                    if (SystemClock.elapsedRealtime() - lastFrameMs > NATIVE_DUAL_FRAME_TIMEOUT_MS) {
                        sessionDead = true
                        break
                    }
                    delay(4); continue
                }
                lastFrameNumber = frameNumber
                val w = outInfo[0].toInt().let { if (it > 0) it else activeW }
                val h = outInfo[1].toInt().let { if (it > 0) it else activeH }
                buffer.position(0).limit(n)
                val emitted = buffer
                // 下一帧用新 buffer，避免 consumer 读时被下次 poll 覆盖（零拷贝 + 不竞争）
                buffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN)
                // 同帧取 confidence（飞点=0）；frameNumber 必须与 depth 一致，否则两次 poll 跨了 native 更新
                // 拿到错配的旧 conf → 宁可置 null 不发错配 mask。
                confBuffer.clear()
                val cn = nativeStack.dualPollDepthConf(confBuffer, confOutInfo)
                val confEmitted: ByteBuffer? = if (cn > 0 && confOutInfo[2] == frameNumber) {
                    confBuffer.position(0).limit(cn)
                    val e = confBuffer
                    confBuffer = ByteBuffer.allocateDirect(confBytes)
                    e
                } else null
                val intr = currentDepthIntrinsics ?: CameraIntrinsics(
                    fx = 0.0, fy = 0.0, cx = 0.0, cy = 0.0,
                    distortion = DoubleArray(5), width = w, height = h,
                )
                val outFrameIdx = ++frameIdx
                val nowMs = SystemClock.elapsedRealtime()
                lastFrameMs = nowMs
                val elapsedMs = nowMs - statWindowStartMs
                if (elapsedMs >= 1_000L) {
                    measuredFps = (((outFrameIdx - statWindowStartFrame) * 1_000L) / elapsedMs)
                        .toInt().coerceAtLeast(0)
                    statWindowStartMs = nowMs
                    statWindowStartFrame = outFrameIdx
                }
                val tsUs = outInfo[3] / 1000L
                _depthStat.value = BerxelFrameStat(
                    frameIndex = outFrameIdx,
                    measuredFps = measuredFps,
                    timestampUs = tsUs,
                    receivedAtElapsedMs = nowMs,
                    width = w,
                    height = h,
                )
                _depthFrames.emit(
                    DepthFrame(
                        timestampUs = tsUs,
                        frameIndex = outFrameIdx,
                        width = w,
                        height = h,
                        data = emitted,
                        intrinsics = intr,
                        registeredToColor = false,
                        confidence = confEmitted,
                    ),
                )
                // DUMP 按钮触发：dump 接下来 N 帧【完整原始 transport 字节】到 app private files，
                // adb pull 离线逐字节分析 4B/px 结构 / 验证劈帧。文件 dual_raw_NN.bin。
                if (dumpRemaining > 0) {
                    try {
                        val seq = dumpRemaining
                        val out = java.io.File(appContext.getExternalFilesDir(null), "dual_raw_%02d.bin".format(31 - seq))
                        val wrote = nativeStack.dualDumpRawDepth(out.absolutePath)
                        if (seq == 30 || seq <= 3 || seq % 10 == 0) {
                            Log.i(TAG, "★ dual dump #${31 - seq} → ${out.absolutePath} wrote=${wrote}B (剩 ${seq - 1})")
                        }
                        dumpRemaining = seq - 1
                    } catch (t: Throwable) { Log.w(TAG, "dual dump fail", t); dumpRemaining = 0 }
                }
                // IR/phase 预览帧（companion 交织的 IR，8-bit 灰度）→ _irFrames，供「切 IR」渲染。
                irBuffer.clear()
                val irn = nativeStack.dualPollIrGrey(irBuffer, irOutInfo)
                if (irn > 0 && irOutInfo[2] != lastIrFrameNumber) {
                    lastIrFrameNumber = irOutInfo[2]
                    val irw = irOutInfo[0].toInt().let { if (it > 0) it else activeW }
                    val irh = irOutInfo[1].toInt().let { if (it > 0) it else activeH }
                    irBuffer.position(0).limit(irn)
                    val irEmitted = irBuffer
                    irBuffer = ByteBuffer.allocateDirect(irBytes)
                    _irFrames.emit(
                        DepthFrame(
                            timestampUs = irOutInfo[3] / 1000L,
                            frameIndex = ++irIdx,
                            width = irw,
                            height = irh,
                            data = irEmitted,
                            intrinsics = currentDepthIntrinsics ?: CameraIntrinsics(
                                fx = 0.0, fy = 0.0, cx = 0.0, cy = 0.0,
                                distortion = DoubleArray(5), width = irw, height = irh,
                            ),
                            registeredToColor = false,
                        ),
                    )
                }
            }
            Log.i(TAG, "NATIVE dual-depth pull exit frames=$frameIdx dead=$sessionDead")
            // session 死（pump IO 掉线、poll 长时间 0 帧）→ 复位，让 re-attach / 重进本页能重连。
            // 不靠 token 失配退出的正常 stop 不走这里（nativeRewriteRunning 已被 stop 置 false）。
            if (sessionDead && nativeRewriteRunning && token == startEpoch.get()) {
                Log.e(TAG, "NATIVE dual-depth ${NATIVE_DUAL_FRAME_TIMEOUT_MS}ms 无帧，判定 session 死，复位")
                runCatching { nativeStack.stopDualNative() }
                nativeRewriteRunning = false
                nativeRewritePullJob = null
                _depthStat.value = null
                _state.value = BerxelDeviceState.Error("双流 session 掉线（USB IO）— 重新插拔相机或重进本页重连")
            }
        }

        // master color pull job：poll 最新 MJPEG → BitmapFactory 解码 → RGB24 → _colorFrames（UI「COLOR」框）。
        nativeRewriteColorJob = scope.launch {
            var colorIdx = 0
            var pixelsScratch: IntArray? = null
            while (isActive && nativeRewriteRunning) {
                val mjpeg = nativeStack.dualPollColorMjpeg()
                if (mjpeg == null) { delay(8); continue }
                val bmp = runCatching { BitmapFactory.decodeByteArray(mjpeg, 0, mjpeg.size) }.getOrNull()
                if (bmp == null) {
                    if (colorIdx < 3) Log.w(TAG, "dual color MJPEG#${colorIdx + 1} decode null size=${mjpeg.size}")
                    delay(8); continue
                }
                val cw = bmp.width
                val ch = bmp.height
                val pixels = pixelsScratch?.takeIf { it.size >= cw * ch }
                    ?: IntArray(cw * ch).also { pixelsScratch = it }
                bmp.getPixels(pixels, 0, cw, 0, 0, cw, ch)
                bmp.recycle()
                val direct = ByteBuffer.allocateDirect(cw * ch * 3).order(ByteOrder.LITTLE_ENDIAN)
                var i = 0
                val totalPx = cw * ch
                while (i < totalPx) {
                    val argb = pixels[i]
                    direct.put(((argb ushr 16) and 0xff).toByte())  // R
                    direct.put(((argb ushr 8) and 0xff).toByte())   // G
                    direct.put((argb and 0xff).toByte())            // B
                    i++
                }
                direct.position(0).limit(cw * ch * 3)
                val cIdx = ++colorIdx
                if (cIdx <= 3 || cIdx % 100 == 0) {
                    Log.i(TAG, "dual color MJPEG#$cIdx decoded ${cw}x${ch} mjpeg=${mjpeg.size}B → rgb24")
                }
                _colorStat.value = BerxelFrameStat(
                    frameIndex = cIdx, measuredFps = 0,
                    timestampUs = SystemClock.elapsedRealtimeNanos() / 1000L,
                    receivedAtElapsedMs = SystemClock.elapsedRealtime(),
                    width = cw, height = ch,
                )
                _colorFrames.emit(
                    ColorFrame(
                        timestampUs = SystemClock.elapsedRealtimeNanos() / 1000L,
                        frameIndex = cIdx,
                        width = cw, height = ch,
                        data = direct,
                        pixelType = "BERXEL_HAWK_PIXEL_TYPE_IMAGE_RGB24",
                        intrinsics = currentColorIntrinsics ?: CameraIntrinsics(
                            fx = 0.0, fy = 0.0, cx = 0.0, cy = 0.0,
                            distortion = DoubleArray(5), width = cw, height = ch,
                        ),
                    ),
                )
            }
            Log.i(TAG, "NATIVE dual-depth color pull exit frames=$colorIdx")
        }
    }

    private suspend fun startNativeRewriteInternal(token: Long) {
        Log.i(TAG, "NATIVE_REWRITE startInternal begin token=$token epoch=${startEpoch.get()} running=$nativeRewriteRunning")
        if (token != startEpoch.get() || !nativeRewriteRunning) return
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDevices = currentBerxelUsbDevices(usbManager)
        Log.i(
            TAG,
            "NATIVE_REWRITE visible USB nodes=${usbDevices.joinToString { usbLabel(it) }}",
        )
        if (usbDevices.isEmpty()) {
            _state.value = BerxelDeviceState.NoDevice
            nativeRewriteRunning = false
            return
        }

        val hasMaster = usbDevices.any { it.vendorId == BERXEL_VID && it.productId == P100R3_PRIMARY_PID }
        val hasCompanion = usbDevices.any {
            it.vendorId == P100R3_COMPANION_VID && it.productId == P100R3_COMPANION_PID
        }
        if (!hasMaster || !hasCompanion) {
            partialNodeRetryCount++
            Log.i(
                TAG,
                "NATIVE_REWRITE wait full P100R3 nodes retry=$partialNodeRetryCount " +
                    "nodes=${usbDevices.joinToString { usbLabel(it) }}",
            )
            if (partialNodeRetryCount > NATIVE_REWRITE_NODE_PAIR_RETRY_LIMIT) {
                _state.value = BerxelDeviceState.Error(
                    "只看到 ${p100r3VisibleNodeLabel(hasMaster, hasCompanion)}，master/companion USB 节点未同时可见；" +
                        "请保持本页打开后重新插拔 iHawk，vivo + Hub 冷启动时 master 可能被系统 UVC 抢走"
                )
                nativeRewriteRunning = false
                return
            }
            companionRetryJob?.cancel()
            companionRetryJob = scope.launch {
                delay(COMPANION_ONLY_RETRY_DELAY_MS)
                if (token == startEpoch.get() && nativeRewriteRunning && _state.value is BerxelDeviceState.Initializing) {
                    startNativeRewriteInternal(token)
                }
            }
            return
        }

        companionRetryJob?.cancel()
        companionRetryJob = null
        partialNodeRetryCount = 0
        _state.value = BerxelDeviceState.Opening

        // NativeStack.start 内部：master vc+vs claim → XU5 init+keepalive → companion vc+vs claim →
        // Sonix init → companion probe/commit → companion BULK pull → master probe/commit (MJPEG 640×360@30) →
        // master openStream → master BULK pull
        Log.i(TAG, "NATIVE_REWRITE nativeStack.start begin masterRgb=${nativeStack.enableMasterStream}")
        val started = nativeStack.start(
            usbManager = usbManager,
            extraDevices = usbDevices,
            companionOnly = false,
        )
        Log.i(TAG, "NATIVE_REWRITE nativeStack.start end started=$started err=${nativeStack.lastError()}")
        if (!started) {
            _state.value = BerxelDeviceState.Error("NativeStack start 失败: ${nativeStack.lastError()}")
            nativeRewriteRunning = false
            return
        }
        if (token != startEpoch.get() || !nativeRewriteRunning) {
            nativeStack.stop()
            return
        }
        val nativeProfile = nativeRewriteProfile(enableMasterStream = nativeStack.enableMasterStream)
        val nativeDepthTarget = nativeProfile.depth ?: BerxelStreamTarget(
            width = BerxelFrameAssembler.DEFAULT_WIDTH,
            height = BerxelFrameAssembler.DEFAULT_HEIGHT,
            fps = 45,
        )
        activeStreamProfile = nativeProfile
        _streamProfile.value = nativeProfile
        _colorStat.value = null
        _depthStat.value = null
        // 单一 Streaming 状态：NATIVE_REWRITE 暂无 SDK 标定，模式信息只填真实 native 流。
        _state.value = BerxelDeviceState.Streaming(
            BerxelDeviceInfo(
                vendorId = BERXEL_VID,
                productId = P100R3_PRIMARY_PID,
                serialNumber = "NATIVE_REWRITE",
                deviceAddress = "",
                firmwareVersion = "",
                sdkVersion = "NATIVE_REWRITE",
                streamFlagMode = BerxelStreamFlagProfile.SINGULAR,
                requestedProfileId = nativeProfile.id,
                colorMode = if (nativeStack.enableMasterStream) {
                    BerxelStreamSpec(
                        width = MASTER_MJPEG_WIDTH,
                        height = MASTER_MJPEG_HEIGHT,
                        fps = MASTER_MJPEG_FPS,
                        pixelType = "BERXEL_HAWK_PIXEL_TYPE_IMAGE_RGB24",
                    )
                } else {
                    null
                },
                depthMode = BerxelStreamSpec(
                    width = nativeDepthTarget.width,
                    height = nativeDepthTarget.height,
                    fps = nativeDepthTarget.fps,
                    pixelType = "IR_GREY8_AB_MAX_NATIVE_REWRITE",
                ),
            ),
        )

        // watchdog：vivo+hub 上 NativeStack 偶尔 ~1s 后 NO_DEVICE 死，监听 state == Error 自动重启
        nativeRewriteWatchdogJob?.cancel()
        nativeRewriteWatchdogJob = scope.launch {
            nativeStack.state.collect { st ->
                if (st == BerxelNativeStack.State.Error && nativeRewriteRunning && token == startEpoch.get()) {
                    val nativeError = nativeStack.lastError()
                    if (nativeError == "stream died after pull loop saturation" && nativeStack.depthFramesOut() == 0L) {
                        val bytesIn = nativeStack.depthBytesIn()
                        Log.e(
                            TAG,
                            "★ NATIVE_REWRITE depth BULK 已打开但未成帧：in=${bytesIn}B；停止自动重启，避免 USB 反复重枚举",
                        )
                        nativeRewriteRunning = false
                        nativeRewritePullJob?.cancel(); nativeRewritePullJob = null
                        nativeRewriteColorJob?.cancel(); nativeRewriteColorJob = null
                        runCatching { nativeStack.stop() }
                        runCatching { nativeStack.invalidateCachedConns() }
                        _state.value = BerxelDeviceState.Error(
                            "NATIVE_REWRITE 已打开 master+companion，但深度 BULK 在 ${bytesIn}B 后断流，未拼出完整帧；这不是 master USB 节点缺失",
                        )
                        return@collect
                    }
                    nativeRewriteRestartCount++
                    Log.w(TAG, "★ NATIVE_REWRITE watchdog: NativeStack Error ($nativeError); 第 $nativeRewriteRestartCount 次自动 restart")
                    nativeRewriteRunning = false
                    nativeRewritePullJob?.cancel(); nativeRewritePullJob = null
                    nativeRewriteColorJob?.cancel(); nativeRewriteColorJob = null
                    runCatching { nativeStack.stop() }
                    // 每次 restart 强制清 conn cache — vivo+hub 每次 NO_DEVICE 后 USB path 都
                    // 重新 enumerate，cached fd 无效但数字仍 >=0，wrap_sys_device 全部 -2002 死循环
                    runCatching { nativeStack.invalidateCachedConns() }
                    delay(800)
                    // 重置 _state 到 Idle，否则 startNativeRewrite() 入口的 Streaming/Opening guard
                    // 会把自动 restart 给 ignored 掉
                    _state.value = BerxelDeviceState.Idle
                    if (nativeRewriteRestartCount < NATIVE_REWRITE_MAX_RESTARTS) {
                        startNativeRewrite()
                    } else {
                        Log.e(TAG, "★ NATIVE_REWRITE watchdog: 已 restart $nativeRewriteRestartCount 次，放弃自动重启")
                        _state.value = BerxelDeviceState.Error("NATIVE_REWRITE 反复重启失败，请拔插相机")
                    }
                    return@collect
                }
            }
        }

        // depth pull job
        nativeRewritePullJob = scope.launch {
            var frameIdx = 0
            var statWindowStartMs = SystemClock.elapsedRealtime()
            var statWindowStartFrame = 0
            var measuredFps = 0
            while (isActive && nativeRewriteRunning) {
                val f = nativeStack.pollFrame()
                if (f == null) {
                    delay(8)
                    continue
                }
                if (frameIdx < 5 || (frameIdx + 1) % 200 == 0) {
                    val s = f.stats()
                    Log.i(TAG, "★ NATIVE_REWRITE depth#${frameIdx + 1} size=${f.data.size}B w=${f.width} h=${f.height} stats: $s")
                }
                // dump 触发：UI 按钮调 triggerDump(N)，把接下来 N 帧 raw 字节 dump 到 app
                // private files。文件名 dump_<seq>.bin，host adb pull 离线分析。
                if (dumpRemaining > 0) {
                    try {
                        val seq = dumpRemaining  // count down 顺序：30,29,...,1
                        val out = java.io.File(appContext.getExternalFilesDir(null), "dump_%02d.bin".format(31 - seq))
                        out.writeBytes(f.data)
                        if (seq == 30 || seq == 1 || seq % 10 == 0) {
                            Log.i(TAG, "★ dump frame#${31 - seq} → ${out.absolutePath} (剩 ${seq - 1})")
                        }
                        dumpRemaining = seq - 1
                    } catch (t: Throwable) { Log.w(TAG, "dump frame fail", t); dumpRemaining = 0 }
                }
                // M1.6.8 修正：之前以为是 12.4 定点 depth，2026-05-28 dump 分析后证伪 —
                // firmware 实际推 IR raw，每像素 2 byte：byte0=phase code (13 unique values),
                // byte1=IR luminance (8-bit grey)。depth 由 SDK reconstruct，我们 NATIVE_REWRITE
                // 跳过 SDK 拿不到 depth；先把 byte1 提取出来当 8-bit grey 渲染让用户看到 IR 预览。
                //
                // 关键：firmware 两种 frame 交替推（98% nonzero 的 IR 亮帧 + 18-37% nonzero 的
                // pattern off 暗帧），UI 渲染时一亮一暗闪烁。计算 byte1 mean，太暗的 drop。
                // firmware ABAB 交替推两种 frame（投影 on/off），按 byte1 max 合成 2 帧 → 1 帧
                // 输出。亮帧 byte1 大 → max 保留 IR 散斑亮度；视觉稳定 (~22fps)。
                val pixelCount = f.data.size / 2
                val curB1 = ByteArray(pixelCount)
                for (i in 0 until pixelCount) curB1[i] = f.data[i * 2 + 1]
                val prev = abPrevB1
                if (prev == null || prev.size != pixelCount) {
                    abPrevB1 = curB1
                    continue
                }
                // 2 帧 max 合成
                val grey = ByteBuffer.allocateDirect(pixelCount).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until pixelCount) {
                    val a = prev[i].toInt() and 0xff
                    val b = curB1[i].toInt() and 0xff
                    grey.put((if (a >= b) a else b).toByte())
                }
                grey.position(0).limit(pixelCount)
                abPrevB1 = null  // 重置，下 2 帧再合一帧
                val intr = currentDepthIntrinsics ?: CameraIntrinsics(
                    fx = 0.0, fy = 0.0, cx = 0.0, cy = 0.0,
                    distortion = DoubleArray(5),
                    width = f.width, height = f.height,
                )
                val outFrameIdx = ++frameIdx
                val nowMs = SystemClock.elapsedRealtime()
                val elapsedMs = nowMs - statWindowStartMs
                if (elapsedMs >= 1_000L) {
                    measuredFps = (((outFrameIdx - statWindowStartFrame) * 1_000L) / elapsedMs)
                        .toInt()
                        .coerceAtLeast(0)
                    statWindowStartMs = nowMs
                    statWindowStartFrame = outFrameIdx
                }
                _depthStat.value = BerxelFrameStat(
                    frameIndex = outFrameIdx,
                    measuredFps = measuredFps,
                    timestampUs = f.timestampNs / 1000L,
                    receivedAtElapsedMs = nowMs,
                    width = f.width,
                    height = f.height,
                )
                _depthFrames.emit(
                    DepthFrame(
                        timestampUs = f.timestampNs / 1000L,
                        frameIndex = outFrameIdx,
                        width = f.width,
                        height = f.height,
                        data = grey,
                        intrinsics = intr,
                        registeredToColor = false,
                    ),
                )
            }
            Log.i(TAG, "★ NATIVE_REWRITE depth pull exit frames=$frameIdx")
        }

        // master RGB pull job：MJPEG → BitmapFactory → ARGB Int[] → RGB24 ByteBuffer
        nativeRewriteColorJob = scope.launch {
            var frameIdx = 0
            var pixelsScratch: IntArray? = null
            while (isActive && nativeRewriteRunning) {
                val mjpeg = nativeStack.pollMjpegFrame()
                if (mjpeg == null) {
                    delay(8)
                    continue
                }
                val bmp = runCatching { BitmapFactory.decodeByteArray(mjpeg, 0, mjpeg.size) }
                    .getOrNull()
                if (bmp == null) {
                    if (frameIdx < 3) Log.w(TAG, "★ NATIVE_REWRITE master MJPEG#${frameIdx + 1} decode null size=${mjpeg.size}")
                    continue
                }
                val w = bmp.width
                val h = bmp.height
                val pixels = pixelsScratch?.takeIf { it.size >= w * h }
                    ?: IntArray(w * h).also { pixelsScratch = it }
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                bmp.recycle()
                val direct = ByteBuffer.allocateDirect(w * h * 3).order(ByteOrder.LITTLE_ENDIAN)
                val totalPx = w * h
                var i = 0
                while (i < totalPx) {
                    val argb = pixels[i]
                    direct.put(((argb ushr 16) and 0xff).toByte())  // R
                    direct.put(((argb ushr 8) and 0xff).toByte())   // G
                    direct.put((argb and 0xff).toByte())            // B
                    i++
                }
                direct.position(0).limit(w * h * 3)
                if (frameIdx < 3) {
                    Log.i(TAG, "★ NATIVE_REWRITE master MJPEG#${frameIdx + 1} decoded ${w}x${h} mjpeg=${mjpeg.size}B → rgb24=${w * h * 3}B")
                }
                val intr = currentColorIntrinsics ?: CameraIntrinsics(
                    fx = 0.0, fy = 0.0, cx = 0.0, cy = 0.0,
                    distortion = DoubleArray(5),
                    width = w, height = h,
                )
                _colorFrames.emit(
                    ColorFrame(
                        timestampUs = SystemClock.elapsedRealtimeNanos() / 1000L,
                        frameIndex = ++frameIdx,
                        width = w,
                        height = h,
                        data = direct,
                        pixelType = "BERXEL_HAWK_PIXEL_TYPE_IMAGE_RGB24",
                        intrinsics = intr,
                    ),
                )
            }
            Log.i(TAG, "★ NATIVE_REWRITE color pull exit frames=$frameIdx; ${nativeStack.masterStreamStats()}")
        }
    }

    private fun stopNativeRewrite() {
        if (!nativeRewriteRunning) return
        Log.i(TAG, "★ NATIVE_REWRITE stop (NativeStack 双流)")
        nativeRewriteRunning = false
        nativeRewritePullJob?.cancel()
        nativeRewritePullJob = null
        nativeRewriteColorJob?.cancel()
        nativeRewriteColorJob = null
        runCatching { nativeStack.stopDualNative() }
            .onFailure { Log.w(TAG, "NativeStack.stopDualNative 异常", it) }
        runCatching { nativeStack.stop() }
            .onFailure { Log.w(TAG, "NativeStack.stop 异常", it) }
        nativeMasterStreamDebugOverride = null
        nativeStack.enableMasterStream = false
        abPrevB1 = null
        _state.value = BerxelDeviceState.Idle
    }

    private fun nativeRewriteProfile(enableMasterStream: Boolean): BerxelStreamProfile {
        val base = BerxelStreamProfiles.NATIVE_REWRITE_640_401_45
        return base.copy(
            id = if (enableMasterStream) {
                "native_rewrite_color_640x400_15_depth_640x401_45"
            } else {
                base.id
            },
            color = if (enableMasterStream) {
                BerxelStreamTarget(
                    width = MASTER_MJPEG_WIDTH,
                    height = MASTER_MJPEG_HEIGHT,
                    fps = MASTER_MJPEG_FPS,
                )
            } else {
                null
            },
        )
    }

    fun startColorOnlyForDebug() {
        debugStartupModeOverride = StartupStreamMode.COLOR_ONLY
        start(StartupStreamMode.COLOR_ONLY)
    }

    fun startDepthOnlyForDebug() {
        debugStartupModeOverride = StartupStreamMode.DEPTH_ONLY
        debugForceMixModeForSingle = false
        start(StartupStreamMode.DEPTH_ONLY)
    }

    /**
     * 实验：DEPTH_ONLY 但让 setStreamFlagMode 走 MIX 模式（而不是 SINGULAR）。
     * 验证假设：SINGULAR + DEPTH_ONLY 失败是因为 Sonix firmware 在 SINGULAR mode 下
     * 不完整初始化 IR pipeline；如果用 MIX_QVGA mode 启 single DEPTH 能跑通，
     * 就证明可以通过这条路径在 25102RKBEC 上拿到 USB3 IR 单流。
     */
    fun startDepthInMixModeForDebug() {
        debugStartupModeOverride = StartupStreamMode.DEPTH_ONLY
        debugForceMixModeForSingle = true
        start(StartupStreamMode.DEPTH_ONLY)
    }

    /**
     * 实验：startStreams(DUAL=3) 满足 Sonix firmware lockstep → 立即 stopStreams(COLOR=1) 停 color stream，
     * 留 depth 单流跑。假设：(1) Berxel SDK 的 stopStreams(int flags) 真的支持单流 stop；
     * (2) 停掉 color 后 host 端不再 submit color BULK transfer，25102RKBEC host stack
     * 看不到两个 device 同时活跃 → 不触发 host kill bug；(3) Sonix companion 不依赖 color 持续
     * streaming 维持 depth 路径。三个假设都对的话，1280 USB3 单深度流就通了。
     */
    /**
     * 实验：单独启 IR_STREAM (flags=4, 即 BERXEL_HAWK_IR_STREAM)。
     * 假设：DEPTH stream 的 lockstep 是因为 SDK 需要做 RGBD 对齐 ←→ 需要 color；
     * IR_STREAM 是 Sonix companion 直接出的散斑 raw 图，理论上不依赖 color。
     * 如果跑通：可以拉 IR raw + host 端调 inner_process_with_IR 自己算 depth，
     * 完全绕开 25102RKBEC host kill + DEPTH lockstep。
     */
    /** IR_STREAM + 切到 slave 模式（true） */
    fun startIrOnlySlaveTrueForDebug() {
        debugSlaveModeOverride = true
        startIrOnlyForDebug()
    }

    /** IR_STREAM + 切到 master 模式（slave=false） */
    fun startIrOnlySlaveFalseForDebug() {
        debugSlaveModeOverride = false
        startIrOnlyForDebug()
    }

    fun startIrOnlyForDebug() {
        debugForceMixModeForSingle = false
        debugHalfStopColorAfterDual = false
        // 注意：debugSlaveModeOverride 由调用方设置（startIrOnlySlaveTrue/False/None）
        streamStartBlockedReason = null
        scope.launch {
            // 入口先清掉所有可能在跑的 stream + 清掉残留 reader thread，避免 state 污染。
            device?.let { dev ->
                val allFlags = 1 or 2 or 4 or 32
                runCatching { dev.stopStreams(allFlags) }
                    .onFailure { Log.w(TAG, "★ IR only: 清 stream 异常", it) }
                Log.i(TAG, "★ IR only: 入口 stopStreams(ALL=$allFlags)")
            }
            readerRunning = false
            runCatching { colorReader?.interrupt() }
            runCatching { depthReader?.interrupt() }
            runCatching { mixReader?.interrupt() }
            colorReader = null
            depthReader = null
            mixReader = null
            delay(500)  // 给 Sonix firmware 时间释放 endpoint
            readerRunning = true
            // 如果 device 是 null，先借 COLOR_ONLY 让 BerxelService 自己走 open device 流程
            if (device == null) {
                Log.i(TAG, "★ IR only: device==null，先启 COLOR_ONLY 让 SDK open device")
                debugStartupModeOverride = StartupStreamMode.COLOR_ONLY
                start(StartupStreamMode.COLOR_ONLY)
                var waited = 0
                while (device == null && waited < 6000) {
                    delay(200)
                    waited += 200
                }
                if (device == null) {
                    Log.w(TAG, "★ IR only: 6s 内 device 仍 null，放弃")
                    return@launch
                }
                Log.i(TAG, "★ IR only: device opened 后等 1s 让 color stream 稳")
                delay(1000)
                val devTmp = device
                if (devTmp != null) {
                    val stopRc = devTmp.stopStreams(BerxelHawkStreamTypeEnum.BERXEL_HAWK_COLOR_STREAM.value)
                    Log.i(TAG, "★ IR only: 借完 device 后 stopStreams(COLOR) rc=$stopRc")
                    // 清掉 color reader 让它别 spam logcat
                    readerRunning = false
                    runCatching { colorReader?.interrupt() }
                    colorReader = null
                    Log.i(TAG, "★ IR only: 清掉 color reader thread")
                    delay(300)  // 等 reader 退出
                    readerRunning = true  // 后续 IR reader 用
                }
            }
            val dev = device
            if (dev == null) {
                Log.w(TAG, "startIrOnly: device 为空")
                return@launch
            }
            // ★★★ 关键实验：在 setStreamFlagMode 之前先调 enableDeviceSlaveMode，
            // 试解开 Berxel 加的 master/slave 双流耦合
            val currentSlaveMode = runCatching { dev.deviceMasterSlaveMode }
                .onFailure { Log.w(TAG, "★ IR only: getDeviceMasterSlaveMode 异常", it) }
                .getOrNull()
            Log.i(TAG, "★ IR only: 当前 master/slave mode (true=slave?) = $currentSlaveMode")
            val debugSlaveValue = debugSlaveModeOverride
            if (debugSlaveValue != null) {
                val slaveRc = runCatching { dev.enableDeviceSlaveMode(debugSlaveValue) }
                    .onFailure { Log.w(TAG, "★ IR only: enableDeviceSlaveMode 异常", it) }
                    .getOrDefault(-99)
                Log.i(TAG, "★ IR only: ★ enableDeviceSlaveMode($debugSlaveValue) rc=$slaveRc")
                val afterSlaveMode = runCatching { dev.deviceMasterSlaveMode }.getOrNull()
                Log.i(TAG, "★ IR only: 切换后 master/slave mode = $afterSlaveMode")
            }
            val flagRc = dev.setStreamFlagMode(BerxelHawkStreamFlagEnum.BERXEL_HAWK_SINGULAR_STREAM_FLAG_MODE)
            Log.i(TAG, "★ IR only: setStreamFlagMode(SINGULAR) rc=$flagRc")
            if (flagRc != 0) return@launch
            // IR_STREAM 实测只支持 640x400@30 和 1280x800@30，没 fps/分辨率灵活选择。
            // 这里直接从 supported list 选最高分辨率（1280×800），如果失败再降到 640×400。
            val supportedIr = runCatching { dev.getSupportFrameModes(BerxelHawkStreamTypeEnum.BERXEL_HAWK_IR_STREAM) }
                .onFailure { Log.w(TAG, "★ IR only: getSupportFrameModes 异常", it) }
                .getOrNull()
            Log.i(TAG, "★ IR only: ir supported modes=${supportedIr?.joinToString { frameModeLabel(it) }}")
            val irMode = supportedIr?.maxByOrNull { it.resolutionX * it.resolutionY }
            if (irMode == null) {
                Log.w(TAG, "★ IR only: 没拿到 IR frame mode")
                return@launch
            }
            Log.i(TAG, "★ IR only: 选 ir mode=${frameModeLabel(irMode)}")
            val setRc = dev.setFrameMode(BerxelHawkStreamTypeEnum.BERXEL_HAWK_IR_STREAM, irMode)
            Log.i(TAG, "★ IR only: setFrameMode ir=${frameModeLabel(irMode)} rc=$setRc")
            val irFlag = BerxelHawkStreamTypeEnum.BERXEL_HAWK_IR_STREAM.value
            Log.i(TAG, "★ IR only: startStreams flags=$irFlag (IR_STREAM bit)")
            val startRc = dev.startStreams(irFlag)
            Log.i(TAG, "★ IR only: startStreams rc=$startRc")
            // 主动跑几次 readIrFrame 看是否拿到帧
            repeat(20) { i ->
                val frame = runCatching { dev.readIrFrame(500) }
                    .onFailure { Log.w(TAG, "★ IR only: readIrFrame[$i] 异常", it) }
                    .getOrNull()
                if (frame != null) {
                    Log.i(TAG, "★ IR only: readIrFrame[$i] 成功! ${frame.width}x${frame.height} pixelType=${frame.pixelType} size=${frame.dataSize}")
                } else {
                    Log.d(TAG, "★ IR only: readIrFrame[$i] null")
                }
                delay(200)
            }
            Log.i(TAG, "★ IR only: 20 次 readIrFrame 测试完成")
        }
    }

    /**
     * 走正常 DUAL 启动路径但启用 debugHalfStopColorAfterDual flag：
     * startStreams(3) 成功后立刻 stopStreams(COLOR=1) 留 depth。
     * 这样 BerxelService 自己走 open device 流程，不要求 device 已 attach。
     */
    fun startDepthByDualThenHalfStopColor() {
        debugStartupModeOverride = null  // 走正常 DUAL 路径
        debugForceMixModeForSingle = false
        debugHalfStopColorAfterDual = true
        streamStartBlockedReason = null  // 绕开 cooldown
        start(StartupStreamMode.DUAL)
    }

    fun startDepthByDualHalfStopForDebug() {
        debugStartupModeOverride = StartupStreamMode.DEPTH_ONLY
        debugForceMixModeForSingle = false
        scope.launch {
            val dev = device
            if (dev == null) {
                Log.w(TAG, "startDepthByDualHalfStop: device 为空 - 先 USB attach")
                return@launch
            }
            val requestedProfile = activeStreamProfile
            val flagProfile = requestedProfile.flagProfile
            val streamFlag = flagProfile.toSdkStreamFlag()
            val flagRc = dev.setStreamFlagMode(streamFlag)
            Log.i(TAG, "★ half-stop: setStreamFlagMode($streamFlag) rc=$flagRc profile=${requestedProfile.logLabel()}")
            if (flagRc != 0) return@launch

            val colorMode = selectStableFrameMode(
                dev = dev,
                streamType = BerxelHawkStreamTypeEnum.BERXEL_HAWK_COLOR_STREAM,
                label = "color",
                target = requestedProfile.color ?: BerxelStreamProfiles.QVGA_15.color!!,
            )
            val depthMode = selectStableFrameMode(
                dev = dev,
                streamType = BerxelHawkStreamTypeEnum.BERXEL_HAWK_DEPTH_STREAM,
                label = "depth",
                target = requestedProfile.depth ?: BerxelStreamProfiles.QVGA_15.depth!!,
            )
            if (colorMode == null || depthMode == null) {
                Log.w(TAG, "★ half-stop: frame mode null")
                return@launch
            }
            dev.setFrameMode(BerxelHawkStreamTypeEnum.BERXEL_HAWK_COLOR_STREAM, colorMode)
            dev.setFrameMode(BerxelHawkStreamTypeEnum.BERXEL_HAWK_DEPTH_STREAM, depthMode)
            val dualFlag = BerxelHawkStreamTypeEnum.BERXEL_HAWK_COLOR_STREAM.value or
                BerxelHawkStreamTypeEnum.BERXEL_HAWK_DEPTH_STREAM.value
            Log.i(TAG, "★ half-stop: startStreams DUAL flag=$dualFlag")
            val startRc = dev.startStreams(dualFlag)
            Log.i(TAG, "★ half-stop: startStreams rc=$startRc")
            if (startRc != 0) return@launch
            // 立刻停 color；不 delay 以减小被 25102RKBEC host kill 的时间窗口
            val colorFlag = BerxelHawkStreamTypeEnum.BERXEL_HAWK_COLOR_STREAM.value
            val stopRc = dev.stopStreams(colorFlag)
            Log.i(TAG, "★ half-stop: stopStreams(COLOR) rc=$stopRc → 现在只剩 depth")
            // 启 depth reader
            depthReader = Thread({ readLoop(StreamKind.DEPTH) }, "berxel-depth-half-stop").also { it.start() }
            Log.i(TAG, "★ half-stop: depth reader 启动")
        }
    }

    /**
     * 实验：在已有 COLOR_ONLY (SINGULAR) 流上**不 stop**直接追加 DEPTH startStreams。
     * 用来验证 25102RKBEC + P100R3 上 host port disable 是不是 MIX 模式特定命令触发
     * （而非 depth 流本身或 IR 投影器电流尖峰）。
     */
    fun tryAppendDepthForDebug() {
        scope.launch {
            val dev = device
            if (dev == null) {
                Log.w(TAG, "tryAppendDepthForDebug: device 为空，请先 start color")
                return@launch
            }
            if (lastStartedMode != StartupStreamMode.COLOR_ONLY) {
                Log.w(TAG, "tryAppendDepthForDebug: 仅支持在 COLOR_ONLY 跑通后追加；当前 mode=$lastStartedMode")
                return@launch
            }
            val targetDepth = activeStreamProfile.depth
            if (targetDepth == null) {
                Log.w(TAG, "tryAppendDepthForDebug: activeStreamProfile 没有 depth target；profile=${activeStreamProfile.logLabel()}")
                return@launch
            }
            val depthMode = selectStableFrameMode(
                dev = dev,
                streamType = BerxelHawkStreamTypeEnum.BERXEL_HAWK_DEPTH_STREAM,
                label = "depth",
                target = targetDepth,
            )
            if (depthMode == null) {
                Log.w(TAG, "tryAppendDepthForDebug: 无 depth frame mode")
                return@launch
            }
            val setRc = dev.setFrameMode(BerxelHawkStreamTypeEnum.BERXEL_HAWK_DEPTH_STREAM, depthMode)
            Log.i(TAG, "tryAppendDepthForDebug setFrameMode depth=${frameModeLabel(depthMode)} rc=$setRc")
            if (setRc != 0) return@launch
            val depthFlag = BerxelHawkStreamTypeEnum.BERXEL_HAWK_DEPTH_STREAM.value
            Log.i(TAG, "tryAppendDepthForDebug startStreams flag=$depthFlag (DEPTH only, color 已在跑)")
            val rc = dev.startStreams(depthFlag)
            Log.i(TAG, "tryAppendDepthForDebug startStreams rc=$rc")
            if (rc != 0) return@launch
            if (depthReader == null || depthReader?.isAlive != true) {
                depthReader = Thread({ readLoop(StreamKind.DEPTH) }, "berxel-depth-reader-appended")
                    .also { it.start() }
                Log.i(TAG, "tryAppendDepthForDebug: depth reader 启动")
            } else {
                Log.i(TAG, "tryAppendDepthForDebug: depth reader 已在跑，复用")
            }
        }
    }

    private fun start(mode: StartupStreamMode) {
        val blockedReason = streamStartBlockedReason
        if (mode == StartupStreamMode.DUAL && blockedReason != null) {
            Log.w(TAG, "start() blocked after repeated early USB disconnect: $blockedReason")
            _state.value = BerxelDeviceState.Error(blockedReason)
            return
        }
        when (_state.value) {
            is BerxelDeviceState.Streaming,
            is BerxelDeviceState.Opening,
            is BerxelDeviceState.WaitingPermission,
            is BerxelDeviceState.Initializing -> {
                Log.d(TAG, "start() ignored, current=${_state.value}")
                return
            }
            else -> Unit
        }
        pendingStartJob?.cancel()
        companionRetryJob?.cancel()
        val token = startEpoch.incrementAndGet()
        pendingUsbPermissionMode = mode
        pendingStartJob = scope.launch {
            if (device != null || readerRunning) {
                Log.i(TAG, "start() 清理旧 Berxel 句柄后重启 state=${_state.value}")
                stopInternal(reason = null)
                if (token != startEpoch.get()) return@launch
            }
            startInternal(token, mode)
        }
    }

    /**
     * 由 MainActivity 在 USB_DEVICE_ATTACHED intent 到达时调 —— 把 intent extras 里的
     * UsbDevice 实例（带有效权限）交给 service。然后立即触发 start。
     *
     * Why: 实测 HONOR Magic OS 上，`usbManager.deviceList` 取到的 UsbDevice 与 intent
     * extras 里的同一物理设备虽 deviceName 相同但 hasPermission 行为不同 —— 后者才能
     * 真实通过 SDK 的 getSerialNumber/openDevice 调用链。
     */
    fun attachAuthorizedDevice(device: UsbDevice) {
        Log.i(TAG, "attachAuthorizedDevice ${usbLabel(device)}")
        if (!isBerxelUsbDevice(device)) {
            Log.w(TAG, "ignoring non-Berxel device ${usbLabel(device)}")
            return
        }
        authorizedDevicesByName[device.deviceName] = device
        // 仅记授权设备；只有当前有扫描页在用（acquireCount>0）才开流。
        // 停在根页面插相机：自动唤起 App + 拿到授权，但不空跑相机。等进扫描页 acquire() 再 start()，
        // 那时已有缓存的授权设备可直接用。若用户在扫描页等待时才插：这里补一次 start。
        if (acquireCount.get() > 0) {
            Log.i(TAG, "有活动消费者，授权设备到位 → start()")
            start()
        } else {
            Log.i(TAG, "无活动消费者，仅缓存授权设备，不开流")
        }
    }

    /**
     * 切换 Berxel 流配置（在 Streaming 时会 stop 再 start，约 300-800ms 中断；Idle 时只改默认 profile）。
     * 幂等：profile 没变直接 noop。
     */
    fun setStreamProfile(profile: BerxelStreamProfile) {
        if (backend == BerxelStackBackend.NATIVE_REWRITE || nativeRewriteRunning) {
            val nativeProfile = nativeRewriteProfile(enableMasterStream = nativeStack.enableMasterStream)
            activeStreamProfile = nativeProfile
            _streamProfile.value = nativeProfile
            Log.i(
                TAG,
                "setStreamProfile ignored in NATIVE_REWRITE; requested=${profile.logLabel()} actual=${nativeProfile.logLabel()}",
            )
            return
        }
        if (activeStreamProfile == profile && streamStartBlockedReason == null) return
        Log.i(TAG, "setStreamProfile ${activeStreamProfile.logLabel()} → ${profile.logLabel()}")
        streamStartBlockedReason = null
        lastStreamStartAtMs = 0L
        lastFirstFrameAtMs = 0L
        activeStreamProfile = profile
        _streamProfile.value = profile
        // 当前如果在跑：stop → 短暂等 → 用新 profile restart。
        val state = _state.value
        val wasActive = state is BerxelDeviceState.Streaming ||
            state is BerxelDeviceState.Opening ||
            state is BerxelDeviceState.Initializing ||
            device != null ||
            readerRunning
        if (!wasActive) return
        startEpoch.incrementAndGet()
        pendingStartJob?.cancel()
        scope.launch {
            stopInternal(reason = null)
            // stopInternal 不动 _state；显式回 Idle，否则 start() 会被 "current=Streaming" 拦下
            _state.value = BerxelDeviceState.Idle
            // 等 P100R3 firmware 释放上一组 endpoint；不等的话开新流偶现 LIBUSB_ERROR_BUSY
            delay(400)
            start(pendingUsbPermissionMode)
        }
    }

    /**
     * 旧三档入口保留给外部调试广播 / 历史调用方。HD 现在映射到 1280×800@5fps，
     * 因为手机 OTG 上高分辨率先保证深度帧出来，再由用户升到 10/15fps。
     */
    fun setStreamResolutionProfile(profile: StreamResolutionProfile) {
        setStreamProfile(
            when (profile) {
                StreamResolutionProfile.QVGA -> BerxelStreamProfiles.QVGA_15
                StreamResolutionProfile.STANDARD -> BerxelStreamProfiles.STANDARD_15
                StreamResolutionProfile.HD -> BerxelStreamProfiles.HD_5
            },
        )
    }

    /** 主动停止：reader 退出 → close device → destroy context → 状态回 Idle。 */
    fun stop() {
        startEpoch.incrementAndGet()
        pendingStartJob?.cancel()
        companionRetryJob?.cancel()
        companionRetryJob = null
        permissionWatchdogJob?.cancel()
        debugStartupModeOverride = null
        streamStartBlockedReason = null
        lastStreamStartAtMs = 0L
        lastFirstFrameAtMs = 0L
        // NATIVE_REWRITE 路径独立 stop（不走 SDK stopInternal）
        if (backend == BerxelStackBackend.NATIVE_REWRITE || nativeRewriteRunning) {
            if (nativeRewriteRunning) {
                stopNativeRewrite()
            } else {
                nativeRewritePullJob?.cancel(); nativeRewritePullJob = null
                nativeRewriteColorJob?.cancel(); nativeRewriteColorJob = null
                nativeRewriteWatchdogJob?.cancel(); nativeRewriteWatchdogJob = null
                runCatching { nativeStack.stop() }
                nativeMasterStreamDebugOverride = null
                nativeStack.enableMasterStream = false
                abPrevB1 = null
            }
            _state.value = BerxelDeviceState.Idle
            return
        }
        scope.launch {
            stopInternal(reason = null)
            _state.value = BerxelDeviceState.Idle
        }
    }

    private fun startInternal(token: Long, mode: StartupStreamMode) {
        try {
            if (token != startEpoch.get()) return
            _state.value = BerxelDeviceState.Initializing

            val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

            // Step 1: 找到当前 iHawk 的所有 USB 节点。
            // P100R3 / iHawk100RS 实机会同时出现 0x0603:0x001f 和 0x3558:0x1012。
            // Berxel SDK openDevice() 内部会枚举并读取这些节点的 serial，任一节点没权限都会抛
            // SecurityException，所以启动 SDK 前必须逐个确认 fd 能打开。
            val usbDevices = usbManager.deviceList.values
                .filter { isBerxelUsbDevice(it) }
                .map { authorizedDevicesByName[it.deviceName] ?: it }
                .sortedWith(
                    compareByDescending<UsbDevice> { it.vendorId == BERXEL_VID }
                        .thenBy { it.deviceName },
                )
            if (usbDevices.isEmpty()) {
                _state.value = BerxelDeviceState.NoDevice
                return
            }
            val hasPrimaryNode = usbDevices.any { it.vendorId == BERXEL_VID }
            val hasP100R3PrimaryNode = usbDevices.any {
                it.vendorId == BERXEL_VID && it.productId == P100R3_PRIMARY_PID
            }
            val hasP100R3CompanionNode = usbDevices.any {
                it.vendorId == P100R3_COMPANION_VID && it.productId == P100R3_COMPANION_PID
            }
            if (!hasPrimaryNode || (hasP100R3PrimaryNode && !hasP100R3CompanionNode)) {
                partialNodeRetryCount++
                Log.i(
                    TAG,
                    "partial iHawk USB nodes visible; wait full device " +
                        "retry=$partialNodeRetryCount nodes=${usbDevices.joinToString { usbLabel(it) }}",
                )
                companionRetryJob?.cancel()
                companionRetryJob = scope.launch {
                    delay(COMPANION_ONLY_RETRY_DELAY_MS)
                    if (token == startEpoch.get() && _state.value is BerxelDeviceState.Initializing) {
                        startInternal(token, mode)
                    }
                }
                return
            }
            val disconnectBackoffMs = PHYSICAL_DISCONNECT_RESTART_BACKOFF_MS -
                (SystemClock.elapsedRealtime() - lastPhysicalDisconnectAtMs)
            if (lastPhysicalDisconnectAtMs > 0L && disconnectBackoffMs > 0L) {
                Log.i(TAG, "recent USB disconnect; wait ${disconnectBackoffMs}ms before Berxel restart")
                companionRetryJob?.cancel()
                companionRetryJob = scope.launch {
                    delay(disconnectBackoffMs)
                    if (token == startEpoch.get() && _state.value is BerxelDeviceState.Initializing) {
                        startInternal(token, mode)
                    }
                }
                return
            }
            if (hasPrimaryNode) {
                companionRetryJob?.cancel()
                companionRetryJob = null
                partialNodeRetryCount = 0
            }
            val workingDev = usbDevices.first()
            Log.i(
                TAG,
                "using ${usbLabel(workingDev)} nodes=${usbDevices.joinToString { usbLabel(it) }}",
            )

            // 实测逐个开一下：openDevice 返 null 即没有有效 fd 权限。
            // (HONOR Magic OS 上 hasPermission 偶现脏缓存返 false 但实际 openDevice 能过；
            // 也有反之的情况。所以不查 flag，只看真实开 fd 结果)
            val missingPermissionDevice = usbDevices.firstOrNull { !canOpenUsbDevice(usbManager, it) }
            if (missingPermissionDevice != null) {
                // 没权限 —— 主动调 requestPermission 弹标准 Android 系统对话框
                // (Why: 之前只靠 manifest USB_DEVICE_ATTACHED intent，用户拒过一次后无路重试；
                //  现在主动请求 → granted 则 receiver 把 authorized device 喂回 startInternal；
                //  HONOR 脏缓存仍然会让 broadcast 直接 deny=false 不弹窗，那种场景由 finding_honor_usb_permission_cache_2026-05-07
                //  指出"重启手机"是唯一根治路径)
                requestUsbPermission(usbManager, missingPermissionDevice, mode)
                return  // 等 receiver 回调时再次进入 startInternal
            }

            val sdkPermissionDevices = usbManager.deviceList.values
                .map { authorizedDevicesByName[it.deviceName] ?: it }
                .filter { needsBerxelSdkUsbPermission(it) }
            val missingSdkPermissionDevice = sdkPermissionDevices.firstOrNull { !canOpenUsbDevice(usbManager, it) }
            if (missingSdkPermissionDevice != null) {
                Log.i(TAG, "Berxel SDK preflight needs permission for ${usbLabel(missingSdkPermissionDevice)}")
                requestUsbPermission(usbManager, missingSdkPermissionDevice, mode)
                return
            }

            // Step 3: 有权限了，初始化 SDK Context（也走 ContextWrapper 兼容 Android 14+）
            val wrappedCtx = SdkCompatContextWrapper(appContext)
            val ctx = BerxelHawkContext.getBerxelContext(wrappedCtx)
                ?: run {
                    _state.value = BerxelDeviceState.Error("SDK Context 加载失败")
                    return
            }
            hawkContext = ctx
            if (!deviceStatusCallbackRegistered) {
                ctx.addDeviceStatusCallBack(deviceStatusCallback)
                deviceStatusCallbackRegistered = true
            }
            val sdkDeviceInfos = runCatching { ctx.deviceLists }
                .onFailure { Log.w(TAG, "SDK getDeviceLists 异常", it) }
                .getOrNull()
                .orEmpty()
            if (sdkDeviceInfos.isNotEmpty()) {
                Log.i(TAG, "SDK device list=${sdkDeviceInfos.joinToString { sdkDeviceLabel(it) }}")
            }
            _state.value = BerxelDeviceState.Opening
            val dev = ctx.CreateDevice() ?: run {
                _state.value = BerxelDeviceState.Error("CreateDevice 返回 null")
                return
            }
            device = dev

            // openDevice 会异步打开设备 + 申请校准/参数；用回调驱动状态机
            val openCallback = object : BerxelHawkDevice.OpenDeviceStatusCallBack {
                override fun onDeviceStausOpenSuccess() {
                    scope.launch {
                        if (token == startEpoch.get()) onDeviceOpened(token, mode)
                    }
                }
                override fun onDeviceStatusOpenFailed() {
                    if (token == startEpoch.get()) {
                        _state.value = BerxelDeviceState.Error("打开 iHawk 失败 —— 检查 USB 接口或拔了再插")
                    }
                }
            }
            // P100R3 显式传主节点或 companion deviceInfo 都会让后续 setStreamFlagMode 返 -3；
            // 无参入口由 SDK 自己挑内部 UVC 句柄，当前是唯一能走到 startStreams 的路径。
            Log.i(TAG, "openDevice target=<SDK default>")
            dev.openDevice(openCallback)
        } catch (t: Throwable) {
            Log.e(TAG, "startInternal 异常", t)
            _state.value = BerxelDeviceState.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun onDeviceOpened(token: Long, mode: StartupStreamMode) {
        val dev = device ?: return
        try {
            if (token != startEpoch.get()) return
            _state.value = BerxelDeviceState.Opening

            val deviceInfo = dev.currentDeviceInfo
            if (deviceInfo?.isP100R3Usb3() == true) {
                Log.i(TAG, "skip setDeviceTransferMode(BULK) for P100R3; firmware returns -8")
            }
            // 2026-05-13 phone OTG 诊断：BULK / setDeviceBandwidth 都返 -8，但来自 firmware 不是 SDK。
            // SDK Java/native 反汇编验证内部纯转发无 P100R3 拒绝逻辑。Native libusb bypass 也救不了。
            // 见 finding_berxel_sdk_p100r3_phone_otg_2026-05-13。

            val requestedProfile = activeStreamProfile
            val streamFlagProfile = when (mode) {
                StartupStreamMode.COLOR_ONLY,
                StartupStreamMode.DEPTH_ONLY -> if (debugForceMixModeForSingle) {
                    requestedProfile.flagProfile
                        .also { Log.i(TAG, "★ debugForceMixModeForSingle=true → 用 MIX profile $it 替代 SINGULAR") }
                } else {
                    BerxelStreamFlagProfile.SINGULAR
                }
                StartupStreamMode.DUAL -> requestedProfile.flagProfile
            }
            val streamFlag = streamFlagProfile.toSdkStreamFlag()
            val flagRc = dev.setStreamFlagMode(streamFlag)
            Log.i(
                TAG,
                "setStreamFlagMode($streamFlag) rc=$flagRc mode=$mode profile=${requestedProfile.logLabel()}",
            )
            if (flagRc != 0) {
                _state.value = BerxelDeviceState.Error("setStreamFlagMode 失败 rc=$flagRc flag=$streamFlagProfile")
                return
            }

            // P100R3 每个 Berxel flag 下有多组 frame mode；分辨率和 fps 必须一起选。
            val colorEnabled = mode != StartupStreamMode.DEPTH_ONLY
            val depthEnabled = mode != StartupStreamMode.COLOR_ONLY
            val colorMode = if (colorEnabled) {
                selectStableFrameMode(
                    dev = dev,
                    streamType = BerxelHawkStreamTypeEnum.BERXEL_HAWK_COLOR_STREAM,
                    label = "color",
                    target = requestedProfile.color,
                )
            } else {
                null
            }
            val depthMode = if (depthEnabled) {
                selectStableFrameMode(
                    dev = dev,
                    streamType = BerxelHawkStreamTypeEnum.BERXEL_HAWK_DEPTH_STREAM,
                    label = "depth",
                    target = requestedProfile.depth,
                )
            } else {
                null
            }
            if ((colorEnabled && colorMode == null) || (depthEnabled && depthMode == null)) {
                _state.value = BerxelDeviceState.Error("无法读取 iHawk 帧模式 mode=$mode")
                return
            }
            val colorSetRc = if (colorMode != null) {
                dev.setFrameMode(BerxelHawkStreamTypeEnum.BERXEL_HAWK_COLOR_STREAM, colorMode)
            } else {
                0
            }
            val depthSetRc = if (depthMode != null) {
                dev.setFrameMode(BerxelHawkStreamTypeEnum.BERXEL_HAWK_DEPTH_STREAM, depthMode)
            } else {
                0
            }
            if (colorMode != null) Log.i(TAG, "setFrameMode color=${frameModeLabel(colorMode)} rc=$colorSetRc")
            if (depthMode != null) Log.i(TAG, "setFrameMode depth=${frameModeLabel(depthMode)} rc=$depthSetRc")
            if (colorSetRc != 0 || depthSetRc != 0) {
                _state.value = BerxelDeviceState.Error("setFrameMode 失败 color=$colorSetRc depth=$depthSetRc")
                return
            }

            val flags = when (mode) {
                StartupStreamMode.COLOR_ONLY -> BerxelHawkStreamTypeEnum.BERXEL_HAWK_COLOR_STREAM.value
                StartupStreamMode.DEPTH_ONLY -> BerxelHawkStreamTypeEnum.BERXEL_HAWK_DEPTH_STREAM.value
                StartupStreamMode.DUAL -> BerxelHawkStreamTypeEnum.BERXEL_HAWK_COLOR_STREAM.value or
                    BerxelHawkStreamTypeEnum.BERXEL_HAWK_DEPTH_STREAM.value
            }
            Log.i(TAG, "startStreams flags=$flags mode=$mode")
            lastStartedProfile = requestedProfile
            lastStartedMode = mode
            lastStreamStartAtMs = SystemClock.elapsedRealtime()
            lastFirstFrameAtMs = 0L
            val rc = dev.startStreams(flags)
            Log.i(TAG, "startStreams rc=$rc")
            if (token != startEpoch.get()) {
                Log.i(TAG, "startStreams returned after USB disconnect; skip stale device")
                return
            }
            if (rc != 0) {
                _state.value = BerxelDeviceState.Error("startStreams 返回 $rc")
                return
            }
            // ★ 实验：DUAL 模式启完后立刻 stopStreams(COLOR=1) 留 depth 单流
            if (debugHalfStopColorAfterDual && mode == StartupStreamMode.DUAL) {
                val colorOnlyFlag = BerxelHawkStreamTypeEnum.BERXEL_HAWK_COLOR_STREAM.value
                val stopRc = dev.stopStreams(colorOnlyFlag)
                Log.i(TAG, "★ halfStopColor: 立刻 stopStreams(COLOR=$colorOnlyFlag) rc=$stopRc")
                debugHalfStopColorAfterDual = false  // 一次性
            }
            if (deviceInfo?.isP100R3Usb3() == true && !hasFullP100R3UsbNodes()) {
                Log.w(TAG, "startStreams returned but P100R3 USB nodes are incomplete; wait reconnect")
                handlePhysicalDisconnect()
                return
            }

            // 读出内参（出厂值；M1.3 实测精度后决定是否需要自标定覆盖）
            currentColorIntrinsics = colorMode?.let { readIntrinsics(dev, it, "color") }
            currentDepthIntrinsics = depthMode?.let { readIntrinsics(dev, it, "depth") }
            if (token != startEpoch.get()) {
                Log.i(TAG, "intrinsics returned after USB disconnect; skip stale device")
                return
            }

            // P100R3 在手机 OTG 上开流后立即写多组控制项，会触发控制传输失败和物理重枚举。
            // 先保持 SDK 出厂默认值，等稳定出帧后再由用户操作显式下发控制命令。
            Log.i(TAG, "skip initial Berxel controls sync until stream is stable")

            val info = collectDeviceInfo(dev, streamFlagProfile, requestedProfile, colorMode, depthMode)
            if (token != startEpoch.get()) {
                Log.i(TAG, "device info returned after USB disconnect; skip stale device")
                return
            }
            if (deviceInfo?.isP100R3Usb3() == true && !hasFullP100R3UsbNodes()) {
                Log.w(TAG, "P100R3 USB nodes became incomplete before reader start; wait reconnect")
                handlePhysicalDisconnect()
                return
            }
            _lastKnownInfo.value = info
            _state.value = BerxelDeviceState.Streaming(info)

            loggedFirstColorFrame = false
            loggedFirstDepthFrame = false
            readerRunning = true
            if (mode == StartupStreamMode.DUAL) {
                mixReader = Thread({ readDualLoop() }, "berxel-mix-reader").also { it.start() }
            } else if (colorEnabled) {
                colorReader = Thread({ readLoop(StreamKind.COLOR) }, "berxel-color-reader").also { it.start() }
            }
            if (mode != StartupStreamMode.DUAL && depthEnabled) {
                depthReader = Thread({ readLoop(StreamKind.DEPTH) }, "berxel-depth-reader").also { it.start() }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "onDeviceOpened 异常", t)
            _state.value = BerxelDeviceState.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun selectStableFrameMode(
        dev: BerxelHawkDevice,
        streamType: BerxelHawkStreamTypeEnum,
        label: String,
        target: BerxelStreamTarget?,
    ): BerxelHawkStreamFrameMode? {
        val current = runCatching { dev.getCurrentFrameMode(streamType) }
            .onFailure { Log.w(TAG, "$label getCurrentFrameMode 异常", it) }
            .getOrNull()
        if (current != null) {
            Log.i(TAG, "$label current mode ${frameModeLabel(current)}")
        }

        val supported = runCatching { dev.getSupportFrameModes(streamType) }
            .onFailure { Log.w(TAG, "$label getSupportFrameModes 异常", it) }
            .getOrNull()
            .orEmpty()
        if (supported.isNotEmpty()) {
            Log.i(TAG, "$label support modes=${supported.joinToString { frameModeLabel(it) }}")
        }

        if (target == null) {
            val selected = supported.firstOrNull() ?: current
            if (selected != null) Log.i(TAG, "$label selected mode ${frameModeLabel(selected)}")
            return selected
        }

        val exactTarget = supported.firstOrNull {
            it.resolutionX == target.width && it.resolutionY == target.height && it.getmFps() == target.fps
        }
        val sameResolution = supported
            .filter { it.resolutionX == target.width && it.resolutionY == target.height }
            .sortedWith(compareBy<BerxelHawkStreamFrameMode> { fpsRank(it.getmFps(), target.fps) })
        val selected = exactTarget
            ?: sameResolution.firstOrNull()
            ?: if (supported.isEmpty()) current?.copyWithTarget(target, label) else null

        if (selected == null && supported.isNotEmpty()) {
            Log.e(
                TAG,
                "$label target ${target.label()} 不在当前 Berxel flag 支持列表内，" +
                    "support=${supported.joinToString { frameModeLabel(it) }}",
            )
        }

        if (selected != null) {
            Log.i(TAG, "$label selected mode ${frameModeLabel(selected)}")
        }
        return selected
    }

    private fun fpsRank(fps: Int, targetFps: Int): Int {
        return if (fps in 1..targetFps) targetFps - fps else 1000 + kotlin.math.abs(fps - targetFps)
    }

    private fun BerxelHawkStreamFrameMode.copyWithTarget(
        target: BerxelStreamTarget,
        label: String,
    ): BerxelHawkStreamFrameMode {
        if (resolutionX == target.width && resolutionY == target.height && getmFps() == target.fps) return this
        Log.w(
            TAG,
            "$label support modes empty，按目标 ${target.label()} 构造 frame mode；" +
                "current=${frameModeLabel(this)}",
        )
        return BerxelHawkStreamFrameMode(pixelType, target.width, target.height, target.fps)
    }

    private fun frameModeLabel(mode: BerxelHawkStreamFrameMode): String {
        return "${mode.resolutionX}x${mode.resolutionY}@${mode.getmFps()} ${mode.pixelType?.name.orEmpty()}"
    }

    private fun BerxelStreamFlagProfile.toSdkStreamFlag(): BerxelHawkStreamFlagEnum {
        return when (this) {
            BerxelStreamFlagProfile.SINGULAR -> BerxelHawkStreamFlagEnum.BERXEL_HAWK_SINGULAR_STREAM_FLAG_MODE
            BerxelStreamFlagProfile.MIX -> BerxelHawkStreamFlagEnum.BERXEL_HAWK_MIX_STREAM_FLAG_MODE
            BerxelStreamFlagProfile.MIX_HD -> BerxelHawkStreamFlagEnum.BERXEL_HAWK_MIX_HD_STREAM_FLAG_MODE
            BerxelStreamFlagProfile.MIX_QVGA -> BerxelHawkStreamFlagEnum.BERXEL_HAWK_MIX_QVGA_STREAM_FLAG_MODE
        }
    }

    private fun BerxelStreamProfile.logLabel(): String {
        return "${flagProfile.name} color=${color?.label() ?: "off"} depth=${depth?.label() ?: "off"}"
    }

    private fun BerxelStreamTarget.label(): String = "${width}x${height}@${fps}"

    /**
     * 从 SDK 读 [BerxelHawkCameraIntrinsic] 转成 core:model 的 [CameraIntrinsics]。
     *
     * SDK 的 `getCameraIntriscParams()` 返**一组**参数 — color/depth 共用，不区分 stream type。
     * 实测 (2026-05-07 LOG-AN10 + iHawk-072): fx=771.79 fy=771.30 cx=630.20 cy=395.90，
     * 但当前 stream 是 640×400，cx/cy 大于宽高的一半 → 这是 SDK 出厂参数针对的是 **registration
     * 后的虚拟统一相机**或基础分辨率（可能是 1280×800）。M1.3 实测精度时要校准：
     *   - setRegistrationEnable(true) 是否影响这组参数
     *   - 不同分辨率切换时 SDK 是否自动 rescale
     *   - getDeviceIntriscParams(FloatBuffer) 接口是否给两路独立参数
     * 当前两路都用同一调用，预览看着没问题；进算法（ICP / 拓印）前必须解决。
     */
    private fun readIntrinsics(
        dev: BerxelHawkDevice,
        mode: BerxelHawkStreamFrameMode?,
        tag: String,
    ): CameraIntrinsics? {
        val intr: BerxelHawkCameraIntrinsic = runCatching { dev.cameraIntriscParams }.getOrNull() ?: run {
            Log.w(TAG, "$tag intrinsics 读取失败 — 用默认 0，M1.3 实测精度时再修")
            return null
        }
        if (mode == null) return null
        // SDK 给的 fx/fy/cx/cy 是 iHawk 出厂基础分辨率（实测 1280×800）的内参；
        // 当前 stream 切到 640×400 时 cx≈630 远超宽度一半 → 直接用会把所有点投到视野外。
        // 推断基础分辨率 baseW = 2*cx, 按 streamW/baseW 等比缩放；不修畸变系数（畸变是无单位的）。
        val rawFx = intr.fxParam.toDouble()
        val rawFy = intr.fyParam.toDouble()
        val rawCx = intr.cxParam.toDouble()
        val rawCy = intr.cyParam.toDouble()
        val streamW = mode.resolutionX
        val streamH = mode.resolutionY
        val baseW = (rawCx * 2.0).coerceAtLeast(1.0)
        val baseH = (rawCy * 2.0).coerceAtLeast(1.0)
        val scaleX = if (kotlin.math.abs(baseW - streamW) > 8) streamW.toDouble() / baseW else 1.0
        val scaleY = if (kotlin.math.abs(baseH - streamH) > 8) streamH.toDouble() / baseH else 1.0
        val rescaled = scaleX != 1.0 || scaleY != 1.0
        return CameraIntrinsics(
            fx = rawFx * scaleX, fy = rawFy * scaleY,
            cx = rawCx * scaleX, cy = rawCy * scaleY,
            distortion = doubleArrayOf(
                intr.k1Param.toDouble(), intr.k2Param.toDouble(),
                intr.p1Param.toDouble(), intr.p2Param.toDouble(),
                intr.k3Param.toDouble(),
            ),
            width = streamW,
            height = streamH,
        ).also {
            if (rescaled) {
                Log.i(TAG, "$tag intrinsics rescaled base ${baseW.toInt()}×${baseH.toInt()} → ${streamW}×${streamH}: " +
                    "fx=${rawFx}→${it.fx} cx=${rawCx}→${it.cx}")
            } else {
                Log.i(TAG, "$tag intrinsics fx=${it.fx} fy=${it.fy} cx=${it.cx} cy=${it.cy} ${streamW}x${streamH}")
            }
        }
    }

    private enum class StartupStreamMode { DUAL, COLOR_ONLY, DEPTH_ONLY }

    /** 旧三档兼容入口；新代码直接使用 [BerxelStreamProfile]。 */
    enum class StreamResolutionProfile { QVGA, STANDARD, HD }
    private enum class StreamKind { COLOR, DEPTH }

    private fun readLoop(kind: StreamKind) {
        Log.i(TAG, "$kind reader 启动")
        var nullStreak = 0
        while (readerRunning) {
            val frame = readFrame(kind)
            if (frame != null) {
                nullStreak = 0
                processFrame(kind, frame)
            } else {
                nullStreak = logReadTimeout(kind, nullStreak)
            }
            // SDK 不需要 release frame；GC 自动回收（Frame 内部 mFrameHandle 由 finalizer 处理）。
        }
        Log.i(TAG, "$kind reader 退出")
    }

    /**
     * 厂商 HawkMixColorDepth / HawkMixHDColorDepth sample 是单循环先读 depth 再读 color。
     * 即使 depth timeout，也继续读 color，避免把 native depth negotiation 问题误放大成双流全黑。
     */
    private fun readDualLoop() {
        Log.i(TAG, "MIX reader 启动")
        var depthNullStreak = 0
        var colorNullStreak = 0
        while (readerRunning) {
            val depthFrame = readFrame(StreamKind.DEPTH)
            if (depthFrame != null) {
                depthNullStreak = 0
                processFrame(StreamKind.DEPTH, depthFrame)
            } else {
                depthNullStreak = logReadTimeout(StreamKind.DEPTH, depthNullStreak)
            }

            val colorFrame = readFrame(StreamKind.COLOR)
            if (colorFrame == null) {
                colorNullStreak = logReadTimeout(StreamKind.COLOR, colorNullStreak)
                continue
            }
            colorNullStreak = 0
            processFrame(StreamKind.COLOR, colorFrame)
        }
        Log.i(TAG, "MIX reader 退出")
    }

    private fun readFrame(kind: StreamKind): BerxelHawkFrame? {
        val dev = device ?: return null
        return try {
            when (kind) {
                StreamKind.COLOR -> dev.readColorFrame(READ_TIMEOUT_MS)
                StreamKind.DEPTH -> dev.readDepthFrame(READ_TIMEOUT_MS)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$kind readFrame 异常", t)
            null
        }
    }

    private fun logReadTimeout(kind: StreamKind, currentStreak: Int): Int {
        val next = currentStreak + 1
        if (next == 30 || next % 150 == 0) {
            Log.w(TAG, "$kind readFrame 连续 ${next} 次 timeout/null")
        }
        if (kind == StreamKind.DEPTH && next == 30 && !loggedFirstDepthFrame) {
            stopAfterReaderTimeout("Depth 读帧超时：SDK 已开流但 readDepthFrame 持续返回 null")
        }
        return next
    }

    private fun stopAfterReaderTimeout(reason: String) {
        if (stoppingAfterReaderError) return
        stoppingAfterReaderError = true
        readerRunning = false
        _state.value = BerxelDeviceState.Error(reason)
        scope.launch {
            stopInternal(reason = null)
            _state.value = BerxelDeviceState.Error(reason)
            stoppingAfterReaderError = false
        }
    }

    /**
     * Reader 线程内：把 SDK Frame 的字节拷贝到独立 DirectByteBuffer + 包成 core:model 的
     * Color/DepthFrame + emit；emit 后尝试跟 pendingColor / pendingDepth 配对成 RgbdFramePair。
     *
     * 不变量：emit 出去的 ByteBuffer 与 SDK Frame 完全脱钩，订阅方可任意时机持有。
     */
    private fun processFrame(kind: StreamKind, frame: BerxelHawkFrame) {
        val stat = BerxelFrameStat(
            frameIndex = frame.frameIndex,
            measuredFps = frame.fps,
            timestampUs = frame.timeStamp,
            receivedAtElapsedMs = SystemClock.elapsedRealtime(),
            width = frame.width,
            height = frame.height,
        )

        // 拷贝 SDK 帧字节到我们的 DirectByteBuffer（脱离 SDK 生命周期）
        val srcData = frame.data
        val srcSize = frame.dataSize
        if (srcData == null || srcSize <= 0) {
            Log.w(TAG, "$kind frame data null or empty (size=$srcSize)")
            return
        }
        val dst = ByteBuffer.allocateDirect(srcSize).order(ByteOrder.nativeOrder())
        // src 是 SDK 内部 buffer，可能 position != 0 / limit != capacity；duplicate + rewind 安全拷
        val srcDup = srcData.duplicate().order(ByteOrder.nativeOrder())
        srcDup.rewind()
        srcDup.limit(srcSize)
        dst.put(srcDup)
        dst.rewind()

        val pixelTypeName = runCatching {
            BerxelHawkPixelTypeEnum.convertValueToEnum(frame.pixelType).name
        }.getOrDefault("unknown(${frame.pixelType})")
        logFirstFrame(kind, frame, pixelTypeName, srcSize)

        when (kind) {
            StreamKind.COLOR -> {
                _colorStat.value = stat
                val cf = ColorFrame(
                    timestampUs = frame.timeStamp,
                    frameIndex = frame.frameIndex,
                    width = frame.width,
                    height = frame.height,
                    data = dst,
                    pixelType = pixelTypeName,
                    intrinsics = currentColorIntrinsics ?: defaultIntrinsics(frame.width, frame.height),
                )
                _colorFrames.tryEmit(cf)
                tryEmitPair(color = cf, depth = null)
            }
            StreamKind.DEPTH -> {
                // SDK depth pixelType 用定点格式 `NNIb_MMd`：NN 位整数 + MM 位小数，
                // 每个 uint16 的真实 mm = raw >> MM。当前已知两种：
                //   - 12I_4D（iHawk-072 / Hawk-100B）: shr 4
                //   - 13I_3D（iHawk100RS P100R3 / 2026-05-14 Redmi 实测）: shr 3
                // 漏掉 13I_3D 分支会导致 raw（如 15041 实际 1880mm）被 depth16ToBitmap 当
                // 成 15m 全部超过 maxMm → 全黑画面。
                val fracBits = when {
                    pixelTypeName.contains("12I_4D") -> 4
                    pixelTypeName.contains("13I_3D") -> 3
                    else -> 0  // 未知 pixelType 不转，下游可能误读 mm，需要新增 case 时补上
                }
                if (fracBits > 0) {
                    val sb = dst.asShortBuffer()
                    val pixels = srcSize / 2
                    val buf = depthScratch ?: ShortArray(pixels).also { depthScratch = it }
                    val tmp = if (buf.size >= pixels) buf else ShortArray(pixels).also { depthScratch = it }
                    sb.position(0)
                    sb.get(tmp, 0, pixels)
                    for (i in 0 until pixels) {
                        tmp[i] = ((tmp[i].toInt() and 0xFFFF) shr fracBits).toShort()
                    }
                    sb.position(0)
                    sb.put(tmp, 0, pixels)
                    dst.rewind()
                }
                _depthStat.value = stat
                val df = DepthFrame(
                    timestampUs = frame.timeStamp,
                    frameIndex = frame.frameIndex,
                    width = frame.width,
                    height = frame.height,
                    data = dst,
                    intrinsics = currentDepthIntrinsics ?: defaultIntrinsics(frame.width, frame.height),
                    // SDK 默认未开 setRegistrationEnable —— M1.3 实测精度后决定是否打开 + 在此处反映
                    registeredToColor = false,
                )
                _depthFrames.tryEmit(df)
                tryEmitPair(color = null, depth = df)
            }
        }
    }

    private fun logFirstFrame(
        kind: StreamKind,
        frame: BerxelHawkFrame,
        pixelTypeName: String,
        dataSize: Int,
    ) {
        val shouldLog = when (kind) {
            StreamKind.COLOR -> !loggedFirstColorFrame.also { if (!it) loggedFirstColorFrame = true }
            StreamKind.DEPTH -> !loggedFirstDepthFrame.also { if (!it) loggedFirstDepthFrame = true }
        }
        if (!shouldLog) return
        if (lastFirstFrameAtMs < lastStreamStartAtMs) {
            lastFirstFrameAtMs = SystemClock.elapsedRealtime()
        }
        streamStartBlockedReason = null
        Log.i(
            TAG,
            "$kind first frame ${frame.width}x${frame.height}@${frame.fps} " +
                "idx=${frame.frameIndex} t=${frame.timeStamp} size=$dataSize pixel=$pixelTypeName",
        )
    }

    /**
     * RGBD 配对器：两路 reader 各自 emit 单流帧后，调本方法尝试与对侧 pending 配对。
     *
     * 配对策略：
     * - 先匹配 frameIndex（SDK 在 MIX 模式下两路 frameIndex 同步递增）
     * - timestampUs 必须严格相等（来自同一物理设备同一帧序，硬件级同步）
     * - 不命中则把当前帧暂存为对侧的 pending；下一次对侧来时再尝试
     * - 配对成功 → 清两边 pending 并 emit 到 [_rgbdPairs]
     */
    private fun tryEmitPair(color: ColorFrame?, depth: DepthFrame?) {
        val pair: RgbdFramePair? = synchronized(pairLock) {
            when {
                color != null -> {
                    val d = pendingDepth
                    if (d != null && d.frameIndex == color.frameIndex && d.timestampUs == color.timestampUs) {
                        pendingDepth = null
                        pendingColor = null
                        RgbdFramePair(color, d)
                    } else {
                        pendingColor = color
                        null
                    }
                }
                depth != null -> {
                    val c = pendingColor
                    if (c != null && c.frameIndex == depth.frameIndex && c.timestampUs == depth.timestampUs) {
                        pendingColor = null
                        pendingDepth = null
                        RgbdFramePair(c, depth)
                    } else {
                        pendingDepth = depth
                        null
                    }
                }
                else -> null
            }
        }
        if (pair != null) _rgbdPairs.tryEmit(pair)
    }

    /** 内参缺失时的桩：fx/fy 用 width 0.8 倍当近似（够 UI 显示用，不进算法）。 */
    private fun defaultIntrinsics(w: Int, h: Int): CameraIntrinsics = CameraIntrinsics(
        fx = w * 0.8, fy = w * 0.8,
        cx = w * 0.5, cy = h * 0.5,
        distortion = DoubleArray(5),
        width = w, height = h,
    )

    private fun stopInternal(reason: String?) {
        companionRetryJob?.cancel()
        companionRetryJob = null
        readerRunning = false
        loggedFirstColorFrame = false
        loggedFirstDepthFrame = false
        joinReaderThread(mixReader, 500)
        joinReaderThread(colorReader, 500)
        joinReaderThread(depthReader, 500)
        mixReader = null
        colorReader = null
        depthReader = null
        partialNodeRetryCount = 0

        val dev = device
        if (dev != null) {
            runCatching { dev.stopStreams(
                BerxelHawkStreamTypeEnum.BERXEL_HAWK_COLOR_STREAM.value or
                    BerxelHawkStreamTypeEnum.BERXEL_HAWK_DEPTH_STREAM.value
            ) }
            runCatching { dev.closeDevice() }
        }
        device = null

        runCatching {
            hawkContext?.removeDeviceStatusCallBack(deviceStatusCallback)
            BerxelHawkContext.destroyBerxelContext()
        }
        deviceStatusCallbackRegistered = false
        hawkContext = null

        _colorStat.value = null
        _depthStat.value = null
        if (reason != null) _state.value = BerxelDeviceState.Error(reason)
    }

    private fun handlePhysicalDisconnect() {
        if (backend == BerxelStackBackend.NATIVE_REWRITE || nativeRewriteRunning) {
            val token = startEpoch.incrementAndGet()
            val now = SystemClock.elapsedRealtime()
            lastPhysicalDisconnectAtMs = now
            Log.i(TAG, "USB physical disconnect; reset NativeStack token=$token")
            pendingStartJob?.cancel()
            companionRetryJob?.cancel()
            companionRetryJob = null
            nativeRewriteRunning = false
            nativeRewritePullJob?.cancel(); nativeRewritePullJob = null
            nativeRewriteColorJob?.cancel(); nativeRewriteColorJob = null
            nativeRewriteWatchdogJob?.cancel(); nativeRewriteWatchdogJob = null
            runCatching { nativeStack.stop() }
            runCatching { nativeStack.invalidateCachedConns() }
            partialNodeRetryCount = 0
            loggedFirstColorFrame = false
            loggedFirstDepthFrame = false
            currentColorIntrinsics = null
            currentDepthIntrinsics = null
            _colorStat.value = null
            _depthStat.value = null
            _state.value = BerxelDeviceState.NoDevice
            return
        }
        val token = startEpoch.incrementAndGet()
        val now = SystemClock.elapsedRealtime()
        val streamLifetimeMs = if (lastStreamStartAtMs > 0L) now - lastStreamStartAtMs else Long.MAX_VALUE
        val lostBeforeFirstFrame = lastFirstFrameAtMs < lastStreamStartAtMs
        val earlyStreamDisconnect = streamLifetimeMs in 0..STREAM_START_DISCONNECT_WINDOW_MS && lostBeforeFirstFrame
        val profileBeforeDisconnect = lastStartedProfile
        if (earlyStreamDisconnect && lastStartedMode == StartupStreamMode.DUAL) {
            val nextProfile = saferDualProfileAfterEarlyDisconnect(profileBeforeDisconnect)
            if (nextProfile != null && nextProfile.id != profileBeforeDisconnect.id) {
                streamStartBlockedReason = null
                activeStreamProfile = nextProfile
                _streamProfile.value = nextProfile
                Log.w(
                    TAG,
                    "startStreams 后 ${streamLifetimeMs}ms USB 断开且无首帧；" +
                        "自动降档 ${profileBeforeDisconnect.logLabel()} → ${nextProfile.logLabel()}",
                )
            } else {
                streamStartBlockedReason =
                    "iHawk 开流后 ${streamLifetimeMs}ms 内 USB 断开，最低 QVGA 双流也没有首帧；" +
                    "请检查 OTG 供电/线材，或用 debug 单流 color/depth 分开验证"
                Log.e(TAG, streamStartBlockedReason.orEmpty())
            }
        }
        lastPhysicalDisconnectAtMs = now
        Log.i(TAG, "USB physical disconnect; abandon SDK device token=$token")
        pendingStartJob?.cancel()
        companionRetryJob?.cancel()
        companionRetryJob = null
        readerRunning = false
        loggedFirstColorFrame = false
        loggedFirstDepthFrame = false
        joinReaderThread(mixReader, 200)
        joinReaderThread(colorReader, 200)
        joinReaderThread(depthReader, 200)
        mixReader = null
        colorReader = null
        depthReader = null
        partialNodeRetryCount = 0
        device = null
        currentColorIntrinsics = null
        currentDepthIntrinsics = null
        _colorStat.value = null
        _depthStat.value = null
        _state.value = streamStartBlockedReason?.let { BerxelDeviceState.Error(it) } ?: BerxelDeviceState.NoDevice
    }

    private fun joinReaderThread(thread: Thread?, timeoutMs: Long) {
        if (thread == null || thread == Thread.currentThread()) return
        runCatching { thread.join(timeoutMs) }
    }

    private fun saferDualProfileAfterEarlyDisconnect(profile: BerxelStreamProfile): BerxelStreamProfile? {
        if (profile.id == BerxelStreamProfiles.QVGA_15.id) return null
        return when (profile.flagProfile) {
            BerxelStreamFlagProfile.MIX_QVGA -> BerxelStreamProfiles.QVGA_15
            BerxelStreamFlagProfile.MIX_HD -> BerxelStreamProfiles.STANDARD_5
            BerxelStreamFlagProfile.MIX -> {
                val fps = profile.depth?.fps ?: profile.color?.fps ?: Int.MAX_VALUE
                if (profile.id == BerxelStreamProfiles.STANDARD_5.id || fps <= 5) {
                    BerxelStreamProfiles.QVGA_15
                } else {
                    BerxelStreamProfiles.STANDARD_5
                }
            }
            BerxelStreamFlagProfile.SINGULAR -> null
        }
    }

    private fun collectDeviceInfo(
        dev: BerxelHawkDevice,
        streamFlagProfile: BerxelStreamFlagProfile,
        requestedProfile: BerxelStreamProfile,
        colorMode: com.berxel.berxelInterface.api.admitmode.BerxelHawkStreamFrameMode?,
        depthMode: com.berxel.berxelInterface.api.admitmode.BerxelHawkStreamFrameMode?,
    ): BerxelDeviceInfo {
        val devInfo = dev.currentDeviceInfo
        val ver = dev.versions
        val fwStr = ver?.let { "${it.fwMajorVersion}.${it.fwMinorVersion}.${it.fwRevision}" }.orEmpty()
        val sdkStr = ver?.let { "${it.sdkMajorVersion}.${it.sdkMinorVersion}.${it.sdkRevision}" }.orEmpty()
        return BerxelDeviceInfo(
            vendorId = devInfo?.vendorId ?: 0,
            productId = devInfo?.productId ?: 0,
            serialNumber = devInfo?.serialNumber.orEmpty(),
            deviceAddress = devInfo?.deviceAddress.orEmpty(),
            firmwareVersion = fwStr,
            sdkVersion = sdkStr,
            streamFlagMode = streamFlagProfile,
            requestedProfileId = requestedProfile.id,
            colorMode = colorMode?.let {
                BerxelStreamSpec(
                    width = it.resolutionX,
                    height = it.resolutionY,
                    fps = it.getmFps(),
                    pixelType = it.pixelType?.name.orEmpty(),
                )
            },
            depthMode = depthMode?.let {
                BerxelStreamSpec(
                    width = it.resolutionX,
                    height = it.resolutionY,
                    fps = it.getmFps(),
                    pixelType = it.pixelType?.name.orEmpty(),
                )
            },
        )
    }

    // ───── 设备控制命令 (UI 双向绑定 + scope.launch 投到 IO) ─────────────────────

    /**
     * 一次性把 [_controls] 当前快照同步到 SDK。
     * 在 `startStreams` 成功后调，让 UI 里的开关默认值真的生效到设备。
     */
    private fun applyDefaultControls() {
        val dev = device ?: return
        val c = _controls.value
        // Kotlin 不能把这些 setter 当 property 用（SDK setter 返回 int 而非 Unit）
        runCatching { dev.setRegistrationEnable(c.registrationEnable) }
        runCatching { dev.setStreamMirror(c.streamMirror) }
        if (c.depthAutoExposure) runCatching { dev.setDepthAEStatus(true) }
        runCatching { dev.setEdgeOptimizationStatus(c.depthEdgeOptimization) }
        runCatching { dev.setDenoiseStatus(c.depthDenoise) }
        runCatching { dev.setTemperatureCompensationEnable(c.depthTemperatureCompensation) }
        if (c.colorAutoExposure) runCatching { dev.enableColorAutoExposure() }
    }

    private inline fun controlOp(label: String, crossinline op: BerxelHawkDevice.() -> Int) {
        val dev = device ?: run {
            Log.w(TAG, "$label skipped: no device")
            return
        }
        scope.launch {
            val rc = runCatching { dev.op() }.getOrElse { t ->
                Log.e(TAG, "$label exception", t); -1
            }
            Log.i(TAG, "$label rc=$rc")
        }
    }

    fun setRegistrationEnable(on: Boolean) {
        _controls.update { it.copy(registrationEnable = on) }
        controlOp("setRegistrationEnable=$on") { setRegistrationEnable(on) }
    }

    /** M1.6.8 debug：NATIVE_REWRITE 路径下切 depth assembler 切帧策略。
     *  strict=true：drop size 偏差大的"长/短帧"（只渲染 ~640×401）
     *  strict=false：全 emit（高度按真实字节算，看上去 height=775/1194 但用户实测跟世界对齐） */
    fun setDepthStrictFrameSize(strict: Boolean) {
        nativeStack.setDepthStrictFrameSize(strict)
    }
    fun isDepthStrictFrameSize(): Boolean = nativeStack.isDepthStrictFrameSize()

    /** Debug only：切 native rewrite 的 master RGB 拉流。
     * vivo+hub 上默认关；换 2510DRK44C 时可通过 debug broadcast 打开验证双路。 */
    fun setNativeMasterStreamForDebug(enable: Boolean) {
        nativeMasterStreamDebugOverride = enable
        Log.i(TAG, "setNativeMasterStreamForDebug overrideEnableMasterStream=$enable")
    }

    /** Debug only：override keepalive 间隔 ms。0=关闭 keepalive（E1 实测 depth 不依赖它）；
     *  负值/不调=默认 50。用于隔离 keepalive 失败刷屏对 color+depth 共存的影响。 */
    fun setNativeKeepaliveMsForDebug(ms: Int) {
        nativeKeepaliveMsDebugOverride = if (ms < 0) null else ms
        Log.i(TAG, "setNativeKeepaliveMsForDebug keepaliveMs=$nativeKeepaliveMsDebugOverride")
    }

    /** Debug only：override depth fps（45/30/15）。<=0/不调=默认 45。用于 1280 高帧率掉线对照。 */
    fun setNativeDepthFpsForDebug(fps: Int) {
        nativeDepthFpsDebugOverride = if (fps <= 0) null else fps
        Log.i(TAG, "setNativeDepthFpsForDebug depthFps=$nativeDepthFpsDebugOverride")
    }

    /** UI 触发：dump 接下来 N 帧 raw 字节到 app private files，host adb pull 离线分析。 */
    @Volatile var dumpRemaining: Int = 0
    fun triggerDump(n: Int = 30) {
        dumpRemaining = n
        Log.i(TAG, "★ trigger dump next $n frames")
    }

    fun setStreamMirror(on: Boolean) {
        _controls.update { it.copy(streamMirror = on) }
        controlOp("setStreamMirror=$on") { setStreamMirror(on) }
    }

    fun setDepthAutoExposure(on: Boolean) {
        _controls.update { it.copy(depthAutoExposure = on) }
        controlOp("setDepthAEStatus=$on") { setDepthAEStatus(on) }
    }

    fun setDepthEdgeOptimization(on: Boolean) {
        _controls.update { it.copy(depthEdgeOptimization = on) }
        controlOp("setEdgeOptimizationStatus=$on") { setEdgeOptimizationStatus(on) }
    }

    fun setDepthDenoise(on: Boolean) {
        _controls.update { it.copy(depthDenoise = on) }
        controlOp("setDenoiseStatus=$on") { setDenoiseStatus(on) }
    }

    fun setDepthTemperatureCompensation(on: Boolean) {
        _controls.update { it.copy(depthTemperatureCompensation = on) }
        controlOp("setTemperatureCompensationEnable=$on") { setTemperatureCompensationEnable(on) }
    }

    fun setColorAutoExposure(on: Boolean) {
        _controls.update { it.copy(colorAutoExposure = on) }
        if (on) {
            controlOp("enableColorAutoExposure") { enableColorAutoExposure() }
        }
        // off 时不立即写入；要等用户给 colorExposureUs/colorGain 数值后调 setColorExposureGain
    }

    /** 手动曝光（仅 colorAutoExposure=false 时有意义）。exposure=0/gain=0 视作 noop。 */
    fun setColorExposureGain(exposureUs: Int, gain: Int) {
        _controls.update { it.copy(colorExposureUs = exposureUs, colorGain = gain) }
        if (exposureUs == 0 && gain == 0) return
        controlOp("setColorExposureGain=$exposureUs/$gain") { setColorExposureGain(exposureUs, gain) }
    }

    fun setColorQuality(q: Int) {
        _controls.update { it.copy(colorQuality = q) }
        if (q <= 0) return
        controlOp("setColorQuality=$q") { setColorQuality(q) }
    }

    /** Process 终止前调，幂等。Hilt @Singleton 自身不会被销毁；该方法留给手动 cleanup 场景。 */
    fun shutdown() {
        scope.cancel()
        stopInternal(reason = null)
        if (usbReceiverRegistered) {
            runCatching { appContext.unregisterReceiver(usbPermissionReceiver) }
            usbReceiverRegistered = false
        }
    }

    private fun ensureUsbReceiver() {
        if (usbReceiverRegistered) return
        val filter = IntentFilter(USB_PERMISSION_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(usbPermissionReceiver, filter)
        }
        usbReceiverRegistered = true
    }

    private fun hasFullP100R3UsbNodes(): Boolean {
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDevices = usbManager.deviceList.values.filter { isBerxelUsbDevice(it) }
        return usbDevices.any { it.vendorId == BERXEL_VID && it.productId == P100R3_PRIMARY_PID } &&
            usbDevices.any { it.vendorId == P100R3_COMPANION_VID && it.productId == P100R3_COMPANION_PID }
    }

    private fun currentBerxelUsbDevices(usbManager: UsbManager): List<UsbDevice> {
        val liveDeviceNames = usbManager.deviceList.values
            .filter { isBerxelUsbDevice(it) }
            .mapTo(mutableSetOf()) { it.deviceName }
        return LinkedHashMap<String, UsbDevice>().apply {
            usbManager.deviceList.values
                .filter { isBerxelUsbDevice(it) }
                .forEach { put(it.deviceName, it) }
            authorizedDevicesByName.values
                .filter { isBerxelUsbDevice(it) }
                .filter { authorized ->
                    if (authorized.deviceName in liveDeviceNames || File(authorized.deviceName).exists()) {
                        true
                    } else {
                        authorizedDevicesByName.remove(authorized.deviceName)
                        Log.i(TAG, "drop stale authorized USB node ${usbLabel(authorized)}")
                        false
                    }
                }
                .forEach { put(it.deviceName, it) }
        }.values.sortedWith(
            compareByDescending<UsbDevice> { it.vendorId == BERXEL_VID }
                .thenBy { it.deviceName },
        )
    }

    private fun p100r3VisibleNodeLabel(hasMaster: Boolean, hasCompanion: Boolean): String {
        return when {
            hasMaster && hasCompanion -> "master + companion"
            hasMaster -> "master"
            hasCompanion -> "companion"
            else -> "无 iHawk 节点"
        }
    }

    private fun canOpenUsbDevice(usbManager: UsbManager, usbDevice: UsbDevice): Boolean {
        val conn = try {
            usbManager.openDevice(usbDevice)
        } catch (t: SecurityException) {
            Log.i(TAG, "USB node permission missing ${usbLabel(usbDevice)}: ${t.message}")
            null
        } catch (t: Throwable) {
            Log.w(TAG, "USB node open probe failed ${usbLabel(usbDevice)}", t)
            null
        }
        conn?.close()
        return conn != null
    }

    /**
     * 处理 `requestPermission` broadcast granted=false 的场景。
     *
     * OEM 实测：HONOR Magic OS / Xiaomi HyperOS 在用户点了"允许"后，broadcast 仍可能回 false
     * 但 system_server 异步把 grant 落进 device_permissions。直接信 broadcast 就会卡死。
     *
     * 处理策略（per 2026-05-14 Redmi 实测）：
     * 1. 延迟 500ms / 1500ms / 3000ms 各做一次 fd probe，任一次成功就立刻 startInternal
     * 2. 三次全失败 → 进 Error("脏缓存") 状态 + 启动 [permissionWatchdogJob]
     *    每 8s 后台 probe；权限突然好了自动恢复 startInternal，不需要用户手动拔插
     */
    private suspend fun retryAfterUsbPermissionBroadcastDenied() {
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val retryDelays = listOf(500L, 1500L, 3000L)
        for ((idx, delayMs) in retryDelays.withIndex()) {
            delay(delayMs)
            val usbDevices = usbManager.deviceList.values
                .filter { needsBerxelSdkUsbPermission(it) }
                .map { authorizedDevicesByName[it.deviceName] ?: it }
            if (usbDevices.isEmpty()) {
                Log.i(TAG, "post-deny retry${idx + 1}: device 已拔出，放弃")
                _state.value = BerxelDeviceState.NoDevice
                return
            }
            if (usbDevices.all { canOpenUsbDevice(usbManager, it) }) {
                Log.i(TAG, "post-deny retry${idx + 1}: fd probe ok, 重进启动路径")
                resumeStartAfterPermission()
                return
            }
        }
        Log.w(TAG, "post-deny 3 次重试都失败；进 Error 状态 + 启动 watchdog")
        startPermissionWatchdog()
    }

    private fun startPermissionWatchdog() {
        _state.value = BerxelDeviceState.Error(
            "USB 权限脏缓存 — 后台轮询恢复中；如长时间无响应请拔插相机或清 app 数据"
        )
        permissionWatchdogJob?.cancel()
        permissionWatchdogJob = scope.launch {
            val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
            while (isActive) {
                delay(PERMISSION_WATCHDOG_INTERVAL_MS)
                val usbDevices = usbManager.deviceList.values
                    .filter { needsBerxelSdkUsbPermission(it) }
                    .map { authorizedDevicesByName[it.deviceName] ?: it }
                if (usbDevices.isEmpty()) {
                    Log.i(TAG, "permission watchdog: device 拔出，退出 watchdog → NoDevice")
                    _state.value = BerxelDeviceState.NoDevice
                    return@launch
                }
                if (usbDevices.all { canOpenUsbDevice(usbManager, it) }) {
                    Log.i(TAG, "permission watchdog: fd probe ok，自动重进启动路径恢复")
                    resumeStartAfterPermission()
                    return@launch
                }
            }
        }
    }

    /**
     * USB 权限 granted / fd-probe 恢复后，按当前 backend 重进对应启动路径。
     * NATIVE_REWRITE → native 双流 depth（startNativeDualDepthInternal）；否则 SDK（startInternal）。
     * 都用新 epoch token 作废旧的 WaitingPermission 等待态。
     */
    private fun resumeStartAfterPermission() {
        val token = startEpoch.incrementAndGet()
        if (backend == BerxelStackBackend.NATIVE_REWRITE) {
            nativeRewriteRunning = true
            scope.launch { startNativeDualDepthInternal(token) }
        } else {
            scope.launch { startInternal(token, pendingUsbPermissionMode) }
        }
    }

    private fun requestUsbPermission(
        usbManager: UsbManager,
        usbDevice: UsbDevice,
        mode: StartupStreamMode = StartupStreamMode.DUAL,
    ) {
        ensureUsbReceiver()
        pendingUsbPermissionMode = mode
        // FLAG_IMMUTABLE：USB 权限广播 EXTRA_PERMISSION_GRANTED 是 system fill 的，不需 mutate；
        // Android 14+ 对 implicit Intent 禁 MUTABLE，IMMUTABLE 也是 Google USB host sample 推荐用法。
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            appContext,
            usbDevice.deviceName.hashCode(),
            Intent(USB_PERMISSION_ACTION).setPackage(appContext.packageName),
            piFlags,
        )
        Log.i(TAG, "requestPermission ${usbLabel(usbDevice)}")
        _state.value = BerxelDeviceState.WaitingPermission
        usbManager.requestPermission(usbDevice, pi)
    }

    private fun isBerxelUsbDevice(usbDevice: UsbDevice): Boolean {
        return usbDevice.vendorId == BERXEL_VID ||
            (usbDevice.vendorId == P100R3_COMPANION_VID && usbDevice.productId == P100R3_COMPANION_PID)
    }

    private fun needsBerxelSdkUsbPermission(usbDevice: UsbDevice): Boolean {
        return isBerxelUsbDevice(usbDevice)
    }

    private fun BerxelHawkDeviceInfo.isP100R3Usb3(): Boolean {
        return (vendorId == BERXEL_VID && productId == P100R3_PRIMARY_PID) ||
            (vendorId == P100R3_COMPANION_VID && productId == P100R3_COMPANION_PID)
    }

    private fun usbLabel(usbDevice: UsbDevice): String {
        return "${usbDevice.deviceName} vid=0x${usbDevice.vendorId.toString(16)} pid=0x${usbDevice.productId.toString(16)}"
    }

    private fun sdkDeviceLabel(info: BerxelHawkDeviceInfo): String {
        return "vid=0x${info.vendorId.toString(16)} pid=0x${info.productId.toString(16)} " +
            "sn=${info.serialNumber.orEmpty()} addr=${info.deviceAddress.orEmpty()}"
    }

    private companion object {
        const val TAG = "BerxelService"
        const val READ_TIMEOUT_MS = 100
        /** 引用计数归 0 后的停流宽限期：吸收导航切换瞬时归零，期满仍无消费者才 stop。 */
        const val CAMERA_RELEASE_GRACE_MS = 600L
        const val BERXEL_VID = 1539  // 0x603 — Berxel 厂商 ID
        const val P100R3_PRIMARY_PID = 31       // 0x001f — iHawk100RS 主节点
        const val P100R3_COMPANION_VID = 13656  // 0x3558 — iHawk100RS 伴随 UVC 节点
        const val P100R3_COMPANION_PID = 4114   // 0x1012
        const val COMPANION_ONLY_RETRY_DELAY_MS = 800L
        const val NATIVE_REWRITE_NODE_PAIR_RETRY_LIMIT = 15
        /** vivo+hub NO_DEVICE 自动重启上限；超过算硬故障，需要用户拔插。 */
        const val NATIVE_REWRITE_MAX_RESTARTS = 20
        /** 进入深度相机页默认拉 master RGB；debug extra master_rgb=false 可临时关掉。 */
        const val NATIVE_REWRITE_MASTER_STREAM_DEFAULT = true
        /** 双流 pull job 多久没拿到新 depth 帧就判定 native session 死（pump IO 掉线）→ 复位。
         *  健康设备首帧 <1s 到，3s 足够宽容；超时即 USB 掉线，必须复位否则卡假 Streaming。 */
        const val NATIVE_DUAL_FRAME_TIMEOUT_MS = 3_000L
        const val PHYSICAL_DISCONNECT_RESTART_BACKOFF_MS = 5_000L
        const val STREAM_START_DISCONNECT_WINDOW_MS = 2_500L
        const val USB_PERMISSION_ACTION = "io.gomob.nativebridge.berxel.USB_PERMISSION"
        // M1.6.8 NATIVE_REWRITE master MJPEG profile（跟 BerxelNativeStack.MASTER_FRAME_INDEX 对齐）
        const val MASTER_MJPEG_WIDTH = 640
        const val MASTER_MJPEG_HEIGHT = 400
        const val MASTER_MJPEG_FPS = 15
        /** USB 权限脏缓存 watchdog 轮询间隔；HONOR/HyperOS broadcast-deny + system_server-grant 异步的 race。 */
        const val PERMISSION_WATCHDOG_INTERVAL_MS = 8_000L
    }
}

/**
 * 给 Berxel SDK 用的 Context wrapper —— 唯一职责：拦下 registerReceiver 自动补
 * `Context.RECEIVER_NOT_EXPORTED` flag。
 *
 * Why: Android 14 (API 34) 起，所有 runtime registerReceiver 必须显式声明
 * EXPORTED / NOT_EXPORTED，否则 SecurityException。Berxel SDK 9.9.190 调
 * `context.registerReceiver(receiver, filter)` 是老 2-参签名，缺 flag。
 * 我们自己 wrap 一层把它转成 4-参签名带 flag 即可，不用改 SDK。
 *
 * 选 `RECEIVER_NOT_EXPORTED`：SDK 注册的是 USB 内部广播（自定义 action 字符串
 * + USB_DEVICE_ATTACHED/DETACHED），不需要被外部 App 主动发送 —— NOT_EXPORTED 更安全。
 */
private class SdkCompatContextWrapper(base: Context) : ContextWrapper(base) {

    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            super.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            super.registerReceiver(receiver, filter)
        }
    }

    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        broadcastPermission: String?,
        scheduler: Handler?,
    ): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            super.registerReceiver(
                receiver, filter, broadcastPermission, scheduler, Context.RECEIVER_NOT_EXPORTED,
            )
        } else {
            super.registerReceiver(receiver, filter, broadcastPermission, scheduler)
        }
    }
}
