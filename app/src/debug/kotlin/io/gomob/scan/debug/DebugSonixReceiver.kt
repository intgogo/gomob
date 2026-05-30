package io.gomob.scan.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import io.gomob.nativebridge.NativeBridge
import org.json.JSONObject

/**
 * Sonix ASIC READ 调试广播 —— 不依赖 UI 点击，直接 adb shell am 触发。
 *
 * 用法：
 * ```
 * adb shell am broadcast -a io.gomob.scan.debug.DEBUG_SONIX_ASIC_READ \
 *   --ei iface 0 --ei reg 0x10D8 --ei timeout 1000
 * ```
 * 节点自动用 companion(0x3558:0x1012)，权限走 usbManager.openDevice fd。
 * 结果写 logcat tag `DebugSonixReceiver`。
 */
class DebugSonixReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (intent.action == ACTION_DUAL_STREAM) {
            runDualStream(context, intent, usbManager)
            return
        }
        val targetVid = intent.getIntExtra(EXTRA_VID, COMPANION_VID)
        val targetPid = intent.getIntExtra(EXTRA_PID, COMPANION_PID)
        val device: UsbDevice? = usbManager.deviceList.values.firstOrNull {
            it.vendorId == targetVid && it.productId == targetPid
        }
        if (device == null) {
            Log.e(TAG, "未发现 USB 设备 0x${"%04x".format(targetVid)}:0x${"%04x".format(targetPid)}；" +
                "当前=${usbManager.deviceList.values.joinToString { "0x%04x:0x%04x".format(it.vendorId, it.productId) }}")
            return
        }
        Log.i(TAG, "找到 ${device.deviceName} 0x%04x:0x%04x".format(device.vendorId, device.productId))
        val conn = usbManager.openDevice(device)
        if (conn == null) {
            Log.e(TAG, "openDevice 返 null —— hasPermission=${usbManager.hasPermission(device)}")
            return
        }
        try {
            val fd = conn.fileDescriptor
            when (intent.action) {
                ACTION_ASIC_READ -> runAsicRead(intent, fd)
                ACTION_USB_DESC -> runUsbDescDump(fd)
                ACTION_SESSION_TEST -> runSessionTest(context, intent, fd)
                ACTION_STREAM_TEST -> runStreamTest(context, intent, fd)
                ACTION_BULK_SYNC -> runBulkSync(context, intent, fd)
                else -> Log.w(TAG, "未知 action=${intent.action}")
            }
        } finally {
            runCatching { conn.close() }
        }
    }

    /**
     * 候选 C 验证：master 节点 XU 5 init + 持续 polling 让 firmware 进入 ready，
     * 同时 companion 走 Sonix init + UVC probe/commit + sync bulk read。
     *
     * Linux SDK trace bus7-master.pcap 显示 master XU 5 selector 0x01 wLen=64 'BX' header
     * 跑了整个 trace 周期 246 次；假设 companion firmware 检查 master XU 5 control
     * channel alive 才出 depth。
     *
     * 步骤：
     *   1. open master (0x0603:0x001f) session vc=0 vs=-1（不 claim vs，避免冲突）
     *   2. 回放前 N 条 master XU 5 SET_CUR + GET_CUR readback
     *   3. 启 background 线程持续重发尾部 SET_CUR + GET_CUR（每 50ms 一轮 keepalive）
     *   4. open companion (0x3558:0x1012) session vc=0 vs=1
     *   5. companion: Sonix init seq → UVC probe/commit → 3× sync bulk read
     *   6. 停 keepalive 线程、关两个 session、关两个 fd
     */
    private fun runDualStream(context: Context, intent: Intent, usbManager: UsbManager) {
        val masterVid = intent.getIntExtra("masterVid", MASTER_VID)
        val masterPid = intent.getIntExtra("masterPid", MASTER_PID)
        val companionVid = intent.getIntExtra("compVid", COMPANION_VID)
        val companionPid = intent.getIntExtra("compPid", COMPANION_PID)
        val ep = intent.getIntExtra("ep", 0x82)
        val format = intent.getIntExtra("format", 1)
        val frame = intent.getIntExtra("frame", 2)
        val interval = intent.getIntExtra("interval", 0x3640E)
        val len = intent.getIntExtra("len", 16384)
        val timeout = intent.getIntExtra(EXTRA_TIMEOUT, 3000)
        val masterInitCount = intent.getIntExtra("masterN", 20)
        val keepalive = intent.getBooleanExtra("keepalive", true)
        val keepaliveIntervalMs = intent.getLongExtra("kaMs", 50L)
        val warmupMs = intent.getIntExtra("warmupMs", 500)

        Log.i(TAG, "=== DUAL STREAM TEST ===")
        Log.i(TAG, "master=0x%04x:0x%04x companion=0x%04x:0x%04x"
            .format(masterVid, masterPid, companionVid, companionPid))
        Log.i(TAG, "masterN=$masterInitCount keepalive=$keepalive ka=${keepaliveIntervalMs}ms warmup=${warmupMs}ms")

        val masterDev = usbManager.deviceList.values.firstOrNull {
            it.vendorId == masterVid && it.productId == masterPid
        } ?: run {
            Log.e(TAG, "❌ 找不到 master 0x%04x:0x%04x".format(masterVid, masterPid))
            return
        }
        val companionDev = usbManager.deviceList.values.firstOrNull {
            it.vendorId == companionVid && it.productId == companionPid
        } ?: run {
            Log.e(TAG, "❌ 找不到 companion 0x%04x:0x%04x".format(companionVid, companionPid))
            return
        }
        val masterConn = usbManager.openDevice(masterDev) ?: run {
            Log.e(TAG, "❌ master openDevice null"); return
        }
        val companionConn = usbManager.openDevice(companionDev) ?: run {
            Log.e(TAG, "❌ companion openDevice null"); masterConn.close(); return
        }

        var masterHandle = 0L
        var companionHandle = 0L
        val kaRunning = java.util.concurrent.atomic.AtomicBoolean(false)
        var kaThread: Thread? = null
        try {
            masterHandle = NativeBridge.berxelOpenDeviceByFd(masterConn.fileDescriptor, 0, -1)
            if (masterHandle == 0L) {
                Log.e(TAG, "❌ master openDeviceByFd 失败"); return
            }
            Log.i(TAG, "✅ master session 0x${masterHandle.toString(16)} (vc=0, vs skipped)")

            val masterPayloads = loadMasterXu5Payloads(context, masterInitCount)
            Log.i(TAG, "master XU 5 payloads loaded: ${masterPayloads.size}")
            replayMasterXu5(masterHandle, masterPayloads)

            if (keepalive && masterPayloads.isNotEmpty()) {
                kaRunning.set(true)
                val keepFrame = masterPayloads.last()  // 最后一条作为 keepalive 模板
                kaThread = Thread({
                    var n = 0L
                    while (kaRunning.get()) {
                        try {
                            val rc = NativeBridge.berxelControlTransfer(
                                masterHandle, 0x21, 0x01, 0x0100, MASTER_XU_INDEX,
                                keepFrame, keepFrame.size, 500)
                            val gb = NativeBridge.berxelControlTransfer(
                                masterHandle, 0xa1, 0x81, 0x0100, MASTER_XU_INDEX,
                                null, keepFrame.size, 500)
                            n++
                            if (n <= 3 || n % 20 == 0L) {
                                Log.i(TAG, "  ka#$n set_rc=${rc?.size ?: -1} get_rc=${gb?.size ?: -1}")
                            }
                        } catch (t: Throwable) {
                            Log.w(TAG, "ka exception: ${t.message}")
                            break
                        }
                        try { Thread.sleep(keepaliveIntervalMs) } catch (_: InterruptedException) { break }
                    }
                    Log.i(TAG, "  ka thread exit n=$n")
                }, "berxel-master-ka").apply { isDaemon = true; start() }
            }

            if (warmupMs > 0) {
                Log.i(TAG, "warmup ${warmupMs}ms before companion init...")
                Thread.sleep(warmupMs.toLong())
            }

            companionHandle = NativeBridge.berxelOpenDeviceByFd(companionConn.fileDescriptor, 0, 1)
            if (companionHandle == 0L) {
                Log.e(TAG, "❌ companion openDeviceByFd 失败"); return
            }
            Log.i(TAG, "✅ companion session 0x${companionHandle.toString(16)}")

            replayInitSequence(context, companionHandle)

            val ctrlBlock = uvcStreamCtrl(format, frame, interval)
            val p1 = NativeBridge.berxelControlTransfer(
                companionHandle, 0x21, 0x01, 0x0100, 1, ctrlBlock, 26, 2000)
            val p2 = NativeBridge.berxelControlTransfer(
                companionHandle, 0xa1, 0x81, 0x0100, 1, null, 26, 2000)
            val c1 = NativeBridge.berxelControlTransfer(
                companionHandle, 0x21, 0x01, 0x0200, 1, p2 ?: ctrlBlock, 26, 2000)
            Log.i(TAG, "  companion UVC probe SET ok=${p1!=null} GET ok=${p2!=null} commit ok=${c1!=null}")

            repeat(3) { iter ->
                val rc = NativeBridge.berxelBulkSyncRead(companionHandle, ep, len, timeout)
                Log.i(TAG, "  companion sync_read#$iter ep=0x%02x len=$len timeout=${timeout}ms → $rc"
                    .format(ep))
            }
        } finally {
            kaRunning.set(false)
            kaThread?.interrupt()
            try { kaThread?.join(500) } catch (_: InterruptedException) {}
            if (companionHandle != 0L) NativeBridge.berxelCloseDevice(companionHandle)
            if (masterHandle != 0L) NativeBridge.berxelCloseDevice(masterHandle)
            runCatching { companionConn.close() }
            runCatching { masterConn.close() }
            Log.i(TAG, "=== DUAL STREAM TEST END ===")
        }
    }

    /** 读 assets/berxel/iHawkP100R3_master_xu5_init.json 前 n 条 64 字节 payload。 */
    private fun loadMasterXu5Payloads(context: Context, n: Int): List<ByteArray> {
        val json = context.assets.open("berxel/iHawkP100R3_master_xu5_init.json")
            .bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("init_set_cur")
        val out = mutableListOf<ByteArray>()
        for (i in 0 until minOf(n, arr.length())) {
            out += hexToBytes(arr.getJSONObject(i).getString("data_hex"))
        }
        return out
    }

    /** 按 trace 时序回放 master XU 5 SET_CUR + GET_CUR。 */
    private fun replayMasterXu5(handle: Long, payloads: List<ByteArray>) {
        payloads.forEachIndexed { i, payload ->
            val set = NativeBridge.berxelControlTransfer(
                handle, 0x21, 0x01, 0x0100, MASTER_XU_INDEX, payload, payload.size, 2000)
            val get = NativeBridge.berxelControlTransfer(
                handle, 0xa1, 0x81, 0x0100, MASTER_XU_INDEX, null, payload.size, 2000)
            val ghead = get?.take(8)?.joinToString(" ") { "%02x".format(it) } ?: "<null>"
            Log.i(TAG, "  master#$i set rc=${set?.size ?: -1} get head=$ghead")
            if (set == null) {
                Log.e(TAG, "  master#$i SET_CUR 失败，中断")
                return
            }
        }
        Log.i(TAG, "✅ master XU 5 init done (${payloads.size} entries)")
    }

    private fun runSessionTest(context: Context, intent: Intent, fd: Int) {
        val vc = intent.getIntExtra(EXTRA_VC, 0)
        val vs = intent.getIntExtra(EXTRA_VS, 1)
        val runInit = intent.getBooleanExtra(EXTRA_RUN_INIT, false)
        Log.i(TAG, "session test fd=$fd vc=$vc vs=$vs runInit=$runInit")
        val handle = NativeBridge.berxelOpenDeviceByFd(fd, vc, vs)
        if (handle == 0L) {
            Log.e(TAG, "❌ openDeviceByFd 失败 (看 gomob_native 详细错误码)")
            return
        }
        try {
            Log.i(TAG, "✅ session opened handle=0x${handle.toString(16)}")

            if (runInit) {
                replayInitSequence(context, handle)
            }

            for (reg in intArrayOf(0x10D0, 0x10D8, 0x10D9, 0x0001, 0x0002)) {
                val v = NativeBridge.berxelSessionAsicRead(handle, reg, 1000)
                Log.i(TAG, "  asic_read[0x%04X] = %s".format(
                    reg,
                    if (v >= 0) "0x%02X (%d)".format(v, v) else "rc=$v",
                ))
            }
        } finally {
            NativeBridge.berxelCloseDevice(handle)
            Log.i(TAG, "✅ session closed")
        }
    }

    /** 把 assets/berxel/iHawkP100R3_init_sequence.json 里的 7 条 SET_CUR 字节按 trace 时序回放。 */
    private fun replayInitSequence(context: Context, handle: Long) {
        val json = context.assets.open("berxel/iHawkP100R3_init_sequence.json")
            .bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("init_set_cur")
        Log.i(TAG, "init sequence: ${arr.length()} entries")
        var prevT = 0.0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val selector = e.getInt("selector")
            val data = hexToBytes(e.getString("data_hex"))
            val t = e.getDouble("t_seconds")
            // 还原 trace 阶段间隔（>50ms 才等，保留 500ms gap，避免回放过快 firmware 拒接）
            if (i > 0) {
                val deltaMs = ((t - prevT) * 1000).toLong().coerceAtLeast(0L)
                if (deltaMs >= 50) {
                    Log.i(TAG, "  [delay ${deltaMs}ms 保持 trace 时序]")
                    Thread.sleep(deltaMs)
                }
            }
            prevT = t
            val rc = NativeBridge.berxelSessionBatchCmd(handle, selector, data, 2000)
            // trace 中每个 SET_CUR 后都紧跟一个 GET_CUR 同 selector 同长度 — firmware
            // 用 GET_CUR 作为"事务完成"信号或返回响应。我们必须按相同 selector + 长度读回，
            // 否则 firmware 可能不进入下一步状态（实测 commit 后 BULK 不出数据就是这个）。
            val readback = NativeBridge.berxelSessionXuGetCur(handle, selector, data.size, 2000)
            val readHead = readback?.take(8)?.joinToString(" ") { "%02x".format(it) } ?: "<null>"
            Log.i(TAG, "  init#$i sel=0x%02x len=%d → set rc=%d  get head=$readHead"
                .format(selector, data.size, rc))
            if (rc < 0) {
                Log.e(TAG, "  init#$i failed; abort sequence")
                return
            }
        }
        Log.i(TAG, "✅ init sequence done")
    }

    /**
     * 完整流测试：open device → 回放 init seq → probe/commit + 起 transfer pool →
     * 等 N 秒、抓帧统计 → close。
     *
     * 默认参数对应 companion 640×400@45fps YUYV：formatIndex=1 frameIndex=2 (descriptor 第 2 档)
     * dwFrameInterval=222222 (0x3640E)，BULK ep=0x82。
     */
    private fun runStreamTest(context: Context, intent: Intent, fd: Int) {
        val vc = intent.getIntExtra(EXTRA_VC, 0)
        val vs = intent.getIntExtra(EXTRA_VS, 1)
        val ep = intent.getIntExtra("ep", 0x82)
        val format = intent.getIntExtra("format", 1)
        val frame = intent.getIntExtra("frame", 2)
        val interval = intent.getIntExtra("interval", 0x3640E)
        val xferCount = intent.getIntExtra("xferCount", 16)
        val xferSize = intent.getIntExtra("xferSize", 0)
        val durationMs = intent.getIntExtra("ms", 3000)
        val skipInit = intent.getBooleanExtra("skipInit", false)

        Log.i(TAG, "stream test fd=$fd ep=0x${"%02x".format(ep)} fmt=$format frm=$frame " +
            "dwFI=0x${"%x".format(interval)} pool=${xferCount}x${xferSize} ms=$durationMs skipInit=$skipInit")

        val handle = NativeBridge.berxelOpenDeviceByFd(fd, vc, vs)
        if (handle == 0L) {
            Log.e(TAG, "❌ openDeviceByFd 失败")
            return
        }
        try {
            if (!skipInit) replayInitSequence(context, handle)
            val rc = NativeBridge.berxelOpenStream(handle, ep, format, frame, interval, xferCount, xferSize)
            if (rc != 0) {
                Log.e(TAG, "❌ openStream rc=$rc")
                return
            }
            Log.i(TAG, "✅ stream opened")
            val deadline = System.currentTimeMillis() + durationMs
            var nFrames = 0
            var totalBytes = 0L
            while (System.currentTimeMillis() < deadline) {
                val data = NativeBridge.berxelReadFrame(handle, 200)
                if (data != null) {
                    nFrames++
                    totalBytes += data.size
                    if (nFrames <= 3 || nFrames % 20 == 0) {
                        val head = data.take(16).joinToString(" ") { "%02x".format(it) }
                        Log.i(TAG, "  frame#$nFrames size=${data.size} head=$head")
                    }
                }
            }
            val stats = NativeBridge.berxelStreamStats(handle)
            Log.i(TAG, "✅ stream summary: frames_app=$nFrames bytes_app=$totalBytes  " +
                "native cb=${stats[0]} bytes=${stats[1]} err=${stats[2]} queue=${stats[3]}")
            NativeBridge.berxelCloseStream(handle)
        } finally {
            NativeBridge.berxelCloseDevice(handle)
            Log.i(TAG, "✅ device closed")
        }
    }

    /**
     * 单次 sync bulk read 诊断 —— init seq + probe/commit 直接走原始 control transfer，
     * 不调 openStream (因 closeStream 后再走 sync 会触发 libusb 内部 mutex UAF)。
     * probe/commit 直接通过会话级 batch_cmd selector=0x01/0x02 走（在 vs_interface 上）。
     */
    private fun runBulkSync(context: Context, intent: Intent, fd: Int) {
        val vc = intent.getIntExtra(EXTRA_VC, 0)
        val vs = intent.getIntExtra(EXTRA_VS, 1)
        val ep = intent.getIntExtra("ep", 0x82)
        val format = intent.getIntExtra("format", 1)
        val frame = intent.getIntExtra("frame", 2)
        val interval = intent.getIntExtra("interval", 0x3640E)
        val len = intent.getIntExtra("len", 16384)
        val timeout = intent.getIntExtra(EXTRA_TIMEOUT, 3000)
        val skipInit = intent.getBooleanExtra("skipInit", false)
        val skipProbe = intent.getBooleanExtra("skipProbe", false)

        val handle = NativeBridge.berxelOpenDeviceByFd(fd, vc, vs)
        if (handle == 0L) {
            Log.e(TAG, "❌ openDeviceByFd 失败")
            return
        }
        try {
            if (!skipInit) replayInitSequence(context, handle)
            if (!skipProbe) {
                // 标准 UVC PROBE (0x21 SET_CUR wValue=0x0100 wIndex=vs_interface wLength=26)
                val ctrlBlock = uvcStreamCtrl(format, frame, interval)
                val p1 = NativeBridge.berxelControlTransfer(
                    handle, 0x21, 0x01, 0x0100, vs, ctrlBlock, 26, 2000)
                val p2 = NativeBridge.berxelControlTransfer(
                    handle, 0xa1, 0x81, 0x0100, vs, null, 26, 2000)
                val c1 = NativeBridge.berxelControlTransfer(
                    handle, 0x21, 0x01, 0x0200, vs, p2 ?: ctrlBlock, 26, 2000)
                Log.i(TAG, "  inline UVC probe SET ok=${p1!=null} GET ok=${p2!=null} commit ok=${c1!=null}")
                if (p2 != null) {
                    val h = p2.take(8).joinToString(" ") { "%02x".format(it) }
                    Log.i(TAG, "  probe GET head=$h")
                }
            }
            repeat(3) { iter ->
                val rc = NativeBridge.berxelBulkSyncRead(handle, ep, len, timeout)
                Log.i(TAG, "  sync_read#$iter ep=0x%02x len=$len timeout=${timeout}ms → $rc".format(ep))
            }
        } finally {
            NativeBridge.berxelCloseDevice(handle)
        }
    }

    private fun uvcStreamCtrl(formatIdx: Int, frameIdx: Int, frameInterval100Ns: Int): ByteArray {
        val b = ByteArray(26)
        b[0] = 0x01; b[1] = 0x00  // bmHint
        b[2] = formatIdx.toByte()
        b[3] = frameIdx.toByte()
        b[4] = (frameInterval100Ns and 0xff).toByte()
        b[5] = ((frameInterval100Ns shr 8) and 0xff).toByte()
        b[6] = ((frameInterval100Ns shr 16) and 0xff).toByte()
        b[7] = ((frameInterval100Ns shr 24) and 0xff).toByte()
        return b
    }

    private fun hexToBytes(hex: String): ByteArray {
        val s = hex.lowercase()
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) +
                Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    private fun runAsicRead(intent: Intent, fd: Int) {
        val iface = intent.getIntExtra(EXTRA_IFACE, 0)
        val reg = intent.getIntExtra(EXTRA_REG, 0x10D8)
        val timeout = intent.getIntExtra(EXTRA_TIMEOUT, 1000)
        Log.i(TAG, "fd=$fd iface=$iface reg=0x%04x timeout=${timeout}ms".format(reg))
        val v = NativeBridge.berxelSonixAsicRead(fd, iface, reg, timeout)
        if (v >= 0) {
            Log.i(TAG, "✅ asic_read[0x%04X] = 0x%02X (%d)".format(reg, v, v))
        } else {
            Log.e(TAG, "❌ asic_read[0x%04X] rc=$v".format(reg))
        }
    }

    private fun runUsbDescDump(fd: Int) {
        Log.i(TAG, "fd=$fd → berxelUsbDescriptorDump")
        val dump = NativeBridge.berxelUsbDescriptorDump(fd)
        // logcat 单行长度限制约 4000，分行打
        dump.lineSequence().forEachIndexed { i, line ->
            Log.i(TAG, "DESC[%03d] %s".format(i, line))
        }
    }

    internal companion object {
        const val TAG = "DebugSonixReceiver"
        const val ACTION_ASIC_READ = "io.gomob.scan.debug.DEBUG_SONIX_ASIC_READ"
        const val ACTION_USB_DESC = "io.gomob.scan.debug.DEBUG_USB_DESCRIPTOR_DUMP"
        const val ACTION_SESSION_TEST = "io.gomob.scan.debug.DEBUG_SESSION_TEST"
        const val ACTION_STREAM_TEST = "io.gomob.scan.debug.DEBUG_STREAM_TEST"
        const val ACTION_BULK_SYNC = "io.gomob.scan.debug.DEBUG_BULK_SYNC"
        const val ACTION_DUAL_STREAM = "io.gomob.scan.debug.DEBUG_DUAL_STREAM"
        const val EXTRA_IFACE = "iface"
        const val EXTRA_REG = "reg"
        const val EXTRA_TIMEOUT = "timeout"
        const val EXTRA_VID = "vid"
        const val EXTRA_PID = "pid"
        const val EXTRA_VC = "vc"
        const val EXTRA_VS = "vs"
        const val EXTRA_RUN_INIT = "init"
        const val COMPANION_VID = 0x3558
        const val COMPANION_PID = 0x1012
        const val MASTER_VID = 0x0603
        const val MASTER_PID = 0x001f
        // XU 5 on iface 0：wIndex = (unit<<8) | iface = 0x0500 = 1280
        const val MASTER_XU_INDEX = 0x0500
    }
}
