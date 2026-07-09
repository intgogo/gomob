package io.gomob.feature.scan3d

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.SettingRow
import io.gomob.designsystem.component.SettingRowDivider
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.theme.Gomob
import io.gomob.nativebridge.NativeBridge
import io.gomob.nativebridge.berxel.BerxelDeviceState
import io.gomob.nativebridge.berxel.BerxelService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val SONIX_DEBUG_ROUTE = "scan3d/depth-camera/sonix-debug"

// iHawk P100R3 USB 节点（跟 BerxelService.companion 一致；这里复制一份避免把 internal 暴露成 public）
private const val BERXEL_VID = 0x0603
private const val P100R3_PRIMARY_PID = 0x001f
private const val P100R3_COMPANION_VID = 0x3558
private const val P100R3_COMPANION_PID = 0x1012

private const val USB_PERMISSION_ACTION = "io.gomob.feature.scan3d.SONIX_DEBUG_USB_PERMISSION"
/** harness 触发 NativeStack 自动测试：`adb shell am broadcast -a $HARNESS_RUN_ACTION --el kaMs 50 --el durMs 10000` */
const val HARNESS_RUN_ACTION = "io.gomob.feature.scan3d.SONIX_DEBUG_HARNESS_RUN"
/** harness 触发 native portable 双流：`adb shell am broadcast -a $HARNESS_DUAL_ACTION --ez color false --el durMs 12000` */
const val HARNESS_DUAL_ACTION = "io.gomob.feature.scan3d.SONIX_DEBUG_HARNESS_DUAL"

// Sonix XU 入口寄存器；按 M1.6.1 反编译结果（.dev/m1.6.1-protocol-reverse/sonix-cmd-table.md）
private data class RegPreset(val label: String, val addr: Int)
private val PRESETS = listOf(
    RegPreset("0x10D0  chip id", 0x10D0),
    RegPreset("0x10D8  status",  0x10D8),
    RegPreset("0x10D9  status",  0x10D9),
)

/**
 * Sonix XU ASIC 寄存器读调试页（M1.6.5/M1.6.6 验证用）。
 *
 * 流程：
 * 1. 枚举 Berxel + companion USB 节点；
 * 2. 用户选节点 → 主动请求 USB 权限（NOT_EXPORTED 广播）；
 * 3. 拿到权限后打开 UsbDeviceConnection 取 fd；
 * 4. 用户填寄存器（hex）+ interface（默认 0）→ NativeBridge.berxelSonixAsicRead；
 * 5. 结果追加进日志。
 *
 * 警告：跟 Berxel SDK 抢同一 interface 会失败，所以使用前请先停掉 BerxelService。
 */
@Composable
fun SonixDebugRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usbManager = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager }

    // 通过 Hilt EntryPoint 直接拿全局 BerxelService 单例（不另起 VM，避免 init 时又 start 一次）。
    val entryPoint = remember {
        EntryPointAccessors.fromApplication(context.applicationContext, SonixDebugEntryPoint::class.java)
    }
    val berxel = remember { entryPoint.berxelService() }
    val nativeStack = remember { entryPoint.berxelNativeStack() }
    val berxelState by berxel.state.collectAsStateWithLifecycle()
    var stoppedOnce by remember { mutableStateOf(false) }

    var devices by remember { mutableStateOf(enumerate(usbManager)) }
    var selectedDeviceName by remember { mutableStateOf<String?>(null) }
    var hasPermission by remember { mutableStateOf(false) }
    var interfaceNumberText by remember { mutableStateOf("0") }
    var regHexText by remember { mutableStateOf("10D8") }
    var timeoutMsText by remember { mutableStateOf("1000") }
    val logLines = remember { mutableStateListOf<String>() }
    // 跨多轮测试持有 master / companion UsbDeviceConnection（vivo close 后 ACL 撤问题修法）
    val sessionHolder = remember { UsbSessionHolder() }
    DisposableEffect(sessionHolder) { onDispose { sessionHolder.dispose() } }

    // 进页就主动 stop Berxel SDK，把 USB interface 让出来，
    // 否则 libusb_claim_interface 会被 SDK 占住直接 -1003。
    LaunchedEffect(Unit) {
        if (!stoppedOnce) {
            stoppedOnce = true
            logLines += line("自动 berxel.stop() 释放 USB interface…")
            berxel.stop()
            // SDK 内部 stop 是异步：reader 退出 + close device + destroy context
            // 给它一点时间走完，再让用户点 READ
            delay(500)
            devices = enumerate(usbManager)
            logLines += line("berxel 状态=${berxelState::class.simpleName}")
        }
    }

    // USB 权限广播 receiver + harness 自动测试广播 listener
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    USB_PERMISSION_ACTION -> {
                        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        val dev = @Suppress("DEPRECATION")
                        intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                        logLines += line(
                            "权限广播 granted=$granted device=${dev?.deviceName}",
                        )
                        devices = enumerate(usbManager)
                        selectedDeviceName?.let { name ->
                            hasPermission = usbManager.deviceList[name]?.let(usbManager::hasPermission) == true
                        }
                    }
                    HARNESS_RUN_ACTION -> {
                        val kaMs = intent.getLongExtra("kaMs", 50L)
                        val durMs = intent.getLongExtra("durMs", 3000L)
                        val masterN = intent.getIntExtra("masterN", 20)
                        logLines += line("[HARNESS] auto trigger kaMs=$kaMs durMs=$durMs masterN=$masterN")
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runNativeStackFrameTest(
                                    nativeStack, usbManager,
                                    keepaliveIntervalMs = kaMs,
                                    durationMs = durMs,
                                    masterInitCount = masterN,
                                ) { l -> scope.launch { logLines += l } }
                            }
                        }
                    }
                    HARNESS_DUAL_ACTION -> {
                        val color = intent.getBooleanExtra("color", false)
                        val durMs = intent.getLongExtra("durMs", 12000L)
                        logLines += line("[HARNESS] dual native trigger color=$color durMs=$durMs")
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runDualNativeTest(nativeStack, usbManager, color, durMs) { l ->
                                    scope.launch { logLines += l }
                                }
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(USB_PERMISSION_ACTION)
            addAction(HARNESS_RUN_ACTION)
            addAction(HARNESS_DUAL_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { runCatching { context.unregisterReceiver(receiver) } }
    }

    // 选中设备变化 → 同步权限状态。实测 vivo Funtouch 上 hasPermission() 缓存会落后 SDK
    // 实际拿到的权限（Berxel SDK PermissionHelper 走自己的 PendingIntent，状态不一定同步到
    // UsbManager 的对外 cache），所以走 openDevice 试一下：成功 = 真有权限。
    LaunchedEffect(selectedDeviceName) {
        val dev = selectedDeviceName?.let { usbManager.deviceList[it] }
        hasPermission = if (dev == null) {
            false
        } else if (usbManager.hasPermission(dev)) {
            true
        } else {
            val probe = runCatching { usbManager.openDevice(dev) }.getOrNull()
            val ok = probe != null
            runCatching { probe?.close() }
            ok
        }
    }

    val listState = rememberLazyListState()
    GlassHeaderScaffold(
        listState = listState,
        header = { BackHeader(title = "Sonix ASIC 调试", eyebrow = "M1.6.5/6 · 验证 XU vendor 协议", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + Gomob.spacing.s12,
                bottom = padding.calculateBottomPadding() + Gomob.spacing.s28,
            ),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            item {
                WarningBlock(
                    text = "进入本页时已自动 berxel.stop() 释放 USB interface；" +
                        "如果先前流没断干净点「重新停止」再试。",
                    state = berxelState,
                    onForceStop = {
                        logLines += line("手动 berxel.stop()")
                        berxel.stop()
                    },
                )
            }

            item { SectionLabel("USB 节点") }
            item {
                Box(Modifier.padding(horizontal = Gomob.spacing.s16)) {
                    HairlineCard(padding = 0.dp) {
                        if (devices.isEmpty()) {
                            Box(Modifier.fillMaxWidth().padding(Gomob.spacing.s16)) {
                                Text("未发现 iHawk USB 节点，请插好 OTG", color = Gomob.colors.fg3)
                            }
                        } else {
                            Column {
                                devices.forEachIndexed { i, d ->
                                    val granted = usbManager.hasPermission(d)
                                    val selected = d.deviceName == selectedDeviceName
                                    DeviceRow(
                                        device = d,
                                        granted = granted,
                                        selected = selected,
                                        onSelect = { selectedDeviceName = d.deviceName },
                                    )
                                    if (i < devices.size - 1) SettingRowDivider()
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Gomob.spacing.s16),
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                ) {
                    ActionButton(
                        text = "刷新设备",
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        onClick = {
                            devices = enumerate(usbManager)
                            logLines += line("rescan → ${devices.size} nodes")
                        },
                    )
                    ActionButton(
                        text = if (hasPermission) "已授权" else "请求权限",
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        enabled = selectedDeviceName != null && !hasPermission,
                        onClick = {
                            val dev = selectedDeviceName?.let(usbManager.deviceList::get) ?: return@ActionButton
                            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                PendingIntent.FLAG_MUTABLE
                            } else {
                                0
                            }
                            val pi = PendingIntent.getBroadcast(
                                context, 0, Intent(USB_PERMISSION_ACTION).setPackage(context.packageName), flags,
                            )
                            usbManager.requestPermission(dev, pi)
                            logLines += line("requestPermission ${dev.deviceName}")
                        },
                    )
                }
            }

            item { SectionLabel("参数") }
            item {
                Box(Modifier.padding(horizontal = Gomob.spacing.s16)) {
                    HairlineCard(padding = 0.dp) {
                        Column {
                            LabeledField(
                                label = "Interface",
                                hint = "companion 节点 XU 在 Interface 0",
                                value = interfaceNumberText,
                                onChange = { interfaceNumberText = it.filter { ch -> ch.isDigit() }.take(2) },
                                keyboard = KeyboardType.Number,
                            )
                            SettingRowDivider()
                            LabeledField(
                                label = "Reg (hex)",
                                hint = "如 10D8 / 10D9 / 10D0；4 位 hex",
                                value = regHexText,
                                onChange = { regHexText = it.filter { ch -> ch in "0123456789abcdefABCDEF" }.take(4) },
                                keyboard = KeyboardType.Ascii,
                            )
                            SettingRowDivider()
                            LabeledField(
                                label = "Timeout(ms)",
                                hint = "默认 1000",
                                value = timeoutMsText,
                                onChange = { timeoutMsText = it.filter { ch -> ch.isDigit() }.take(5) },
                                keyboard = KeyboardType.Number,
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    Modifier.padding(horizontal = Gomob.spacing.s16),
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                ) {
                    PRESETS.forEach { p ->
                        ActionButton(
                            text = p.label.substringBefore(" "),
                            modifier = Modifier.weight(1f),
                            onClick = { regHexText = "%04X".format(p.addr) },
                        )
                    }
                }
            }

            item {
                ActionButton(
                    text = "ASIC READ",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Gomob.spacing.s16),
                    enabled = selectedDeviceName != null,  // 不卡 hasPermission；openDevice 失败时日志里说原因
                    onClick = {
                        val dev = selectedDeviceName?.let(usbManager.deviceList::get) ?: return@ActionButton
                        val reg = regHexText.toIntOrNull(16) ?: run {
                            logLines += line("reg 解析失败 raw='$regHexText'")
                            return@ActionButton
                        }
                        val iface = interfaceNumberText.toIntOrNull() ?: 0
                        val timeout = timeoutMsText.toIntOrNull() ?: 1000
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                runAsicRead(usbManager, dev, iface, reg, timeout)
                            }
                            logLines += result
                        }
                    },
                )
            }

            item {
                ActionButton(
                    text = "运行双流测试 (M1.6.6 候选 C)",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Gomob.spacing.s16),
                    enabled = true,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runDualStreamTest(context, usbManager, sessionHolder) { line ->
                                    scope.launch { logLines += line }
                                }
                            }
                        }
                    },
                )
            }

            item {
                ActionButton(
                    text = "NativeStack 拉 depth 帧测试 (3s)",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Gomob.spacing.s16),
                    enabled = true,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runNativeStackFrameTest(nativeStack, usbManager) { l ->
                                    scope.launch { logLines += l }
                                }
                            }
                        }
                    },
                )
            }

            item {
                ActionButton(
                    text = "★ native portable 双流 — depth only (12s)",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Gomob.spacing.s16),
                    enabled = true,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runDualNativeTest(nativeStack, usbManager, enableColor = false, durationMs = 12000L) { l ->
                                    scope.launch { logLines += l }
                                }
                            }
                        }
                    },
                )
            }

            item {
                ActionButton(
                    text = "★ native portable 双流 — depth+color (12s)",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Gomob.spacing.s16),
                    enabled = true,
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runDualNativeTest(nativeStack, usbManager, enableColor = true, durationMs = 12000L) { l ->
                                    scope.launch { logLines += l }
                                }
                            }
                        }
                    },
                )
            }

            item { SectionLabel("日志") }
            if (logLines.isEmpty()) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Gomob.spacing.s16),
                    ) {
                        Text("（尚无操作）", color = Gomob.colors.fg3)
                    }
                }
            } else {
                items(logLines.reversed()) { l ->
                    Box(Modifier.padding(horizontal = Gomob.spacing.s16)) {
                        Text(l, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Gomob.colors.fg2)
                    }
                }
            }
        }
    }
}

/**
 * 在 IO 线程上：openDevice → 取 fd → 调 native → 拼一行日志返回。
 * 不抛异常；失败一律返回带 [ERR] 前缀的字符串。
 */
private fun runAsicRead(
    usbManager: UsbManager,
    device: UsbDevice,
    interfaceNumber: Int,
    regAddr: Int,
    timeoutMs: Int,
): String {
    var conn: UsbDeviceConnection? = null
    return try {
        conn = usbManager.openDevice(device)
            ?: return line("[ERR] openDevice 返回 null（${device.deviceName}）")
        val fd = conn.fileDescriptor
        if (fd < 0) return line("[ERR] fd<0 (${fd})")
        val v = NativeBridge.berxelSonixAsicRead(fd, interfaceNumber, regAddr, timeoutMs)
        val regStr = "0x%04X".format(regAddr)
        when {
            v >= 0 -> line("asic_read[$regStr] = 0x%02X (%d)  iface=$interfaceNumber".format(v, v))
            else -> line("[ERR] asic_read[$regStr] rc=$v  iface=$interfaceNumber  ${decodeError(v)}")
        }
    } catch (t: Throwable) {
        line("[ERR] 异常 ${t.javaClass.simpleName} ${t.message}")
    } finally {
        runCatching { conn?.close() }
    }
}

/**
 * M1.6.6 候选 C：master 节点 XU 5 init + 持续 polling 让 firmware ready，
 * 同时 companion 走 Sonix init + UVC probe/commit + sync bulk read。
 *
 * 假设：companion firmware 在 commit 后不出 BULK 是因为 master XU 5 control channel
 * 未保持活跃；replay trace 中前 20 条 master SET_CUR 后挂 keepalive，让 firmware 看到
 * master 在线，再 commit companion。
 */
private fun runDualStreamTest(
    context: Context,
    usbManager: UsbManager,
    sessionHolder: UsbSessionHolder?,
    log: (String) -> Unit,
) {
    log(line("=== DUAL STREAM TEST 开始 ==="))
    val all = usbManager.deviceList.values.toList()
    log(line("deviceList: ${all.size} 项 → ${all.joinToString { "0x%04x:0x%04x".format(it.vendorId, it.productId) }}"))

    val master = all.firstOrNull { it.vendorId == BERXEL_VID && it.productId == P100R3_PRIMARY_PID }
    val companion = all.firstOrNull { it.vendorId == P100R3_COMPANION_VID && it.productId == P100R3_COMPANION_PID }
    if (master == null) { log(line("❌ master 0x0603:0x001f 未发现")); return }
    if (companion == null) { log(line("❌ companion 0x3558:0x1012 未发现")); return }

    // 优先复用 page-scope 缓存的 connection（避免 vivo close 撤 ACL 后下一轮 EACCES）
    val (cachedMasterConn, cachedCompanionConn) = sessionHolder?.take(master.deviceName, companion.deviceName)
        ?: (null to null)

    // master 拿 fd
    val masterFd: Int
    var masterConn: android.hardware.usb.UsbDeviceConnection? = cachedMasterConn
    if (masterConn != null) {
        masterFd = masterConn.fileDescriptor
        log(line("✅ master 复用缓存 conn fd=$masterFd"))
    } else if (usbManager.hasPermission(master)) {
        val c = usbManager.openDevice(master)
        if (c != null) {
            masterConn = c
            masterFd = c.fileDescriptor
            log(line("✅ master via UsbManager fd=$masterFd path=${master.deviceName}"))
        } else {
            val fd = NativeBridge.berxelOpenUsbPath(master.deviceName)
            if (fd < 0) {
                log(line("❌ master open ${master.deviceName} 失败 errno=${-fd}"))
                return
            }
            masterFd = fd
            log(line("✅ master via /dev path fd=$masterFd"))
        }
    } else {
        log(line("❌ master 无权限"))
        return
    }
    val companionConn: android.hardware.usb.UsbDeviceConnection
    if (cachedCompanionConn != null) {
        companionConn = cachedCompanionConn
        log(line("✅ companion 复用缓存 conn fd=${companionConn.fileDescriptor}"))
    } else {
        if (!usbManager.hasPermission(companion)) {
            log(line("❌ companion 无权限"))
            return
        }
        companionConn = usbManager.openDevice(companion)
            ?: run {
                log(line("❌ companion openDevice null"))
                return
            }
    }

    var masterHandle = 0L
    var companionHandle = 0L
    val kaRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    var kaThread: Thread? = null
    try {
        masterHandle = NativeBridge.berxelOpenDeviceByFd(masterFd, 0, -1)
        if (masterHandle == 0L) { log(line("❌ master openDeviceByFd 失败 (看 gomob_native 错误码)")); return }
        log(line("✅ master session 0x${masterHandle.toString(16)} (vc=0, vs 跳过)"))

        // 加载 master XU 5 init payloads
        val masterPayloads = loadMasterPayloads(context, 20)
        log(line("master XU 5 payloads loaded: ${masterPayloads.size}"))

        // 回放 master XU 5 SET_CUR + GET_CUR
        masterPayloads.forEachIndexed { i, payload ->
            val set = NativeBridge.berxelControlTransfer(
                masterHandle, 0x21, 0x01, 0x0100, 0x0500, payload, payload.size, 2000)
            val get = NativeBridge.berxelControlTransfer(
                masterHandle, 0xa1, 0x81, 0x0100, 0x0500, null, payload.size, 2000)
            if (i < 3 || i == masterPayloads.size - 1) {
                val ghead = get?.take(8)?.joinToString(" ") { "%02x".format(it) } ?: "<null>"
                log(line("  master#$i set rc=${set?.size ?: -1} get head=$ghead"))
            }
            if (set == null) { log(line("  ❌ master#$i SET_CUR 失败")); return }
        }
        log(line("✅ master XU 5 init done"))

        // Linux SDK trace bus7-master.pcap 显示 master XU 5 steady-state 是 prefix 42580a000d0500
        // 的 64-byte payload，bytes 10-13 是 uint32 LE counter（每次 +0x36），间隔 ~2-5ms。
        // 我们用 5ms 节流 + counter 自增模拟。50ms 静态 payload 只能撑 3s 就 firmware silent-die。
        val kaFrame = masterPayloads.last().copyOf()  // 拷贝可变
        // 取 payload 当前 counter 值作为起点
        val kaCounterStart = (kaFrame[10].toInt() and 0xff) or
            ((kaFrame[11].toInt() and 0xff) shl 8) or
            ((kaFrame[12].toInt() and 0xff) shl 16) or
            ((kaFrame[13].toInt() and 0xff) shl 24)
        kaRunning.set(true)
        kaThread = Thread({
            var n = 0L
            var cnt = kaCounterStart
            while (kaRunning.get()) {
                cnt = (cnt + 0x36) and 0xffffffff.toInt()
                kaFrame[10] = (cnt and 0xff).toByte()
                kaFrame[11] = ((cnt ushr 8) and 0xff).toByte()
                kaFrame[12] = ((cnt ushr 16) and 0xff).toByte()
                kaFrame[13] = ((cnt ushr 24) and 0xff).toByte()
                try {
                    NativeBridge.berxelControlTransfer(
                        masterHandle, 0x21, 0x01, 0x0100, 0x0500, kaFrame, kaFrame.size, 500)
                    NativeBridge.berxelControlTransfer(
                        masterHandle, 0xa1, 0x81, 0x0100, 0x0500, null, kaFrame.size, 500)
                    n++
                } catch (t: Throwable) { break }
                try { Thread.sleep(20) } catch (_: InterruptedException) { break }
            }
            android.util.Log.i("SonixDualStream", "ka thread exit n=$n lastCnt=0x${cnt.toString(16)}")
        }, "berxel-master-ka").apply { isDaemon = true; start() }
        log(line("✅ master keepalive 启动 (20ms, counter+=0x36, start=0x${kaCounterStart.toString(16)})"))

        Thread.sleep(300)  // 让 keepalive 跑几轮

        companionHandle = NativeBridge.berxelOpenDeviceByFd(companionConn.fileDescriptor, 0, 1)
        if (companionHandle == 0L) { log(line("❌ companion openDeviceByFd 失败")); return }
        log(line("✅ companion session 0x${companionHandle.toString(16)}"))

        // companion Sonix init seq (7 SET_CUR + GET_CUR readback)
        replayCompanionInit(context, companionHandle, log)

        // 关键发现：openStream 内部双 probe/commit + 8 并发 in-flight transfer 触发
        // vivo OTG 462ms host kill，全部 status=5 NO_DEVICE。退回外层 probe/commit +
        // sync_read 循环 path（18:20 验证过 3×16KB success）。
        // 外层 UVC PROBE + COMMIT
        val ctrl = uvcStreamCtrlBlock(1, 2, 0x3640E)
        val p1 = NativeBridge.berxelControlTransfer(companionHandle, 0x21, 0x01, 0x0100, 1, ctrl, 26, 2000)
        val p2 = NativeBridge.berxelControlTransfer(companionHandle, 0xa1, 0x81, 0x0100, 1, null, 26, 2000)
        val c1 = NativeBridge.berxelControlTransfer(companionHandle, 0x21, 0x01, 0x0200, 1, p2 ?: ctrl, 26, 2000)
        log(line("  companion UVC probe SET=${p1!=null} GET=${p2!=null} commit=${c1!=null}"))
        if (p2 != null) {
            // dwMaxPayloadTransferSize = adj[18..21]，firmware 协商出的单次 BULK 大小
            val maxLen = (p2[18].toInt() and 0xff) or
                ((p2[19].toInt() and 0xff) shl 8) or
                ((p2[20].toInt() and 0xff) shl 16) or
                ((p2[21].toInt() and 0xff) shl 24)
            log(line("  probe dwMaxPayloadTransferSize=$maxLen"))
        }

        // sync_read loop：18:20 验证 16384 字节 buffer + 2000ms timeout 跑通。
        // 1MB buffer 触发 NO_DEVICE — vivo OTG 撑不住大块同步 BULK。
        val readLen = 16384
        val durationMs = 10000L  // 10s 持续测试，看 counter+5ms 能不能维持 firmware ready
        val deadline = System.currentTimeMillis() + durationMs
        var nReads = 0
        var nWithData = 0
        var totalBytes = 0L
        var firstHead = ""
        var firstErr = 0
        while (System.currentTimeMillis() < deadline) {
            val rc = NativeBridge.berxelBulkSyncRead(companionHandle, 0x82, readLen, 200)
            nReads++
            if (rc > 0) {
                nWithData++
                totalBytes += rc
                if (nWithData == 1) firstHead = "first size=$rc"
                if (nWithData <= 3 || nWithData % 50 == 0) {
                    log(line("  read#$nReads → bytes=$rc"))
                }
            } else {
                if (firstErr == 0) firstErr = rc
                // 连续失败 10 次直接退出，避免 NO_DEVICE 后 spin
                if (nReads - nWithData > 10 && nWithData == 0) break
            }
        }
        log(line("  ★ summary: reads=$nReads dataReads=$nWithData totalBytes=$totalBytes firstErr=$firstErr $firstHead"))
    } finally {
        // 顺序：先停 keepalive → wait → close companion handle (JNI session) → close master handle。
        // 但 ** 不关 UsbDeviceConnection **：vivo 实测 UsbDeviceConnection.close() 会撤销
        // /dev/bus/usb/* 文件 ACL，但 usbManager.hasPermission() 还 cache 为 true，下一轮
        // openDevice() 返 null + 直接 open errno=13。把 conn 交给 page 级 sessionHolder
        // 缓存，下一轮直接复用；只有 page dispose 才真正 close。
        if (kaRunning.get()) {
            kaRunning.set(false)
            kaThread?.interrupt()
            try { kaThread?.join(500) } catch (_: InterruptedException) {}
        }
        try { Thread.sleep(200) } catch (_: InterruptedException) {}
        if (companionHandle != 0L) NativeBridge.berxelCloseDevice(companionHandle)
        try { Thread.sleep(200) } catch (_: InterruptedException) {}
        if (masterHandle != 0L) NativeBridge.berxelCloseDevice(masterHandle)
        sessionHolder?.put(masterConn, companionConn)
        log(line("=== DUAL STREAM TEST 结束 (conn 缓存到 sessionHolder，下轮复用) ==="))
    }
}

/**
 * 跨多轮测试持有 master / companion 的 [UsbDeviceConnection]，避免 close 后 vivo 撤 ACL。
 * 只在 SonixDebugScreen DisposableEffect 退出时 close。
 */
class UsbSessionHolder {
    @Volatile var masterConn: android.hardware.usb.UsbDeviceConnection? = null
    @Volatile var companionConn: android.hardware.usb.UsbDeviceConnection? = null

    fun put(master: android.hardware.usb.UsbDeviceConnection?,
            companion: android.hardware.usb.UsbDeviceConnection?) {
        if (master != null) masterConn = master
        if (companion != null) companionConn = companion
    }

    fun take(masterDevPath: String, companionDevPath: String):
            Pair<android.hardware.usb.UsbDeviceConnection?, android.hardware.usb.UsbDeviceConnection?> {
        // 检查 fd 是否仍有效：fileDescriptor < 0 = invalid，重置
        val mc = masterConn?.takeIf { it.fileDescriptor >= 0 }
        val cc = companionConn?.takeIf { it.fileDescriptor >= 0 }
        return mc to cc
    }

    fun dispose() {
        runCatching { masterConn?.close() }
        runCatching { companionConn?.close() }
        masterConn = null
        companionConn = null
    }
}

/**
 * 扫 /sys/bus/usb/devices 找 idVendor=0603 idProduct=001f 的设备，回 /dev/bus/usb/BBB/DDD。
 * 读 sysfs 文件 app 进程一般可读（没 SELinux 限制）。
 */
private fun findMasterUsbPath(): String? {
    val sysRoot = java.io.File("/sys/bus/usb/devices")
    val children = sysRoot.listFiles() ?: return null
    for (child in children) {
        val vidFile = java.io.File(child, "idVendor")
        val pidFile = java.io.File(child, "idProduct")
        if (!vidFile.exists() || !pidFile.exists()) continue
        val vid = runCatching { vidFile.readText().trim() }.getOrNull() ?: continue
        val pid = runCatching { pidFile.readText().trim() }.getOrNull() ?: continue
        if (vid.equals("0603", ignoreCase = true) && pid.equals("001f", ignoreCase = true)) {
            val busnum = runCatching { java.io.File(child, "busnum").readText().trim().toInt() }.getOrNull() ?: continue
            val devnum = runCatching { java.io.File(child, "devnum").readText().trim().toInt() }.getOrNull() ?: continue
            return "/dev/bus/usb/%03d/%03d".format(busnum, devnum)
        }
    }
    return null
}

private fun loadMasterPayloads(context: Context, n: Int): List<ByteArray> {
    val json = context.assets.open("berxel/iHawkP100R3_master_xu5_init.json")
        .bufferedReader().use { it.readText() }
    val arr = org.json.JSONObject(json).getJSONArray("init_set_cur")
    val out = mutableListOf<ByteArray>()
    for (i in 0 until minOf(n, arr.length())) {
        out += hexBytes(arr.getJSONObject(i).getString("data_hex"))
    }
    return out
}

private fun replayCompanionInit(context: Context, handle: Long, log: (String) -> Unit) {
    val json = context.assets.open("berxel/iHawkP100R3_init_sequence.json")
        .bufferedReader().use { it.readText() }
    val arr = org.json.JSONObject(json).getJSONArray("init_set_cur")
    var prevT = 0.0
    for (i in 0 until arr.length()) {
        val e = arr.getJSONObject(i)
        val selector = e.getInt("selector")
        val data = hexBytes(e.getString("data_hex"))
        val t = e.getDouble("t_seconds")
        if (i > 0) {
            val deltaMs = ((t - prevT) * 1000).toLong().coerceAtLeast(0L)
            if (deltaMs >= 50) Thread.sleep(deltaMs)
        }
        prevT = t
        val rc = NativeBridge.berxelSessionBatchCmd(handle, selector, data, 2000)
        val rb = NativeBridge.berxelSessionXuGetCur(handle, selector, data.size, 2000)
        val rh = rb?.take(8)?.joinToString(" ") { "%02x".format(it) } ?: "<null>"
        log(line("  companion init#$i sel=0x%02x set rc=%d get head=%s".format(selector, rc, rh)))
        if (rc < 0) { log(line("  ❌ init#$i 失败")); return }
    }
}

private fun uvcStreamCtrlBlock(formatIdx: Int, frameIdx: Int, frameInterval100Ns: Int): ByteArray {
    val b = ByteArray(26)
    b[0] = 0x01; b[1] = 0x00
    b[2] = formatIdx.toByte()
    b[3] = frameIdx.toByte()
    b[4] = (frameInterval100Ns and 0xff).toByte()
    b[5] = ((frameInterval100Ns shr 8) and 0xff).toByte()
    b[6] = ((frameInterval100Ns shr 16) and 0xff).toByte()
    b[7] = ((frameInterval100Ns shr 24) and 0xff).toByte()
    return b
}

/**
 * NativeStack 端到端测试：start → 后台 pull 循环 + assembler 拼 512000B depth 帧 →
 * 3s 内 pollFrame 看拿到几帧 → stop。验证 BerxelNativeStack + BerxelFrameAssembler
 * 接入到 Hilt + UsbManager 是 OK 的。
 */
private fun runNativeStackFrameTest(
    stack: io.gomob.nativebridge.berxel.BerxelNativeStack,
    usbManager: android.hardware.usb.UsbManager,
    keepaliveIntervalMs: Long = 50L,
    durationMs: Long = 3000L,
    masterInitCount: Int = 20,
    log: (String) -> Unit,
) {
    log(line("=== NativeStack frame test 开始 (kaMs=$keepaliveIntervalMs durMs=$durationMs masterN=$masterInitCount) ==="))
    if (!stack.start(usbManager, keepaliveIntervalMs = keepaliveIntervalMs, masterInitCount = masterInitCount)) {
        log(line("❌ stack.start 失败: ${stack.lastError()}"))
        stack.stop()
        log(line("=== NativeStack frame test 结束 ==="))
        return
    }
    log(line("✅ stack.start OK, state=${stack.state.value}"))
    val deadline = System.currentTimeMillis() + durationMs
    var pulled = 0
    while (System.currentTimeMillis() < deadline) {
        val f = stack.pollFrame()
        if (f != null) {
            pulled++
            if (pulled <= 3 || pulled % 10 == 0) {
                val stats = f.stats()
                val head = f.data.take(8).joinToString(" ") { "%02x".format(it) }
                log(line("  frame#$pulled ${f.width}×${f.height}  ${stats.pretty(f.pixelCount)}  head=$head"))
            }
        } else {
            Thread.sleep(10)
        }
    }
    log(line("  ★ NativeStack summary: frames=$pulled  ${stack.assemblerStats()}"))
    stack.stop()
    log(line("=== NativeStack frame test 结束 ==="))
}

// Android 迁移 Step 3 验证：native portable 双流。XU replay / dense depth controls / UVC 协商 /
// bulk pump / RGBD 配对全在 C++（gomob_berxel_dual tag 每秒打 RUN 日志，含 center_median mm + valid）。
// 这里只触发 + 每秒抓一次 dualStats 进 UI 日志。
private fun runDualNativeTest(
    stack: io.gomob.nativebridge.berxel.BerxelNativeStack,
    usbManager: android.hardware.usb.UsbManager,
    enableColor: Boolean,
    durationMs: Long,
    log: (String) -> Unit,
) {
    log(line("=== native portable 双流测试开始 color=$enableColor dur=${durationMs}ms ==="))
    val h = stack.startDualNative(usbManager, enableColor)
    if (h == 0L) {
        log(line("❌ startDualNative 失败: ${stack.lastError()}"))
        log(line("=== native portable 双流测试结束 ==="))
        return
    }
    log(line("✅ startDualNative handle=$h（native 每秒打 'gomob_berxel_dual' RUN 日志）"))
    val deadline = System.currentTimeMillis() + durationMs
    while (System.currentTimeMillis() < deadline) {
        Thread.sleep(1000)
        val s = stack.dualStats()
        if (s.size >= 16) {
            log(line("  depthFrames=${s[0]} chunks=${s[1]} bytes=${s[2]} err=${s[3]} | " +
                "colorFrames=${s[4]} err=${s[7]} | pairs=${s[8]} lastDeltaNs=${s[9]} " +
                "ka=${s[14]} depthSeq=${s[15]}"))
        }
    }
    stack.stopDualNative()
    log(line("=== native portable 双流测试结束 ==="))
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

private fun decodeError(rc: Int): String = when (rc) {
    -1001 -> "libusb init / set_option 失败"
    -1002 -> "libusb_wrap_sys_device 失败（fd 失效 / 已被回收）"
    -1003 -> "claim_interface 失败（被 Berxel SDK 占用？interface 不存在？）"
    -1004 -> "asic_read 自身失败（USB stall / 超时 / firmware 拒绝）"
    else -> "未知"
}

private fun enumerate(usbManager: UsbManager): List<UsbDevice> {
    return usbManager.deviceList.values
        .filter { isBerxelLike(it) }
        .sortedBy { it.deviceName }
}

private fun isBerxelLike(d: UsbDevice): Boolean {
    if (d.vendorId == BERXEL_VID) return true
    if (d.vendorId == P100R3_COMPANION_VID && d.productId == P100R3_COMPANION_PID) return true
    return false
}

private val logTimeFmt by lazy { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }
private fun line(msg: String): String = "[${logTimeFmt.format(Date())}] $msg"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface SonixDebugEntryPoint {
    fun berxelService(): BerxelService
    fun berxelNativeStack(): io.gomob.nativebridge.berxel.BerxelNativeStack
}

// ─── 小组件 ─────────────────────────────────────────────────────────────────

@Composable
private fun SectionLabel(text: String) {
    Box(Modifier.padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s4)) {
        Text(text, style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
    }
}

@Composable
private fun WarningBlock(
    text: String,
    state: BerxelDeviceState,
    onForceStop: () -> Unit,
) {
    val stateLabel = when (state) {
        is BerxelDeviceState.Idle -> "Idle（已释放，可读）"
        is BerxelDeviceState.NoDevice -> "NoDevice"
        is BerxelDeviceState.WaitingPermission -> "WaitingPermission"
        is BerxelDeviceState.Initializing -> "Initializing"
        is BerxelDeviceState.Opening -> "Opening"
        is BerxelDeviceState.Streaming -> "Streaming（占用中 → 先停）"
        is BerxelDeviceState.Error -> "Error: ${state.reason}"
    }
    Box(Modifier.padding(horizontal = Gomob.spacing.s16)) {
        HairlineCard(padding = 0.dp) {
            Column(Modifier.fillMaxWidth().padding(Gomob.spacing.s12)) {
                Text(text, fontSize = 12.sp, color = Gomob.colors.danger)
                Text(
                    "Berxel 当前状态：$stateLabel",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Gomob.colors.fg2,
                    modifier = Modifier.padding(top = 4.dp),
                )
                ActionButton(
                    text = "重新停止 Berxel SDK",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Gomob.spacing.s8),
                    onClick = onForceStop,
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: UsbDevice,
    granted: Boolean,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    val isCompanion = device.vendorId == P100R3_COMPANION_VID && device.productId == P100R3_COMPANION_PID
    val title = buildString {
        append("0x%04X:0x%04X".format(device.vendorId, device.productId))
        if (isCompanion) append("  · companion(XU)") else append("  · master")
    }
    val subtitle = buildString {
        append(device.deviceName)
        append("  · ")
        append(if (granted) "已授权" else "未授权")
        append("  · ")
        append(if (selected) "已选中" else "点击选择")
    }
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = onSelect,
    )
}

@Composable
private fun ActionButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val bg = if (enabled) Gomob.colors.accent else Gomob.colors.bg2
    val fg = if (enabled) Gomob.colors.bg0 else Gomob.colors.fg3
    Box(
        modifier = modifier
            .clip(Gomob.shapes.r2)
            .background(bg)
            .let { if (enabled) it.clickable(onClick = onClick) else it }
            .padding(vertical = Gomob.spacing.s12),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = fg, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LabeledField(
    label: String,
    hint: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType,
) {
    Column(Modifier.fillMaxWidth().padding(Gomob.spacing.s12)) {
        Text(label, style = Gomob.type.caption, color = Gomob.colors.fg2)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Gomob.colors.fg0,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .clip(Gomob.shapes.r1)
                .background(Gomob.colors.bg2)
                .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s8),
        )
        Text(hint, fontSize = 10.sp, color = Gomob.colors.fg3, modifier = Modifier.padding(top = 2.dp))
    }
}
