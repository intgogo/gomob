package io.gomob.nativebridge.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gomob.model.CameraIntrinsics
import io.gomob.model.ColorFrame
import io.gomob.model.DepthFrame
import io.gomob.model.DepthSampleFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * eYs3D / Etron RS-D550（ROSIE4，0x3438:0x0206）取流服务。
 *
 * ★★★ 2026-06-17 收口为 **纯 native 直驱厂商 C++ 引擎路径**（[CameraStack] → native [Eys3dVendorCppSession]）：
 *   native 层 dlopen libUVCCamera.so 直调厂商 UVCCamera/UVCPreview/FrameGrabber C++ 类跑 mode25 起流链，
 *   帧经 FrameGrabber 回调 trampoline 全 native 进 core，零 Java 编排（仅本服务 UsbManager 拿 fd）。
 *   复用厂商已验证起流链出 mode25 真深度，规避自研 -EPROTO 硬墙。Java ApcCamera shim 已退役（见 git 历史）。
 *   详见 finding_eys3d_zero_vendor_independence + docs/architecture/13-eys3d-driver.md。
 *
 * PUSH→POLL 模型：vendor native 线程出帧进 native core；本服务 [startPollLoop] 轮询 [CameraStack.pollDepthMm]/
 *   [CameraStack.pollColor] 转 [DepthFrame]/[ColorFrame] 发流。看门狗判活：出帧刷 [lastFrameMs]，超
 *   [FRAME_TIMEOUT_MS] 无帧判死。
 *
 * USB fd 所有权：本服务 [startIndependentNative] 内 [UsbManager.openDevice] 拿 fd 交 native；
 *   [stop] → [tearDownNative]（cameraStack.stop + usbConn.close）是唯一释放点。
 */
@Singleton
class Eys3dCameraService @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : CameraSource {

    override val deviceLabel: String get() = CameraModel.Eys3d.deviceTypeLabel

    @Volatile private var activeDeviceSerial: String? = null
    override val deviceSerial: String? get() = activeDeviceSerial
    @Volatile private var activeDeviceName: String? = null
    @Volatile private var authorizedDevice: UsbDevice? = null

    private val _sourceState = MutableStateFlow<CameraSourceState>(CameraSourceState.Idle)
    override val sourceState: StateFlow<CameraSourceState> = _sourceState.asStateFlow()

    // 任一路（color/depth）出帧即刷新；看门狗判活以此为准。
    @Volatile private var lastFrameMs = 0L

    private val _colorFrames = MutableSharedFlow<ColorFrame>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val colorFrames: SharedFlow<ColorFrame> = _colorFrames.asSharedFlow()

    private val _depthFrames = MutableSharedFlow<DepthFrame>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val depthFrames: SharedFlow<DepthFrame> = _depthFrames.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var running = false
    private var watchdogJob: Job? = null
    private var depthFrameIdx = 0
    private var colorFrameIdx = 0
    // 看门狗判死后的有限次自动重连计数（对齐 BerxelService：出帧恢复清零，超上限放弃报错）。
    @Volatile private var restartCount = 0
    @Volatile private var sessionStartedAtMs = 0L
    @Volatile private var firstDepthFrameArrived = false

    // ─── native 直驱厂商 C++ 引擎路径（dlopen libUVCCamera.so，零 Java 编排） ───
    private val cameraStack = CameraStack()
    @Volatile private var usbConn: UsbDeviceConnection? = null
    private var pollJob: Job? = null

    // ─── 引用计数生命周期（导航切换时计数不归 0，相机不抖） ───
    private val leaseState = CameraLeaseState()
    private val usbGeneration = AtomicLong(0)
    private val sessionGeneration = AtomicLong(0)
    private val releaseJobLock = Any()
    @Volatile private var releaseStopJob: Job? = null

    override fun acquire() {
        val lease = leaseState.acquire()
        synchronized(releaseJobLock) {
            releaseStopJob?.cancel()
            releaseStopJob = null
        }
        Log.i(TAG, "acquire → count=${lease.count}")
        if (lease.shouldStart) scope.launch { startInternal() }
    }

    /** 缓存系统 attach intent 中带真实 USB 权限的实例；VIN 页已打开时立即热启动。 */
    fun attachAuthorizedDevice(device: UsbDevice) {
        if (CameraModel.fromUsbIds(device.vendorId, device.productId) !is CameraModel.Eys3d) return
        val generation = usbGeneration.incrementAndGet()
        authorizedDevice = device
        Log.i(TAG, "attachAuthorizedDevice ${usbLabel(device)} generation=$generation consumers=${leaseState.consumerCount}")
        if (leaseState.consumerCount > 0) {
            scope.launch {
                if (generation != usbGeneration.get()) return@launch
                if (running || usbConn != null || cameraStack.isOpen) stop()
                startInternal(device, generation)
            }
        }
    }

    fun detachDevice(device: UsbDevice) {
        if (CameraModel.fromUsbIds(device.vendorId, device.productId) !is CameraModel.Eys3d) return
        val generation = usbGeneration.incrementAndGet()
        scope.launch { detachInternal(device.deviceName, generation) }
    }

    @Synchronized
    private fun detachInternal(deviceName: String, generation: Long) {
        if (generation != usbGeneration.get()) return
        val cachedMatches = authorizedDevice?.deviceName == deviceName
        val activeMatches = activeDeviceName == deviceName
        if (!cachedMatches && !activeMatches) return
        if (cachedMatches) authorizedDevice = null
        sessionGeneration.incrementAndGet()
        running = false
        restartCount = 0
        watchdogJob?.cancel(); watchdogJob = null
        tearDownNative(invalidateGeneration = false)
        _sourceState.value = if (leaseState.consumerCount > 0) CameraSourceState.NoDevice else CameraSourceState.Idle
        Log.i(TAG, "eYs3D detached $deviceName generation=$generation")
    }

    override fun release() {
        val lease = leaseState.release()
        if (lease.underflow) {
            Log.w(TAG, "release 被忽略：当前没有消费者")
            return
        }
        Log.i(TAG, "release → count=${lease.count}")
        val stopToken = lease.stopToken ?: return
        val stopJob = scope.launch {
            delay(CAMERA_RELEASE_GRACE_MS)
            synchronized(this@Eys3dCameraService) {
                if (leaseState.canStop(stopToken)) {
                    Log.i(TAG, "release 宽限期满且无消费者 → stop()")
                    stop()
                } else {
                    Log.i(TAG, "忽略过期 release 宽限任务，保持最新相机意图")
                }
            }
        }
        synchronized(releaseJobLock) {
            releaseStopJob?.cancel()
            releaseStopJob = stopJob
        }
    }

    /** IR 投射器开关：关→L' 出干净灰度(标定用)，开→散斑(测深)。经 vendor session 写 FW 0xE0(0/3)。 */
    override fun setIrProjector(on: Boolean) {
        val ok = cameraStack.setControls(irCurrent = if (on) 3 else 0)
        Log.i(TAG, "setIrProjector on=$on → ${if (ok) "ok" else "no-op(未起流?)"}")
    }

    // ─── 启动：枚举 → 权限 → native 直驱厂商 C++ 会话 → poll + 看门狗 ───
    @Synchronized
    private fun startInternal(
        preferredDevice: UsbDevice? = null,
        expectedUsbGeneration: Long = usbGeneration.get(),
    ) {
        if (leaseState.consumerCount <= 0) {
            _sourceState.value = CameraSourceState.Idle
            return
        }
        if (expectedUsbGeneration != usbGeneration.get()) return
        if (running) return
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = resolveCurrentUsbDevice(
            usbManager = usbManager,
            model = CameraModel.Eys3d,
            preferredDevice = preferredDevice,
            authorizedDevice = authorizedDevice,
        )
        if (device == null) {
            _sourceState.value = CameraSourceState.NoDevice
            return
        }
        // ★ 厂商 C++ 引擎(libUVCCamera/libESPDI)仅 arm64-v8a：32 位设备无对应 .so，dlopen 会失败。
        //   提前给明确不支持提示，避免在 32 位机上空跑 30s 看门狗超时才报错。补 v7a 需匹配版本 vendor 二进制
        //   + 重做 32 位 struct offset RE（vendor_uvc_abi.h 的 0x2430/+8/+0x10 是 64 位布局），低优先级。
        if (Build.SUPPORTED_64_BIT_ABIS.isEmpty()) {
            Log.w(TAG, "eYs3D 需 64 位设备：厂商 C++ 引擎仅 arm64-v8a")
            _sourceState.value = CameraSourceState.Error("eYs3D 需 64 位手机（厂商深度引擎仅支持 arm64-v8a）")
            return
        }
        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(usbManager, device, expectedUsbGeneration)
            return  // 等 receiver granted 回调重进 startInternal
        }
        activeDeviceSerial = runCatching { device.serialNumber?.trim() }
            .onFailure { Log.w(TAG, "读取 eYs3D 序列号失败", it) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
        activeDeviceName = device.deviceName
        Log.i(TAG, "eYs3D 物理序列号=${activeDeviceSerial ?: "<unavailable>"}")
        _sourceState.value = CameraSourceState.Opening
        if (!startIndependentNative(usbManager, device)) {
            activeDeviceSerial = null
            activeDeviceName = null
            return
        }
        running = true
        val generation = sessionGeneration.incrementAndGet()
        val nowMs = SystemClock.elapsedRealtime()
        sessionStartedAtMs = nowMs
        lastFrameMs = nowMs
        firstDepthFrameArrived = false
        depthFrameIdx = 0
        colorFrameIdx = 0
        Log.i(TAG, "eYs3D 会话已启动，等待 mode25 深度首帧")
        startPollLoop(generation)
        startWatchdog(generation)
    }

    // ─── native 直驱厂商 C++ 路径：开 usbfs fd → CameraStack → native Eys3dVendorCppSession ───
    private fun startIndependentNative(usbManager: UsbManager, device: UsbDevice): Boolean {
        val conn = runCatching { usbManager.openDevice(device) }.getOrNull()
        if (conn == null || conn.fileDescriptor < 0) {
            _sourceState.value = CameraSourceState.Error("eYs3D openDevice 失败（无 USB 权限或被占用）")
            runCatching { conn?.close() }
            return false
        }
        usbConn = conn
        // ★ vendor C++ 直驱路径需先 setVM：libUVCCamera 的 startPreview 起抓拍线程,内部 getVM()→AttachCurrentThread,
        //   未 setVM 则 getVM()=null 崩。bindEys3dVendorJni 经 JNI_OnLoad 调 vendor setVM(本进程 JavaVM)+RegisterNatives。
        runCatching { io.gomob.nativebridge.NativeBridge.bindEys3dVendorJni() }
            .onFailure { Log.w(TAG, "bindEys3dVendorJni 异常", it) }
        // ★ vendor connect 内部 strdup 此目录传给 UVCPreview（NULL 必崩）；用 app 专属外部目录（无需权限、必可写），
        //   经 configJson → SessionConfig.options_json 下发，替代此前 native 硬编码 /storage/emulated/0/eys3d。
        val storageDir = (appContext.getExternalFilesDir("eys3d") ?: appContext.filesDir).absolutePath
        // CameraStack → NativeBridge.cameraOpenByFds(0x3438,0x0206,[fd]) → Eys3dFdDriver.open_fd → Eys3dVendorCppSession
        //   （纯 native 直驱厂商 C++ 引擎，零 Java 编排）。fd 所有权留本服务（stop 关）。
        if (!cameraStack.start(CameraModel.Eys3d, intArrayOf(conn.fileDescriptor), storageDir.toByteArray())) {
            _sourceState.value = CameraSourceState.Error("eYs3D cameraOpenByFds 失败（看 eys3d_vcpp logcat）")
            runCatching { conn.close() }
            usbConn = null
            return false
        }
        Log.i(TAG, "eYs3D native 直驱会话已开（fd=${conn.fileDescriptor}, storage=$storageDir）")
        return true
    }

    // poll 循环（IO 线程）：用循环外复用的 scratch buffer 做 native poll（绝大多数迭代 n<=0 无帧，
    // 旧实现每迭代白分配 160KB DirectByteBuffer）。只有真出帧（n>0）才把数据拷进新 buffer 再 emit，
    // 保证下发给 consumer 的 buffer 不会被下一次 poll 覆盖（零拷贝竞争）。
    private fun startPollLoop(generation: Long) {
        pollJob?.cancel()
        pollJob = scope.launch {
            val outInfo = LongArray(4)
            val colorInfo = LongArray(4)
            var polls = 0
            var got = 0
            var lastNegativePollLogMs = 0L
            val pollScratch = ByteBuffer.allocateDirect(DEPTH_W * DEPTH_H * 2).order(ByteOrder.LITTLE_ENDIAN)
            while (isActive && running && generation == sessionGeneration.get()) {
                pollScratch.clear()
                val n = cameraStack.pollDepthMm(pollScratch, outInfo)
                if (generation != sessionGeneration.get()) break
                polls++
                if (n < 0) {
                    val nowMs = SystemClock.elapsedRealtime()
                    val nativeState = cameraStack.sessionState()
                    when (
                        evaluateEys3dSessionHealth(
                            nativeState = nativeState,
                            firstDepthFrameArrived = firstDepthFrameArrived,
                            startupElapsedMs = nowMs - sessionStartedAtMs,
                            startupTimeoutMs = EYS3D_STARTUP_TIMEOUT_MS,
                        )
                    ) {
                        Eys3dSessionHealth.NativeTerminal -> {
                            markSessionDead(
                                generation,
                                "native state=$nativeState（pollDepth=$n）",
                            )
                            break
                        }
                        Eys3dSessionHealth.StartupTimedOut -> {
                            markSessionDead(
                                generation,
                                "启动超过 ${EYS3D_STARTUP_TIMEOUT_MS}ms 仍无深度首帧（state=$nativeState）",
                            )
                            break
                        }
                        Eys3dSessionHealth.HealthyOrStarting -> {
                            // snapshot 的 -1 还可表示容量/快照暂不可用，不能越过 native state 直接判死。
                            if (nowMs - lastNegativePollLogMs >= NEGATIVE_POLL_LOG_INTERVAL_MS) {
                                Log.i(TAG, "eYs3D 启动/运行中忽略非终态负 poll=$n state=$nativeState")
                                lastNegativePollLogMs = nowMs
                            }
                        }
                    }
                }
                if (n > 0) {
                    got++
                    val w = outInfo[0].toInt().let { if (it > 0) it else DEPTH_W }
                    val h = outInfo[1].toInt().let { if (it > 0) it else DEPTH_H }
                    val len = w * h * 2
                    // 出帧才分配 + 拷贝；emit 后此 buffer 归 consumer，scratch 继续被下次 poll 复用。
                    val frameBuf = ByteBuffer.allocateDirect(len).order(ByteOrder.LITTLE_ENDIAN)
                    pollScratch.position(0).limit(len)
                    frameBuf.put(pollScratch)
                    frameBuf.position(0).limit(len)
                    onDepthFrame(w, h, frameBuf, outInfo[2].toInt(), outInfo[3] / 1000L)
                    if (got == 1) {
                        Log.i(TAG, "eYs3D 首帧深度到达 ${w}x$h")
                        if (restartCount > 0) {
                            Log.i(TAG, "eYs3D 首帧恢复，清零自动重连计数 old=$restartCount")
                            restartCount = 0
                        }
                    }
                }
                if (polls % 300 == 0) Log.d(TAG, "pollDepthMm polls=$polls got=$got")
                // 彩色：eYs3D 自身 L' 流（vendor uvc_any2rgb 出 RGB24 1280×256），poll 出来喂 onColorFrame。
                val cbytes = cameraStack.pollColor(colorInfo)
                if (generation != sessionGeneration.get()) break
                if (cbytes != null && cbytes.size == COLOR_W * COLOR_H * 3) {
                    val cbuf = ByteBuffer.allocateDirect(cbytes.size).order(ByteOrder.LITTLE_ENDIAN)
                    cbuf.put(cbytes); cbuf.position(0).limit(cbytes.size)
                    onColorFrame(COLOR_W, COLOR_H, cbuf, colorInfo[2].toInt(), colorInfo[3] / 1000L)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    // 深度回调（vendor native 线程）：mode25 原始 disparity×8（u16 LE），VIN 服务端按原厂 bin 恢复毫米坐标。
    private fun onDepthFrame(w: Int, h: Int, disparityX8: ByteBuffer, serial: Int, timestampUs: Long) {
        if (!running || timestampUs <= 0L) return
        lastFrameMs = SystemClock.elapsedRealtime()
        firstDepthFrameArrived = true
        if (_sourceState.value !is CameraSourceState.Streaming) {
            _sourceState.value = CameraSourceState.Streaming(deviceLabel, w, h)
        }
        _depthFrames.tryEmit(
            DepthFrame(
                timestampUs = timestampUs,
                frameIndex = serial.takeIf { it > 0 } ?: ++depthFrameIdx,
                width = w,
                height = h,
                data = disparityX8,
                // 深度与 L' 共用 mode25 裁切坐标，只是统一缩放到 0.5。
                intrinsics = rsd550Mode25Intrinsics(w, h),
                registeredToColor = false,
                sampleFormat = DepthSampleFormat.DISPARITY_X8_U16,
            ),
        )
    }

    // 彩色回调（vendor native 线程）：RGB24 下采样预览帧 → 发流 + 刷看门狗。
    private fun onColorFrame(w: Int, h: Int, rgb24: ByteBuffer, serial: Int, timestampUs: Long) {
        if (!running || timestampUs <= 0L) return
        lastFrameMs = SystemClock.elapsedRealtime()
        _colorFrames.tryEmit(
            ColorFrame(
                timestampUs = timestampUs,
                frameIndex = serial.takeIf { it > 0 } ?: ++colorFrameIdx,
                width = w,
                height = h,
                data = rgb24,
                pixelType = "EYS3D_RGB24",
                // mode25 是全幅矫正图的竖向裁切带，再做统一缩放；不是把 960 高各向异性压成 256/128。
                intrinsics = rsd550Mode25Intrinsics(w, h),
            ),
        )
    }

    private fun startWatchdog(generation: Long) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && running && generation == sessionGeneration.get()) {
                delay(1_000)
                if (generation != sessionGeneration.get() || !running) break
                val nowMs = SystemClock.elapsedRealtime()
                val nativeState = cameraStack.sessionState()
                when (
                    evaluateEys3dSessionHealth(
                        nativeState = nativeState,
                        firstDepthFrameArrived = firstDepthFrameArrived,
                        startupElapsedMs = nowMs - sessionStartedAtMs,
                        startupTimeoutMs = EYS3D_STARTUP_TIMEOUT_MS,
                    )
                ) {
                    Eys3dSessionHealth.NativeTerminal -> {
                        markSessionDead(generation, "native state=$nativeState")
                        break
                    }
                    Eys3dSessionHealth.StartupTimedOut -> {
                        markSessionDead(
                            generation,
                            "启动超过 ${EYS3D_STARTUP_TIMEOUT_MS}ms 仍无深度首帧（state=$nativeState）",
                        )
                        break
                    }
                    Eys3dSessionHealth.HealthyOrStarting -> {
                        if (firstDepthFrameArrived && nowMs - lastFrameMs > FRAME_TIMEOUT_MS) {
                            markSessionDead(generation, "${FRAME_TIMEOUT_MS}ms 无新帧（state=$nativeState）")
                            break
                        }
                    }
                }
            }
        }
    }

    // LOW(error): 旧实现判死后只置 Error 不重连。对齐 BerxelService 的有限次自动重连：
    // 释放 native 会话 → 若仍有消费者且未超上限 → 延时后重进 startInternal；
    // 出帧恢复会在 poll 循环清零 restartCount，连续失败超 EYS3D_MAX_RESTARTS 才落终态 Error。
    @Synchronized
    private fun markSessionDead(generation: Long, reason: String) {
        if (generation != sessionGeneration.get()) return
        restartCount++
        val attempt = restartCount
        Log.e(TAG, "eYs3D session 失效：$reason；第 $attempt 次自动重连")
        sessionGeneration.incrementAndGet()
        running = false
        tearDownNative(invalidateGeneration = false)
        if (leaseState.consumerCount <= 0) {
            Log.i(TAG, "eYs3D session 死，但无活动消费者，不自动重连")
            _sourceState.value = CameraSourceState.Idle
            return
        }
        if (attempt >= EYS3D_MAX_RESTARTS) {
            Log.e(TAG, "eYs3D 已连续自动重连 $attempt 次，放弃")
            _sourceState.value =
                CameraSourceState.Error("eYs3D 流反复掉线（USB IO）— 重新插拔相机或检查 OTG 供电")
            return
        }
        _sourceState.value = CameraSourceState.Opening
        scope.launch {
            delay(EYS3D_RESTART_DELAY_MS)
            if (leaseState.consumerCount > 0 && !running) startInternal()
        }
    }

    // 自研 native 路径释放：停 poll → cameraStack.stop（释放 native 会话）→ 关 usbfs 连接（fd 唯一释放点）。
    private fun tearDownNative(invalidateGeneration: Boolean = true) {
        if (invalidateGeneration) sessionGeneration.incrementAndGet()
        pollJob?.cancel(); pollJob = null
        runCatching { cameraStack.stop() }.onFailure { Log.w(TAG, "cameraStack.stop 异常", it) }
        runCatching { usbConn?.close() }.onFailure { Log.w(TAG, "usbConn.close 异常", it) }
        usbConn = null
        activeDeviceSerial = null
        activeDeviceName = null
    }

    /** 停止 + 释放（fd 唯一释放点）。 */
    @Synchronized
    fun stop() {
        if (!running && !cameraStack.isOpen) {
            _sourceState.value = CameraSourceState.Idle
            return
        }
        Log.i(TAG, "eYs3D stop")
        running = false
        restartCount = 0
        watchdogJob?.cancel(); watchdogJob = null
        tearDownNative()
        _sourceState.value = CameraSourceState.Idle
    }

    // ─── USB 权限链（单节点 0x3438:0x0206） ───
    @Volatile private var usbReceiverRegistered = false

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != USB_PERMISSION_ACTION) return
            val rawGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val generation = intent.getLongExtra(EXTRA_USB_GENERATION, -1L)
            if (generation != usbGeneration.get()) {
                Log.i(TAG, "忽略过期 eYs3D USB permission generation=$generation current=${usbGeneration.get()}")
                return
            }
            @Suppress("DEPRECATION")
            val broadcastDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = resolveCurrentUsbDevice(
                usbManager = usbManager,
                model = CameraModel.Eys3d,
                preferredDevice = broadcastDevice,
                authorizedDevice = authorizedDevice,
            )
            val managerGranted = device?.let(usbManager::hasPermission) == true
            val granted = isUsbPermissionGranted(rawGranted, managerGranted)
            val grantedDevice = when {
                rawGranted && broadcastDevice != null && device != null &&
                    broadcastDevice.isSameUsbNode(device) -> broadcastDevice
                rawGranted -> device
                managerGranted -> device
                else -> null
            }
            Log.i(
                TAG,
                "eYs3D USB permission raw=$rawGranted manager=$managerGranted effective=$granted " +
                    "device=${device?.let(::usbLabel) ?: "<missing>"} generation=$generation",
            )
            if (grantedDevice != null) authorizedDevice = grantedDevice
            if (leaseState.consumerCount > 0) {
                if (granted && grantedDevice != null) {
                    scope.launch { startInternal(grantedDevice, generation) }
                } else {
                    _sourceState.value = CameraSourceState.Error("eYs3D USB 权限被拒绝")
                }
            }
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

    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice, generation: Long) {
        ensureUsbReceiver()
        val piFlags = usbPermissionPendingIntentFlags(Build.VERSION.SDK_INT)
        val pi = PendingIntent.getBroadcast(
            appContext,
            usbPermissionRequestCode(device.deviceName, generation),
            Intent(USB_PERMISSION_ACTION)
                .setPackage(appContext.packageName)
                .putExtra(EXTRA_USB_GENERATION, generation),
            piFlags,
        )
        Log.i(TAG, "requestPermission ${usbLabel(device)} generation=$generation")
        _sourceState.value = CameraSourceState.WaitingPermission
        usbManager.requestPermission(device, pi)
    }

    private fun usbLabel(d: UsbDevice): String =
        "${d.deviceName} vid=0x${d.vendorId.toString(16)} pid=0x${d.productId.toString(16)}"

    private companion object {
        const val TAG = "Eys3dCameraService"
        const val USB_PERMISSION_ACTION = "io.gomob.nativebridge.camera.EYS3D_USB_PERMISSION"
        const val EXTRA_USB_GENERATION = "usb_generation"
        const val CAMERA_RELEASE_GRACE_MS = 600L
        const val FRAME_TIMEOUT_MS = 30_000L  // 任一路无帧判死阈;mode25 排障期放宽,稳定后回 8s
        // 实机正常链：setVideoMode→彩色起流→固定暖机→深度首帧约 5.3s；给慢 USB 调度留近一倍余量。
        const val EYS3D_STARTUP_TIMEOUT_MS = 10_000L
        const val EYS3D_MAX_RESTARTS = 3  // 看门狗判死后最多连续自动重连次数（对齐 BerxelService）
        const val EYS3D_RESTART_DELAY_MS = 1_200L  // 每次自动重连前延时，给 USB/native 释放留窗口
        const val POLL_INTERVAL_MS = 33L  // ~30Hz poll（mode25 出 ~5fps，poll 快于产帧即可不漏）
        const val NEGATIVE_POLL_LOG_INTERVAL_MS = 1_000L
        // mode25 深度分辨率（videoMode=36，CameraModeKt.DEFAULT_ROSIE4_U2_MODE）。
        const val DEPTH_W = 640
        const val DEPTH_H = 128
        const val COLOR_W = 1280  // eYs3D mode25 彩色(L' 矫正参考)分辨率
        const val COLOR_H = 256
    }
}

internal enum class Eys3dSessionHealth {
    HealthyOrStarting,
    NativeTerminal,
    StartupTimedOut,
}

/**
 * eYs3D native 会话异步启动：句柄返回后约 5 秒才有 mode25 深度首帧。
 * `pollDepthMm < 0` 本身不是终态；只有 stats 明确 Error/Stopped，或首帧 deadline 超时，才能 teardown。
 */
internal fun evaluateEys3dSessionHealth(
    nativeState: NativeCameraSessionState,
    firstDepthFrameArrived: Boolean,
    startupElapsedMs: Long,
    startupTimeoutMs: Long,
): Eys3dSessionHealth {
    require(startupTimeoutMs > 0L) { "启动超时必须为正数" }
    if (nativeState.isTerminal) return Eys3dSessionHealth.NativeTerminal
    if (!firstDepthFrameArrived && startupElapsedMs >= startupTimeoutMs) {
        return Eys3dSessionHealth.StartupTimedOut
    }
    return Eys3dSessionHealth.HealthyOrStarting
}

// RS-D550 出厂矫正内参：rectlog_1.bin 与 VIN 工厂文件交叉验证一致。基准是 1280×960 全幅矫正左目，
// mode25 取 rows[352:608] 得 1280×256，再按 0.5 统一缩放成 640×128 深度。因此 fx/fy 必须同比缩放；
// 旧实现把 h/960 当 sy，令深度 fy=163.9（正确约 614.6），是多角度 VIN 竖向压缩的直接根因。
internal const val RSD550_RECT_W = 1280.0
internal const val RSD550_RECT_FX = 1229.205
internal const val RSD550_RECT_CX = 648.0
internal const val RSD550_RECT_CY = 482.865
internal const val RSD550_MODE25_CROP_TOP = 352.0
internal const val RSD550_MODE25_CROP_H = 256.0

internal fun rsd550Mode25Intrinsics(w: Int, h: Int): CameraIntrinsics {
    require(w > 0 && h > 0) { "mode25 分辨率必须为正数" }
    val scale = w / RSD550_RECT_W
    val expectedHeight = RSD550_MODE25_CROP_H * scale
    require(kotlin.math.abs(h - expectedHeight) <= 0.5) {
        "非 mode25 5:1 裁切档：${w}×${h}，期望高度 $expectedHeight"
    }
    return CameraIntrinsics(
        fx = RSD550_RECT_FX * scale,
        fy = RSD550_RECT_FX * scale,
        cx = RSD550_RECT_CX * scale,
        cy = (RSD550_RECT_CY - RSD550_MODE25_CROP_TOP) * scale,
        distortion = DoubleArray(5),
        width = w,
        height = h,
    )
}
