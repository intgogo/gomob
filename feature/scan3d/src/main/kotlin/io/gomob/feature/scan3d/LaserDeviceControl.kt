package io.gomob.feature.scan3d

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.scan.DeviceCalib
import io.gomob.data.scan.DeviceFullInfo
import io.gomob.data.scan.DeviceStatusInfo
import io.gomob.data.scan.LaserScanRepository
import io.gomob.data.scan.ScanSettings
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import javax.inject.Inject

/**
 * 激光设备控制面板（M8'-F，对齐原厂功能键）：每单元（A·.101 / B·.102）状态信息 + 控制键
 * （零位校准 / 守望 / 停止 / 清除错误 / 软件复位）+ 扫描设置 + 标定参数 + 设备信息。
 * 破坏性操作（软件复位 / 零位校准 / 标定覆写）走二次确认弹窗。瘦客户端：全部经服务端
 * /v1/scans/laser/device-* 反代打单元 :4000。
 */

data class LaserDeviceUi(
    val unit: String = "a", // "a"|"b"
    val loading: Boolean = false,
    val busy: String? = null, // 正在执行的操作标签
    val status: DeviceStatusInfo? = null,
    val info: DeviceFullInfo? = null,
    val error: String? = null,
    val toast: String? = null,
)

@HiltViewModel
class LaserDeviceViewModel @Inject constructor(
    private val repo: LaserScanRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(LaserDeviceUi())
    val ui = _ui.asStateFlow()

    private var refreshInFlight = false
    private var operationInFlight = false

    fun selectUnit(u: String) {
        if (operationInFlight) return
        if (u == _ui.value.unit) return
        // 不清空旧 status/info —— 切换时保留上一单元数据当占位，避免面板因等待新数据而收缩；
        // 新数据 ~40ms 到达后原地替换。
        _ui.update { it.copy(unit = u, error = null) }
        refresh()
    }

    fun refresh() {
        if (refreshInFlight || operationInFlight) return
        val unit = _ui.value.unit
        refreshInFlight = true
        viewModelScope.launch {
            _ui.update { it.copy(loading = true, error = null) }
            try {
                val st = repo.deviceStatus(unit)
                val info = repo.deviceInfo(unit)
                _ui.update { it.copy(loading = false, status = st, info = info) }
            } catch (e: Throwable) {
                _ui.update { it.copy(loading = false, error = e.message ?: "查询失败") }
            } finally {
                refreshInFlight = false
            }
        }
    }

    fun command(cmd: String, label: String) {
        if (operationInFlight) return
        operationInFlight = true
        val unit = _ui.value.unit
        viewModelScope.launch {
            _ui.update { it.copy(busy = label, toast = null, error = null) }
            try {
                repo.deviceCommand(unit, cmd)
                val reloadError = refreshVisibleUnit(unit)
                _ui.update {
                    it.copy(
                        busy = null,
                        toast = if (reloadError == null) "「$label」已下发" else "「$label」已下发，状态刷新失败: ${reloadError.message}",
                    )
                }
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = null, toast = "「$label」失败: ${e.message}") }
            } finally {
                operationInFlight = false
            }
        }
    }

    fun saveScanSettings(s: ScanSettings) {
        if (operationInFlight) return
        operationInFlight = true
        val unit = _ui.value.unit
        viewModelScope.launch {
            _ui.update { it.copy(busy = "保存扫描设置", toast = null, error = null) }
            try {
                repo.updateScanSettings(unit, s)
                val reloadError = refreshVisibleUnit(unit)
                _ui.update {
                    it.copy(
                        busy = null,
                        toast = if (reloadError == null) "扫描设置已下发" else "扫描设置已下发，状态刷新失败: ${reloadError.message}",
                    )
                }
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = null, toast = "保存扫描设置失败: ${e.message}") }
            } finally {
                operationInFlight = false
            }
        }
    }

    fun saveCalib(c: DeviceCalib) {
        if (operationInFlight) return
        operationInFlight = true
        val unit = _ui.value.unit
        viewModelScope.launch {
            _ui.update { it.copy(busy = "保存标定", toast = null, error = null) }
            try {
                repo.updateCalib(unit, c)
                val reloadError = refreshVisibleUnit(unit)
                _ui.update {
                    it.copy(
                        busy = null,
                        toast = if (reloadError == null) "标定已覆写到设备" else "标定已覆写到设备，状态刷新失败: ${reloadError.message}",
                    )
                }
            } catch (e: Throwable) {
                _ui.update { it.copy(busy = null, toast = "保存标定失败: ${e.message}") }
            } finally {
                operationInFlight = false
            }
        }
    }

    fun clearToast() = _ui.update { it.copy(toast = null) }

    private suspend fun refreshVisibleUnit(unit: String): Throwable? =
        runCatching {
            val st = repo.deviceStatus(unit)
            val info = repo.deviceInfo(unit)
            _ui.update { current ->
                if (current.unit == unit) current.copy(loading = false, status = st, info = info) else current
            }
        }.exceptionOrNull()
}

// 待确认的破坏性操作。
private data class PendingConfirm(val title: String, val body: String, val onConfirm: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LaserDeviceControlSheet(
    onDismiss: () -> Unit,
    // 车位框（一次性标定）入口：底座固定后圈一次范围，之后扫描自动裁框内测量。
    // canEditCropBox=有融合云可作顶视底图时才可圈选；cropBoxSaved=已存框。
    canEditCropBox: Boolean = false,
    cropBoxSaved: Boolean = false,
    onEditCropBox: () -> Unit = {},
    vm: LaserDeviceViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var confirm by remember { mutableStateOf<PendingConfirm?>(null) }
    val controlsEnabled = ui.busy == null && !ui.loading

    LaunchedEffect(Unit) { if (ui.status == null) vm.refresh() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Gomob.colors.bg0,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 标题 + 单元切换 + 刷新
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("设备控制", style = Gomob.type.numInline.copy(fontSize = 16.sp), color = Gomob.colors.fg0, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                IconPill(GomobIcons.Refresh, "刷新", enabled = controlsEnabled, onClick = vm::refresh)
            }
            UnitTabs(unit = ui.unit, enabled = controlsEnabled, onSelect = vm::selectUnit)

            ui.toast?.let { ToastBar(it, onClose = vm::clearToast) }
            ui.busy?.let { BusyBar(it) }

            if (ui.loading && ui.status == null) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Gomob.colors.accent, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
            ui.error?.let {
                SectionCard("连接失败") {
                    Text(it, fontSize = 11.sp, color = Gomob.colors.danger)
                }
            }

            ui.status?.let { StatusSection(it) }

            // 控制键
            SectionCard("控制") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CtlButton("零位校准", GomobIcons.Calibrate, enabled = controlsEnabled) {
                        confirm = PendingConfirm("零位校准", "电机将转动回零位，请确认扫描区域已清空、无人靠近。") {
                            vm.command("ALIGN_ZERO", "零位校准"); confirm = null
                        }
                    }
                    CtlButton("守望", GomobIcons.Eyeball, enabled = controlsEnabled) { vm.command("SCAN_WATCH", "守望") }
                    CtlButton("停止", GomobIcons.Minus, danger = true, enabled = controlsEnabled) { vm.command("SCAN_STOP", "停止") }
                    CtlButton("清除错误", GomobIcons.Check, enabled = controlsEnabled) { vm.command("CLEAR_ERROR", "清除错误") }
                    CtlButton("软件复位", GomobIcons.Refresh, danger = true, enabled = controlsEnabled) {
                        confirm = PendingConfirm("软件复位", "设备将立即断连并重启（约 40 秒），开机自检会自动回零动电机。确认执行？") {
                            vm.command("SOFT_REBOOT", "软件复位"); confirm = null
                        }
                    }
                }
            }

            // 测量范围 · 车位框（一次性标定）：底座固定，圈一次即长期生效。
            CropBoxSection(canEdit = canEditCropBox, saved = cropBoxSaved, onEdit = onEditCropBox)

            ui.info?.let { info ->
                ScanSettingsSection(info.scanSettings, busy = !controlsEnabled, onSave = vm::saveScanSettings)
                CalibSection(info.calib, busy = !controlsEnabled, onSave = { c ->
                    confirm = PendingConfirm("覆写标定", "将把标定参数写入设备并覆盖出厂值，且不可撤销。确认下发？") {
                        vm.saveCalib(c); confirm = null
                    }
                })
                DeviceInfoSection(info)
            }
        }
    }

    confirm?.let { c ->
        ConfirmDialog(c, onDismiss = { confirm = null })
    }
}

// ───── 子区块 ─────

/**
 * 测量范围 · 车位框入口（设置内的一次性标定）。底座固定后圈一次顶视范围，之后每次扫描自动裁框内测量。
 * 编辑器需融合点云作顶视底图：[canEdit]=true（当前有完成态融合云）才可圈选；否则提示先扫一次。
 * [saved]=已存框（持久于 unit_a 世界系，跨会话）。
 */
@Composable
private fun CropBoxSection(canEdit: Boolean, saved: Boolean, onEdit: () -> Unit) {
    SectionCard("测量范围") {
        val border = if (canEdit) Gomob.colors.accent else Gomob.colors.line2
        val bg = if (canEdit) Gomob.colors.accentSoft else Gomob.colors.bg2
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(Gomob.shapes.r2)
                .background(bg)
                .border(BorderStroke(1.dp, border), Gomob.shapes.r2)
                .then(if (canEdit) Modifier.clickable(onClick = onEdit) else Modifier)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                GomobIcons.Cube, "圈选车位框",
                tint = if (canEdit) Gomob.colors.accent else Gomob.colors.fg3,
                modifier = Modifier.size(18.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    if (saved) "车位框 · 已圈选" else "圈选车位框",
                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = if (canEdit) Gomob.colors.accent else Gomob.colors.fg1,
                )
                Text(
                    when {
                        canEdit && saved -> "顶视拖框可重调 · 下次扫描自动裁框内测量"
                        canEdit -> "顶视拖框定车位 → 裁掉背景 → 框内量尺寸"
                        saved -> "已保存 · 下次扫描自动裁框内测量（重扫后可调整）"
                        else -> "先完成一次扫描，再来圈选车位范围"
                    },
                    fontSize = 9.sp,
                    color = if (saved && !canEdit) Gomob.colors.ok else Gomob.colors.fg3,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusSection(s: DeviceStatusInfo) {
    SectionCard("状态") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StateBadge(s.state)
            Text(s.ip, fontSize = 11.sp, color = Gomob.colors.fg2, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.weight(1f))
            Text("${fmt1(s.tempre)}℃", fontSize = 11.sp, color = if (s.tempre > 70) Gomob.colors.danger else Gomob.colors.fg2)
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OnlineChip("编码器", s.encoderOnline)
            OnlineChip("激光", s.lidarOnline)
            OnlineChip("相机", s.cameraOnline)
            OnlineChip("控制", s.controlOnline)
        }
        Spacer(Modifier.height(8.dp))
        KV("当前角度", "${fmt2(s.angleDegs)}°")
        KV("最新角度", "${fmt2(s.latestAngle)}°  · 零位 ${fmt2(s.zeroDegs)}°")
        KV("运行时长", "${(s.uptimeSec / 3600).toInt()}h ${((s.uptimeSec % 3600) / 60).toInt()}m")
        KV("错误码", if (s.errorCode == 0L) "无" else "0x%X (bit %s)".format(s.errorCode, bitList(s.errorCode)))
        if (s.scanMsg.isNotBlank()) KV("消息", s.scanMsg)
    }
}

@Composable
private fun ScanSettingsSection(s: ScanSettings, busy: Boolean, onSave: (ScanSettings) -> Unit) {
    var speed by remember(s) { mutableStateOf(num(s.scanSpeed)) }
    var zero by remember(s) { mutableStateOf(num(s.zeroSpeed)) }
    var start by remember(s) { mutableStateOf(num(s.scanStartAngle)) }
    var scanAngle by remember(s) { mutableStateOf(num(s.scanAngle ?: signedScanAngleDeg(s.scanStartAngle, s.scanStopAngle))) }
    var watch by remember(s) { mutableStateOf(num(s.watchingAngle)) }
    var fps by remember(s) { mutableStateOf(num(s.cameraFps)) }
    val angleWarning = remember(start, scanAngle) {
        scanAngleWarning(start.toDoubleOrNull(), scanAngle.toDoubleOrNull())
    }
    val derivedStop = remember(start, scanAngle, angleWarning) {
        if (angleWarning == null) {
            val startAngle = start.toDoubleOrNull()
            val sweepAngle = scanAngle.toDoubleOrNull()
            if (startAngle != null && sweepAngle != null) stopAngleFromScan(startAngle, sweepAngle) else null
        } else {
            null
        }
    }

    SectionCard("扫描设置") {
        NumField("扫描速度 °/s", speed) { speed = it }
        NumField("回零速度 °/s", zero) { zero = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { NumField("初始位置 °", start) { start = it } }
            Box(Modifier.weight(1f)) { NumField("扫描角度 °", scanAngle) { scanAngle = it } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { NumField("守望角 °", watch) { watch = it } }
            Box(Modifier.weight(1f)) { NumField("相机 FPS", fps) { fps = it } }
        }
        derivedStop?.let {
            KV("结束角", "${fmt2(it)}°")
        }
        angleWarning?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, fontSize = 10.sp, color = Gomob.colors.danger)
        }
        Spacer(Modifier.height(8.dp))
        SaveButton("下发扫描设置", enabled = angleWarning == null && !busy) {
            val startAngle = start.toDoubleOrNull() ?: s.scanStartAngle
            val sweepAngle = scanAngle.toDoubleOrNull() ?: (s.scanAngle ?: signedScanAngleDeg(s.scanStartAngle, s.scanStopAngle))
            onSave(
                s.copy(
                    scanSpeed = speed.toDoubleOrNull() ?: s.scanSpeed,
                    zeroSpeed = zero.toDoubleOrNull() ?: s.zeroSpeed,
                    scanStartAngle = startAngle,
                    scanStopAngle = stopAngleFromScan(startAngle, sweepAngle),
                    scanAngle = sweepAngle,
                    watchingAngle = watch.toDoubleOrNull() ?: s.watchingAngle,
                    cameraFps = fps.toDoubleOrNull() ?: s.cameraFps,
                ),
            )
        }
    }
}

internal fun scanAngleWarning(start: Double?, scanAngle: Double?): String? {
    if (start == null || scanAngle == null) return "初始位置和扫描角度必须是数字"
    val span = abs(scanAngle)
    val stop = start + scanAngle
    return when {
        start <= -180.0 || start >= 180.0 -> "无效范围：初始位置需在 -180°～180° 内，并避开 ±180° 边界"
        scanAngle <= 0.0 -> "当前固件只支持沿设备正向扫描；负扫描角会跨 +180° 扫成超大角度，请调换初始位置后使用正角度"
        stop <= -180.0 || stop >= 180.0 -> "无效范围：结束位置 ${fmt2(stop)}° 需避开 ±180° 边界"
        span < 10.0 -> "无效范围：扫描角度需 ≥10°，过小角度不会形成有效点云"
        span >= 179.5 -> "无效范围：单段扫描角度必须小于 180°"
        else -> null
    }
}

internal fun signedScanAngleDeg(start: Double, stop: Double): Double =
    stop - start

internal fun stopAngleFromScan(start: Double, scanAngle: Double): Double =
    start + scanAngle

@Composable
private fun CalibSection(c: DeviceCalib, busy: Boolean, onSave: (DeviceCalib) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // 每个数组以逗号分隔文本编辑。
    var lRot by remember(c) { mutableStateOf(arr(c.lidarRotQuat)) }
    var lCorr by remember(c) { mutableStateOf(arr(c.lidarCorrQuat)) }
    var lOff by remember(c) { mutableStateOf(arr(c.lidarCorrOffset)) }
    var cRot by remember(c) { mutableStateOf(arr(c.cameraRotQuat)) }
    var cCorr by remember(c) { mutableStateOf(arr(c.cameraCorrQuat)) }
    var cOff by remember(c) { mutableStateOf(arr(c.cameraCorrOffset)) }
    var cIntr by remember(c) { mutableStateOf(arr(c.cameraIntrinsic)) }
    var cDist by remember(c) { mutableStateOf(arr(c.cameraDistortion)) }
    var bQuat by remember(c) { mutableStateOf(arr(c.b2wQuat)) }
    var bOff by remember(c) { mutableStateOf(arr(c.b2wOffset)) }
    var bScale by remember(c) { mutableStateOf(num(c.b2wScale)) }

    SectionCard("标定参数") {
        Row(
            Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (expanded) "收起" else "展开查看 / 编辑（破坏性）", fontSize = 11.sp, color = Gomob.colors.accent)
            Spacer(Modifier.weight(1f))
            Icon(if (expanded) GomobIcons.Minus else GomobIcons.Plus, null, tint = Gomob.colors.accent, modifier = Modifier.size(14.dp))
        }
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            Text("激光 lidar", style = Gomob.type.caption, color = Gomob.colors.fg2)
            NumField("rot_quat [w,x,y,z]", lRot) { lRot = it }
            NumField("corr_quat [w,x,y,z]", lCorr) { lCorr = it }
            NumField("corr_offset [x,y,z] m", lOff) { lOff = it }
            Spacer(Modifier.height(6.dp))
            Text("相机 camera", style = Gomob.type.caption, color = Gomob.colors.fg2)
            NumField("rot_quat [w,x,y,z]", cRot) { cRot = it }
            NumField("corr_quat [w,x,y,z]", cCorr) { cCorr = it }
            NumField("corr_offset [x,y,z] m", cOff) { cOff = it }
            NumField("intrinsic [fx,fy,cx,cy]", cIntr) { cIntr = it }
            NumField("distortion [k1,k2,p1,p2,k3]", cDist) { cDist = it }
            Spacer(Modifier.height(6.dp))
            Text("身体→世界 body2world", style = Gomob.type.caption, color = Gomob.colors.fg2)
            NumField("b2w_quat [w,x,y,z]", bQuat) { bQuat = it }
            NumField("b2w_offset [x,y,z] m", bOff) { bOff = it }
            NumField("b2w_scale", bScale) { bScale = it }
            Spacer(Modifier.height(8.dp))
            SaveButton("覆写标定到设备", danger = true, enabled = !busy) {
                onSave(
                    c.copy(
                        lidarRotQuat = parseArr(lRot, c.lidarRotQuat),
                        lidarCorrQuat = parseArr(lCorr, c.lidarCorrQuat),
                        lidarCorrOffset = parseArr(lOff, c.lidarCorrOffset),
                        cameraRotQuat = parseArr(cRot, c.cameraRotQuat),
                        cameraCorrQuat = parseArr(cCorr, c.cameraCorrQuat),
                        cameraCorrOffset = parseArr(cOff, c.cameraCorrOffset),
                        cameraIntrinsic = parseArr(cIntr, c.cameraIntrinsic),
                        cameraDistortion = parseArr(cDist, c.cameraDistortion),
                        b2wQuat = parseArr(bQuat, c.b2wQuat),
                        b2wOffset = parseArr(bOff, c.b2wOffset),
                        b2wScale = bScale.toDoubleOrNull() ?: c.b2wScale,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DeviceInfoSection(info: DeviceFullInfo) {
    SectionCard("设备信息") {
        KV("型号", info.model)
        KV("序列号", info.sn)
        KV("固件", "${info.swver}  · 硬件 ${info.hwver}")
        KV("网络", "${info.network}  (${info.networkType})")
        KV("激光", "${info.lidarModel}  :${info.lidarPort}  有效角 ${arr(info.lidarValidZone)}")
        KV("相机", "${info.cameraModel}  ${info.cameraWidth}×${info.cameraHeight}  @${fmt2(info.cameraCaptureFps)}fps")
        KV("编码器", "分辨率 ${info.encoderResolution}")
    }
}

// ───── 通用小组件 ─────

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .border(BorderStroke(1.dp, Gomob.colors.line1), Gomob.shapes.r3)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = Gomob.type.numInline.copy(fontSize = 10.sp), color = Gomob.colors.accent)
        content()
    }
}

@Composable
private fun UnitTabs(unit: String, enabled: Boolean, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg2)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        UnitTab("镜头 A · .101", unit == "a", enabled, Modifier.weight(1f)) { onSelect("a") }
        UnitTab("镜头 B · .102", unit == "b", enabled, Modifier.weight(1f)) { onSelect("b") }
    }
}

@Composable
private fun UnitTab(label: String, active: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(Gomob.shapes.r1)
            .background(if (active) Gomob.colors.accentSoft else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, color = if (active) Gomob.colors.accent else Gomob.colors.fg2)
    }
}

@Composable
private fun StateBadge(state: String) {
    val color = when (state) {
        "READY" -> Gomob.colors.ok
        "SCAN", "ALIGN", "BUSY", "WATCH" -> Gomob.colors.accent
        "ERROR" -> Gomob.colors.danger
        else -> Gomob.colors.fg3
    }
    Box(
        Modifier.clip(Gomob.shapes.r1).background(color.copy(alpha = 0.18f))
            .border(BorderStroke(1.dp, color), Gomob.shapes.r1).padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(state.ifBlank { "—" }, fontSize = 11.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun OnlineChip(label: String, online: Boolean) {
    val color = if (online) Gomob.colors.ok else Gomob.colors.danger
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(7.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color))
        Text(label, fontSize = 11.sp, color = Gomob.colors.fg2)
    }
}

@Composable
private fun KV(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(k, fontSize = 11.sp, color = Gomob.colors.fg3, modifier = Modifier.width(72.dp))
        Text(v, fontSize = 11.sp, color = Gomob.colors.fg1, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun CtlButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var clickLocked by remember(label) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (enabled) clickLocked = false }
    val canClick = enabled && !clickLocked
    val tint = when {
        !canClick -> Gomob.colors.fg3
        danger -> Gomob.colors.danger
        else -> Gomob.colors.fg1
    }
    val line = if (danger && canClick) Gomob.colors.danger else Gomob.colors.line2
    Row(
        Modifier
            .height(38.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg2)
            .border(BorderStroke(1.dp, line), Gomob.shapes.r2)
            .clickable(enabled = canClick) {
                clickLocked = true
                onClick()
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(14.dp))
        Text(label, fontSize = 12.sp, color = tint)
    }
}

@Composable
private fun SaveButton(label: String, danger: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    var clickLocked by remember(label) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (enabled) clickLocked = false }
    val canClick = enabled && !clickLocked
    val tint = when {
        !canClick -> Gomob.colors.fg3
        danger -> Gomob.colors.danger
        else -> Gomob.colors.accent
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(Gomob.shapes.r2)
            .background(
                when {
                    !canClick -> Gomob.colors.bg2
                    danger -> Gomob.colors.danger.copy(alpha = 0.12f)
                    else -> Gomob.colors.accentSoft
                },
            )
            .border(BorderStroke(1.dp, tint), Gomob.shapes.r2)
            .clickable(enabled = canClick) {
                clickLocked = true
                onClick()
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(GomobIcons.Check, label, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 13.sp, color = tint, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NumField(label: String, value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, fontSize = 10.sp, color = Gomob.colors.fg3)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            textStyle = TextStyle(color = Gomob.colors.fg0, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(Gomob.colors.accent),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 3.dp)
                .clip(Gomob.shapes.r1)
                .background(Gomob.colors.bg2)
                .border(BorderStroke(1.dp, Gomob.colors.line1), Gomob.shapes.r1)
                .padding(horizontal = 8.dp, vertical = 7.dp),
        )
    }
}

@Composable
private fun IconPill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    cd: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    var clickLocked by remember(cd) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (enabled) clickLocked = false }
    val canClick = enabled && !clickLocked
    Box(
        Modifier
            .size(34.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg2)
            .border(BorderStroke(1.dp, Gomob.colors.line2), Gomob.shapes.r2)
            .clickable(enabled = canClick) {
                clickLocked = true
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, cd, tint = if (canClick) Gomob.colors.fg1 else Gomob.colors.fg3, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ToastBar(msg: String, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.accentSoft)
            .border(BorderStroke(1.dp, Gomob.colors.accentLine), Gomob.shapes.r2)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(msg, fontSize = 11.sp, color = Gomob.colors.accent, modifier = Modifier.weight(1f))
        Icon(GomobIcons.Close, "关闭", tint = Gomob.colors.accent, modifier = Modifier.size(13.dp).clickable(onClick = onClose))
    }
}

@Composable
private fun BusyBar(label: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(color = Gomob.colors.accent, modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Text("$label…", fontSize = 11.sp, color = Gomob.colors.fg2)
    }
}

@Composable
private fun ConfirmDialog(c: PendingConfirm, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(c.title, color = Gomob.colors.fg0) },
        text = { Text(c.body, fontSize = 13.sp, color = Gomob.colors.fg1) },
        confirmButton = {
            Text(
                "确认执行",
                color = Gomob.colors.danger,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable(onClick = c.onConfirm).padding(8.dp),
            )
        },
        dismissButton = {
            Text("取消", color = Gomob.colors.fg2, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
        },
        containerColor = Gomob.colors.bg1,
    )
}

// ───── 数值/数组格式化 ─────

private fun num(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
private fun fmt1(d: Double): String = "%.1f".format(d)
private fun fmt2(d: Double): String = "%.2f".format(d)
private fun arr(l: List<Double>): String = l.joinToString(", ") { num(it) }
private fun parseArr(s: String, fallback: List<Double>): List<Double> {
    val parsed = s.split(",").mapNotNull { it.trim().toDoubleOrNull() }
    return if (parsed.size == fallback.size) parsed else fallback
}
private fun bitList(code: Long): String {
    val bits = (0 until 32).filter { (code shr it) and 1L == 1L }
    return bits.joinToString(",") { it.toString() }
}
