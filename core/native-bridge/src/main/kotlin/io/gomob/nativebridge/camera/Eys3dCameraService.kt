package io.gomob.nativebridge.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.cancel
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
 * eYs3D / Etron RS-D550(ROSIE4, 0x3438:0x0206)取流服务（M6.8b ⑤）。
 *
 * 与 [io.gomob.nativebridge.berxel.BerxelService] 平行：同样的引用计数生命周期 + USB 权限链，但
 * - 单节点：只需 1 个 usbfs fd（Berxel 是 master+companion 双节点）。
 * - 走 [CameraStack]（native CameraRegistry → Eys3dFdDriver，libusb wrap_sys_device + mode25 videoMode=36），
 *   深度由设备 ASIC 直出 metric mm，poll 出来即真深度，不在端侧软算。
 * - 零厂商 SDK：fd 取自 [UsbManager.openDevice]，native 用 libusb 自研栈，全程不链 eSPDI。
 *
 * fd 所有权：native `libusb_wrap_sys_device` **不** dup fd，整段会话期间必须保持 [connection] 不关；
 * [stop] 里先 [CameraStack.stop] 再 close connection，是 fd 唯一释放点。
 *
 * ★ 现为独立服务，不与 BerxelService 共享任何状态；feature 路由按 [CameraModel] 二选一注入。
 */
@Singleton
class Eys3dCameraService @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : CameraSource {

    override val deviceLabel: String get() = CameraModel.Eys3d.deviceTypeLabel

    private val _sourceState = MutableStateFlow<CameraSourceState>(CameraSourceState.Idle)
    override val sourceState: StateFlow<CameraSourceState> = _sourceState.asStateFlow()

    private val _colorFrames = MutableSharedFlow<ColorFrame>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val colorFrames: SharedFlow<ColorFrame> = _colorFrames.asSharedFlow()

    private val _depthFrames = MutableSharedFlow<DepthFrame>(
        replay = 0, extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val depthFrames: SharedFlow<DepthFrame> = _depthFrames.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val stack = CameraStack()
    /** open 出来的连接，必须保持开启直到 stop（native 不持有 fd 所有权）。 */
    @Volatile private var connection: UsbDeviceConnection? = null
    @Volatile private var running = false
    private var depthJob: Job? = null
    private var colorJob: Job? = null

    // ─── 引用计数生命周期（与 BerxelService 同语义：导航切换时计数不归 0，相机不抖） ───
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

    // ─── 启动：枚举 → 权限 → fd → CameraStack.start → poll 循环 ───
    private fun startInternal() {
        if (running) return
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = CameraDetection.primaryNode(usbManager, CameraModel.Eys3d)
        if (device == null) {
            _sourceState.value = CameraSourceState.NoDevice
            return
        }
        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(usbManager, device)
            return  // 等 receiver granted 回调重进 startInternal
        }
        _sourceState.value = CameraSourceState.Opening
        val conn = try {
            usbManager.openDevice(device)
        } catch (t: Throwable) {
            Log.w(TAG, "openDevice 失败 ${usbLabel(device)}", t)
            null
        }
        if (conn == null) {
            _sourceState.value = CameraSourceState.Error("打开 eYs3D 失败（USB 权限 / 设备被占用？）")
            return
        }
        val fd = conn.fileDescriptor
        if (fd < 0) {
            conn.close()
            _sourceState.value = CameraSourceState.Error("eYs3D fd 无效（$fd）")
            return
        }
        // native 单节点：fds=[fd]；configJson 暂空（mode25 默认配置由 driver 内置）。
        if (!stack.start(CameraModel.Eys3d, intArrayOf(fd))) {
            conn.close()
            _sourceState.value = CameraSourceState.Error("CameraStack.start 失败（mode25 协商未通过？）")
            return
        }
        connection = conn
        running = true
        _sourceState.value = CameraSourceState.Streaming(deviceLabel, DEPTH_W, DEPTH_H)
        Log.i(TAG, "eYs3D 开流 fd=$fd → mode25")
        startDepthPump()
        startColorPump()
    }

    private fun startDepthPump() {
        depthJob = scope.launch {
            // buffer 按最大可能深度分辨率（USB3 rectify 640×480 / mode25 640×128）分配；
            // poll 实际写 active*2 字节，DepthFrame 用 outInfo 回报的真实 w/h。
            val cap = MAX_DEPTH_W * MAX_DEPTH_H * 2
            var buffer = ByteBuffer.allocateDirect(cap).order(ByteOrder.LITTLE_ENDIAN)
            val outInfo = LongArray(4)  // [w, h, serial, host_ns]
            var lastSerial = -1L
            var frameIdx = 0
            var deadMs = SystemClock.elapsedRealtime()
            while (isActive && running) {
                buffer.clear()
                val n = stack.pollDepthMm(buffer, outInfo)
                if (n <= 0) {
                    if (SystemClock.elapsedRealtime() - deadMs > FRAME_TIMEOUT_MS) {
                        markSessionDead()
                        break
                    }
                    delay(8)
                    continue
                }
                val serial = outInfo[2]
                if (serial == lastSerial) { delay(4); continue }
                lastSerial = serial
                deadMs = SystemClock.elapsedRealtime()
                val w = outInfo[0].toInt().let { if (it > 0) it else DEPTH_W }
                val h = outInfo[1].toInt().let { if (it > 0) it else DEPTH_H }
                buffer.position(0).limit(n)
                val emitted = buffer
                buffer = ByteBuffer.allocateDirect(cap).order(ByteOrder.LITTLE_ENDIAN)
                _depthFrames.emit(
                    DepthFrame(
                        timestampUs = outInfo[3] / 1000L,
                        frameIndex = ++frameIdx,
                        width = w,
                        height = h,
                        data = emitted,
                        // 内参：eYs3D 出厂内参 / ZD 表需 device-gated flash pull，未注入前置零（不造假 fx）。
                        intrinsics = zeroIntrinsics(w, h),
                        registeredToColor = false,
                    ),
                )
            }
            Log.i(TAG, "eYs3D depth pump exit frames=$frameIdx")
        }
    }

    private fun startColorPump() {
        colorJob = scope.launch {
            var frameIdx = 0
            var pixelsScratch: IntArray? = null
            while (isActive && running) {
                // mode25 color = 1280×256 MJPG；pollColor 出 MJPEG 原始字节，端侧 BitmapFactory 解码。
                val mjpeg = stack.pollColor()
                if (mjpeg == null) { delay(8); continue }
                val bmp = runCatching { BitmapFactory.decodeByteArray(mjpeg, 0, mjpeg.size) }.getOrNull()
                if (bmp == null) { delay(8); continue }
                val cw = bmp.width
                val ch = bmp.height
                val pixels = pixelsScratch?.takeIf { it.size >= cw * ch }
                    ?: IntArray(cw * ch).also { pixelsScratch = it }
                bmp.getPixels(pixels, 0, cw, 0, 0, cw, ch)
                bmp.recycle()
                val direct = ByteBuffer.allocateDirect(cw * ch * 3).order(ByteOrder.LITTLE_ENDIAN)
                var i = 0
                val total = cw * ch
                while (i < total) {
                    val argb = pixels[i]
                    direct.put(((argb ushr 16) and 0xff).toByte())  // R
                    direct.put(((argb ushr 8) and 0xff).toByte())   // G
                    direct.put((argb and 0xff).toByte())            // B
                    i++
                }
                direct.position(0).limit(cw * ch * 3)
                _colorFrames.emit(
                    ColorFrame(
                        timestampUs = SystemClock.elapsedRealtimeNanos() / 1000L,
                        frameIndex = ++frameIdx,
                        width = cw,
                        height = ch,
                        data = direct,
                        pixelType = "EYS3D_RGB24",
                        intrinsics = zeroIntrinsics(cw, ch),
                    ),
                )
            }
            Log.i(TAG, "eYs3D color pump exit frames=$frameIdx")
        }
    }

    private fun markSessionDead() {
        Log.e(TAG, "eYs3D ${FRAME_TIMEOUT_MS}ms 无深度帧，判定 session 死，复位")
        running = false
        stopStackAndConn()
        _sourceState.value = CameraSourceState.Error("eYs3D 流掉线（USB IO）— 重新插拔相机或重进本页")
    }

    /** 停止 + 释放（fd 唯一释放点）。 */
    fun stop() {
        if (!running && connection == null) {
            _sourceState.value = CameraSourceState.Idle
            return
        }
        Log.i(TAG, "eYs3D stop")
        running = false
        depthJob?.cancel(); depthJob = null
        colorJob?.cancel(); colorJob = null
        stopStackAndConn()
        _sourceState.value = CameraSourceState.Idle
    }

    private fun stopStackAndConn() {
        runCatching { stack.stop() }.onFailure { Log.w(TAG, "CameraStack.stop 异常", it) }
        // native 已 close fd 之后才能关 connection（顺序不可换：native 仍可能在用 fd）。
        runCatching { connection?.close() }.onFailure { Log.w(TAG, "connection.close 异常", it) }
        connection = null
    }

    // ─── USB 权限链（单节点 0x3438:0x0206） ───
    @Volatile private var usbReceiverRegistered = false

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != USB_PERMISSION_ACTION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "eYs3D USB permission broadcast granted=$granted")
            // 不盲信 broadcast（OEM 实测会 deny=false 但 system_server 已落 grant）：
            // 只要还有人 acquire，就以 hasPermission 真值重进 startInternal。
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

    private fun zeroIntrinsics(w: Int, h: Int) = CameraIntrinsics(
        fx = 0.0, fy = 0.0, cx = 0.0, cy = 0.0, distortion = DoubleArray(5), width = w, height = h,
    )

    private companion object {
        const val TAG = "Eys3dCameraService"
        const val USB_PERMISSION_ACTION = "io.gomob.nativebridge.camera.EYS3D_USB_PERMISSION"
        const val CAMERA_RELEASE_GRACE_MS = 600L
        const val FRAME_TIMEOUT_MS = 3_000L
        // mode25 深度默认分辨率（videoMode=36）。
        const val DEPTH_W = 640
        const val DEPTH_H = 128
        // buffer 上限按 USB3 rectify(640×480)留余量，分辨率切换不溢出。
        const val MAX_DEPTH_W = 640
        const val MAX_DEPTH_H = 480
    }
}
