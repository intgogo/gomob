package io.gomob.nativebridge.berxel

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import io.gomob.nativebridge.NativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 替代 Berxel Android SDK 的 native USB stack（M1.6.6 落地）。
 *
 * 工作原理（候选 C 已验证 2026-05-27 18:20 vivo PD2324）：
 * 1. 打开 master 节点（0x0603:0x001f）：vc=0 仅 claim videocontrol，vs=-1 跳过 vs claim
 * 2. 回放 trace bus7-master.pcap 前 N 条 Berxel XU 5 SET_CUR + GET_CUR（'BX' header 协议）
 * 3. 起 keepalive 线程持续重发 XU 5 SET_CUR（payload bytes 10-13 单调递增 counter）—
 *    companion firmware 检查 master 控制通道活跃才推 depth BULK
 * 4. 打开 companion 节点（0x3558:0x1012）vc=0 + vs=1
 * 5. companion 回放 Sonix init seq 7 条 SET_CUR（selector 0x19/0x1e）
 * 6. 标准 UVC PROBE / GET / COMMIT 26 字节
 * 7. ep=0x82 BULK sync_read 16384 字节读取，loop 拼装成 YUYV depth frame
 *
 * 关闭顺序（避免 vivo OTG 462ms host kill）：
 *    stop keepalive → wait 200ms → close companion session → wait 200ms → close master session
 *
 * 调用方：通过 Hilt 注入；start()/stop() 触发，state 暴露生命周期，pullChunk() 拉取单次 BULK 数据。
 * 高层 frame assembler / 渲染流水线在 feature:scan3d 里组装多 chunk 成完整 YUYV depth frame。
 */
@Singleton
class BerxelNativeStack @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {

    enum class State { Idle, Opening, Streaming, Error, Closing }

    private val _state = MutableStateFlow(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var lastError: String? = null
    fun lastError(): String? = lastError

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var masterConn: UsbDeviceConnection? = null
    private var companionConn: UsbDeviceConnection? = null
    @Volatile private var masterHandle: Long = 0L
    @Volatile private var companionHandle: Long = 0L

    private val kaRunning = AtomicBoolean(false)
    private var kaJob: Job? = null

    private val pullRunning = AtomicBoolean(false)
    private var pullJob: Job? = null

    // master RGB 拉流（M1.6.8 新增，弃 SDK 后取代 SDK COLOR_ONLY 路径）
    private val masterPullRunning = AtomicBoolean(false)
    private var masterPullJob: Job? = null
    private val mjpegAssembler = BerxelMjpegAssembler()
    @Volatile var totalMasterStreamErrors: Long = 0
        private set
    /** 调试 toggle：false = 跳过 master RGB probe/commit/openStream，**且** master 不 claim VS。
     *  vivo+hub 上 master VS=1 claim 会立刻死掉 companion；2510DRK44C (USB2 OTG 友好机) 上理论 OK，
     *  重新打开默认 true 实测。如果这台也死 companion，再退 false。 */
    @Volatile var enableMasterStream: Boolean = false

    private val assembler = BerxelFrameAssembler(shortReadThreshold = BULK_READ_LEN).apply {
        // SHORT_READ：信任 firmware short-packet 边界，避免 BYTE_COUNT 按 513280B 切跟
        // firmware 实际 513292B 错位累积导致的左右平移 / 闪烁现象。
        mode = BerxelFrameAssembler.Mode.SHORT_READ
    }
    fun assemblerStats(): String = "in=${assembler.totalBytesIn}B " +
        "frames=${assembler.totalFramesOut} dropped=${assembler.droppedFrames} " +
        "splits=${assembler.splitWarnings} queue=${assembler.queueDepth()}"

    fun depthBytesIn(): Long = assembler.totalBytesIn

    fun depthFramesOut(): Long = assembler.totalFramesOut

    /**
     * 启动：找设备 + open + init + keepalive + probe/commit。
     *
     * @param usbManager 系统 UsbManager（Activity 上下文取）
     * @param keepaliveIntervalMs master XU 5 keepalive 重发间隔，默认 50ms（vivo 上稳定）
     * @param masterInitCount 深度单流保活时回放 master payload 数量；彩色路径固定回放完整序列
     * @return 是否进入 Streaming 状态
     */
    fun start(
        usbManager: UsbManager,
        keepaliveIntervalMs: Long = 50L,
        masterInitCount: Int = 20,
        extraDevices: Collection<UsbDevice> = emptyList(),
        /** companionOnly=true：跳过 master 开 + XU 5 init/keepalive。
         *  用在 hybrid 模式（SDK 抓 master 同时给 companion 续命），避免 vivo + Dell hub
         *  下 master 不进 UsbManager.deviceList 导致 NativeStack 起不来。 */
        companionOnly: Boolean = false,
    ): Boolean {
        // Error 状态允许 retry（之前一次 start fail 后 stack 留在 Error，不能阻塞下一次）；
        // Opening / Streaming / Closing 才算真 busy 拒绝。
        when (_state.value) {
            State.Opening, State.Streaming, State.Closing -> {
                lastError = "stack busy state=${_state.value}"
                Log.w(TAG, "start ignored — $lastError")
                return false
            }
            State.Error -> Log.i(TAG, "retry from Error state")
            State.Idle -> Unit
        }
        _state.value = State.Opening
        lastError = null

        val all = LinkedHashMap<String, UsbDevice>().apply {
            usbManager.deviceList.values.forEach { put(it.deviceName, it) }
            // USB_DEVICE_ATTACHED intent 里的 UsbDevice 在部分 OEM 上比 deviceList 里的对象权限更准。
            extraDevices.forEach { put(it.deviceName, it) }
        }.values.toList()
        val master = if (companionOnly) null else all.firstOrNull { it.vendorId == MASTER_VID && it.productId == MASTER_PID }
        val companion = all.firstOrNull { it.vendorId == COMPANION_VID && it.productId == COMPANION_PID }
        Log.i(
            TAG,
            "start devices=${all.joinToString { "${it.deviceName} 0x${it.vendorId.toString(16)}:0x${it.productId.toString(16)}" }} " +
                "master=${master?.deviceName} companion=${companion?.deviceName} masterRgb=$enableMasterStream",
        )
        if (!companionOnly && master == null) {
            return fail("master $MASTER_VID:$MASTER_PID 未发现")
        }
        if (companion == null) {
            return fail("companion $COMPANION_VID:$COMPANION_PID 未发现")
        }

        // companionOnly 跳过 master 全部操作
        if (!companionOnly && master != null) {
            // 复用上一轮 conn（vivo close 撤 ACL 修法）；fd<0 = invalid 则重新开。
            // 注意：USB detach/reattach 后 fd 数字仍然 >=0（valid 整数）但底层 device 已死，
            // wrap_sys_device 会返 LIBUSB_ERROR_NOT_FOUND(-2002)。openDeviceByFd 失败时
            // 自动 close cached conn + 用新的 master UsbDevice reopen 一次。
            val masterVsClaim = if (enableMasterStream) MASTER_VS_IFACE else -1
            masterHandle = tryOpenMaster(usbManager, master, masterVsClaim)
            if (masterHandle == 0L) return fail("master openDeviceByFd 失败 vs=$masterVsClaim", dropCachedConns = true)

            val requestedMasterInitCount =
                if (enableMasterStream) MASTER_RGB_INIT_COUNT else masterInitCount
            val masterPayloads = runCatching { loadMasterPayloads(requestedMasterInitCount) }.getOrNull()
                ?: return fail("master xu5 init seq 资源缺失")
            Log.i(
                TAG,
                "master XU5 init payloads=${masterPayloads.size} requested=$requestedMasterInitCount rgb=$enableMasterStream",
            )
            if (!replayMasterInit(masterPayloads)) return fail("master XU 5 init 回放失败", dropCachedConns = true)

            if (enableMasterStream) {
                // SDK 双流路径是先启动 master color，再启动 companion depth；此时 master UVC 流本身
                // 维持主控活跃。继续重放 depth-only trace 里的 XU keepalive 会让 master 只推空 payload。
                if (!negotiateMasterUvcStream()) {
                    Log.w(TAG, "master MJPEG probe/commit 失败 — 改用 XU keepalive 保住 depth")
                    startKeepalive(masterPayloads.last(), keepaliveIntervalMs)
                    Thread.sleep(200)
                } else if (!openMasterStream()) {
                    Log.w(TAG, "master openStream 失败 — 改用 XU keepalive 保住 depth")
                    startKeepalive(masterPayloads.last(), keepaliveIntervalMs)
                    Thread.sleep(200)
                } else {
                    startMasterPullLoop()
                    Thread.sleep(120)
                }
            } else {
                startKeepalive(masterPayloads.last(), keepaliveIntervalMs)
                Thread.sleep(200)  // warmup keepalive
            }
        }

        // companion 跟 master 同样的 stale-fd 重试逻辑（cached fd 数字 >=0 但 wrap_sys_device
        // -2002 时清缓存 + 重新 usbManager.openDevice 拿新 fd）
        companionHandle = tryOpenCompanion(usbManager, companion)
        if (companionHandle == 0L) return fail("companion openDeviceByFd 失败", dropCachedConns = true)

        if (!replayCompanionInit()) return fail("companion sonix init 失败", dropCachedConns = true)
        if (!negotiateUvcStream()) return fail("UVC probe/commit 失败", dropCachedConns = true)

        assembler.reset()
        startPullLoop()

        if (!companionOnly && masterHandle != 0L && !enableMasterStream) {
            Log.w(TAG, "★ enableMasterStream=false — 不开 master RGB，只 keepalive（验证 firmware starve 假说）")
        }

        _state.value = State.Streaming
        Log.i(TAG, "✅ stack started; companionOnly=$companionOnly keepalive=${if (companionOnly) "skip" else "${keepaliveIntervalMs}ms"} masterRgb=${masterPullRunning.get()}")
        return true
    }

    // ---- Android 迁移 Step 3：native 全流程双流（portable 层，替代上面的 Kotlin 编排） ----
    // 取 master+companion 原始 fd 交给 NativeBridge.cameraOpenByFds(→ BerxelDriver → berxel_open_dual)，
    // XU replay / dense depth controls / UVC 协商 / bulk pump / RGBD 配对全部在 C++ portable 层完成，
    // 复用 Linux host 已验证的启动序列（enableColor 时走原厂 MIX 序列，color+depth 真机 PASS）。
    // 与上面单流路径互斥：不预先 berxelOpenDeviceByFd（那会 claim），dual session 自己 wrap+claim。
    @Volatile private var dualHandle: Long = 0L
    private var dualMasterConn: UsbDeviceConnection? = null
    private var dualCompanionConn: UsbDeviceConnection? = null

    /**
     * 用 native portable 层跑 host 已验证的双流序列。
     * @param enableColor false=仅 depth（先隔离验证 P0 depth 链路），true=depth+color 双流。
     * @return native 会话句柄；0 = 失败（看 [lastError] / logcat）。
     */
    fun startDualNative(
        usbManager: UsbManager,
        enableColor: Boolean = false,
        authorizedByName: Map<String, UsbDevice> = emptyMap(),
        keepaliveMs: Int = 50,
        depthFps: Int = 45,
        depthTemporal: Boolean = true,
    ): Long {
        if (dualHandle != 0L) {
            Log.w(TAG, "startDualNative: 已有会话 handle=$dualHandle，先 stopDualNative()")
            return dualHandle
        }
        val all = usbManager.deviceList.values
        val masterListed = all.firstOrNull { it.vendorId == MASTER_VID && it.productId == MASTER_PID }
            ?: return failDual("master $MASTER_VID:$MASTER_PID 未发现（vivo 上可能被 uvcvideo 抢，见 M1.6.10）")
        val companionListed = all.firstOrNull { it.vendorId == COMPANION_VID && it.productId == COMPANION_PID }
            ?: return failDual("companion $COMPANION_VID:$COMPANION_PID 未发现")
        // HONOR Magic OS / Android 15 实测：deviceList 取到的 UsbDevice 实例【没】USB 读权限，
        // openDevice 会卡 ~68s 后失败；只有 USB_DEVICE_ATTACHED intent extras 携带的授权实例才有权限。
        // 优先用 authorizedByName（attachAuthorizedDevice 缓存的 intent 授权实例）去 open。
        val master = authorizedByName[masterListed.deviceName] ?: masterListed
        val companion = authorizedByName[companionListed.deviceName] ?: companionListed
        Log.i(TAG, "startDualNative open master=${master.deviceName}(authorized=${authorizedByName.containsKey(masterListed.deviceName)}) " +
            "companion=${companion.deviceName}(authorized=${authorizedByName.containsKey(companionListed.deviceName)})")

        val mConn = usbManager.openDevice(master) ?: return failDual("master openDevice 失败(无权限?)")
        val cConn = usbManager.openDevice(companion)
            ?: run { mConn.close(); return failDual("companion openDevice 失败(无权限?)") }
        dualMasterConn = mConn
        dualCompanionConn = cConn

        // enable_color = MIX 并发：master/companion 都用【原厂 MIX 序列】（berxel_mix_trace usbmon
        //   抓原厂 SDK setStreamFlagMode(MIX)+startStreams(COLOR|DEPTH) 的 definitive 配方）。
        //   depth-only 用 SINGULAR 序列。MIX 序列让设备协调 master 彩色 + companion 深度并发——
        //   host 实测 color 1003 帧 / depth 146 帧（metric 298mm）/ 0 错 / 145 RGBD 对。
        //   关键差异：StreamFlagMode(0x0030)=0x0000 写两次 + cmd0x0007=01 + COLOR OpenStream 中段，
        //   companion reg0x19=04（MIX）。这些都已 baked 在 MIX 资产里，native 不再 patch StreamFlagMode。
        val masterXu = context.assets.open(
            if (enableColor) MASTER_MIX_ASSET else MASTER_XU5_ASSET).use { it.readBytes() }
        val companionInit = context.assets.open(
            if (enableColor) COMPANION_MIX_ASSET else COMPANION_INIT_ASSET).use { it.readBytes() }

        // 【depth 640x401@45 默认档 — USB2-safe，2026-06-01 真机定档】
        // depth transport 640x401（active 640x400，native 自动裁状态行 p100r3_depth_active_height），
        //   frame_index=2，interval100ns=222222（45fps）。mode_code 由 width>=640→0x08 自动派生
        //   （p100r3_depth_mode_code），companion open-stream payload 由 patch_*_depth_open_stream 自动注入 0x08。
        // 选 640 而非 1280 的第一性依据：
        //   · 640x401@45 RAW16 ≈ 23MB/s，稳在 USB2 HS（~53MB/s）内；P100R3 理想工作距 0.25-2m，640 分辨率足够量测。
        //   · 1280x801@45 ≈ 92MB/s 超 USB2 HS，物理上只有【带电 hub 协商高速链路】才撑得住——
        //     2510DRK44C(USB2 直插无 hub) 实测 1280 档 set_cur rc=-7 TIMEOUT + depth_chunks=0（M1.6.18 饿死）。
        //   · 当前测试机池（2510DRK44C/HONOR 等）都是 USB2-only OTG，640 是唯一普适稳定档。
        // TODO（终态，待供电/USB3 链路探测模块就绪）：按 bcdUSB + 是否带电 hub 自动选 640/1280，
        //   而非编译期定档；不在此造 fallback，模块就绪前固定 USB2-safe 640。
        // color 档 640x400@30：原厂 MIX 抓包 master 彩色 UVC commit = fmt1 frame3 interval100ns=333333(30fps)，
        //   与 master_mix_init 里 COLOR OpenStream(640x400@30) 一致。enableColor=false 时该档不起。
        // depthFps 可调（45/30/15）：interval=1e7/fps。
        val depthInterval = (10_000_000 / depthFps.coerceIn(5, 60))
        // 大单次读长（64KB）：减少 transfer/事件开销，配合 48 个在途把管子喂满。
        // cfg[13]：时域降噪开关（0/正=启用，负=关闭做 A/B）。默认启用：滑窗均值 N=8 把
        // 相邻帧抖动 ~38mm 压到 ~10mm（harness depth_temporal_quality 实测 3.73×、零偏移、密度不掉）。
        val config = intArrayOf(
            640, 401, depthFps, 2, depthInterval,
            640, 400, 30, 3, 333333,
            keepaliveMs,
            DUAL_ASYNC_READ_LEN,
            if (enableColor) 1 else 0,
            if (depthTemporal) 0 else -1,
        )
        Log.i(TAG, "startDualNative masterFd=${mConn.fileDescriptor} companionFd=${cConn.fileDescriptor} enableColor=$enableColor keepaliveMs=$keepaliveMs depthFps=$depthFps temporal=$depthTemporal interval=$depthInterval readLen=$DUAL_ASYNC_READ_LEN")
        // M6.8b ④：经厂商无关 cameraOpenByFds 分发到 native BerxelDriver(0x0603:0x001f)。
        // options 打包 [masterXu | companionInit | 14-int config]，BerxelDriver 解包后调 berxel_open_dual
        // （与历史 berxelDualStart 同一双流序列，逐位不变）。句柄=ICameraSession*，poll/stop 走 camera*。
        val options = packBerxelOptions(masterXu, companionInit, config)
        val h = NativeBridge.cameraOpenByFds(
            MASTER_VID, MASTER_PID, intArrayOf(mConn.fileDescriptor, cConn.fileDescriptor), options)
        if (h == 0L) {
            cConn.close(); mConn.close()
            dualCompanionConn = null; dualMasterConn = null
            return failDual("cameraOpenByFds(Berxel) 返 0（看 gomob_camera_jni / gomob_berxel_dual logcat）")
        }
        dualHandle = h
        Log.i(TAG, "✅ startDualNative handle=$h")
        return h
    }

    private fun failDual(msg: String): Long {
        lastError = msg
        Log.e(TAG, "startDualNative 失败: $msg")
        return 0L
    }

    /** 把 masterXu/companionInit/14-int config 打包成 BerxelDriver 的 options_json 二进制
     *  （小端：[u32 xuLen][xu][u32 initLen][init][14×i32 config]，与 native unpack_berxel_options 对称）。 */
    private fun packBerxelOptions(masterXu: ByteArray, companionInit: ByteArray, config: IntArray): ByteArray {
        val size = 4 + masterXu.size + 4 + companionInit.size + 14 * 4
        val bb = java.nio.ByteBuffer.allocate(size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.putInt(masterXu.size); bb.put(masterXu)
        bb.putInt(companionInit.size); bb.put(companionInit)
        for (i in 0 until 14) bb.putInt(config.getOrElse(i) { 0 })
        return bb.array()
    }

    /** 双流富诊断统计（16 项 keepalive/配对/seq，见 BerxelDriver extended_stats 顺序）。无会话返全 0。 */
    fun dualStats(): LongArray {
        if (dualHandle == 0L) return LongArray(16)
        val s = NativeBridge.cameraExtendedStats(dualHandle)
        return if (s.size >= 16) s else LongArray(16).also { s.copyInto(it) }
    }

    fun isDualRunning(): Boolean = dualHandle != 0L

    /** 取最新 depth 帧 active 16bit mm 到 directBuffer；返回字节数 / 0 无帧 / -1 buffer 不足。 */
    fun dualPollDepthMm(buffer: java.nio.ByteBuffer, outInfo: LongArray): Int =
        if (dualHandle != 0L) NativeBridge.cameraPollDepthMm(dualHandle, buffer, outInfo) else 0

    /** 取最新 depth 帧逐像素 confidence(uint8) 到 directBuffer（飞点=0）；返回字节数 / 0 无 / -1 不足。 */
    fun dualPollDepthConf(buffer: java.nio.ByteBuffer, outInfo: LongArray): Int =
        if (dualHandle != 0L) NativeBridge.cameraPollDepthConf(dualHandle, buffer, outInfo) else 0

    /** 取最新 IR/phase 帧 IR 亮度到 directBuffer 当 8-bit 灰度；返回字节数(activeW*activeH) / 0 无帧 / -1 不足。 */
    fun dualPollIrGrey(buffer: java.nio.ByteBuffer, outInfo: LongArray): Int =
        if (dualHandle != 0L) NativeBridge.cameraPollIrGrey(dualHandle, buffer, outInfo) else 0

    /** 取最新 master MJPEG color 帧（consume-once，无新帧返 null）。 */
    fun dualPollColorMjpeg(): ByteArray? =
        if (dualHandle != 0L) NativeBridge.cameraPollColor(dualHandle) else null

    /** 调试：dump 最新 depth transport 完整原始字节到 path（native fopen）。返回写入字节数。 */
    fun dualDumpRawDepth(path: String): Int =
        if (dualHandle != 0L) NativeBridge.cameraDumpRawDepth(dualHandle, path) else 0

    fun stopDualNative() {
        val h = dualHandle
        dualHandle = 0L
        if (h != 0L) NativeBridge.cameraStop(h)
        runCatching { dualCompanionConn?.close() }
        runCatching { dualMasterConn?.close() }
        dualCompanionConn = null
        dualMasterConn = null
        Log.i(TAG, "stopDualNative done")
    }

    /** 同步 pull 一段 BULK 数据（最多 16KB）。返回 null 表示 timeout 或 error。诊断用；
     *  生产建议走 [pollFrame] 拿完整 YUYV depth frame（512000B）。 */
    fun pullChunk(timeoutMs: Int = 200): ByteArray? {
        if (_state.value != State.Streaming || companionHandle == 0L) return null
        return NativeBridge.berxelBulkSyncReadBytes(companionHandle, COMPANION_BULK_EP, BULK_READ_LEN, timeoutMs)
    }

    /** 取一帧拼装好的 depth frame；队列空返 null。需要 background pull 线程在跑。 */
    fun pollFrame(): DepthFrame? = assembler.pollFrame()

    /** 切换 depth assembler 是否 drop size 偏差大的"长/短帧"。
     *  strict=true：只保留 width*height*2 ± 1 行的；
     *  strict=false：全 emit（用户可视化对比哪个跟世界对齐）。 */
    fun setDepthStrictFrameSize(strict: Boolean) {
        assembler.strictFrameSize = strict
        Log.i(TAG, "setDepthStrictFrameSize=$strict")
    }
    fun isDepthStrictFrameSize(): Boolean = assembler.strictFrameSize

    /** 取一帧 MJPEG 字节流（master RGB）；队列空返 null。Service 端调 BitmapFactory decode。 */
    fun pollMjpegFrame(): ByteArray? = mjpegAssembler.pollFrame()

    fun masterStreamStats(): String = "mjpegIn=${mjpegAssembler.totalBytesIn}B " +
        "frames=${mjpegAssembler.totalFramesOut} dropped=${mjpegAssembler.droppedFrames} " +
        "skipped=${mjpegAssembler.skippedFrames} queue=${mjpegAssembler.queueDepth()} " +
        "${mjpegAssembler.debugStats()} " +
        "errs=$totalMasterStreamErrors"

    /**
     * M1.6.7 占位：从 device firmware 读出厂标定参数（color / IR 内参 + color↔IR 外参）。
     *
     * **2026-05-27 深入 RE 终结论**（详 `.dev/m1.6.1-protocol-reverse/device-params-readback.md`）：
     * propId=0x4a 的 getProperty 实现是 `memcpy(dst, this+0x1a0, *bufLen)`，**无 USB 读**。
     * 156 字节 cache 在 `BerxelDeviceSonix::initialize` 调用 `BerxelFirmware::initialize` 时
     * 从本地 `<SN>_params.bin` / 厂内 `params.bin` 等 6MB blob 中切出来加载。
     * → 不存在 on-device XU read 协议；本接口永久返 null，唯一 path 是 [loadDeviceParamsFromBlob]。
     *
     * @return 永远 null（设计如此）
     * @see loadDeviceParamsFromBlob 离线 blob → 156 字节 intrinsic 块（偏移待定，需真机 ADB pull 后定位）
     */
    fun getDeviceParams(): BerxelDeviceParams? {
        Log.w(TAG, "getDeviceParams: SDK 用本地 blob 加载（非 USB 读），用 loadDeviceParamsFromBlob")
        return null
    }

    /** 启动 background pull 循环：从 BULK 拉 chunk → 喂 assembler。 */
    private fun startPullLoop() {
        pullRunning.set(true)
        pullJob = scope.launch {
            var consecErr = 0
            while (pullRunning.get() && companionHandle != 0L) {
                val chunk = NativeBridge.berxelBulkSyncReadBytes(
                    companionHandle, COMPANION_BULK_EP, BULK_READ_LEN, 200)
                if (chunk == null) {
                    consecErr++
                    if (consecErr >= 200) {  // 200 × 200ms = 40s 没数据才认死
                        Log.e(TAG, "pull loop: $consecErr 连续 timeout/err — 视为 stream 死，停 pull")
                        pullRunning.set(false)
                        _state.value = State.Error
                        lastError = "stream died after pull loop saturation"
                        break
                    }
                } else {
                    consecErr = 0
                    assembler.append(chunk)
                }
            }
            Log.i(TAG, "pull loop exit; ${assemblerStats()}")
        }
    }

    private fun stopPullLoop() {
        pullRunning.set(false)
        pullJob?.cancel()
        pullJob = null
    }

    fun stop() {
        if (_state.value == State.Idle) return
        _state.value = State.Closing
        // 顺序：
        //   companion pull 停 → master pull 停 → keepalive 停 →
        //   master closeStream（cancel URB pool） → wait →
        //   companion close → wait → master close
        stopPullLoop()
        stopMasterStream()
        stopKeepalive()
        try { Thread.sleep(200) } catch (_: InterruptedException) {}
        if (companionHandle != 0L) {
            NativeBridge.berxelCloseDevice(companionHandle)
            companionHandle = 0L
        }
        try { Thread.sleep(200) } catch (_: InterruptedException) {}
        if (masterHandle != 0L) {
            NativeBridge.berxelCloseDevice(masterHandle)
            masterHandle = 0L
        }
        // 不关 UsbDeviceConnection：vivo 实测 close() 后 Android 撤销 /dev/bus/usb/* 文件 ACL，
        // 下一轮 openDevice() 返 null + 直接 open 错 EACCES。@Singleton lifecycle 保留 conn，
        // 下次 start() 复用同 fd；只有 [dispose] 才真正 close（App 销毁时）。
        _state.value = State.Idle
    }

    private fun fail(msg: String, dropCachedConns: Boolean = false): Boolean {
        Log.e(TAG, "stack start failed: $msg")
        lastError = msg
        _state.value = State.Error
        stop()
        if (dropCachedConns) {
            invalidateCachedConns()
        }
        _state.value = State.Error  // stop() 会把 state 设为 Idle，覆盖回 Error
        return false
    }

    /** companion 同样的 stale-fd 重试：cached → 失败 → close + 重 open。 */
    private fun tryOpenCompanion(usbManager: UsbManager, companion: UsbDevice): Long {
        val cached = companionConn?.takeIf { it.fileDescriptor >= 0 }
        if (cached != null) {
            val fd = cached.fileDescriptor
            Log.i(TAG, "companion 复用缓存 conn fd=$fd")
            val h = NativeBridge.berxelOpenDeviceByFd(fd, 0, 1)
            if (h != 0L) return h
            Log.w(TAG, "cached companion fd=$fd 已失效，close 重新 open")
            runCatching { cached.close() }
            companionConn = null
        }
        val raw = NativeBridge.berxelOpenUsbPath(companion.deviceName)
        if (raw >= 0) {
            Log.i(TAG, "companion raw open ${companion.deviceName} fd=$raw")
            return NativeBridge.berxelOpenDeviceByFd(raw, 0, 1)
        }
        Log.w(TAG, "companion raw open ${companion.deviceName} failed errno=${-raw}; fallback UsbManager.openDevice")
        val cc = openDeviceWithTimeout(usbManager, companion, "companion") ?: return 0L
        companionConn = cc
        Log.i(TAG, "companion UsbManager.openDevice fd=${cc.fileDescriptor}")
        return NativeBridge.berxelOpenDeviceByFd(cc.fileDescriptor, 0, 1)
    }

    /** 先复用 cached masterConn fd；wrap_sys_device 失败 → close cached + 用 device 重 open。
     *  返回 0 表示彻底失败。companionOnly 模式不走此函数。 */
    private fun tryOpenMaster(usbManager: UsbManager, master: UsbDevice, vsClaim: Int): Long {
        val cached = masterConn?.takeIf { it.fileDescriptor >= 0 }
        if (cached != null) {
            val fd = cached.fileDescriptor
            Log.i(TAG, "master 复用缓存 conn fd=$fd")
            val h = NativeBridge.berxelOpenDeviceByFd(fd, 0, vsClaim)
            if (h != 0L) return h
            // cached fd stale（USB reattach 后 path 变了）— 弃掉重 open
            Log.w(TAG, "cached master fd=$fd 已失效，close 重新 open")
            runCatching { cached.close() }
            masterConn = null
        }
        val raw = NativeBridge.berxelOpenUsbPath(master.deviceName)
        if (raw >= 0) {
            Log.i(TAG, "master raw open ${master.deviceName} fd=$raw vs=$vsClaim")
            return NativeBridge.berxelOpenDeviceByFd(raw, 0, vsClaim)
        }
        Log.w(TAG, "master raw open ${master.deviceName} failed errno=${-raw}; fallback UsbManager.openDevice")
        val mc = openDeviceWithTimeout(usbManager, master, "master")
        val fd = if (mc != null) {
            masterConn = mc
            Log.i(TAG, "master UsbManager.openDevice fd=${mc.fileDescriptor} vs=$vsClaim")
            mc.fileDescriptor
        } else {
            Log.e(TAG, "master UsbManager.openDevice returned null after raw errno=${-raw}")
            return 0L
        }
        return NativeBridge.berxelOpenDeviceByFd(fd, 0, vsClaim)
    }

    private fun openDeviceWithTimeout(
        usbManager: UsbManager,
        device: UsbDevice,
        label: String,
    ): UsbDeviceConnection? {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "berxel-open-$label").apply { isDaemon = true }
        }
        return try {
            val future = executor.submit<UsbDeviceConnection?> { usbManager.openDevice(device) }
            future.get(OPEN_DEVICE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            Log.e(TAG, "$label UsbManager.openDevice timeout ${OPEN_DEVICE_TIMEOUT_MS}ms device=${device.deviceName}")
            null
        } catch (t: Throwable) {
            Log.e(TAG, "$label UsbManager.openDevice failed device=${device.deviceName}", t)
            null
        } finally {
            executor.shutdownNow()
        }
    }

    private fun replayMasterInit(payloads: List<ByteArray>): Boolean {
        payloads.forEach { p ->
            val rc = NativeBridge.berxelControlTransfer(
                masterHandle, 0x21, 0x01, 0x0100, MASTER_XU5_WINDEX, p, p.size, 2000)
            NativeBridge.berxelControlTransfer(
                masterHandle, 0xa1, 0x81, 0x0100, MASTER_XU5_WINDEX, null, p.size, 2000)
            if (rc == null) return false
        }
        return true
    }

    private fun startKeepalive(seedPayload: ByteArray, intervalMs: Long) {
        val frame = seedPayload.copyOf()
        val cnt0 = (frame[10].toInt() and 0xff) or
            ((frame[11].toInt() and 0xff) shl 8) or
            ((frame[12].toInt() and 0xff) shl 16) or
            ((frame[13].toInt() and 0xff) shl 24)
        kaRunning.set(true)
        kaJob = scope.launch {
            var n = 0L
            var c = cnt0
            try {
                while (kaRunning.get() && masterHandle != 0L) {
                    c += 0x36
                    frame[10] = (c and 0xff).toByte()
                    frame[11] = ((c ushr 8) and 0xff).toByte()
                    frame[12] = ((c ushr 16) and 0xff).toByte()
                    frame[13] = ((c ushr 24) and 0xff).toByte()
                    NativeBridge.berxelControlTransfer(
                        masterHandle, 0x21, 0x01, 0x0100, MASTER_XU5_WINDEX, frame, frame.size, 500)
                    NativeBridge.berxelControlTransfer(
                        masterHandle, 0xa1, 0x81, 0x0100, MASTER_XU5_WINDEX, null, frame.size, 500)
                    n++
                    kotlinx.coroutines.delay(intervalMs)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "keepalive ended: ${t.message}")
            }
            Log.i(TAG, "keepalive exit n=$n")
        }
    }

    private fun stopKeepalive() {
        kaRunning.set(false)
        kaJob?.cancel()
        kaJob = null
    }

    private fun replayCompanionInit(): Boolean {
        val arr = JSONObject(context.assets.open(COMPANION_INIT_ASSET).bufferedReader().use { it.readText() })
            .getJSONArray("init_set_cur")
        var prevT = 0.0
        for (i in 0 until arr.length()) {
            val e = arr.getJSONObject(i)
            val selector = e.getInt("selector")
            val data = hexBytes(e.getString("data_hex"))
            val t = e.getDouble("t_seconds")
            if (i > 0) {
                val deltaMs = ((t - prevT) * 1000).toLong().coerceAtLeast(0L)
                if (deltaMs >= 50) try { Thread.sleep(deltaMs) } catch (_: InterruptedException) { return false }
            }
            prevT = t
            val rc = NativeBridge.berxelSessionBatchCmd(companionHandle, selector, data, 2000)
            NativeBridge.berxelSessionXuGetCur(companionHandle, selector, data.size, 2000)
            if (rc < 0) {
                Log.e(TAG, "companion init#$i sel=$selector rc=$rc")
                return false
            }
        }
        return true
    }

    /**
     * master 0x0603 标准 UVC PROBE/COMMIT：协商彩色 640×400@30fps。
     * descriptor 显示 master VS interface 1 alt 0 EP 0x81 BULK MPS=512：
     *   formatIdx=1 (MJPEG) 3 个 frame：1920×1080 / 1280×800 / 640×400（默认 interval=333333）
     *   formatIdx=2 (YUY2 Uncompressed) frameIdx=7：640×400@30
     * SDK 传给 libuvc 的 frame_format=7，即 UVC_FRAME_FORMAT_MJPEG；回调前再解成 RGB24。
     */
    private fun negotiateMasterUvcStream(): Boolean {
        val ctrl = (
            NativeBridge.berxelControlTransfer(
                masterHandle, 0xa1, 0x83, 0x0100, MASTER_VS_IFACE, null, 26, 2000)
                ?.takeIf { it.size >= 26 }
                ?.copyOf(26)
                ?: ByteArray(26)
            ).apply {
            this[0] = 0x01; this[1] = 0x00
            this[2] = MASTER_FORMAT_INDEX.toByte()
            this[3] = MASTER_FRAME_INDEX.toByte()   // 640×400
            this[4] = (MASTER_FRAME_INTERVAL_100NS and 0xff).toByte()
            this[5] = ((MASTER_FRAME_INTERVAL_100NS ushr 8) and 0xff).toByte()
            this[6] = ((MASTER_FRAME_INTERVAL_100NS ushr 16) and 0xff).toByte()
            this[7] = ((MASTER_FRAME_INTERVAL_100NS ushr 24) and 0xff).toByte()
        }
        val setProbe = NativeBridge.berxelControlTransfer(
            masterHandle, 0x21, 0x01, 0x0100, MASTER_VS_IFACE, ctrl, 26, 2000)
        val getProbe = NativeBridge.berxelControlTransfer(
            masterHandle, 0xa1, 0x81, 0x0100, MASTER_VS_IFACE, null, 26, 2000)
        val commit = NativeBridge.berxelControlTransfer(
            masterHandle, 0x21, 0x01, 0x0200, MASTER_VS_IFACE, getProbe ?: ctrl, 26, 2000)
        if (setProbe != null && getProbe != null && commit != null) {
            val negPayload = getProbe.joinToString("") { "%02x".format(it.toInt() and 0xff) }
            Log.i(
                TAG,
                "master probe/commit OK format=$MASTER_FORMAT_INDEX frame=$MASTER_FRAME_INDEX " +
                    "interval=$MASTER_FRAME_INTERVAL_100NS negotiated=$negPayload",
            )
            return true
        }
        return false
    }

    /** master BULK IN ep=0x81 — 跟 companion 一样用 sync read，避免 libusb_context event 锁
     *  跟 companion 抢（berxelOpenStream 内部 event_thread + sync read 同时调
     *  libusb_handle_events 会 deadlock 或 EBUSY 立返）。 */
    private fun openMasterStream(): Boolean {
        // 啥都不做 — sync read 不需要预先 submit URB pool
        return true
    }

    private fun startMasterPullLoop() {
        masterPullRunning.set(true)
        mjpegAssembler.reset()
        totalMasterStreamErrors = 0
        masterPullJob = scope.launch {
            var consecErr = 0
            var chunkIdx = 0L
            while (masterPullRunning.get() && masterHandle != 0L) {
                // sync read 跟 companion 一致；MJPEG chunk 来后 assembler 找 SOI/EOI 切帧
                val chunk = NativeBridge.berxelBulkSyncReadBytes(
                    masterHandle, MASTER_BULK_EP, BULK_READ_LEN, 200)
                if (chunk == null) {
                    consecErr++
                    if (consecErr <= 5 || consecErr % 50 == 0) {
                        Log.w(TAG, "master pull timeout#$consecErr ${masterStreamStats()}")
                    }
                    if (consecErr >= 200) {  // 200 × 200ms = 40s 没数据 → 死
                        Log.e(TAG, "master pull: $consecErr 连续 timeout — RGB 流死，停 pull")
                        masterPullRunning.set(false)
                        break
                    }
                } else {
                    consecErr = 0
                    chunkIdx++
                    if (chunkIdx <= 12 || chunkIdx % 300L == 0L) {
                        Log.i(
                            TAG,
                            "master chunk#$chunkIdx size=${chunk.size} ${describeMasterChunk(chunk)} " +
                                masterStreamStats(),
                        )
                    }
                    mjpegAssembler.append(chunk)
                }
            }
            Log.i(TAG, "master pull loop exit; ${masterStreamStats()}")
        }
    }

    private fun stopMasterStream() {
        masterPullRunning.set(false)
        masterPullJob?.cancel()
        masterPullJob = null
        // sync read 没有 URB pool，不需要 closeStream
    }

    private fun negotiateUvcStream(): Boolean {
        val ctrl = ByteArray(26).apply {
            this[0] = 0x01; this[1] = 0x00
            this[2] = COMPANION_FORMAT_INDEX.toByte()
            this[3] = COMPANION_FRAME_INDEX.toByte()
            this[4] = (COMPANION_FRAME_INTERVAL_100NS and 0xff).toByte()
            this[5] = ((COMPANION_FRAME_INTERVAL_100NS ushr 8) and 0xff).toByte()
            this[6] = ((COMPANION_FRAME_INTERVAL_100NS ushr 16) and 0xff).toByte()
            this[7] = ((COMPANION_FRAME_INTERVAL_100NS ushr 24) and 0xff).toByte()
        }
        val setProbe = NativeBridge.berxelControlTransfer(
            companionHandle, 0x21, 0x01, 0x0100, COMPANION_VS_IFACE, ctrl, 26, 2000)
        val getProbe = NativeBridge.berxelControlTransfer(
            companionHandle, 0xa1, 0x81, 0x0100, COMPANION_VS_IFACE, null, 26, 2000)
        val commit = NativeBridge.berxelControlTransfer(
            companionHandle, 0x21, 0x01, 0x0200, COMPANION_VS_IFACE, getProbe ?: ctrl, 26, 2000)
        return setProbe != null && getProbe != null && commit != null
    }

    private fun loadMasterPayloads(n: Int): List<ByteArray> {
        val arr = JSONObject(context.assets.open(MASTER_XU5_ASSET).bufferedReader().use { it.readText() })
            .getJSONArray("init_set_cur")
        return (0 until minOf(n, arr.length())).map {
            hexBytes(arr.getJSONObject(it).getString("data_hex"))
        }
    }

    private fun hexBytes(hex: String): ByteArray {
        val s = hex.lowercase()
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(s[i * 2], 16) shl 4) +
                Character.digit(s[i * 2 + 1], 16)).toByte()
        }
        return out
    }

    private fun describeMasterChunk(chunk: ByteArray): String {
        val headerLen = if (chunk.size >= 2) chunk[0].toInt() and 0xff else -1
        val flags = if (chunk.size >= 2) chunk[1].toInt() and 0xff else -1
        val payloadOffset = headerLen.takeIf { it in 2..64 && it < chunk.size } ?: 0
        val payloadNonzero = countNonzero(chunk, payloadOffset, chunk.size)
        val firstNonzero = firstNonzeroIndex(chunk, payloadOffset, chunk.size)
        return "uvcLen=$headerLen flags=0x${flags.toString(16)} " +
            "soi=${countPair(chunk, 0xff, 0xd8)} eoi=${countPair(chunk, 0xff, 0xd9)} " +
            "payloadNz=$payloadNonzero firstNz=${if (firstNonzero >= 0) firstNonzero - payloadOffset else -1} " +
            "head=${hexHead(chunk, 0, 24)} payload=${hexHead(chunk, payloadOffset, 24)}"
    }

    private fun hexHead(bytes: ByteArray, start: Int, count: Int): String {
        if (bytes.isEmpty() || start >= bytes.size) return ""
        val end = minOf(bytes.size, start + count)
        val sb = StringBuilder((end - start) * 2)
        var i = start
        while (i < end) {
            sb.append("%02x".format(bytes[i].toInt() and 0xff))
            i++
        }
        return sb.toString()
    }

    private fun countPair(bytes: ByteArray, a: Int, b: Int): Int {
        var n = 0
        var i = 0
        while (i + 1 < bytes.size) {
            if ((bytes[i].toInt() and 0xff) == a && (bytes[i + 1].toInt() and 0xff) == b) n++
            i++
        }
        return n
    }

    private fun countNonzero(bytes: ByteArray, start: Int, end: Int): Int {
        var n = 0
        var i = start.coerceAtLeast(0)
        val limit = end.coerceAtMost(bytes.size)
        while (i < limit) {
            if (bytes[i].toInt() != 0) n++
            i++
        }
        return n
    }

    private fun firstNonzeroIndex(bytes: ByteArray, start: Int, end: Int): Int {
        var i = start.coerceAtLeast(0)
        val limit = end.coerceAtMost(bytes.size)
        while (i < limit) {
            if (bytes[i].toInt() != 0) return i
            i++
        }
        return -1
    }

    /** 清掉 master/companion 缓存 UsbDeviceConnection。watchdog restart 前调，避免拿旧 fd
     *  跑 wrap_sys_device 全部 -2002（USB path 变了 fd 还是 valid 整数，但底层 device 已死）。 */
    fun invalidateCachedConns() {
        runCatching { masterConn?.close() }
        runCatching { companionConn?.close() }
        masterConn = null
        companionConn = null
        Log.i(TAG, "invalidateCachedConns: master+companion conn cleared")
    }

    fun dispose() {
        stop()
        runCatching { companionConn?.close() }
        runCatching { masterConn?.close() }
        masterConn = null
        companionConn = null
        scope.cancel()
    }

    companion object {
        const val TAG = "BerxelNativeStack"

        // P100R3 USB 节点（iHawkP100R3_descriptor.md 真理源）
        const val MASTER_VID = 0x0603
        const val MASTER_PID = 0x001f
        const val COMPANION_VID = 0x3558
        const val COMPANION_PID = 0x1012

        // master Berxel XU 5 on iface 0 = wIndex (5<<8)|0 = 0x0500
        const val MASTER_XU5_WINDEX = 0x0500

        // master VideoStreaming iface 1，BULK IN ep 0x81 MPS 512（descriptor 真理源）
        const val MASTER_VS_IFACE = 1
        const val MASTER_BULK_EP = 0x81
        // SDK 彩色走 MJPEG：formatIdx=1，640×400@30 frameIdx=3，Service 端解成 RGB24。
        const val MASTER_FORMAT_INDEX = 1
        const val MASTER_FRAME_INDEX = 3
        const val MASTER_FRAME_INTERVAL_100NS = 0x0A2C2A
        // 异步 URB 单次 transfer 大小（master MJPEG 一帧 ~50-100KB，16KB×16 transfer 一秒 RGB 余量足）
        const val MASTER_TRANSFER_SIZE = 16 * 1024

        // companion VideoStreaming iface 1，BULK IN ep
        const val COMPANION_VS_IFACE = 1
        const val COMPANION_BULK_EP = 0x82

        // 默认 companion stream 参数：YUYV 640x400 @45fps
        // formatIdx=1 frameIdx=2 dwFrameInterval=0x3640E (222222) — 跟 trace 对齐
        const val COMPANION_FORMAT_INDEX = 1
        const val COMPANION_FRAME_INDEX = 2
        const val COMPANION_FRAME_INTERVAL_100NS = 0x3640E

        // BULK sync read chunk size — vivo 上 1MB 触发 NO_DEVICE，16KB 验证稳定
        const val BULK_READ_LEN = 16 * 1024
        // dual 异步路径专用大读长（不动旧同步路径的 16KB）：1280@45 高吞吐喂满管子用。
        const val DUAL_ASYNC_READ_LEN = 64 * 1024
        const val OPEN_DEVICE_TIMEOUT_MS = 5_000L

        const val MASTER_XU5_ASSET = "berxel/iHawkP100R3_master_xu5_init.json"
        // 0..18 是 master 启动配置；从 #19 开始是 depth-only trace 里的 0x0a 保活/时钟命令，
        // 在彩色 UVC 前回放会让 master 只吐空 payload，完整回放甚至不吐 BULK。
        const val MASTER_RGB_INIT_COUNT = 19
        const val COMPANION_INIT_ASSET = "berxel/iHawkP100R3_init_sequence.json"
        // 原厂 MIX 并发 color+depth 序列（berxel_mix_trace usbmon 抓 vendor SDK 的 definitive 配方）。
        // enableColor=true 时替代上面的 SINGULAR 资产。master 21 条 / companion 8 条。
        const val MASTER_MIX_ASSET = "berxel/iHawkP100R3_master_mix_init.json"
        const val COMPANION_MIX_ASSET = "berxel/iHawkP100R3_companion_mix_init.json"

        /**
         * 从 156 字节 raw blob 加载 device params（测试 / 离线标定路径）。
         *
         * 入口约定：blob 字节布局跟 SDK `BerxelHawkDeviceIntrinsicParams` 一致 — 见
         * [BerxelDeviceParams] doc。on-device 读取见 [getDeviceParams]（M1.6.7 待实现）。
         */
        fun loadDeviceParamsFromBlob(bytes: ByteArray): BerxelDeviceParams =
            BerxelDeviceParams.fromBytes(bytes)
    }
}
