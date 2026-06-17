package io.gomob.nativebridge.camera

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
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
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger
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
 * - 预览解码降采样到 ≤[PREVIEW_MAX_W] 宽省内存/GC（13MP 全幅每帧 ~38MB 瞬时太重）；
 *   正射图所需全幅 RGB 后续走专门 capture（不走连续预览流）。
 *
 * fd 所有权：native `uvc_get_device_with_fd` 不 dup fd，整段会话必须保持 [connection] 不关；
 * [stop] 里先 [CameraStack.stop] 再 close，是 fd 唯一释放点。
 */
@Singleton
class Hlsd8CameraService @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : CameraSource {

    override val deviceLabel: String get() = CameraModel.Hlsd8.deviceTypeLabel

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

    private fun startInternal() {
        if (running) return
        val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = CameraDetection.primaryNode(usbManager, CameraModel.Hlsd8)
        if (device == null) {
            _sourceState.value = CameraSourceState.NoDevice
            return
        }
        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(usbManager, device)
            return
        }
        _sourceState.value = CameraSourceState.Opening
        val conn = try {
            usbManager.openDevice(device)
        } catch (t: Throwable) {
            Log.w(TAG, "openDevice 失败 ${usbLabel(device)}", t)
            null
        }
        if (conn == null) {
            _sourceState.value = CameraSourceState.Error("打开 HLSD8 失败（USB 权限 / 设备被占用？）")
            return
        }
        val fd = conn.fileDescriptor
        if (fd < 0) {
            conn.close()
            _sourceState.value = CameraSourceState.Error("HLSD8 fd 无效（$fd）")
            return
        }
        // 标准 UVC：内核 uvcvideo 绑定其 VideoStreaming 接口并独占流端点 → force-claim 抢过来再交 libuvc。
        claimAllInterfaces(conn, device)
        // 无 XU arming：native Hlsd8UvcSession 用 libuvc 标准协商 + 自动选最大 MJPEG。
        if (!stack.start(CameraModel.Hlsd8, intArrayOf(fd))) {
            conn.close()
            _sourceState.value = CameraSourceState.Error("HLSD8 CameraStack.start 失败（MJPEG 协商未通过？）")
            return
        }
        connection = conn
        running = true
        _sourceState.value = CameraSourceState.Streaming(deviceLabel, 0, 0)
        Log.i(TAG, "HLSD8 开流 fd=$fd")
        startColorPump()
    }

    private fun claimAllInterfaces(conn: UsbDeviceConnection, device: UsbDevice) {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            val claimed = runCatching { conn.claimInterface(intf, /*force=*/true) }.getOrDefault(false)
            Log.i(TAG, "claimInterface #${intf.id} cls=${intf.interfaceClass} force=true → $claimed")
        }
    }

    private fun startColorPump() {
        colorJob = scope.launch {
            var frameIdx = 0
            var pixelsScratch: IntArray? = null
            lastFrameMs = SystemClock.elapsedRealtime()
            while (isActive && running) {
                val mjpeg = stack.pollColor()
                if (mjpeg == null) {
                    if (SystemClock.elapsedRealtime() - lastFrameMs > FRAME_TIMEOUT_MS) {
                        markSessionDead()
                        break
                    }
                    delay(8)
                    continue
                }
                // MJPEG → 降采样解码（预览省内存）→ RGB24 DirectByteBuffer。
                val bmp = decodeDownsampled(mjpeg)
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
                lastFrameMs = SystemClock.elapsedRealtime()
                _colorFrames.emit(
                    ColorFrame(
                        timestampUs = SystemClock.elapsedRealtimeNanos() / 1000L,
                        frameIndex = ++frameIdx,
                        width = cw,
                        height = ch,
                        data = direct,
                        pixelType = "HLSD8_RGB24",
                        intrinsics = zeroIntrinsics(cw, ch),
                    ),
                )
            }
            Log.i(TAG, "HLSD8 color pump exit frames=$frameIdx")
        }
    }

    /** MJPEG → Bitmap，inSampleSize 降采样到 ≤[PREVIEW_MAX_W] 宽（13MP 全幅预览太重）。 */
    private fun decodeDownsampled(mjpeg: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeByteArray(mjpeg, 0, mjpeg.size, bounds) }
        val srcW = bounds.outWidth
        var sample = 1
        if (srcW > PREVIEW_MAX_W) {
            while (srcW / (sample * 2) >= PREVIEW_MAX_W) sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return runCatching { BitmapFactory.decodeByteArray(mjpeg, 0, mjpeg.size, opts) }.getOrNull()
    }

    private fun markSessionDead() {
        Log.e(TAG, "HLSD8 ${FRAME_TIMEOUT_MS}ms 无帧，判定 session 死，复位")
        running = false
        stopStackAndConn()
        _sourceState.value = CameraSourceState.Error("HLSD8 流掉线（USB IO）— 重新插拔或重进本页")
    }

    fun stop() {
        if (!running && connection == null) {
            _sourceState.value = CameraSourceState.Idle
            return
        }
        Log.i(TAG, "HLSD8 stop")
        running = false
        colorJob?.cancel(); colorJob = null
        stopStackAndConn()
        _sourceState.value = CameraSourceState.Idle
    }

    private fun stopStackAndConn() {
        runCatching { stack.stop() }.onFailure { Log.w(TAG, "CameraStack.stop 异常", it) }
        runCatching { connection?.close() }.onFailure { Log.w(TAG, "connection.close 异常", it) }
        connection = null
    }

    // ─── USB 权限链（单节点 0x0C45:0x6366） ───
    @Volatile private var usbReceiverRegistered = false

    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != USB_PERMISSION_ACTION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            Log.i(TAG, "HLSD8 USB permission broadcast granted=$granted")
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
        const val TAG = "Hlsd8CameraService"
        const val USB_PERMISSION_ACTION = "io.gomob.nativebridge.camera.HLSD8_USB_PERMISSION"
        const val CAMERA_RELEASE_GRACE_MS = 600L
        const val FRAME_TIMEOUT_MS = 30_000L
        const val PREVIEW_MAX_W = 1280  // 预览解码目标宽上限（13MP 全幅降采样到此量级）
    }
}
