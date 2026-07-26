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
import io.gomob.nativebridge.NativeBridge
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
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HLSD8 RGB 相机取流服务（Image+ / Sonix 0x0C45:0x6366）。
 *
 * ★ HLSD8 是与深度模组 RS-D550 **物理独立的第二颗 USB 相机**（13MP 真彩，约 4160 宽 MJPEG），
 *   gomob 之前从未接入；本服务补齐 RGB 这一路，作正射图（RGB×深度几何校正）的高分辨率来源。
 *
 * 与 [Eys3dCameraService] 平行（同引用计数生命周期 + USB 权限链 + fd 所有权约定），但：
 * - **color-only**：[depthFrames] 永不出帧（HLSD8 无深度）。
 * - **无 XU arming**：标准 Sonix UVC，native [Hlsd8UvcSession] 用 libuvc 标准 PROBE/COMMIT 协商最大 MJPEG。
 * - 原始流固定使用原厂全分辨率 MJPEG；本层不再逐帧转 RGB，消费者只在需要显示时降采样解码，
 *   正射拍照直接复用同一 native 回调的原始 MJPEG。
 *
 * fd 所有权：native `uvc_get_device_with_fd` 不 dup fd，整段会话必须保持 [connection] 不关；
 * [stop] 里先 [CameraStack.stop] 再 close，是 fd 唯一释放点。
 */
@Singleton
class Hlsd8CameraService @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : CameraSource {

    override val deviceLabel: String get() = CameraModel.Hlsd8.deviceTypeLabel

    @Volatile private var activeDeviceSerial: String? = null
    override val deviceSerial: String? get() = activeDeviceSerial
    @Volatile private var activeDeviceName: String? = null
    @Volatile private var authorizedDevice: UsbDevice? = null

    private val _sourceState = MutableStateFlow<CameraSourceState>(CameraSourceState.Idle)
    override val sourceState: StateFlow<CameraSourceState> = _sourceState.asStateFlow()

    @Volatile private var lastFrameMs = 0L

    private val _colorFrames = MutableSharedFlow<ColorFrame>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val colorFrames: SharedFlow<ColorFrame> = _colorFrames.asSharedFlow()

    // HLSD8 无深度：depthFrames 永不出帧，仅为满足 CameraSource 契约。
    private val _depthFrames = MutableSharedFlow<DepthFrame>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val depthFrames: SharedFlow<DepthFrame> = _depthFrames.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val stack = CameraStack()
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var running = false
    private var colorJob: Job? = null

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
        if (CameraModel.fromUsbIds(device.vendorId, device.productId) !is CameraModel.Hlsd8) return
        val generation = usbGeneration.incrementAndGet()
        authorizedDevice = device
        Log.i(TAG, "attachAuthorizedDevice ${usbLabel(device)} generation=$generation consumers=${leaseState.consumerCount}")
        if (leaseState.consumerCount > 0) {
            scope.launch {
                if (generation != usbGeneration.get()) return@launch
                if (running || connection != null || stack.isOpen) stop()
                startInternal(device, generation)
            }
        }
    }

    fun detachDevice(device: UsbDevice) {
        if (CameraModel.fromUsbIds(device.vendorId, device.productId) !is CameraModel.Hlsd8) return
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
        colorJob?.cancel(); colorJob = null
        stopStackAndConn()
        _sourceState.value = if (leaseState.consumerCount > 0) CameraSourceState.NoDevice else CameraSourceState.Idle
        Log.i(TAG, "HLSD8 detached $deviceName generation=$generation")
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
            synchronized(this@Hlsd8CameraService) {
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
            model = CameraModel.Hlsd8,
            preferredDevice = preferredDevice,
            authorizedDevice = authorizedDevice,
        )
        if (device == null) {
            activeDeviceSerial = null
            _sourceState.value = CameraSourceState.NoDevice
            return
        }
        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(usbManager, device, expectedUsbGeneration)
            return
        }
        activeDeviceSerial = runCatching { device.serialNumber?.trim() }
            .onFailure { Log.w(TAG, "读取 HLSD8 序列号失败", it) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
        activeDeviceName = device.deviceName
        Log.i(TAG, "HLSD8 物理序列号=${activeDeviceSerial ?: "<unavailable>"}")
        _sourceState.value = CameraSourceState.Opening
        val conn = try {
            usbManager.openDevice(device)
        } catch (t: Throwable) {
            Log.w(TAG, "openDevice 失败 ${usbLabel(device)}", t)
            null
        }
        if (conn == null) {
            activeDeviceSerial = null
            activeDeviceName = null
            _sourceState.value = CameraSourceState.Error("打开 HLSD8 失败（USB 权限 / 设备被占用？）")
            return
        }
        val fd = conn.fileDescriptor
        if (fd < 0) {
            conn.close()
            activeDeviceSerial = null
            activeDeviceName = null
            _sourceState.value = CameraSourceState.Error("HLSD8 fd 无效（$fd）")
            return
        }
        // 标准 UVC：内核 uvcvideo 绑定其 VideoStreaming 接口并独占流端点 → force-claim 抢过来再交 libuvc。
        claimAllInterfaces(conn, device)
        // 无 XU arming：native Hlsd8UvcSession 用 libuvc 标准协商 + 自动选最大 MJPEG。
        if (!stack.start(CameraModel.Hlsd8, intArrayOf(fd))) {
            conn.close()
            activeDeviceSerial = null
            activeDeviceName = null
            _sourceState.value = CameraSourceState.Error("HLSD8 CameraStack.start 失败（MJPEG 协商未通过？）")
            return
        }
        connection = conn
        running = true
        val generation = sessionGeneration.incrementAndGet()
        Log.i(TAG, "HLSD8 会话已启动 fd=$fd，等待彩色首帧")
        startColorPump(generation)
    }

    private fun claimAllInterfaces(conn: UsbDeviceConnection, device: UsbDevice) {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            val claimed = runCatching { conn.claimInterface(intf, /*force=*/true) }.getOrDefault(false)
            Log.i(TAG, "claimInterface #${intf.id} cls=${intf.interfaceClass} force=true → $claimed")
        }
    }

    private fun startColorPump(generation: Long) {
        colorJob = scope.launch {
            var frameIdx = 0
            var emptyPolls = 0
            val colorInfo = LongArray(4)
            lastFrameMs = SystemClock.elapsedRealtime()
            while (isActive && running && generation == sessionGeneration.get()) {
                val mjpeg = stack.pollColor(colorInfo)
                if (generation != sessionGeneration.get()) break
                if (mjpeg == null) {
                    emptyPolls++
                    if (emptyPolls % NATIVE_STATE_CHECK_INTERVAL_POLLS == 0 &&
                        stack.stats().getOrNull(4) == NATIVE_SESSION_ERROR
                    ) {
                        markSessionDead(generation, "HLSD8 native 开流失败—请重新插拔相机或重进本页")
                        break
                    }
                    if (SystemClock.elapsedRealtime() - lastFrameMs > FRAME_TIMEOUT_MS) {
                        markSessionDead(generation)
                        break
                    }
                    delay(8)
                    continue
                }
                emptyPolls = 0
                // native libuvc 回调时刻与 eYs3D depth 的 host_ns 同属 steady_clock；拉帧/解码时刻不可代替。
                val captureTimestampUs = colorInfo[3] / 1000L
                if (captureTimestampUs <= 0L) {
                    Log.e(TAG, "HLSD8 color 缺 native host_ns，丢弃无法同步的帧 serial=${colorInfo[2]}")
                    continue
                }
                val encodedWidth = colorInfo[0].toInt()
                val encodedHeight = colorInfo[1].toInt()
                lastFrameMs = SystemClock.elapsedRealtime()
                if (_sourceState.value !is CameraSourceState.Streaming) {
                    _sourceState.value = CameraSourceState.Streaming(deviceLabel, 0, 0)
                    Log.i(TAG, "HLSD8 首帧彩色到达 ${encodedWidth}x$encodedHeight")
                }
                if (generation != sessionGeneration.get()) break
                _colorFrames.emit(
                    ColorFrame(
                        timestampUs = captureTimestampUs,
                        frameIndex = ++frameIdx,
                        width = encodedWidth,
                        height = encodedHeight,
                        data = EMPTY_ENCODED_FRAME_DATA,
                        pixelType = "HLSD8_MJPEG",
                        intrinsics = zeroIntrinsics(encodedWidth, encodedHeight),
                        encodedJpeg = mjpeg,
                        encodedWidth = encodedWidth,
                        encodedHeight = encodedHeight,
                    ),
                )
            }
            Log.i(TAG, "HLSD8 color pump exit frames=$frameIdx")
        }
    }

    @Synchronized
    private fun markSessionDead(
        generation: Long,
        message: String = "HLSD8 流掉线（USB IO）— 重新插拔或重进本页",
    ) {
        if (generation != sessionGeneration.get()) return
        Log.e(TAG, "HLSD8 ${FRAME_TIMEOUT_MS}ms 无帧，判定 session 死，复位")
        sessionGeneration.incrementAndGet()
        running = false
        colorJob?.cancel(); colorJob = null
        stopStackAndConn()
        _sourceState.value = CameraSourceState.Error(message)
    }

    @Synchronized
    fun stop() {
        if (!running && connection == null && !stack.isOpen) {
            activeDeviceSerial = null
            _sourceState.value = CameraSourceState.Idle
            return
        }
        Log.i(TAG, "HLSD8 stop")
        sessionGeneration.incrementAndGet()
        running = false
        colorJob?.cancel(); colorJob = null
        stopStackAndConn()
        _sourceState.value = CameraSourceState.Idle
    }

    private fun stopStackAndConn() {
        runCatching { stack.stop() }.onFailure { Log.w(TAG, "CameraStack.stop 异常", it) }
        runCatching { connection?.close() }.onFailure { Log.w(TAG, "connection.close 异常", it) }
        connection = null
        activeDeviceSerial = null
        activeDeviceName = null
    }

    // ─── USB 权限链（单节点 0x0C45:0x6366） ───
    @Volatile private var usbReceiverRegistered = false

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != USB_PERMISSION_ACTION) return
            val rawGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val generation = intent.getLongExtra(EXTRA_USB_GENERATION, -1L)
            if (generation != usbGeneration.get()) {
                Log.i(TAG, "忽略过期 HLSD8 USB permission generation=$generation current=${usbGeneration.get()}")
                return
            }
            @Suppress("DEPRECATION")
            val broadcastDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = resolveCurrentUsbDevice(
                usbManager = usbManager,
                model = CameraModel.Hlsd8,
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
                "HLSD8 USB permission raw=$rawGranted manager=$managerGranted effective=$granted " +
                    "device=${device?.let(::usbLabel) ?: "<missing>"} generation=$generation",
            )
            if (grantedDevice != null) authorizedDevice = grantedDevice
            if (leaseState.consumerCount > 0) {
                if (granted && grantedDevice != null) {
                    scope.launch { startInternal(grantedDevice, generation) }
                } else {
                    _sourceState.value = CameraSourceState.Error("HLSD8 USB 权限被拒绝")
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

    private fun zeroIntrinsics(w: Int, h: Int) = CameraIntrinsics(
        fx = 0.0, fy = 0.0, cx = 0.0, cy = 0.0, distortion = DoubleArray(5), width = w, height = h,
    )

    private companion object {
        const val TAG = "Hlsd8CameraService"
        const val USB_PERMISSION_ACTION = "io.gomob.nativebridge.camera.HLSD8_USB_PERMISSION"
        const val EXTRA_USB_GENERATION = "usb_generation"
        const val CAMERA_RELEASE_GRACE_MS = 600L
        const val FRAME_TIMEOUT_MS = 30_000L
        const val NATIVE_STATE_CHECK_INTERVAL_POLLS = 25
        const val NATIVE_SESSION_ERROR = 3L
        val EMPTY_ENCODED_FRAME_DATA: ByteBuffer = ByteBuffer.allocateDirect(0).asReadOnlyBuffer()
    }
}

/** BitmapFactory 只支持 2 的幂采样；结果宽必须不大于预览预算。 */
fun hlsd8PreviewSampleSize(srcWidth: Int, maxWidth: Int): Int {
    if (srcWidth <= 0 || maxWidth <= 0) return 1
    var sample = 1
    while (srcWidth / sample > maxWidth) sample *= 2
    return sample
}
