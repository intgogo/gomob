package io.gomob.feature.scan3d

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.data.scan.VehicleMeasurement
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import kotlin.math.roundToInt

const val SCAN_LASER_ROUTE = "scan3d/laser"

/** 主窗口当前展示的点云：融合 / 镜头A / 镜头B。由缩略图切换；融合完成自动切到 FUSED。 */
enum class LaserCloudKind { FUSED, A, B }

/**
 * 激光双单元车辆外廓扫描屏（M8' 瘦客户端，模型查看器范式）：
 * 单主点云窗口（复用一个）+ 缩略图切换行（融合/A/B，采集时实时 2D 预览）+ 状态结果区 + 操作键。
 * [switcher] 为顶栏右上角设备切换段控（由外层 [VehicleContourScanRoute] 注入，激光/相机共用）。
 */
@Composable
fun LaserScanScreen(
    onBack: () -> Unit,
    switcher: @Composable () -> Unit,
    vm: LaserScanViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val fused by vm.fusedCloud.collectAsStateWithLifecycle()
    val unitA by vm.unitACloud.collectAsStateWithLifecycle()
    val unitB by vm.unitBCloud.collectAsStateWithLifecycle()
    val vehicleType by vm.vehicleType.collectAsStateWithLifecycle()
    var showDevice by remember { mutableStateOf(false) }
    // 车位框编辑器：入口已收进设备控制 sheet（一次性标定）。进场预载已存框；完成态有融合云时方可圈选。
    var showCropEditor by remember { mutableStateOf(false) }
    var loadedBox by remember { mutableStateOf<io.gomob.data.scan.ScanCropBox?>(null) }
    var savedHint by remember { mutableStateOf(false) }
    val completed = state as? LaserScanState.Completed
    val canEditCropBox = completed != null && fused.isNotEmpty()
    val hasSavedBox = loadedBox != null || savedHint
    // 持久化车位框（unit_a 世界系，跨会话）进场预载一次，用于设置内显示「已圈选」并作编辑器初值。
    LaunchedEffect(Unit) { loadedBox = vm.loadCropBox() }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = "车辆外廓扫描",
            eyebrow = "激光扫描",
            onBack = onBack,
            // 右上角：激光/相机段控（设备控制已下移到控制栏「设置」键）。
            trailing = { switcher() },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                is LaserScanState.Error -> LaserErrorPanel(msg = s.msg, onRestart = vm::restart)
                else -> LaserCaptureBody(
                    state = s,
                    fused = fused,
                    unitA = unitA,
                    unitB = unitB,
                    onStart = vm::start,
                    onStop = vm::stop,
                    onRestart = vm::restart,
                    vehicleType = vehicleType,
                    onSelectVehicleType = vm::selectVehicleType,
                    onOpenSettings = { showDevice = true },
                )
            }
        }
    }
    if (showDevice) {
        LaserDeviceControlSheet(
            onDismiss = { showDevice = false },
            canEditCropBox = canEditCropBox,
            cropBoxSaved = hasSavedBox,
            // 一次性标定：关设置 → 开顶视编辑器（编辑器仍以当前融合云为底图）。
            onEditCropBox = {
                showDevice = false
                showCropEditor = true
            },
        )
    }
    // 全屏车位框编辑器（叠在最上层）。
    if (showCropEditor && fused.isNotEmpty()) {
        val ground = completed?.ground
        Box(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
            LaserCropBoxEditor(
                cloud = fused,
                groundNormal = if (ground != null && ground.valid) floatArrayOf(ground.nx, ground.ny, ground.nz) else null,
                initialBox = loadedBox,
                onPreview = vm::cropPreview,
                onSave = { box ->
                    vm.saveCropBox(box) { ok -> savedHint = ok; if (ok) loadedBox = box }
                    showCropEditor = false
                },
                onDismiss = { showCropEditor = false },
            )
        }
    }
    }
}

@Composable
private fun LaserCaptureBody(
    state: LaserScanState,
    fused: FloatArray,
    unitA: FloatArray,
    unitB: FloatArray,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    vehicleType: io.gomob.data.scan.VehicleType,
    onSelectVehicleType: (io.gomob.data.scan.VehicleType) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // 融合模型视角预设（完成后生效，相对检测到的地面"上"方向）。新扫描重置为自由家位。
    var viewPreset by remember { mutableStateOf(LaserViewPreset.FREE) }
    // 视角重置信号：每次「重置」自增一次，PointCloud3dView 据此回到当前预设家位 + 清平移。
    var resetSignal by remember { mutableStateOf(0) }
    // 主窗口当前展示哪朵云（缩略图切换）。
    var selected by remember { mutableStateOf(LaserCloudKind.FUSED) }

    val completed = state as? LaserScanState.Completed
    val ground = completed?.ground

    // 状态驱动自动取景：起扫且融合尚空→看镜头 A 实时；完成→自动切融合；回 Idle 复位。
    // 用户随后手动点缩略图的选择会保留（同一 state 实例不再触发本效应）。
    LaunchedEffect(state) {
        when (state) {
            LaserScanState.Scanning -> if (fused.isEmpty()) selected = LaserCloudKind.A
            is LaserScanState.Completed -> selected = LaserCloudKind.FUSED
            LaserScanState.Idle -> { selected = LaserCloudKind.FUSED; viewPreset = LaserViewPreset.FREE }
            else -> Unit
        }
    }
    LaunchedEffect(state is LaserScanState.Completed) {
        if (state !is LaserScanState.Completed) viewPreset = LaserViewPreset.FREE
    }

    val selectedCloud = when (selected) {
        LaserCloudKind.FUSED -> fused
        LaserCloudKind.A -> unitA
        LaserCloudKind.B -> unitB
    }
    val isFused = selected == LaserCloudKind.FUSED
    val showGround = isFused && ground != null && ground.valid
    // 上方向：融合且地面有效→地面法向（把设备世界系竖直倾斜摆正）；否则激光设备系 +Z 近似竖直。
    val viewUp = if (showGround) floatArrayOf(ground!!.nx, ground.ny, ground.nz) else floatArrayOf(0f, 0f, 1f)
    val title = when (selected) {
        LaserCloudKind.FUSED -> "融合点云"
        LaserCloudKind.A -> "镜头 A · .101"
        LaserCloudKind.B -> "镜头 B · .102"
    }
    val accent = if (selected == LaserCloudKind.B) Gomob.colors.ok else Gomob.colors.accent
    val emptyHint = when (selected) {
        LaserCloudKind.FUSED -> when (state) {
            is LaserScanState.Completed -> "无融合点"
            LaserScanState.Scanning, LaserScanState.Processing -> "采集完成后在此显示融合外廓"
            else -> "开始扫描以采集车辆外廓"
        }
        LaserCloudKind.A -> "等待 A 点云…"
        LaserCloudKind.B -> "等待 B 点云…"
    }

    Column(Modifier.fillMaxSize()) {
        // 主点云窗口（复用一个，按缩略图切换内容）。占满剩余高度。
        // autoFit=true：每朵云坐标量级/中心不同，按包围球自动取景，避免固定取景出锥全黑。
        Box(Modifier.weight(1f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)) {
            LaserCloudPanel(
                title = title, accent = accent, cloud = selectedCloud, emptyHint = emptyHint, state = state,
                autoFit = true, modifier = Modifier.fillMaxSize(),
                upAxis = viewUp, viewPreset = if (isFused) viewPreset else LaserViewPreset.FREE,
                showGround = showGround, groundD = ground?.d ?: 0f, resetSignal = resetSignal,
                autoFitKey = selected, // 切换显示的云(融合/A/B)时重拟合；同一云增量生长时不变→不冲掉手动视角
            )
            // 视角段控仅对融合模型（含地面摆正）有意义，完成后叠右上。
            if (completed != null && isFused) {
                ViewPresetBar(
                    current = viewPreset, onSelect = { viewPreset = it }, onReset = { resetSignal++ },
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
                )
            }
        }
        // 缩略图切换行（融合/A/B，2D 散点轻量预览；采集时实时长，点击切主窗口）。
        CloudSwitcherRow(
            selected = selected, fused = fused, unitA = unitA, unitB = unitB,
            onSelect = { selected = it }, modifier = Modifier.padding(horizontal = 16.dp),
        )
        // 状态 & 结果区（状态行 + 完成后计数 + 测量卡/不可用）。车位框入口已移入设备控制 sheet。
        LaserStatusPanel(
            state = state, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
        )
        LaserControlBar(
            state = state, onStart = onStart, onStop = onStop, onRestart = onRestart,
            vehicleType = vehicleType, onSelectVehicleType = onSelectVehicleType, onOpenSettings = onOpenSettings,
        )
    }
}

/** 缩略图切换行：融合 / 镜头A / 镜头B 三个 2D 散点缩略图，选中高亮，点击切主窗口。 */
@Composable
private fun CloudSwitcherRow(
    selected: LaserCloudKind,
    fused: FloatArray,
    unitA: FloatArray,
    unitB: FloatArray,
    onSelect: (LaserCloudKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CloudThumbnail("融合", fused, selected == LaserCloudKind.FUSED, Gomob.colors.accent,
            { onSelect(LaserCloudKind.FUSED) }, Modifier.weight(1f))
        CloudThumbnail("镜头 A", unitA, selected == LaserCloudKind.A, Gomob.colors.accent,
            { onSelect(LaserCloudKind.A) }, Modifier.weight(1f))
        CloudThumbnail("镜头 B", unitB, selected == LaserCloudKind.B, Gomob.colors.ok,
            { onSelect(LaserCloudKind.B) }, Modifier.weight(1f))
    }
}

/**
 * 单个点云缩略图：正交投影 2D 散点（Canvas 轻量画，不开 Filament 引擎），下方标签 + 点数。
 * 选中态描边高亮。空云显示占位符。
 */
@Composable
private fun CloudThumbnail(
    label: String,
    cloud: FloatArray,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pts = remember(cloud) { project2D(cloud) }
    val count = cloud.size / 3
    Column(modifier = modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(54.dp)
                .clip(Gomob.shapes.r2)
                .background(Color(0xFF0A0E18))
                .border(
                    BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) accent else Gomob.colors.line2),
                    Gomob.shapes.r2,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (pts.isEmpty()) {
                Text("—", fontSize = 16.sp, color = Gomob.colors.fg3)
            } else {
                Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                    val offs = ArrayList<Offset>(pts.size / 2)
                    var i = 0
                    while (i < pts.size) {
                        offs.add(Offset(pts[i] * size.width, pts[i + 1] * size.height)); i += 2
                    }
                    drawPoints(offs, PointMode.Points, accent, strokeWidth = 2.5f, cap = StrokeCap.Round)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (selected) accent else Gomob.colors.fg3))
            Text(label, fontSize = 10.sp, color = if (selected) accent else Gomob.colors.fg2)
            Text("· $count", fontSize = 9.sp, color = Gomob.colors.fg3)
        }
    }
}

/**
 * 结果区（缩略图下方）：仅在完成态渲染描边卡（计数摘要 + 测量卡/不可用）。
 * 非完成态不渲染——状态已上移到点云窗口角标（[CloudStatusBadge]），竖向空间全让给点云主窗。
 * 车位框圈选入口已移入设备控制 sheet（[LaserDeviceControlSheet] 的「测量范围」节），一次性标定不占主流程。
 */
@Composable
private fun LaserStatusPanel(
    state: LaserScanState,
    modifier: Modifier = Modifier,
) {
    val s = state as? LaserScanState.Completed ?: return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .border(BorderStroke(1.dp, Gomob.colors.line2), Gomob.shapes.r3)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 计数摘要：小字次要信息（测量数字才是主角）。
        Text(
            "融合 ${s.points} 点 · A ${s.ptsA} / B ${s.ptsB} · ${s.alignMethod}",
            style = Gomob.type.numInline.copy(fontSize = 9.sp), color = Gomob.colors.fg2,
        )
        if (s.measurement.valid) {
            MeasurementCard(s.measurement)
        } else {
            Text(
                "测量不可用（点云不足 / 超出量程）",
                style = Gomob.type.numInline.copy(fontSize = 11.sp), color = Gomob.colors.fg3,
            )
        }
    }
}

/**
 * 正交投影点云→2D 归一化散点 [x0,y0,x1,y1,...] ∈ [0,1]，取 range 最大的两轴成像，纵轴翻转适配屏幕。
 * stride 抽样到 ~maxPts 控开销；跳过爆表垃圾点（与 PointCloud3dView/handlePts 阈值一致）。供缩略图轻量预览。
 */
private fun project2D(cloud: FloatArray, maxPts: Int = 1500): FloatArray {
    val total = cloud.size / 3
    if (total == 0) return FloatArray(0)
    val stride = if (total > maxPts) (total + maxPts - 1) / maxPts else 1
    var x0 = Float.POSITIVE_INFINITY; var x1 = Float.NEGATIVE_INFINITY
    var y0 = Float.POSITIVE_INFINITY; var y1 = Float.NEGATIVE_INFINITY
    var z0 = Float.POSITIVE_INFINITY; var z1 = Float.NEGATIVE_INFINITY
    var kept = 0
    var i = 0
    while (i < total) {
        val b = i * 3
        val x = cloud[b]; val y = cloud[b + 1]; val z = cloud[b + 2]
        if (sane(x) && sane(y) && sane(z)) {
            if (x < x0) x0 = x; if (x > x1) x1 = x
            if (y < y0) y0 = y; if (y > y1) y1 = y
            if (z < z0) z0 = z; if (z > z1) z1 = z
            kept++
        }
        i += stride
    }
    if (kept == 0) return FloatArray(0)
    val ranges = floatArrayOf(x1 - x0, y1 - y0, z1 - z0)
    val mins = floatArrayOf(x0, y0, z0)
    // 按 range 降序挑两轴：a0=横，a1=纵。
    val order = intArrayOf(0, 1, 2)
    for (p in 0..1) for (q in p + 1..2) {
        if (ranges[order[q]] > ranges[order[p]]) { val t = order[p]; order[p] = order[q]; order[q] = t }
    }
    val a0 = order[0]; val a1 = order[1]
    val ra = ranges[a0].coerceAtLeast(1e-3f); val rb = ranges[a1].coerceAtLeast(1e-3f)
    val out = FloatArray(kept * 2)
    var w = 0
    i = 0
    while (i < total) {
        val b = i * 3
        val x = cloud[b]; val y = cloud[b + 1]; val z = cloud[b + 2]
        if (sane(x) && sane(y) && sane(z)) {
            val ca = cloud[b + a0]; val cb = cloud[b + a1]
            out[w] = (ca - mins[a0]) / ra
            out[w + 1] = 1f - (cb - mins[a1]) / rb // 纵轴翻转：屏幕 y 向下
            w += 2
        }
        i += stride
    }
    return out
}

/** 坐标合理性（有限且 |v|≤50m mm），与 PointCloud3dView.isSane / 服务端 handlePts 阈值一致。 */
private fun sane(v: Float): Boolean = v.isFinite() && kotlin.math.abs(v) <= 50_000f

@Composable
private fun LaserCloudPanel(
    title: String,
    accent: Color,
    cloud: FloatArray,
    emptyHint: String,
    state: LaserScanState,
    modifier: Modifier = Modifier,
    autoFit: Boolean = false,
    upAxis: FloatArray = floatArrayOf(0f, 1f, 0f),
    viewPreset: LaserViewPreset = LaserViewPreset.FREE,
    showGround: Boolean = false,
    groundD: Float = 0f,
    resetSignal: Int = 0,
    autoFitKey: Any? = null,
) {
    // 车辆尺度点云在数千 mm 量级；按点云均值 z 居中以改善取景（按 cloud 实例 memo，避免每帧 O(n)）。
    val centerZ = remember(cloud) { meanZ(cloud) }
    Box(
        modifier = modifier
            .clip(Gomob.shapes.r3)
            .background(Color(0xFF060912)),
    ) {
        if (cloud.isNotEmpty()) {
            PointCloud3dView(
                points = cloud, modifier = Modifier.fillMaxSize(), gridCenterZmm = centerZ,
                autoFit = autoFit, upAxis = upAxis, viewPreset = viewPreset,
                showGround = showGround, groundD = groundD, resetSignal = resetSignal,
                autoFitKey = autoFitKey,
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    emptyHint,
                    style = Gomob.type.numInline.copy(fontSize = 11.sp),
                    color = Gomob.colors.fg3,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Column(
            modifier = Modifier.align(Alignment.TopStart).padding(start = 10.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.1.em), color = accent)
            Text(
                "${cloud.size / 3} 点",
                style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.08.em),
                color = Gomob.colors.fg2,
            )
            // 状态上移到角标（替代原底部常驻 StatusLine，省一整行竖向预算）。
            CloudStatusBadge(state)
        }
    }
}

/** 点云窗口左上角状态徽标：状态点/spinner + 短状态词（采集中/连接中/融合中/完成/就绪/错误）。 */
@Composable
private fun CloudStatusBadge(state: LaserScanState) {
    val (word, color, spinner) = when (state) {
        LaserScanState.Idle -> Triple("就绪", Gomob.colors.fg3, false)
        LaserScanState.Connecting -> Triple("连接中", Gomob.colors.accent, true)
        LaserScanState.Scanning -> Triple("采集中", Gomob.colors.danger, false)
        LaserScanState.Processing -> Triple("融合中", Gomob.colors.accent, true)
        is LaserScanState.Completed -> Triple("完成", Gomob.colors.ok, false)
        is LaserScanState.Error -> Triple("错误", Gomob.colors.danger, false)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (spinner) {
            CircularProgressIndicator(color = color, modifier = Modifier.size(9.dp), strokeWidth = 1.5.dp)
        } else {
            Box(Modifier.size(5.dp).clip(CircleShape).background(color))
        }
        Text(word, style = Gomob.type.numInline.copy(fontSize = 9.sp), color = color)
    }
}

/**
 * 测量卡片（M9.6）：完成后在状态结果区显示车长/车宽/车高（米）+ GB7258-2017 外廓合规结论。
 * 数据来自服务端 measure.go（融合后对 fused 云算 OBB + Z 跨度），经 scan.fusion_done 事件推来（mm）。
 * 容器装饰交由外层 [LaserStatusPanel]，本卡只排版内容。
 */
@Composable
private fun MeasurementCard(m: VehicleMeasurement, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            DimChip("车长", m.lengthMm)
            DimChip("车宽", m.widthMm)
            DimChip("车高", m.heightMm)
        }
        ComplianceBadge(m.compliant, m.violations)
    }
}

/** 单个尺寸：标题 + 大号米值 + 小号 mm（车辆尺度 mm 量级，米更直观；米值仍是全屏最大数字）。 */
@Composable
private fun DimChip(label: String, mm: Float) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Text(label, fontSize = 9.sp, color = Gomob.colors.fg3)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "%.2f".format(mm / 1000f),
                style = Gomob.type.numInline.copy(fontSize = 17.sp),
                color = Gomob.colors.fg1,
            )
            Text("m", fontSize = 10.sp, color = Gomob.colors.fg3, modifier = Modifier.padding(bottom = 2.dp))
        }
        Text("${mm.roundToInt()} mm", fontSize = 9.sp, color = Gomob.colors.fg2)
    }
}

/** 合规徽章：绿=符合 / 红=超限并列出违规项（GB7258-2017 §4.15 外廓限值）。 */
@Composable
private fun ComplianceBadge(compliant: Boolean, violations: List<String>) {
    val color = if (compliant) Gomob.colors.ok else Gomob.colors.danger
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r2)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(
            if (compliant) "符合 GB7258-2017 外廓限值" else "超限：" + violations.joinToString("、"),
            fontSize = 11.sp,
            color = color,
        )
    }
}

/** 视角预设段控（顶/侧/斜/自由）+ 重置：完成后叠在融合模型右上角，一键切机位 / 复位取景。 */
@Composable
private fun ViewPresetBar(
    current: LaserViewPreset,
    onSelect: (LaserViewPreset) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(Gomob.shapes.r2)
            .background(Color(0xCC0B1220))
            .border(BorderStroke(1.dp, Gomob.colors.line2), Gomob.shapes.r2)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ViewPresetChip("顶", LaserViewPreset.TOP, current, onSelect)
        ViewPresetChip("侧", LaserViewPreset.SIDE, current, onSelect)
        ViewPresetChip("斜", LaserViewPreset.OBLIQUE, current, onSelect)
        ViewPresetChip("自由", LaserViewPreset.FREE, current, onSelect)
        // 分隔线 + 重置（动作非选项，不高亮）。
        Box(Modifier.size(1.dp, 16.dp).background(Gomob.colors.line2))
        Box(
            Modifier
                .clip(Gomob.shapes.r2)
                .clickable { onReset() }
                .padding(horizontal = 11.dp, vertical = 5.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("重置", fontSize = 11.sp, color = Gomob.colors.fg2)
        }
    }
}

@Composable
private fun ViewPresetChip(
    label: String,
    preset: LaserViewPreset,
    current: LaserViewPreset,
    onSelect: (LaserViewPreset) -> Unit,
) {
    val sel = preset == current
    Box(
        Modifier
            .clip(Gomob.shapes.r2)
            .background(if (sel) Gomob.colors.accentSoft else Color.Transparent)
            .clickable { onSelect(preset) }
            .padding(horizontal = 11.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, color = if (sel) Gomob.colors.accent else Gomob.colors.fg2)
    }
}

@Composable
private fun LaserControlBar(
    state: LaserScanState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    vehicleType: io.gomob.data.scan.VehicleType,
    onSelectVehicleType: (io.gomob.data.scan.VehicleType) -> Unit,
    onOpenSettings: () -> Unit,
) {
    // 三键：左=车型下拉（逆向 JCHY 26 型，随扫描下发）｜中=主控（开始/停止/融合中/重新扫描）｜右=设置（设备控制）。
    // 退出走标题栏返回箭头；计数/结果在 LaserStatusPanel。
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VehicleTypeDropdown(current = vehicleType, onSelect = onSelectVehicleType)
            Box(contentAlignment = Alignment.Center) {
                when (state) {
                    LaserScanState.Idle -> PillButton("开始扫描", primary = true, onClick = onStart)
                    LaserScanState.Connecting -> PillStatus("连接设备中…", spinner = true)
                    LaserScanState.Scanning -> Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LiveDot()
                        PillButton("停止扫描", GomobIcons.Refresh, primary = false, danger = true, onClick = onStop)
                    }
                    LaserScanState.Processing -> PillStatus("云端融合中…", spinner = true)
                    is LaserScanState.Completed -> PillButton("重新扫描", GomobIcons.Refresh, primary = true, onClick = onRestart)
                    is LaserScanState.Error -> PillButton("重新扫描", GomobIcons.Refresh, primary = true, onClick = onRestart)
                }
            }
            RoundIconButton(GomobIcons.Settings, "设备控制", onClick = onOpenSettings)
        }
    }
}

/**
 * 车型下拉选（控制栏左槽）：46dp 全圆角 pill 芯片显示当前车型名 + 下拉箭头，点开按货车/挂车分组列出
 * 逆向 JCHY 26 型。选中随扫描下发服务端（carType 偏移 + 按型合规 + 记录）。与中间动作 pill、右侧设置
 * 圆钮同高同圆角，左右中性、中间 accent —— 统一视觉层级。
 */
@Composable
private fun VehicleTypeDropdown(
    current: io.gomob.data.scan.VehicleType,
    onSelect: (io.gomob.data.scan.VehicleType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .height(46.dp)
                .widthIn(min = 88.dp, max = 132.dp)
                .clip(CircleShape)
                .background(Gomob.colors.bg1)
                .border(BorderStroke(1.dp, Gomob.colors.line2), CircleShape)
                .clickable { expanded = true }
                .padding(start = 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                current.name, fontSize = 14.sp, color = Gomob.colors.fg1, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            Icon(GomobIcons.ChevronRight, "选择车型", tint = Gomob.colors.fg3,
                modifier = Modifier.size(15.dp).rotate(90f))
        }
        DropdownMenu(
            expanded = expanded, onDismissRequest = { expanded = false },
            modifier = Modifier.background(Gomob.colors.bg1).heightIn(max = 360.dp),
        ) {
            VehicleGroupLabel("货车")
            io.gomob.data.scan.VehicleTypeCatalog.all
                .filter { it.group == io.gomob.data.scan.VehicleGroup.TRUCK }
                .forEach { VehicleTypeMenuItem(it, current) { onSelect(it); expanded = false } }
            VehicleGroupLabel("挂车")
            io.gomob.data.scan.VehicleTypeCatalog.all
                .filter { it.group == io.gomob.data.scan.VehicleGroup.TRAILER }
                .forEach { VehicleTypeMenuItem(it, current) { onSelect(it); expanded = false } }
        }
    }
}

/** 控制栏右槽设置圆钮：46dp 圆形图标键（无文字标签），与车型 pill / 动作 pill 同高，中性描边。 */
@Composable
private fun RoundIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(Gomob.colors.bg1)
            .border(BorderStroke(1.dp, Gomob.colors.line2), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = Gomob.colors.fg1, modifier = Modifier.size(19.dp))
    }
}

@Composable
private fun VehicleGroupLabel(text: String) {
    Text(
        text, fontSize = 10.sp, color = Gomob.colors.fg3,
        modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
    )
}

@Composable
private fun VehicleTypeMenuItem(
    t: io.gomob.data.scan.VehicleType,
    current: io.gomob.data.scan.VehicleType,
    onClick: () -> Unit,
) {
    val sel = t.id == current.id
    androidx.compose.material3.DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(t.name, fontSize = 13.sp, color = if (sel) Gomob.colors.accent else Gomob.colors.fg1)
                if (t.tank) Text("罐", fontSize = 9.sp, color = Gomob.colors.fg3)
                if (t.crane) Text("吊", fontSize = 9.sp, color = Gomob.colors.fg3)
            }
        },
        onClick = onClick,
        trailingIcon = if (sel) {
            { Icon(GomobIcons.Check, "已选", tint = Gomob.colors.accent, modifier = Modifier.size(14.dp)) }
        } else null,
    )
}

@Composable
private fun PillButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    primary: Boolean,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = when {
        danger -> Gomob.colors.danger
        primary -> Gomob.colors.accent
        else -> Gomob.colors.fg1
    }
    val line = when {
        danger -> Gomob.colors.danger
        primary -> Gomob.colors.accent
        else -> Gomob.colors.line2
    }
    Row(
        modifier = Modifier
            .height(46.dp)
            .clip(CircleShape)
            .background(if (primary) Gomob.colors.accentSoft else Gomob.colors.bg1)
            .border(BorderStroke(1.dp, line), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, fontSize = 14.sp, color = tint)
    }
}

@Composable
private fun PillStatus(label: String, spinner: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (spinner) CircularProgressIndicator(color = Gomob.colors.accent, modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        Text(label, style = Gomob.type.numInline.copy(fontSize = 13.sp), color = Gomob.colors.fg1)
    }
}

@Composable
private fun LiveDot() {
    Box(
        Modifier.size(10.dp).clip(CircleShape).background(Gomob.colors.danger),
    )
}

@Composable
private fun LaserErrorPanel(msg: String, onRestart: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(msg, style = Gomob.type.numInline.copy(fontSize = 13.sp), color = Gomob.colors.danger, textAlign = TextAlign.Center)
            PillButton("重新扫描", GomobIcons.Refresh, primary = true, onClick = onRestart)
        }
    }
}

/** 点云均值 z（mm）。空云回退 750（与 PointCloud3dView 默认一致）。 */
private fun meanZ(cloud: FloatArray): Float {
    if (cloud.size < 3) return 750f
    var sum = 0.0
    var i = 2
    var count = 0
    while (i < cloud.size) {
        sum += cloud[i]
        count++
        i += 3
    }
    return if (count == 0) 750f else (sum / count).toFloat()
}
