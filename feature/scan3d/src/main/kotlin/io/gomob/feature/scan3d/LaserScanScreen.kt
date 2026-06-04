package io.gomob.feature.scan3d

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

const val SCAN_LASER_ROUTE = "scan3d/laser"

/**
 * 激光双单元车辆外廓扫描屏（M8' 瘦客户端）：融合点云（上）+ 两镜头点云（下）+ 操作键。
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
    var showDevice by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = "车辆外廓扫描",
            eyebrow = "激光扫描",
            onBack = onBack,
            // 右上角：设备控制（原厂功能键）+ 激光/相机段控。
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    DeviceCtlButton(onClick = { showDevice = true })
                    switcher()
                }
            },
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
                    onUndo = vm::undo,
                    onFinish = onBack,
                )
            }
        }
    }
    if (showDevice) {
        LaserDeviceControlSheet(onDismiss = { showDevice = false })
    }
}

/** 顶栏「设备控制」入口键（齿轮图标）。 */
@Composable
private fun DeviceCtlButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1)
            .border(BorderStroke(1.dp, Gomob.colors.line2), Gomob.shapes.r2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(GomobIcons.Settings, "设备控制", tint = Gomob.colors.fg1, modifier = Modifier.size(17.dp))
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
    onUndo: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        // 融合点云（上 ~60%）。完成前为空，提示在采集/融合。
        // autoFit=true：融合整云是一次性结果，坐标 X/Y 可能远离原点、Z 跨数十米（双单元配准后），
        // 需按包围球自动取景，否则固定取景会整云出锥全黑。
        LaserCloudPanel(
            title = "融合点云",
            accent = Gomob.colors.accent,
            cloud = fused,
            emptyHint = when (state) {
                is LaserScanState.Completed -> "无融合点"
                LaserScanState.Scanning, LaserScanState.Processing -> "采集完成后在此显示融合外廓"
                else -> "开始扫描以采集车辆外廓"
            },
            autoFit = true,
            modifier = Modifier.weight(0.58f).fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        )
        // 两镜头各自点云（下 ~40%）。autoFit=true：把真机实时扫描点云直接渲染出来，相机随点云
        // 生长自动取景，确保整片真实点都在视野内（不做融合/变换处理，纯原始点直渲）。
        Row(
            modifier = Modifier.weight(0.42f).fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LaserCloudPanel("镜头 A · .101", Gomob.colors.accent, unitA, "等待 A 点云…", Modifier.weight(1f).fillMaxSize(), autoFit = true)
            LaserCloudPanel("镜头 B · .102", Gomob.colors.ok, unitB, "等待 B 点云…", Modifier.weight(1f).fillMaxSize(), autoFit = true)
        }
        LaserControlBar(
            state = state,
            onStart = onStart,
            onStop = onStop,
            onRestart = onRestart,
            onUndo = onUndo,
            onFinish = onFinish,
        )
    }
}

@Composable
private fun LaserCloudPanel(
    title: String,
    accent: Color,
    cloud: FloatArray,
    emptyHint: String,
    modifier: Modifier = Modifier,
    autoFit: Boolean = false,
) {
    // 车辆尺度点云在数千 mm 量级；按点云均值 z 居中以改善取景（按 cloud 实例 memo，避免每帧 O(n)）。
    val centerZ = remember(cloud) { meanZ(cloud) }
    Box(
        modifier = modifier
            .clip(Gomob.shapes.r3)
            .background(Color(0xFF060912)),
    ) {
        if (cloud.isNotEmpty()) {
            PointCloud3dView(points = cloud, modifier = Modifier.fillMaxSize(), gridCenterZmm = centerZ, autoFit = autoFit)
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
        }
    }
}

@Composable
private fun LaserControlBar(
    state: LaserScanState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onUndo: () -> Unit,
    onFinish: () -> Unit,
) {
    // 相机式三键：左=撤销（清空+镜头归零）｜中=主控（开始/停止/融合中/重新扫描）｜右=完成。
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 10.dp, end = 20.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state is LaserScanState.Completed) {
            Text(
                "融合 ${state.points} 点 · A ${state.ptsA} / B ${state.ptsB} · ${state.alignMethod}",
                style = Gomob.type.numInline.copy(fontSize = 11.sp),
                color = Gomob.colors.fg2,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LaserSideButton(GomobIcons.Refresh, "撤销", onClick = onUndo)
            Box(contentAlignment = Alignment.Center) {
                when (state) {
                    LaserScanState.Idle -> PillButton("开始扫描", GomobIcons.Check, primary = true, onClick = onStart)
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
            LaserSideButton(GomobIcons.Check, "完成", primary = true, onClick = onFinish)
        }
    }
}

/** 控制栏两侧圆形图标键（撤销 / 完成），对照相机页 RoundSideButton。 */
@Composable
private fun LaserSideButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (primary) Gomob.colors.accent else Gomob.colors.fg1
    val line = if (primary) Gomob.colors.accent else Gomob.colors.line2
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (primary) Gomob.colors.accentSoft else Gomob.colors.bg1)
                .border(BorderStroke(1.dp, line), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = tint, modifier = Modifier.size(18.dp))
        }
        Text(label, fontSize = 11.sp, color = tint)
    }
}

@Composable
private fun PillButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
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
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
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
