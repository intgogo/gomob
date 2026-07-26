package io.gomob.feature.scan3d

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.data.scan.LaserCloudRenderData
import io.gomob.data.scan.VehicleMeasurement
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.glass.glassChrome
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

const val SCAN_LASER_ROUTE = "scan3d/laser"

/** 真实点云通道；当前移动端只存在融合、镜头 A、镜头 B。 */
enum class LaserCloudKind { FUSED, A, B }

/** 暗面板视图模式：分镜（A/B 2×2 网格）/ 融合（3D 整云）。 */
private enum class LaserPanelMode { STORYBOARD, FUSED }

private val LaserControlBarHeight = 76.dp
private val LaserViewportBg = Color(0xFF0B0E13)
private val LaserViewportText = Color.White.copy(alpha = 0.90f)
private val LaserViewportMuted = Color.White.copy(alpha = 0.48f)
private val LaserViewportDanger = Color(0xFFFF8A8A)

// 数据可视化通道色（分镜/缩略条按镜头着色，与网页端查看器一致）。
private val LaserChannelA = Color(0xFF38D1E0)
private val LaserChannelB = Color(0xFFF4A361)

// 暗面板内状态色（固定暗色体系，不随主题）。
private val LaserTeal = Color(0xFF5EEAD4)
private val LaserOk = Color(0xFF34D399)

// 尺寸标签徽章配色（工程图风格，与网页端查看器一致）。
private val LaserDimBadgeBg = Color(0xFF12171E)
private val LaserDimBadgeLine = Color(0x8067E8E0)
private val LaserDimBadgeText = Color(0xFFA7F3EE)

// 测量浮层底色 rgba(16,20,26,.92)。
private val LaserMeasureCardBg = Color(0xEB10141A)

private const val ADAPTIVE_AXES_MIN_POINTS = 500
private const val PROJECTION_BOUNDS_MAX_POINTS = 12_000

/**
 * 3D 工位（激光双单元）车辆外廓扫描屏 —— 操作员范式（配置全在网页端管理端）：
 * 扫描态用真实 A/B 点云做 2×2 分镜网格；完成态加载真实融合点云 3D，可在分镜/融合间切换，
 * 并在视口内显示真实测量浮层（「尺寸叠加」可关）。
 * 端侧只做：选设备 → 开始/停止 → 查看服务端完整测量结果；不再有车型下拉、设备控制/设置、测量范围(车位框)标定入口。
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
    val stopping by vm.stopping.collectAsStateWithLifecycle()
    val onSafeBack = {
        when (val current = state) {
            LaserScanState.Connecting,
            LaserScanState.Scanning,
            LaserScanState.Processing,
            -> vm.stopThen(onBack)
            is LaserScanState.Error -> if (current.activeScan) vm.stopThen(onBack) else onBack()
            else -> onBack()
        }
    }
    BackHandler(onBack = onSafeBack)

    // 玻璃 header 骨架；整页非滚动布局（点云主窗 weight 占满），内容整体避让不穿越（规则 3）。
    GlassHeaderScaffold(
        header = {
            BackHeader(
                title = "车辆外廓扫描",
                eyebrow = "3D 工位",
                onBack = onSafeBack,
                // 单工位期保留真实工位选择；当前选择尚不切换服务端 endpoint。
                trailing = { switcher() },
            )
        },
        overlay = { _ ->
            LaserControlBar(
                state = state,
                stopping = stopping,
                onStart = vm::start,
                onStop = vm::stop,
                onRestart = vm::restart,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .glassChrome(topEdge = true)
                    .navigationBarsPadding(),
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + LaserControlBarHeight,
                ),
        ) {
            when (val s = state) {
                is LaserScanState.Error -> LaserErrorPanel(msg = s.msg)
                else -> LaserCaptureBody(
                    state = s,
                    fused = fused,
                    unitA = unitA,
                    unitB = unitB,
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
) {
    val completed = state as? LaserScanState.Completed
    var viewPreset by remember { mutableStateOf(LaserViewPreset.FREE) }
    var resetSignal by remember { mutableStateOf(0) }
    var adaptiveAxes by remember { mutableStateOf<ProjectionAxes?>(null) }
    var panelMode by remember(completed != null) {
        mutableStateOf(if (completed != null) LaserPanelMode.FUSED else LaserPanelMode.STORYBOARD)
    }
    var dimsOverlay by remember { mutableStateOf(true) }
    var measurementCardHeightPx by remember(completed?.measurement) { mutableStateOf(0) }
    // 真实用时：从进入采集起端侧计时（服务端未下发耗时字段；Completed 冻结显示为「用时」）。
    var scanStartMs by remember { mutableStateOf<Long?>(null) }
    var elapsedSec by remember { mutableStateOf<Int?>(null) }
    val ground = completed?.ground
    val measurementScene = remember(completed?.measurement) {
        completed?.measurement?.let(::buildVehicleMeasurementScene)
    }
    val adaptiveCandidate = remember(fused, unitA, unitB) {
        projectionAxes(LaserViewPreset.FREE, fused.xyz, unitA.xyz, unitB.xyz)
    }
    val livePointCount = unitA.pointCount + unitB.pointCount
    val axes = remember(viewPreset, completed, adaptiveAxes, fused, unitA, unitB) {
        if (viewPreset == LaserViewPreset.FREE && completed == null) {
            adaptiveAxes ?: ObliqueProjectionAxes
        } else {
            projectionAxes(viewPreset, fused.xyz, unitA.xyz, unitB.xyz)
        }
    }
    val projection = remember(axes, fused, unitA, unitB) {
        projectionFrame(axes, fused.xyz, unitA.xyz, unitB.xyz)
    }

    LaunchedEffect(state) {
        if (state == LaserScanState.Idle) {
            viewPreset = LaserViewPreset.FREE
            adaptiveAxes = null
            scanStartMs = null
            elapsedSec = null
        }
        if (state == LaserScanState.Scanning) {
            val start = scanStartMs ?: SystemClock.elapsedRealtime().also { scanStartMs = it }
            while (true) {
                elapsedSec = ((SystemClock.elapsedRealtime() - start) / 1000L).toInt()
                delay(1_000L)
            }
        }
    }
    LaunchedEffect(completed, livePointCount, adaptiveCandidate) {
        if (completed == null && adaptiveAxes == null && livePointCount >= ADAPTIVE_AXES_MIN_POINTS) {
            // 本次扫描只锁定一次自适应基，后续点云增长不再触发横纵轴互换。
            adaptiveAxes = adaptiveCandidate
        }
    }
    val showGround = ground != null && ground.valid
    val viewUp = if (showGround) floatArrayOf(ground!!.nx, ground.ny, ground.nz) else floatArrayOf(0f, 0f, 1f)
    // 「融合」段只有真实融合点云就绪才可点；扫描中融合云为空 → 不可点。
    val fusedSelectable = fused.pointCount > 0
    // 进入页面恢复最近完成任务时，A/B 与 fused 分批下载；Idle 下先收到 A/B 不应短暂创建两套
    // 分镜 Filament Engine，随后又立刻销毁改建 fused。直接保持融合模式等待最终云即可。
    val effectiveMode = when {
        completed != null -> panelMode
        state == LaserScanState.Connecting -> LaserPanelMode.FUSED
        state == LaserScanState.Idle && (fused.pointCount > 0 || livePointCount > 0) -> LaserPanelMode.FUSED
        else -> LaserPanelMode.STORYBOARD
    }

    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(
                    start = Gomob.spacing.pageGutter,
                    end = Gomob.spacing.pageGutter,
                    top = Gomob.spacing.s12,
                    bottom = Gomob.spacing.s8,
                ),
        ) {
            LaserViewportPanel(
                mode = effectiveMode,
                fusedSelectable = fusedSelectable,
                onSelectMode = { panelMode = it },
                rightControls = {
                    if (effectiveMode == LaserPanelMode.FUSED && completed != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                        ) {
                            DimsToggle(on = dimsOverlay, onToggle = { dimsOverlay = !dimsOverlay })
                            BareChip("重置", onClick = { resetSignal++ })
                        }
                    } else {
                        ViewPresetBar(
                            current = viewPreset,
                            onSelect = { viewPreset = it },
                            onReset = {
                                viewPreset = LaserViewPreset.FREE
                                resetSignal++
                            },
                            freeLabel = if (completed == null) "自适应" else "自由",
                        )
                    }
                },
                pill = { LaserStatusPill(state = state, livePoints = livePointCount, elapsedSec = elapsedSec) },
                modifier = Modifier.fillMaxSize(),
            ) {
                LaserPersistentCloudContent(
                    mode = effectiveMode,
                    fused = fused,
                    unitA = unitA,
                    unitB = unitB,
                    fusedUpAxis = viewUp,
                    viewPreset = viewPreset,
                    showGround = showGround,
                    groundD = ground?.d ?: 0f,
                    resetSignal = resetSignal,
                    measurementScene = measurementScene.takeIf {
                        effectiveMode == LaserPanelMode.FUSED && dimsOverlay
                    },
                    measurementReservedBottomPx = measurementCardHeightPx,
                    modifier = Modifier.fillMaxSize(),
                )
                if (effectiveMode == LaserPanelMode.FUSED) {
                    if (completed != null && dimsOverlay) {
                        if (
                            LASER_MEASUREMENT_TEXT_OVERLAY_ENABLED &&
                            completed.measurement.valid
                        ) {
                            DimBadgeRow(
                                measurement = completed.measurement,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = Gomob.spacing.s8),
                            )
                        }
                        MeasurementOverlay(
                            measurement = completed.measurement,
                            siteQualityVerified = completed.siteQualityVerified,
                            siteQualityOverride = completed.siteQualityOverride,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .onSizeChanged { measurementCardHeightPx = it.height }
                                .padding(Gomob.spacing.s8),
                        )
                    }
                }
            }
        }
        CloudSwitcherRow(
            fused = fused,
            unitA = unitA,
            unitB = unitB,
            projection = projection,
            completed = completed != null,
            modifier = Modifier.padding(horizontal = Gomob.spacing.pageGutter),
        )
        completed?.pointIntegrityWarning?.let { warning ->
            Text(
                "点云完整性告警：$warning",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gomob.spacing.pageGutter, vertical = Gomob.spacing.s6)
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.dangerSoft)
                    .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s6),
                style = Gomob.type.micro,
                color = Gomob.colors.danger,
                maxLines = 2,
            )
        }
    }
}

/**
 * 暗面板骨架：顶行（左「分镜|融合」segmented + 右侧控件槽）→ 居中状态 pill → 主内容区。
 * 暗面板落在浅色页面上，外框用 1dp 深色 10% 边（line2）。
 */
@Composable
private fun LaserViewportPanel(
    mode: LaserPanelMode,
    fusedSelectable: Boolean,
    onSelectMode: (LaserPanelMode) -> Unit,
    rightControls: @Composable () -> Unit,
    pill: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(Gomob.shapes.r3)
            .background(LaserViewportBg)
            .border(BorderStroke(1.dp, Gomob.colors.line2), Gomob.shapes.r3),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PanelModeSegmented(mode = mode, fusedSelectable = fusedSelectable, onSelect = onSelectMode)
            rightControls()
        }
        Box(
            Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = Gomob.spacing.s8),
        ) { pill() }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(top = Gomob.spacing.s8),
            content = content,
        )
    }
}

/** 「分镜|融合」segmented：融合段只有真实融合点云就绪才可点（不做假切换）。 */
@Composable
private fun PanelModeSegmented(
    mode: LaserPanelMode,
    fusedSelectable: Boolean,
    onSelect: (LaserPanelMode) -> Unit,
) {
    Row(
        Modifier
            .clip(Gomob.shapes.r2)
            .background(Color.White.copy(alpha = 0.07f))
            .padding(2.dp),
    ) {
        PanelModeSegment(
            label = "分镜",
            selected = mode == LaserPanelMode.STORYBOARD,
            enabled = true,
            onClick = { onSelect(LaserPanelMode.STORYBOARD) },
        )
        PanelModeSegment(
            label = "融合",
            selected = mode == LaserPanelMode.FUSED,
            enabled = fusedSelectable,
            onClick = { onSelect(LaserPanelMode.FUSED) },
        )
    }
}

@Composable
private fun PanelModeSegment(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(Gomob.shapes.r1)
            .background(if (selected) LaserTeal.copy(alpha = 0.16f) else Color.Transparent)
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            color = when {
                selected -> LaserTeal
                enabled -> Color.White.copy(alpha = 0.55f)
                else -> Color.White.copy(alpha = 0.30f)
            },
        )
    }
}

/**
 * 面板居中状态 pill：胶囊 1dp 边 + 同色 8% 底 + 6dp 状态点/spinner + 11sp 文字（时间/点数 mono）。
 * 状态语义与真实状态机一一对应（采集中/连接中/融合中/完成/就绪）。
 */
@Composable
private fun LaserStatusPill(
    state: LaserScanState,
    livePoints: Int,
    elapsedSec: Int?,
    modifier: Modifier = Modifier,
) {
    val mono = SpanStyle(fontFamily = FontFamily.Monospace)
    data class PillSpec(val color: Color, val borderAlpha: Float, val spinner: Boolean, val text: androidx.compose.ui.text.AnnotatedString)
    val spec = when (state) {
        LaserScanState.Idle -> PillSpec(
            Color.White, 0.20f, false,
            buildAnnotatedString { append("就绪") },
        )
        LaserScanState.Connecting -> PillSpec(
            LaserTeal, 0.35f, true,
            buildAnnotatedString { append("连接设备中") },
        )
        LaserScanState.Scanning -> PillSpec(
            LaserTeal, 0.35f, false,
            buildAnnotatedString {
                append("采集中")
                elapsedSec?.let {
                    append(" · 已用 ")
                    withStyle(mono) { append(formatElapsed(it)) }
                }
                append(" · ")
                append("源 ")
                withStyle(mono) { append("%,d".format(livePoints)) }
                append(" 点")
            },
        )
        LaserScanState.Processing -> PillSpec(
            LaserTeal, 0.35f, true,
            buildAnnotatedString { append("云端融合中") },
        )
        is LaserScanState.Completed -> PillSpec(
            LaserOk, 0.40f, false,
            buildAnnotatedString {
                append("扫描完成 · 融合源 ")
                withStyle(mono) { append("%,d".format(state.points)) }
                append(" 点")
                elapsedSec?.let {
                    append(" · 用时 ")
                    withStyle(mono) { append(formatElapsed(it)) }
                }
            },
        )
        is LaserScanState.Error -> PillSpec(
            LaserViewportDanger, 0.40f, false,
            buildAnnotatedString { append("扫描出错") },
        )
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(spec.color.copy(alpha = 0.08f))
            .border(BorderStroke(1.dp, spec.color.copy(alpha = spec.borderAlpha)), CircleShape)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        if (spec.spinner) {
            CircularProgressIndicator(color = spec.color, modifier = Modifier.size(9.dp), strokeWidth = 1.5.dp)
        } else {
            Box(
                Modifier
                    .size(Gomob.spacing.dot6)
                    .clip(CircleShape)
                    .background(if (state == LaserScanState.Idle) LaserViewportMuted else spec.color),
            )
        }
        Text(spec.text, fontSize = 11.sp, color = Color.White.copy(alpha = 0.85f))
    }
}

private fun formatElapsed(sec: Int): String = "%02d:%02d".format(sec / 60, sec % 60)

/**
 * 激光页持久渲染域：融合/A/B 各自保留独立相机与 GPU 云；A/B 在首次进入分镜后保留到页面退出。
 * 三个 Surface 尺寸固定，模式切换只平移活动层，不再重传点云、resize 或销毁/重建 Engine。
 */
@Composable
private fun LaserPersistentCloudContent(
    mode: LaserPanelMode,
    fused: LaserCloudRenderData,
    unitA: LaserCloudRenderData,
    unitB: LaserCloudRenderData,
    fusedUpAxis: FloatArray,
    viewPreset: LaserViewPreset,
    showGround: Boolean,
    groundD: Float,
    resetSignal: Int,
    measurementScene: VehicleMeasurementScene?,
    measurementReservedBottomPx: Int,
    modifier: Modifier = Modifier,
) {
    val fusedMode = mode == LaserPanelMode.FUSED
    val unitUpAxis = remember { floatArrayOf(0f, 0f, 1f) }
    var storyboardRetained by remember { mutableStateOf(!fusedMode) }
    val keepStoryboardViews = storyboardRetained || !fusedMode
    LaunchedEffect(fusedMode) {
        if (!fusedMode) storyboardRetained = true
    }

    var projection by remember { mutableStateOf<CameraProjectionSnapshot?>(null) }
    val updateProjection = remember {
        { snapshot: CameraProjectionSnapshot -> projection = snapshot }
    }
    LaunchedEffect(fusedMode, measurementScene) {
        if (!fusedMode || measurementScene == null) projection = null
    }

    BoxWithConstraints(
        modifier = modifier.background(
            if (fusedMode) LaserViewportBg else Color.White.copy(alpha = 0.08f),
        ),
    ) {
        val gap = 1.dp
        val cellWidth = (maxWidth - gap) / 2
        val cellHeight = (maxHeight - gap) / 2

        // 三个 Surface 的尺寸从创建到页面退出保持不变；切模式只把非活动 Surface 平移到视口外。
        // 这样既保留三路独立相机/GPU 云，也不触发 BLAST Surface resize/reallocate 长尾。
        PointCloud3dView(
            points = fused.xyz,
            colors = fused.rgb,
            modifier = Modifier
                .offset(x = if (fusedMode) 0.dp else maxWidth)
                .fillMaxSize(),
            autoFit = true,
            upAxis = fusedUpAxis,
            viewPreset = viewPreset,
            showGround = showGround,
            groundD = groundD,
            resetSignal = resetSignal,
            autoFitKey = LaserCloudKind.FUSED,
            pointBudget = LASER_FUSED_RENDER_POINT_BUDGET,
            onProjectionChanged = updateProjection.takeIf { fusedMode && measurementScene != null },
            renderingEnabled = fusedMode,
        )

        if (keepStoryboardViews) {
            PointCloud3dView(
                points = unitA.xyz,
                colors = unitA.rgb,
                modifier = Modifier
                    .offset(y = if (fusedMode) maxHeight else 0.dp)
                    .width(cellWidth)
                    .height(cellHeight),
                autoFit = true,
                upAxis = unitUpAxis,
                viewPreset = viewPreset,
                resetSignal = resetSignal,
                autoFitKey = LaserCloudKind.A,
                pointBudget = LASER_LIVE_PREVIEW_POINT_BUDGET,
                renderingEnabled = !fusedMode,
            )
            PointCloud3dView(
                points = unitB.xyz,
                colors = unitB.rgb,
                modifier = Modifier
                    .offset(
                        x = cellWidth + gap,
                        y = if (fusedMode) maxHeight else 0.dp,
                    )
                    .width(cellWidth)
                    .height(cellHeight),
                autoFit = true,
                upAxis = unitUpAxis,
                viewPreset = viewPreset,
                resetSignal = resetSignal,
                autoFitKey = LaserCloudKind.B,
                pointBudget = LASER_LIVE_PREVIEW_POINT_BUDGET,
                renderingEnabled = !fusedMode,
            )
        }

        if (fusedMode) {
            if (fused.xyz.isEmpty()) {
                Text(
                    "融合点云不可用",
                    modifier = Modifier.align(Alignment.Center),
                    style = Gomob.type.micro,
                    color = LaserViewportMuted,
                    textAlign = TextAlign.Center,
                )
            }
            val currentProjection = projection
            if (measurementScene != null && currentProjection != null) {
                VehicleMeasurementCloudOverlay(
                    scene = measurementScene,
                    projection = currentProjection,
                    reservedBottomPx = measurementReservedBottomPx,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            StoryboardCellChrome(
                label = "镜头 A",
                cloud = unitA,
                accent = LaserChannelA,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(cellWidth)
                    .height(cellHeight),
            )
            StoryboardCellChrome(
                label = "镜头 B",
                cloud = unitB,
                accent = LaserChannelB,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .width(cellWidth)
                    .height(cellHeight),
            )
            StoryboardPlaceholderCell(
                "镜头 C · 未接入",
                Modifier
                    .align(Alignment.BottomStart)
                    .width(cellWidth)
                    .height(cellHeight),
            )
            StoryboardPlaceholderCell(
                "镜头 D · 未接入",
                Modifier
                    .align(Alignment.BottomEnd)
                    .width(cellWidth)
                    .height(cellHeight),
            )
        }
    }
}

/** 分镜单格的纯 Compose 标识层；真实点云由持久 SurfaceView 在下层渲染。 */
@Composable
private fun StoryboardCellChrome(
    label: String,
    cloud: LaserCloudRenderData,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (cloud.xyz.isEmpty()) {
            Text(
                "等待点云…",
                modifier = Modifier.align(Alignment.Center),
                style = Gomob.type.micro,
                color = LaserViewportMuted,
            )
        }
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(start = Gomob.spacing.s8, top = Gomob.spacing.s6),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
        ) {
            Box(
                Modifier
                    .clip(Gomob.shapes.r1)
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    cloud.latestAngleDeg?.let { "$label · %.1f°".format(it) } ?: label,
                    fontSize = 9.sp,
                    color = accent,
                )
            }
            Text(
                "源 %,d · 显示 %,d".format(cloud.pointCount, cloud.renderPointCount),
                style = Gomob.type.eyebrow.copy(fontSize = 9.sp),
                color = Color.White.copy(alpha = 0.40f),
            )
        }
    }
}

/** 预留镜头空位：20dp 虚线圆 + 「镜头 X · 未接入」。 */
@Composable
private fun StoryboardPlaceholderCell(
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(modifier.background(LaserViewportBg), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color.White.copy(alpha = 0.25f),
                        style = Stroke(
                            width = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                        ),
                    )
                }
                Text("+", fontSize = 12.sp, color = Color.White.copy(alpha = 0.35f))
            }
            Text(label, fontSize = 10.sp, color = Color.White.copy(alpha = 0.35f))
        }
    }
}

/** 车长/车宽/车高真实测量值徽章行（尺寸叠加开时显示）。 */
@Composable
private fun DimBadgeRow(
    measurement: VehicleMeasurement,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
        DimBadge("车长 %,d".format(measurement.lengthMm.roundToInt()))
        DimBadge("车宽 %,d".format(measurement.widthMm.roundToInt()))
        DimBadge("车高 %,d".format(measurement.heightMm.roundToInt()))
    }
}

@Composable
private fun DimBadge(text: String) {
    Box(
        Modifier
            .clip(Gomob.shapes.r1)
            .background(LaserDimBadgeBg)
            .border(BorderStroke(1.dp, LaserDimBadgeLine), Gomob.shapes.r1)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(text, style = Gomob.type.eyebrow.copy(fontSize = 10.sp), color = LaserDimBadgeText)
    }
}

/** 「尺寸叠加」开关：36×20 胶囊轨道 + 16dp thumb。 */
@Composable
private fun DimsToggle(
    on: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        Text(
            "尺寸叠加",
            fontSize = 11.sp,
            color = Color.White.copy(alpha = if (on) 0.70f else 0.45f),
        )
        Box(
            Modifier
                .size(width = 36.dp, height = 20.dp)
                .clip(CircleShape)
                .background(if (on) LaserTeal.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f))
                .padding(2.dp),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (on) Color.White else Color.White.copy(alpha = 0.8f)),
            )
        }
    }
}

@Composable
private fun CloudSwitcherRow(
    fused: LaserCloudRenderData,
    unitA: LaserCloudRenderData,
    unitB: LaserCloudRenderData,
    projection: ProjectionFrame,
    completed: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        CloudThumbnail(
            label = "融合", cloud = fused,
            active = completed && fused.pointCount > 0,
            accent = Gomob.colors.accent,
            activeBorder = Gomob.colors.accent, // 完成选中：1.5dp accent
            projection = projection, modifier = Modifier.weight(1f),
        )
        CloudThumbnail(
            label = "镜头 A", cloud = unitA,
            active = !completed && unitA.pointCount > 0,
            accent = LaserChannelA,
            activeBorder = Gomob.colors.accentLine, // 采集中活跃：1.5dp accentLine
            projection = projection, modifier = Modifier.weight(1f),
        )
        CloudThumbnail(
            label = "镜头 B", cloud = unitB,
            active = !completed && unitB.pointCount > 0,
            accent = LaserChannelB,
            activeBorder = Gomob.colors.accentLine,
            projection = projection, modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 单个点云缩略图：正交投影 2D 散点（Canvas 轻量画，不开 Filament 引擎），下方标签 + 点数。
 * 活跃态 1.5dp 描边高亮、非活跃 1dp。空云显示占位符。
 */
@Composable
private fun CloudThumbnail(
    label: String,
    cloud: LaserCloudRenderData,
    active: Boolean,
    accent: Color,
    activeBorder: Color,
    projection: ProjectionFrame,
    modifier: Modifier = Modifier,
) {
    val pts = remember(cloud, projection) { project2D(cloud.xyz, projection) }
    val count = cloud.pointCount
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(Gomob.shapes.r2)
                .background(LaserViewportBg)
                .border(
                    BorderStroke(if (active) 1.5.dp else 1.dp, if (active) activeBorder else Gomob.colors.line2),
                    Gomob.shapes.r2,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (pts.isEmpty()) {
                Text("—", fontSize = 16.sp, color = LaserViewportMuted)
            } else {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .padding(6.dp)
                        .normalizedPointPlot(pts, accent, strokeWidth = 2.5f),
                ) {}
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
            Box(Modifier.size(5.dp).clip(CircleShape).background(if (active) accent else Gomob.colors.fg3))
            Text(
                label,
                style = Gomob.type.micro.copy(fontSize = 10.sp),
                color = if (active) accent else Gomob.colors.fg2,
            )
            Text(
                formatPointCount(count),
                style = Gomob.type.eyebrow.copy(fontSize = 9.sp),
                color = Gomob.colors.fg3,
            )
        }
    }
}

@Composable
private fun MeasurementOverlay(
    measurement: VehicleMeasurement,
    siteQualityVerified: Boolean,
    siteQualityOverride: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r2)
            .background(LaserMeasureCardBg)
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)), Gomob.shapes.r2)
            .padding(horizontal = Gomob.spacing.s12, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("车辆外廓测量", style = Gomob.type.caption.copy(fontWeight = FontWeight.SemiBold), color = LaserViewportText)
            ComplianceBadge(measurement)
        }
        siteQualityStatusText(siteQualityVerified, siteQualityOverride)?.let { statusText ->
            Text(
                statusText,
                style = Gomob.type.micro,
                color = if (siteQualityOverride) LaserViewportMuted else LaserViewportDanger,
            )
        }
        if (measurement.valid) {
            val resultItems = remember(measurement) { measurementResultItems(measurement) }
            Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                resultItems.chunked(2).forEach { rowItems ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
                    ) {
                        rowItems.forEach { item ->
                            MeasurementResultCell(item = item, modifier = Modifier.weight(1f))
                        }
                        if (rowItems.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            if (measurement.violations.isNotEmpty()) {
                Text(
                    measurement.violations.joinToString(" · "),
                    style = Gomob.type.micro,
                    color = LaserViewportDanger,
                    maxLines = 2,
                )
            }
        } else {
            Text("当前融合点云不足，无法给出可靠尺寸。", style = Gomob.type.micro, color = LaserViewportMuted)
        }
    }
}

private data class MeasurementResultItem(
    val label: String,
    val value: String,
)

/** 完成态结果卡只列服务端同次 measured 结果，不从 fused 点云或相邻字段二次推算。 */
private fun measurementResultItems(measurement: VehicleMeasurement): List<MeasurementResultItem> = buildList {
    add(MeasurementResultItem("车长", formatMeasurementMm(measurement.lengthMm)))
    add(MeasurementResultItem("车宽", formatMeasurementMm(measurement.widthMm)))
    add(MeasurementResultItem("车高", formatMeasurementMm(measurement.heightMm)))

    val axle = measurement.axle
    if (axle.valid) {
        add(MeasurementResultItem("轴数", "${axle.numAxles} 轴"))
        axle.wheelbasesMm.forEachIndexed { index, wheelbaseMm ->
            add(MeasurementResultItem("轴距 ${index + 1}", formatMeasurementMm(wheelbaseMm)))
        }
        add(MeasurementResultItem("总轴距", formatMeasurementMm(axle.totalWheelbaseMm)))
        add(MeasurementResultItem("前悬", formatMeasurementMm(axle.frontOverhangMm)))
        add(MeasurementResultItem("后悬", formatMeasurementMm(axle.rearOverhangMm)))
    }

    val cargo = measurement.cargoBox
    if (cargo.hasBox) {
        add(MeasurementResultItem("货箱外长", formatMeasurementMm(cargo.outerLengthMm)))
        add(MeasurementResultItem("货箱外宽", formatMeasurementMm(cargo.outerWidthMm)))
        add(MeasurementResultItem("货箱深", formatMeasurementMm(cargo.depthMm)))
        if (cargo.innerWidthMm > 0f) {
            add(MeasurementResultItem("货箱内宽（参考）", formatMeasurementMm(cargo.innerWidthMm)))
        }
    }
}

private fun formatMeasurementMm(value: Float): String =
    String.format(Locale.US, "%,d mm", value.roundToInt())

@Composable
private fun MeasurementResultCell(
    item: MeasurementResultItem,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = "车辆测量 ${item.label} ${item.value}"
            }
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(item.label, style = Gomob.type.micro, color = LaserViewportMuted, maxLines = 1)
        Text(item.value, style = Gomob.type.eyebrow, color = LaserViewportText, maxLines = 1)
    }
}

internal fun siteQualityStatusText(verified: Boolean, override: Boolean): String? = when {
    override -> "当前外参已受控启用 · 法规状态未判定"
    !verified -> "工位外参未通过生产验证，不可用于正式量测"
    else -> null
}

/** 合规判定小徽章（无状态点，10sp）：真实判定语义（通过/超限/不可判定）。 */
@Composable
private fun ComplianceBadge(measurement: VehicleMeasurement) {
    val (text, fg, bg) = when {
        !measurement.valid || !measurement.complianceDetermined ->
            Triple("不可判定", LaserViewportMuted, Color.White.copy(alpha = 0.10f))
        measurement.compliant -> Triple("通用限值通过", Gomob.colors.ok, Gomob.colors.okSoft)
        else -> Triple("通用限值超限", Gomob.colors.danger, Gomob.colors.dangerSoft)
    }
    Box(
        Modifier
            .clip(Gomob.shapes.r1)
            .background(bg)
            .padding(horizontal = Gomob.spacing.s8, vertical = 2.dp),
    ) {
        Text(text, fontSize = 10.sp, color = fg)
    }
}

private data class ProjectionAxes(
    val ux: Float,
    val uy: Float,
    val uz: Float,
    val vx: Float,
    val vy: Float,
    val vz: Float,
)

private data class ProjectionFrame(
    val axes: ProjectionAxes,
    val uMin: Float,
    val uMax: Float,
    val vMin: Float,
    val vMax: Float,
)

private val ObliqueProjectionAxes = ProjectionAxes(
    0.7071f, -0.7071f, 0f,
    -0.4082f, -0.4082f, 0.8165f,
)

private fun projectionAxes(preset: LaserViewPreset, vararg clouds: FloatArray): ProjectionAxes = when (preset) {
    LaserViewPreset.TOP -> ProjectionAxes(1f, 0f, 0f, 0f, 1f, 0f)
    LaserViewPreset.SIDE -> ProjectionAxes(1f, 0f, 0f, 0f, 0f, 1f)
    LaserViewPreset.OBLIQUE -> ObliqueProjectionAxes
    LaserViewPreset.FREE -> {
        val mins = floatArrayOf(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        val maxs = floatArrayOf(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        clouds.forEach { cloud ->
            val total = cloud.size / 3
            val stride = (total / 2500).coerceAtLeast(1)
            var i = 0
            while (i < total) {
                val b = i * 3
                val x = cloud[b]
                val y = cloud[b + 1]
                val z = cloud[b + 2]
                if (sane(x) && sane(y) && sane(z)) {
                    if (x < mins[0]) mins[0] = x
                    if (x > maxs[0]) maxs[0] = x
                    if (y < mins[1]) mins[1] = y
                    if (y > maxs[1]) maxs[1] = y
                    if (z < mins[2]) mins[2] = z
                    if (z > maxs[2]) maxs[2] = z
                }
                i += stride
            }
        }
        val ranges = FloatArray(3) { index ->
            if (mins[index].isFinite() && maxs[index].isFinite()) maxs[index] - mins[index] else 0f
        }
        val order = intArrayOf(0, 1, 2)
        for (a in 0..1) for (b in a + 1..2) {
            if (ranges[order[b]] > ranges[order[a]]) {
                val t = order[a]
                order[a] = order[b]
                order[b] = t
            }
        }
        axesForIndices(order[0], order[1])
    }
}

/** A/B/融合共用同一投影边界，分镜之间的比例与位置才可直接比较。 */
private fun projectionFrame(axes: ProjectionAxes, vararg clouds: FloatArray): ProjectionFrame {
    var uMin = Float.POSITIVE_INFINITY
    var uMax = Float.NEGATIVE_INFINITY
    var vMin = Float.POSITIVE_INFINITY
    var vMax = Float.NEGATIVE_INFINITY
    clouds.forEach { cloud ->
        val total = cloud.size / 3
        val stride = (total / PROJECTION_BOUNDS_MAX_POINTS).coerceAtLeast(1)
        var i = 0
        while (i < total) {
            val b = i * 3
            val x = cloud[b]
            val y = cloud[b + 1]
            val z = cloud[b + 2]
            if (sane(x) && sane(y) && sane(z)) {
                val u = x * axes.ux + y * axes.uy + z * axes.uz
                val v = x * axes.vx + y * axes.vy + z * axes.vz
                if (u < uMin) uMin = u
                if (u > uMax) uMax = u
                if (v < vMin) vMin = v
                if (v > vMax) vMax = v
            }
            i += stride
        }
    }
    if (!uMin.isFinite() || !uMax.isFinite() || !vMin.isFinite() || !vMax.isFinite()) {
        return ProjectionFrame(axes, 0f, 1f, 0f, 1f)
    }
    val uPad = ((uMax - uMin).coerceAtLeast(1e-3f) * 0.04f)
    val vPad = ((vMax - vMin).coerceAtLeast(1e-3f) * 0.04f)
    return ProjectionFrame(axes, uMin - uPad, uMax + uPad, vMin - vPad, vMax + vPad)
}

private fun axesForIndices(u: Int, v: Int): ProjectionAxes {
    fun component(axis: Int, target: Int) = if (axis == target) 1f else 0f
    return ProjectionAxes(
        ux = component(0, u), uy = component(1, u), uz = component(2, u),
        vx = component(0, v), vy = component(1, v), vz = component(2, v),
    )
}

private fun project2D(cloud: FloatArray, projection: ProjectionFrame, maxPts: Int = 1500): FloatArray {
    val total = cloud.size / 3
    if (total == 0) return FloatArray(0)
    val stride = if (total > maxPts) (total + maxPts - 1) / maxPts else 1
    val projected = FloatArray(((total + stride - 1) / stride) * 2)
    val axes = projection.axes
    var w = 0
    var i = 0
    while (i < total) {
        val b = i * 3
        val x = cloud[b]; val y = cloud[b + 1]; val z = cloud[b + 2]
        if (sane(x) && sane(y) && sane(z)) {
            val u = x * axes.ux + y * axes.uy + z * axes.uz
            val v = x * axes.vx + y * axes.vy + z * axes.vz
            projected[w] = u
            projected[w + 1] = v
            w += 2
        }
        i += stride
    }
    if (w == 0) return FloatArray(0)
    val uRange = (projection.uMax - projection.uMin).coerceAtLeast(1e-3f)
    val vRange = (projection.vMax - projection.vMin).coerceAtLeast(1e-3f)
    var p = 0
    while (p < w) {
        projected[p] = ((projected[p] - projection.uMin) / uRange).coerceIn(0f, 1f)
        projected[p + 1] = (1f - (projected[p + 1] - projection.vMin) / vRange).coerceIn(0f, 1f)
        p += 2
    }
    return projected.copyOf(w)
}

/** 像素 Offset 仅在点或画布尺寸变化时生成，避免每次 Canvas 重绘分配数千个对象。 */
private fun Modifier.normalizedPointPlot(
    points: FloatArray,
    color: Color,
    strokeWidth: Float,
): Modifier = drawWithCache {
    val offsets = ArrayList<Offset>(points.size / 2)
    var i = 0
    while (i < points.size) {
        offsets += Offset(points[i] * size.width, points[i + 1] * size.height)
        i += 2
    }
    onDrawBehind {
        drawPoints(offsets, PointMode.Points, color, strokeWidth = strokeWidth, cap = StrokeCap.Round)
    }
}

private fun formatPointCount(count: Int): String = when {
    count >= 1_000_000 -> "%.2fM".format(count / 1_000_000f)
    count >= 1_000 -> "%.0fK".format(count / 1_000f)
    else -> count.toString()
}

/** 坐标合理性（有限且 |v|≤50m mm），与 PointCloud3dView.isSane / 服务端 handlePts 阈值一致。 */
private fun sane(v: Float): Boolean = v.isFinite() && kotlin.math.abs(v) <= 50_000f

/** 视角预设裸 chip 群（顶视/侧视/斜/自适应|自由 + 重置）：叠在面板顶行右侧，一键切机位 / 复位取景。 */
@Composable
private fun ViewPresetBar(
    current: LaserViewPreset,
    onSelect: (LaserViewPreset) -> Unit,
    onReset: () -> Unit,
    freeLabel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
    ) {
        ViewPresetChip("顶视", LaserViewPreset.TOP, current, onSelect)
        ViewPresetChip("侧视", LaserViewPreset.SIDE, current, onSelect)
        ViewPresetChip("斜", LaserViewPreset.OBLIQUE, current, onSelect)
        ViewPresetChip(freeLabel, LaserViewPreset.FREE, current, onSelect)
        // 重置是动作非选项，恒用未选样式。
        BareChip("重置", onClick = onReset)
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
            .clip(Gomob.shapes.r1)
            .background(Color.White.copy(alpha = if (sel) 0.14f else 0.05f))
            .clickable { onSelect(preset) }
            .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s4),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = if (sel) 0.90f else 0.50f))
    }
}

/** 暗面板内动作 chip（重置等）：未选中态样式。 */
@Composable
private fun BareChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(Gomob.shapes.r1)
            .background(Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s4),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.50f))
    }
}

/**
 * 主控栏（双槽玻璃条）：只有真实可执行动作。
 * - Idle：主=开始扫描；页面返回统一由顶栏和系统返回承担。
 * - Scanning：主=取消扫描（真实取消接口）。
 *   TODO(终态): 主按钮应为「结束扫描并融合」（停采→服务端融合出结果）——当前服务端
 *   POST /v1/scans/laser/{id}/stop 仅取消不触发融合（自然完成才走 fusing→done），
 *   服务端补 finish 接口后在此接线为 副=取消扫描 / 主=结束扫描并融合。
 * - Completed：主=重新扫描。
 *   TODO(终态): 主按钮应为「确认入档 · 存为 3D 资产」（副=重新扫描）——当前无入档 API，
 *   服务端补资产归档接口后接线。
 */
@Composable
private fun LaserControlBar(
    state: LaserScanState,
    stopping: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(LaserControlBarHeight)
            .padding(horizontal = Gomob.spacing.pageGutter),
        contentAlignment = Alignment.Center,
    ) {
        when {
            stopping -> PillStatus("正在确认停止…", spinner = true)
            else -> when (state) {
                LaserScanState.Idle ->
                    PillButton("开始扫描", primary = true, onClick = onStart, modifier = Modifier.fillMaxWidth())
                LaserScanState.Connecting -> PillStatus("连接设备中…", spinner = true)
                LaserScanState.Scanning ->
                    PillButton("取消扫描", primary = false, danger = true, onClick = onStop, modifier = Modifier.fillMaxWidth())
                LaserScanState.Processing -> PillStatus("云端融合中…", spinner = true)
                is LaserScanState.Completed ->
                    PillButton("重新扫描", GomobIcons.Refresh, primary = true, onClick = onRestart, modifier = Modifier.fillMaxWidth())
                is LaserScanState.Error -> if (state.activeScan) {
                    PillButton("重试停止", primary = false, danger = true, onClick = onStop, modifier = Modifier.fillMaxWidth())
                } else {
                    PillButton("重新扫描", GomobIcons.Refresh, primary = true, onClick = onRestart, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** 44dp 胶囊按钮：主=accent 底 15sp / 副=lineStrong 边 + bg1@60% 底 14sp / 危险=danger 边字。 */
@Composable
private fun PillButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    primary: Boolean,
    danger: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canClick = enabled
    val tint = when {
        !canClick -> Gomob.colors.fg3
        danger -> Gomob.colors.danger
        primary -> Gomob.colors.bg0
        else -> Gomob.colors.fg1
    }
    val line = when {
        !canClick -> Gomob.colors.line2
        danger -> Gomob.colors.danger
        primary -> Gomob.colors.accent
        else -> Gomob.colors.lineStrong
    }
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (primary && canClick) Gomob.colors.accent else Gomob.colors.bg1.copy(alpha = 0.6f))
            .border(BorderStroke(1.dp, line), CircleShape)
            .clickable(enabled = canClick, onClick = onClick)
            .padding(horizontal = if (primary) 22.dp else 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8, Alignment.CenterHorizontally),
    ) {
        if (icon != null) Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
        Text(label, fontSize = if (primary) 15.sp else 14.sp, fontWeight = FontWeight.Medium, color = tint)
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
private fun LaserErrorPanel(msg: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s16),
        ) {
            Text(msg, style = Gomob.type.numInline.copy(fontSize = 13.sp), color = Gomob.colors.danger, textAlign = TextAlign.Center)
        }
    }
}
