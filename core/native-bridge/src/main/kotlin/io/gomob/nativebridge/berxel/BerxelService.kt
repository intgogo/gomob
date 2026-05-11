package io.gomob.nativebridge.berxel

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
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
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Berxel iHawk 设备的 Hilt @Singleton 包装 —— 整个 App 进程独占一个 SDK Context + 一台 device。
 *
 * 设计：
 * - SDK 的 `BerxelHawkContext` 自身是单例 (`getBerxelContext` 反复调返回同一对象)，
 *   配合 USB BroadcastReceiver / AsyncTask 搞 USB 权限。本类与 SDK 的单例 1:1 对齐。
 * - 三态 reader：Color reader / Depth reader 两个独立线程，谁拿到帧谁更新自己的 [BerxelFrameStat]，
 *   主线程 UI 通过 [colorStat] / [depthStat] StateFlow 订阅。
 * - 帧不出 reader 线程：SDK Frame 持有 native handle，逃出 reader 后做后续处理是 UAF 风险。
 *   后续 fusion / reconstruction 想要帧数据的话，应该在 reader 线程里立刻拷贝 ByteBuffer 出来再丢。
 *
 * Why "service" 而不是更细粒度: 双流必须共用一个 Device 实例（SDK 限制），
 * 所以"全局单例"是 SDK 自带语义不是过度抽象。
 */
@Singleton
class BerxelService @Inject constructor(
    @ApplicationContext private val appContext: Context,
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
    private var colorReader: Thread? = null
    private var depthReader: Thread? = null
    private var pendingStartJob: Job? = null
    private var companionRetryJob: Job? = null
    @Volatile private var partialNodeRetryCount = 0
    @Volatile private var lastPhysicalDisconnectAtMs = 0L
    @Volatile private var pendingUsbPermissionMode = StartupStreamMode.DUAL
    private val startEpoch = AtomicLong(0L)
    /**
     * 来自 USB_DEVICE_ATTACHED intent extras / requestPermission broadcast 的 UsbDevice。
     *
     * P100R3 / iHawk100RS 会枚举成两个 USB 节点；Android 的授权按 deviceName 绑定，
     * 所以这里按节点缓存，避免只记住其中一个导致 SDK 内部枚举另一个节点时读 serial 崩掉。
     */
    private val authorizedDevicesByName = ConcurrentHashMap<String, UsbDevice>()

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
                // 拿到权限后把 device 喂回 service，再走完整启动
                authorizedDevicesByName[grantedDevice.deviceName] = grantedDevice
                scope.launch { startInternal(startEpoch.incrementAndGet(), pendingUsbPermissionMode) }
            } else {
                // HONOR Magic OS 实测：用户点了允许后，dumpsys 已经有 device_permissions，
                // 但广播仍可能回 granted=false/device=null。这里不信广播，立刻用 openDevice 复查。
                scope.launch { continueAfterUsbPermissionBroadcastDenied() }
            }
        }
    }
    @Volatile private var usbReceiverRegistered = false

    /**
     * 启动整套生命周期：加载 SDK → 弹 USB 权限 → 开 device → 起 reader 线程。
     * 幂等：当前已 Streaming/Opening/WaitingPermission 时直接返回，不重复触发权限弹窗。
     */
    fun start() = start(StartupStreamMode.DUAL)

    fun startColorOnlyForDebug() = start(StartupStreamMode.COLOR_ONLY)

    fun startDepthOnlyForDebug() = start(StartupStreamMode.DEPTH_ONLY)

    private fun start(mode: StartupStreamMode) {
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
        pendingStartJob = scope.launch { startInternal(token, mode) }
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
        start()
    }

    /** 主动停止：reader 退出 → close device → destroy context → 状态回 Idle。 */
    fun stop() {
        startEpoch.incrementAndGet()
        pendingStartJob?.cancel()
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

            _state.value = BerxelDeviceState.Opening
            val dev = ctx.CreateDevice() ?: run {
                _state.value = BerxelDeviceState.Error("CreateDevice 返回 null")
                return
            }
            device = dev

            // openDevice 会异步打开设备 + 申请校准/参数；用回调驱动状态机
            dev.openDevice(object : BerxelHawkDevice.OpenDeviceStatusCallBack {
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
            })
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
                Log.i(TAG, "skip setDeviceTransferMode(BULK) for P100R3; SDK returned -8 on phone OTG")
            }

            val streamFlag = when (mode) {
                StartupStreamMode.COLOR_ONLY -> BerxelHawkStreamFlagEnum.BERXEL_HAWK_SINGULAR_STREAM_FLAG_MODE
                StartupStreamMode.DEPTH_ONLY,
                StartupStreamMode.DUAL -> BerxelHawkStreamFlagEnum.BERXEL_HAWK_MIX_STREAM_FLAG_MODE
            }
            val flagRc = dev.setStreamFlagMode(streamFlag)
            Log.i(TAG, "setStreamFlagMode($streamFlag) rc=$flagRc mode=$mode")

            // P100R3 实测 SDK 的 current mode 可能给出 640x400@45；手机 OTG 双流先用保守 fps。
            val colorEnabled = mode != StartupStreamMode.DEPTH_ONLY
            val depthEnabled = mode != StartupStreamMode.COLOR_ONLY
            val colorMode = if (colorEnabled) {
                selectStableFrameMode(
                    dev = dev,
                    streamType = BerxelHawkStreamTypeEnum.BERXEL_HAWK_COLOR_STREAM,
                    label = "color",
                )
            } else {
                null
            }
            val depthMode = if (depthEnabled) {
                selectStableFrameMode(
                    dev = dev,
                    streamType = BerxelHawkStreamTypeEnum.BERXEL_HAWK_DEPTH_STREAM,
                    label = "depth",
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

            val info = collectDeviceInfo(dev, colorMode, depthMode)
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

            readerRunning = true
            if (colorEnabled) {
                colorReader = Thread({ readLoop(StreamKind.COLOR) }, "berxel-color-reader").also { it.start() }
            }
            if (depthEnabled) {
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

        val stable640x400 = supported
            .filter { it.resolutionX == 640 && it.resolutionY == 400 && it.getmFps() in 1..P100R3_SAFE_STREAM_FPS }
            .sortedWith(compareByDescending<BerxelHawkStreamFrameMode> { it.getmFps() })
        val selected = stable640x400.firstOrNull()
            ?: supported
                .filter { it.getmFps() in 1..P100R3_SAFE_STREAM_FPS }
                .sortedWith(
                    compareBy<BerxelHawkStreamFrameMode> {
                        kotlin.math.abs(it.resolutionX - 640) + kotlin.math.abs(it.resolutionY - 400)
                    }.thenByDescending { it.getmFps() },
                )
                .firstOrNull()
            ?: current?.coerceToStableFps()

        if (selected != null) {
            Log.i(TAG, "$label selected mode ${frameModeLabel(selected)}")
        }
        return selected
    }

    private fun BerxelHawkStreamFrameMode.coerceToStableFps(): BerxelHawkStreamFrameMode {
        val fps = getmFps()
        if (fps in 1..P100R3_SAFE_STREAM_FPS) return this
        val stableFps = P100R3_SAFE_STREAM_FPS
        Log.w(TAG, "current mode fps=$fps 超过手机 OTG 保守上限，强制降到 $stableFps: ${frameModeLabel(this)}")
        return BerxelHawkStreamFrameMode(pixelType, resolutionX, resolutionY, stableFps)
    }

    private fun frameModeLabel(mode: BerxelHawkStreamFrameMode): String {
        return "${mode.resolutionX}x${mode.resolutionY}@${mode.getmFps()} ${mode.pixelType?.name.orEmpty()}"
    }

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
    private enum class StreamKind { COLOR, DEPTH }

    private fun readLoop(kind: StreamKind) {
        Log.i(TAG, "$kind reader 启动")
        while (readerRunning) {
            val dev = device ?: break
            val frame: BerxelHawkFrame? = try {
                when (kind) {
                    StreamKind.COLOR -> dev.readColorFrame(READ_TIMEOUT_MS)
                    StreamKind.DEPTH -> dev.readDepthFrame(READ_TIMEOUT_MS)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "$kind readFrame 异常", t)
                null
            }
            if (frame != null) {
                processFrame(kind, frame)
            }
            // SDK 不需要 release frame；GC 自动回收（Frame 内部 mFrameHandle 由 finalizer 处理）。
        }
        Log.i(TAG, "$kind reader 退出")
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
                // SDK pixelType 是 BERXEL_HAWK_PIXEL_TYPE_DEP_16BIT_12I_4D 时，每个 uint16 是
                // 12bit-int + 4bit-fraction 定点格式：raw_value / 16.0 才是真实毫米。
                // 实测 (LOG-AN10 + iHawk-072): raw=15041 应解释为 940mm，不是 15m。
                // 在此原地把 dst 转成纯 mm（每像素右移 4 位），下游 ingest / 拓印 / 渲染统一拿 mm。
                //
                // 性能: 用 bulk get/put 走 ShortArray 一次性 256K 元素拷贝 + 处理 + 回写；
                // 比 per-element ShortBuffer.put(i, v) 快 5-10x，在 reader 线程上 < 5ms 完成
                // 不阻塞 30fps 帧流（之前 per-element 版本会拖慢 reader 导致前端时间不动）。
                if (pixelTypeName.contains("12I_4D")) {
                    val sb = dst.asShortBuffer()
                    val pixels = srcSize / 2
                    val buf = depthScratch ?: ShortArray(pixels).also { depthScratch = it }
                    val tmp = if (buf.size >= pixels) buf else ShortArray(pixels).also { depthScratch = it }
                    sb.position(0)
                    sb.get(tmp, 0, pixels)
                    for (i in 0 until pixels) {
                        tmp[i] = ((tmp[i].toInt() and 0xFFFF) shr 4).toShort()
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
        runCatching { colorReader?.join(500) }
        runCatching { depthReader?.join(500) }
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
        val token = startEpoch.incrementAndGet()
        lastPhysicalDisconnectAtMs = SystemClock.elapsedRealtime()
        Log.i(TAG, "USB physical disconnect; abandon SDK device token=$token")
        pendingStartJob?.cancel()
        companionRetryJob?.cancel()
        companionRetryJob = null
        readerRunning = false
        runCatching { colorReader?.join(200) }
        runCatching { depthReader?.join(200) }
        colorReader = null
        depthReader = null
        partialNodeRetryCount = 0
        device = null
        currentColorIntrinsics = null
        currentDepthIntrinsics = null
        _colorStat.value = null
        _depthStat.value = null
        _state.value = BerxelDeviceState.NoDevice
    }

    private fun collectDeviceInfo(
        dev: BerxelHawkDevice,
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

    private fun continueAfterUsbPermissionBroadcastDenied() {
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDevices = usbManager.deviceList.values
            .filter { needsBerxelSdkUsbPermission(it) }
            .map { authorizedDevicesByName[it.deviceName] ?: it }
        if (usbDevices.isNotEmpty() && usbDevices.all { canOpenUsbDevice(usbManager, it) }) {
            Log.i(TAG, "USB permission broadcast was false, but fd probe says SDK-visible nodes are authorized")
            startInternal(startEpoch.incrementAndGet(), pendingUsbPermissionMode)
        } else {
            _state.value = BerxelDeviceState.Error("用户拒绝了 USB 权限 — 拔出重插以重试")
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
        return isBerxelUsbDevice(usbDevice) || isKnownDellDockUsbNode(usbDevice)
    }

    private fun isKnownDellDockUsbNode(usbDevice: UsbDevice): Boolean {
        return usbDevice.vendorId == DELL_DOCK_VID &&
            (usbDevice.productId == DELL_DOCK_PID_45166 || usbDevice.productId == DELL_DOCK_PID_45167)
    }

    private fun BerxelHawkDeviceInfo.isP100R3Usb3(): Boolean {
        return (vendorId == BERXEL_VID && productId == P100R3_PRIMARY_PID) ||
            (vendorId == P100R3_COMPANION_VID && productId == P100R3_COMPANION_PID)
    }

    private fun usbLabel(usbDevice: UsbDevice): String {
        return "${usbDevice.deviceName} vid=0x${usbDevice.vendorId.toString(16)} pid=0x${usbDevice.productId.toString(16)}"
    }

    private companion object {
        const val TAG = "BerxelService"
        const val READ_TIMEOUT_MS = 100
        const val BERXEL_VID = 1539  // 0x603 — Berxel 厂商 ID
        const val P100R3_PRIMARY_PID = 31       // 0x001f — iHawk100RS 主节点
        const val P100R3_COMPANION_VID = 13656  // 0x3558 — iHawk100RS 伴随 UVC 节点
        const val P100R3_COMPANION_PID = 4114   // 0x1012
        const val DELL_DOCK_VID = 16700
        const val DELL_DOCK_PID_45166 = 45166
        const val DELL_DOCK_PID_45167 = 45167
        const val COMPANION_ONLY_RETRY_DELAY_MS = 800L
        const val PHYSICAL_DISCONNECT_RESTART_BACKOFF_MS = 5_000L
        const val P100R3_SAFE_STREAM_FPS = 15
        const val USB_PERMISSION_ACTION = "io.gomob.nativebridge.berxel.USB_PERMISSION"
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
