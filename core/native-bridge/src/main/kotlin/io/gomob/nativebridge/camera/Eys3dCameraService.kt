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
import java.util.concurrent.atomic.AtomicInteger
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

    // ─── native 直驱厂商 C++ 引擎路径（dlopen libUVCCamera.so，零 Java 编排） ───
    private val cameraStack = CameraStack()
    @Volatile private var usbConn: UsbDeviceConnection? = null
    private var pollJob: Job? = null

    // ─── 引用计数生命周期（导航切换时计数不归 0，相机不抖） ───
    private val acquireCount = AtomicInteger(0)
    @Volatile private var releaseStopJob: Job? = null

    override fun acquire() {
        releaseStopJob?.cancel()
        releaseStopJob = null
        val n = acquireCount.incrementAndGet()
        Log.i(TAG, "acquire → count=$n")
        if (n == 1) scope.launch { startInternal() }
    }

    override fun release() {
        val n = acquireCount.decrementAndGet()
        Log.i(TAG, "release → count=$n")
        if (n > 0) return
        if (n < 0) acquireCount.set(0)
        releaseStopJob?.cancel()
        releaseStopJob = scope.launch {
            delay(CAMERA_RELEASE_GRACE_MS)
            if (acquireCount.get() == 0) {
                Log.i(TAG, "release 宽限期满且无消费者 → stop()")
                stop()
            }
        }
    }

    // ─── 启动：枚举 → 权限 → native 直驱厂商 C++ 会话 → poll + 看门狗 ───
    private fun startInternal() {
        if (running) return
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = CameraDetection.primaryNode(usbManager, CameraModel.Eys3d)
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
            requestUsbPermission(usbManager, device)
            return  // 等 receiver granted 回调重进 startInternal
        }
        _sourceState.value = CameraSourceState.Opening
        if (!startIndependentNative(usbManager, device)) return
        running = true
        lastFrameMs = SystemClock.elapsedRealtime()
        depthFrameIdx = 0
        colorFrameIdx = 0
        _sourceState.value = CameraSourceState.Streaming(deviceLabel, DEPTH_W, DEPTH_H)
        Log.i(TAG, "eYs3D 开流完成（native 直驱厂商 C++，mode25 真深度）")
        startPollLoop()
        startWatchdog()
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

    // poll 循环（IO 线程）：每帧新建 direct buffer 喂 onDepthFrame（避免复用 buffer 与下一次 poll 竞争）。
    private fun startPollLoop() {
        pollJob?.cancel()
        pollJob = scope.launch {
            val outInfo = LongArray(4)
            var polls = 0
            var got = 0
            while (isActive && running) {
                val buf = ByteBuffer.allocateDirect(DEPTH_W * DEPTH_H * 2).order(ByteOrder.LITTLE_ENDIAN)
                val n = cameraStack.pollDepthMm(buf, outInfo)
                polls++
                if (n > 0) {
                    got++
                    val w = outInfo[0].toInt().let { if (it > 0) it else DEPTH_W }
                    val h = outInfo[1].toInt().let { if (it > 0) it else DEPTH_H }
                    buf.position(0).limit(w * h * 2)
                    onDepthFrame(w, h, buf)
                    if (got == 1) Log.i(TAG, "eYs3D 首帧深度到达 ${w}x$h")
                }
                if (polls % 300 == 0) Log.d(TAG, "pollDepthMm polls=$polls got=$got")
                // 彩色：eYs3D 自身 L' 流（vendor uvc_any2rgb 出 RGB24 1280×256），poll 出来喂 onColorFrame。
                val cbytes = cameraStack.pollColor()
                if (cbytes != null && cbytes.size == COLOR_W * COLOR_H * 3) {
                    val cbuf = ByteBuffer.allocateDirect(cbytes.size).order(ByteOrder.LITTLE_ENDIAN)
                    cbuf.put(cbytes); cbuf.position(0).limit(cbytes.size)
                    onColorFrame(COLOR_W, COLOR_H, cbuf)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    // 深度回调（vendor native 线程）：已转 metric mm（u16 LE）。
    private fun onDepthFrame(w: Int, h: Int, mm: ByteBuffer) {
        if (!running) return
        lastFrameMs = SystemClock.elapsedRealtime()
        _depthFrames.tryEmit(
            DepthFrame(
                timestampUs = SystemClock.elapsedRealtimeNanos() / 1000L,
                frameIndex = ++depthFrameIdx,
                width = w,
                height = h,
                data = mm,
                // 内参：RS-D550 出厂矫正内参按本帧分辨率缩放（见 rsd550Intrinsics）。深度在矫正左目坐标系。
                intrinsics = rsd550Intrinsics(w, h),
                registeredToColor = false,
            ),
        )
    }

    // 彩色回调（vendor native 线程）：RGB24 下采样预览帧 → 发流 + 刷看门狗。
    private fun onColorFrame(w: Int, h: Int, rgb24: ByteBuffer) {
        if (!running) return
        lastFrameMs = SystemClock.elapsedRealtime()
        _colorFrames.tryEmit(
            ColorFrame(
                timestampUs = SystemClock.elapsedRealtimeNanos() / 1000L,
                frameIndex = ++colorFrameIdx,
                width = w,
                height = h,
                data = rgb24,
                pixelType = "EYS3D_RGB24",
                // 彩色 L' 是矫正左目（与深度同标定），内参按本帧分辨率缩放。
                intrinsics = rsd550Intrinsics(w, h),
            ),
        )
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && running) {
                delay(1_000)
                if (running && SystemClock.elapsedRealtime() - lastFrameMs > FRAME_TIMEOUT_MS) {
                    markSessionDead()
                    break
                }
            }
        }
    }

    private fun markSessionDead() {
        Log.e(TAG, "eYs3D ${FRAME_TIMEOUT_MS}ms 无帧，判定 session 死")
        running = false
        tearDownNative()
        _sourceState.value = CameraSourceState.Error("eYs3D 流掉线（USB IO）— 重新插拔相机或重进本页")
    }

    // 自研 native 路径释放：停 poll → cameraStack.stop（释放 native 会话）→ 关 usbfs 连接（fd 唯一释放点）。
    private fun tearDownNative() {
        pollJob?.cancel(); pollJob = null
        runCatching { cameraStack.stop() }.onFailure { Log.w(TAG, "cameraStack.stop 异常", it) }
        runCatching { usbConn?.close() }.onFailure { Log.w(TAG, "usbConn.close 异常", it) }
        usbConn = null
    }

    /** 停止 + 释放（fd 唯一释放点）。 */
    fun stop() {
        if (!running && !cameraStack.isOpen) {
            _sourceState.value = CameraSourceState.Idle
            return
        }
        Log.i(TAG, "eYs3D stop")
        running = false
        watchdogJob?.cancel(); watchdogJob = null
        tearDownNative()
        _sourceState.value = CameraSourceState.Idle
    }

    // ─── USB 权限链（单节点 0x3438:0x0206） ───
    @Volatile private var usbReceiverRegistered = false

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != USB_PERMISSION_ACTION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "eYs3D USB permission broadcast granted=$granted")
            // 不盲信 broadcast：只要还有人 acquire，就以 hasPermission 真值重进 startInternal。
            if (acquireCount.get() > 0) scope.launch { startInternal() }
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

    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice) {
        ensureUsbReceiver()
        val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_IMMUTABLE else 0
        val pi = PendingIntent.getBroadcast(
            appContext,
            device.deviceName.hashCode(),
            Intent(USB_PERMISSION_ACTION).setPackage(appContext.packageName),
            piFlags,
        )
        Log.i(TAG, "requestPermission ${usbLabel(device)}")
        _sourceState.value = CameraSourceState.WaitingPermission
        usbManager.requestPermission(device, pi)
    }

    private fun usbLabel(d: UsbDevice): String =
        "${d.deviceName} vid=0x${d.vendorId.toString(16)} pid=0x${d.productId.toString(16)}"

    // RS-D550 出厂矫正内参（rectlog_1.bin 提取，canonical = native/eys3d/portable/eys3d_driver.h
    // kRsd550RectifiedFx；rectlog 与 VIN 两来源交叉验证一致）。基准 = 单目全幅矫正左目 1280×960
    // （cx≈648/cy≈483 即该幅中心，fx=fy=1229.205 方形像素）。mode25 各流（color 1280×256 / depth 640×128）
    // 是该全幅的【各向异性下采样】，内参按 目标分辨率/基准 线性缩放（fx·sx, fy·sy, cx·sx, cy·sy）。
    // ★ device-gated 残留：垂直方向是纯缩放（本式假设；SCALE_DOWN 模式名 + 预览非裁切 佐证）还是裁剪带，影响 fy，
    //   须用平面靶 harness 量测核实（见 docs/architecture/13-eys3d-driver.md §内参 + TODO M6.5）。
    //   终态：adb pull 每台设备 ZD/rectify flash + native 单一标定真理源经 JNI 下发，届时删此 Kotlin 兜底常量。
    private fun rsd550Intrinsics(w: Int, h: Int): CameraIntrinsics {
        val sx = w / RSD550_CALIB_W
        val sy = h / RSD550_CALIB_H
        return CameraIntrinsics(
            fx = RSD550_RECT_FX * sx, fy = RSD550_RECT_FX * sy,
            cx = RSD550_RECT_CX * sx, cy = RSD550_RECT_CY * sy,
            distortion = DoubleArray(5),  // 矫正后无畸变
            width = w, height = h,
        )
    }

    private companion object {
        const val TAG = "Eys3dCameraService"
        const val USB_PERMISSION_ACTION = "io.gomob.nativebridge.camera.EYS3D_USB_PERMISSION"
        const val CAMERA_RELEASE_GRACE_MS = 600L
        const val FRAME_TIMEOUT_MS = 30_000L  // 任一路无帧判死阈;mode25 排障期放宽,稳定后回 8s
        const val POLL_INTERVAL_MS = 33L  // ~30Hz poll（mode25 出 ~5fps，poll 快于产帧即可不漏）
        // mode25 深度分辨率（videoMode=36，CameraModeKt.DEFAULT_ROSIE4_U2_MODE）。
        const val DEPTH_W = 640
        const val DEPTH_H = 128
        const val COLOR_W = 1280  // eYs3D mode25 彩色(L' 矫正参考)分辨率
        const val COLOR_H = 256
        // RS-D550 矫正标定（见 rsd550Intrinsics 注释）。基准全幅 1280×960。
        const val RSD550_CALIB_W = 1280.0
        const val RSD550_CALIB_H = 960.0
        const val RSD550_RECT_FX = 1229.205  // 矫正焦距 px（fx=fy，方形像素）
        const val RSD550_RECT_CX = 648.0
        const val RSD550_RECT_CY = 482.865
    }
}
