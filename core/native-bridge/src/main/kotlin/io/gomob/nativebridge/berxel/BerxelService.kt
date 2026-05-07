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
import com.berxel.berxelInterface.api.admitenum.BerxelHawkStreamFlagEnum
import com.berxel.berxelInterface.api.admitenum.BerxelHawkStreamTypeEnum
import com.berxel.berxelInterface.api.admitmode.BerxelHawkFrame
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    private val _colorStat = MutableStateFlow<BerxelFrameStat?>(null)
    val colorStat: StateFlow<BerxelFrameStat?> = _colorStat.asStateFlow()

    private val _depthStat = MutableStateFlow<BerxelFrameStat?>(null)
    val depthStat: StateFlow<BerxelFrameStat?> = _depthStat.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var hawkContext: BerxelHawkContext? = null
    @Volatile private var device: BerxelHawkDevice? = null
    @Volatile private var readerRunning = false
    private var colorReader: Thread? = null
    private var depthReader: Thread? = null
    private var pendingStartJob: Job? = null
    /** 来自 USB_DEVICE_ATTACHED intent extras 的 UsbDevice —— 持有有效读权限。 */
    @Volatile private var authorizedDevice: UsbDevice? = null

    private val deviceStatusCallback = BerxelHawkContext.DeviceStatusChangedCallBack { vid, pid, status ->
        Log.i(TAG, "device status change vid=0x${vid.toString(16)} pid=0x${pid.toString(16)} -> $status")
        if (status == BerxelHawkDeviceStatusEnum.BERXEL_HAWK_DEVICE_STATUS_DISCONNECT) {
            // 异步：调用方可能正在 reader 线程，立即停掉自己再 update state
            scope.launch { stopInternal(reason = null); _state.value = BerxelDeviceState.NoDevice }
        }
    }

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != USB_PERMISSION_ACTION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "USB permission broadcast granted=$granted")
            if (granted) {
                // 拿到权限后接着完成 SDK open 流程
                scope.launch { startInternal() }
            } else {
                _state.value = BerxelDeviceState.Error("用户拒绝了 USB 权限")
            }
        }
    }
    @Volatile private var usbReceiverRegistered = false

    /**
     * 启动整套生命周期：加载 SDK → 弹 USB 权限 → 开 device → 起 reader 线程。
     * 幂等：当前已 Streaming/Opening/WaitingPermission 时直接返回，不重复触发权限弹窗。
     */
    fun start() {
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
        pendingStartJob = scope.launch { startInternal() }
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
        Log.i(TAG, "attachAuthorizedDevice ${device.deviceName} vid=0x${device.vendorId.toString(16)}")
        if (device.vendorId != BERXEL_VID) {
            Log.w(TAG, "ignoring non-Berxel device vid=0x${device.vendorId.toString(16)}")
            return
        }
        authorizedDevice = device
        start()
    }

    /** 主动停止：reader 退出 → close device → destroy context → 状态回 Idle。 */
    fun stop() {
        pendingStartJob?.cancel()
        scope.launch {
            stopInternal(reason = null)
            _state.value = BerxelDeviceState.Idle
        }
    }

    private fun startInternal() {
        try {
            _state.value = BerxelDeviceState.Initializing

            val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

            // Step 1: 优先用 attachAuthorizedDevice 喂进来的 device。
            // 否则退回扫 deviceList。
            val authDev = authorizedDevice
            val workingDev = authDev ?: run {
                val list = usbManager.deviceList.values.filter { it.vendorId == BERXEL_VID }
                if (list.isEmpty()) {
                    _state.value = BerxelDeviceState.NoDevice
                    return
                }
                list.first()
            }
            Log.i(TAG, "using ${workingDev.deviceName} (fromIntent=${authDev != null}, hasPermission=${usbManager.hasPermission(workingDev)})")

            // 实测开一下：openDevice 返 null 即权限有效失败，进入"请拔了重插"状态
            // (HONOR Magic OS 上 hasPermission 偶现脏缓存返 false 但实际 openDevice 能过；
            // 也有反之的情况。所以不查 flag，只看真实开 fd 结果)
            val testConn = usbManager.openDevice(workingDev)
            if (testConn == null) {
                _state.value = BerxelDeviceState.Error("USB 设备无访问权限 —— 请拔出 USB 重新插入并允许 mob3d 访问")
                return
            }
            testConn.close()

            // Step 3: 有权限了，初始化 SDK Context（也走 ContextWrapper 兼容 Android 14+）
            val wrappedCtx = SdkCompatContextWrapper(appContext)
            val ctx = BerxelHawkContext.getBerxelContext(wrappedCtx)
                ?: run {
                    _state.value = BerxelDeviceState.Error("SDK Context 加载失败")
                    return
                }
            hawkContext = ctx
            ctx.addDeviceStatusCallBack(deviceStatusCallback)

            _state.value = BerxelDeviceState.Opening
            val dev = ctx.CreateDevice() ?: run {
                _state.value = BerxelDeviceState.Error("CreateDevice 返回 null")
                return
            }
            device = dev

            // openDevice 会异步打开设备 + 申请校准/参数；用回调驱动状态机
            dev.openDevice(object : BerxelHawkDevice.OpenDeviceStatusCallBack {
                override fun onDeviceStausOpenSuccess() {
                    scope.launch { onDeviceOpened() }
                }
                override fun onDeviceStatusOpenFailed() {
                    _state.value = BerxelDeviceState.Error("打开 iHawk 失败 —— 检查 USB 接口或拔了再插")
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "startInternal 异常", t)
            _state.value = BerxelDeviceState.Error(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun onDeviceOpened() {
        val dev = device ?: return
        try {
            _state.value = BerxelDeviceState.Opening

            // 默认 MIX 流模式（COLOR + DEPTH 同时出帧）—— 跟 sample HawkColorDepth 一致
            dev.setStreamFlagMode(BerxelHawkStreamFlagEnum.BERXEL_HAWK_MIX_STREAM_FLAG_MODE)

            // 用 SDK 默认分辨率（getCurrentFrameMode 返回 default）。后续标定 / 性能调优可在
            // BerxelService 上加 setMode(spec) 接口；当前 smoke 阶段先吃默认。
            val colorMode = dev.getCurrentFrameMode(BerxelHawkStreamTypeEnum.BERXEL_HAWK_COLOR_STREAM)
            val depthMode = dev.getCurrentFrameMode(BerxelHawkStreamTypeEnum.BERXEL_HAWK_DEPTH_STREAM)
            dev.setFrameMode(BerxelHawkStreamTypeEnum.BERXEL_HAWK_COLOR_STREAM, colorMode)
            dev.setFrameMode(BerxelHawkStreamTypeEnum.BERXEL_HAWK_DEPTH_STREAM, depthMode)

            val flags = BerxelHawkStreamTypeEnum.BERXEL_HAWK_COLOR_STREAM.value or
                BerxelHawkStreamTypeEnum.BERXEL_HAWK_DEPTH_STREAM.value
            val rc = dev.startStreams(flags)
            if (rc != 0) {
                _state.value = BerxelDeviceState.Error("startStreams 返回 $rc")
                return
            }

            val info = collectDeviceInfo(dev, colorMode, depthMode)
            _state.value = BerxelDeviceState.Streaming(info)

            readerRunning = true
            colorReader = Thread({ readLoop(StreamKind.COLOR) }, "berxel-color-reader").also { it.start() }
            depthReader = Thread({ readLoop(StreamKind.DEPTH) }, "berxel-depth-reader").also { it.start() }
        } catch (t: Throwable) {
            Log.e(TAG, "onDeviceOpened 异常", t)
            _state.value = BerxelDeviceState.Error(t.message ?: t.javaClass.simpleName)
        }
    }

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
                val stat = BerxelFrameStat(
                    frameIndex = frame.frameIndex,
                    measuredFps = frame.fps,
                    timestampUs = frame.timeStamp,
                    receivedAtElapsedMs = SystemClock.elapsedRealtime(),
                    width = frame.width,
                    height = frame.height,
                )
                when (kind) {
                    StreamKind.COLOR -> _colorStat.value = stat
                    StreamKind.DEPTH -> _depthStat.value = stat
                }
            }
            // SDK 不需要 release frame；GC 自动回收（Frame 内部 mFrameHandle 由 finalizer 处理）。
        }
        Log.i(TAG, "$kind reader 退出")
    }

    private fun stopInternal(reason: String?) {
        readerRunning = false
        runCatching { colorReader?.join(500) }
        runCatching { depthReader?.join(500) }
        colorReader = null
        depthReader = null

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
        hawkContext = null

        _colorStat.value = null
        _depthStat.value = null
        if (reason != null) _state.value = BerxelDeviceState.Error(reason)
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

    /** Process 终止前调，幂等。Hilt @Singleton 自身不会被销毁；该方法留给手动 cleanup 场景。 */
    fun shutdown() {
        scope.cancel()
        stopInternal(reason = null)
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

    private companion object {
        const val TAG = "BerxelService"
        const val READ_TIMEOUT_MS = 100
        const val BERXEL_VID = 1539  // 0x603 — Berxel 厂商 ID
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
