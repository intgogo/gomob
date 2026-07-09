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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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
import io.gomob.data.scan.LaserCloudRenderData
import io.gomob.data.scan.VehicleMeasurement
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import kotlin.math.roundToInt

const val SCAN_LASER_ROUTE = "scan3d/laser"

/** 主窗口当前展示的点云：融合 / 镜头A / 镜头B。由缩略图切换；融合完成自动切到 FUSED。 */
enum class LaserCloudKind { FUSED, A, B }

/**
 * 3D 工位（激光双单元）车辆外廓扫描屏 —— 操作员范式（配置全在网页端管理端）：
 * 单主点云窗口（复用一个）+ 缩略图切换行（融合/A/B，采集时实时 2D 预览）+ 完成态外廓尺寸结果卡 + 主控键。
 * 端侧只做：选设备 → 开始/停止 → 看长宽高结果；不再有车型下拉、设备控制/设置、测量范围(车位框)标定入口。
 * [switcher] 为顶栏右上角设备下拉选（由外层 [VehicleContourScanRoute] 注入，工位/相机共用）。
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

    // 玻璃 header 骨架；整页非滚动布局（点云主窗 weight 占满），内容整体避让不穿越（规则 3）。
    GlassHeaderScaffold(
        header = {
            BackHeader(
                title = "车辆外廓扫描",
                eyebrow = "3D 工位",
                onBack = onBack,
                // 右上角：设备下拉选（3D 工位 / 3D 相机）。
                trailing = { switcher() },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
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
                )
            }
        }
    }
}

@Composable
private fun LaserCaptureBody(
    state: LaserScanState,
    fused: LaserCloudRenderData,
    unitA: LaserCloudRenderData,
    unitB: LaserCloudRenderData,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    // 视角预设（顶/侧/斜/自由，相对各云的"上"方向）。A/B/融合三窗同款；新扫描重置为自由家位。
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
            LaserScanState.Scanning -> if (fused.xyz.isEmpty()) selected = LaserCloudKind.A
            is LaserScanState.Completed -> selected = LaserCloudKind.FUSED
            LaserScanState.Idle -> { selected = LaserCloudKind.FUSED; viewPreset = LaserViewPreset.FREE }
            else -> Unit
        }
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
                upAxis = viewUp, viewPreset = viewPreset,
                showGround = showGround, groundD = ground?.d ?: 0f, resetSignal = resetSignal,
                autoFitKey = selected, // 切换显示的云(融合/A/B)时重拟合；同一云增量生长时不变→不冲掉手动视角
            )
            // 视角段控：A/B/融合三窗同款，凡当前云非空即叠右上一键切机位/复位。
            if (selectedCloud.xyz.isNotEmpty()) {
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
        // 完成态外廓尺寸结果卡（放大展示车长/车宽/车高）。非完成态不渲染，竖向空间全让给点云主窗。
        LaserResultPanel(
            state = state, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
        )
        LaserControlBar(state = state, onStart = onStart, onStop = onStop, onRestart = onRestart)
    }
}

/** 缩略图切换行：融合 / 镜头A / 镜头B 三个 2D 散点缩略图，选中高亮，点击切主窗口。 */
@Composable
private fun CloudSwitcherRow(
    selected: LaserCloudKind,
    fused: LaserCloudRenderData,
    unitA: LaserCloudRenderData,
    unitB: LaserCloudRenderData,
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
    cloud: LaserCloudRenderData,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pts = remember(cloud) { project2D(cloud.xyz) }
    val count = cloud.pointCount
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
 * 完成态外廓尺寸结果卡（缩略图下方）：放大展示车长/车宽/车高（米 + mm），是全屏最醒目内容。
 * 数据来自服务端 measure.go（融合后对 fused 云算 OBB + Z 跨度），经 scan.fusion_done 事件推来（mm）。
 * 只算长宽高（纯几何，与车型无关）；不再显示按车型合规结论（车型/合规已移到网页端）。
 * 非完成态不渲染——状态在点云窗口左上角标（[CloudStatusBadge]），竖向空间全让给点云主窗。
 */
@Composable
private fun LaserResultPanel(
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "外廓尺寸",
            style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.14.em),
            color = Gomob.colors.accent,
        )
        if (s.measurement.valid) {
            MeasurementResult(s.measurement)
        } else {
            Text(
                "测量不可用（点云不足 / 超出量程）",
                style = Gomob.type.numInline.copy(fontSize = 12.sp), color = Gomob.colors.fg3,
            )
        }
        // 次要信息：点数摘要（小字，尺寸数字才是主角）。
        Text(
            "融合 ${s.points} 点 · A ${s.ptsA} / B ${s.ptsB} · ${s.alignMethod}",
            style = Gomob.type.numInline.copy(fontSize = 9.sp), color = Gomob.colors.fg2,
        )
        s.pointIntegrityWarning?.let {
            Text(
                "点云完整性告警：$it",
                style = Gomob.type.numInline.copy(fontSize = 10.sp), color = Gomob.colors.danger,
            )
        }
    }
}

/** 外廓三尺寸并排放大展示（车长/车宽/车高），中间用细分隔线区隔。 */
@Composable
private fun MeasurementResult(m: VehicleMeasurement, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BigDim("车长", m.lengthMm, Modifier.weight(1f))
        DimDivider()
        BigDim("车宽", m.widthMm, Modifier.weight(1f))
        DimDivider()
        BigDim("车高", m.heightMm, Modifier.weight(1f))
    }
}

/** 单个尺寸：标题 + 大号米值（全屏最大数字）+ 小号 mm。 */
@Composable
private fun BigDim(label: String, mm: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(label, fontSize = 11.sp, color = Gomob.colors.fg3)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                "%.2f".format(mm / 1000f),
                style = Gomob.type.numInline.copy(fontSize = 26.sp),
                color = Gomob.colors.fg0,
                maxLines = 1,
            )
            Text("m", fontSize = 12.sp, color = Gomob.colors.fg3, modifier = Modifier.padding(bottom = 3.dp))
        }
        Text("${mm.roundToInt()} mm", fontSize = 10.sp, color = Gomob.colors.fg2)
    }
}

/** 尺寸之间的细竖分隔线。 */
@Composable
private fun DimDivider() {
    Box(Modifier.size(1.dp, 40.dp).background(Gomob.colors.line2))
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
    cloud: LaserCloudRenderData,
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
    val centerZ = remember(cloud) { meanZ(cloud.xyz) }
    Box(
        modifier = modifier
            .clip(Gomob.shapes.r3)
            .background(Color(0xFF060912)),
    ) {
        if (cloud.xyz.isNotEmpty()) {
            PointCloud3dView(
                points = cloud.xyz, colors = cloud.rgb,
                modifier = Modifier.fillMaxSize(), gridCenterZmm = centerZ,
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
                "${cloud.pointCount} 点",
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

/** 视角预设段控（顶/侧/斜/自由）+ 重置：叠在点云窗口右上角，一键切机位 / 复位取景。 */
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

/**
 * 主控栏（单键居中）：开始/停止/融合中/重新扫描。退出走标题栏返回箭头；结果在 [LaserResultPanel]。
 * 车型下拉与设备控制/设置已移除——工位配置全在网页端管理端，App 只负责操作。
 */
@Composable
private fun LaserControlBar(
    state: LaserScanState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            LaserScanState.Idle -> PillButton("开始扫描", primary = true, onClick = onStart)
            LaserScanState.Connecting -> PillStatus("连接设备中…", spinner = true)
            // 与「开始扫描」同款单 pill（仅 danger 色）；采集中指示已在点云窗口左上「采集中」徽标。
            LaserScanState.Scanning -> PillButton("停止扫描", primary = false, danger = true, onClick = onStop)
            LaserScanState.Processing -> PillStatus("云端融合中…", spinner = true)
            is LaserScanState.Completed -> PillButton("重新扫描", GomobIcons.Refresh, primary = true, onClick = onRestart)
            is LaserScanState.Error -> PillButton("重新扫描", GomobIcons.Refresh, primary = true, onClick = onRestart)
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    primary: Boolean,
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
        primary -> Gomob.colors.accent
        else -> Gomob.colors.fg1
    }
    val line = when {
        !canClick -> Gomob.colors.line2
        danger -> Gomob.colors.danger
        primary -> Gomob.colors.accent
        else -> Gomob.colors.line2
    }
    Row(
        modifier = Modifier
            .height(46.dp)
            .clip(CircleShape)
            .background(if (primary && canClick) Gomob.colors.accentSoft else Gomob.colors.bg1)
            .border(BorderStroke(1.dp, line), CircleShape)
            .clickable(enabled = canClick) {
                clickLocked = true
                onClick()
            }
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
