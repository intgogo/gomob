package io.gomob.feature.scan3d

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.gomob.data.scan.CropPreviewResult
import io.gomob.data.scan.ScanCropBox
import io.gomob.designsystem.theme.Gomob
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 车位框圈选编辑器（M9.11）：在融合云顶视图上拖一个 3D 框（footprint 矩形 + 高度带 + 朝向），
 * 实时显示框内点数与临时尺寸，确认后存为持久裁剪/测量边界。几何完全镜像服务端 cropbox.go：
 * up=地面法向（可翻转），right/fwd 由 up+yaw 确定性构造，框内点按 OBB 局部系判定 —— 端云一致。
 *
 * 为什么是 3D 框而非"扫描角"：设备只有 pan+俯仰角度闸门、无深度闸门，缩扫描角分不开车与同立体角
 * 更远的背景（真机实测保留 99% 背景点）；唯一能按深度隔离的是 3D 框软件裁剪。
 */
@Composable
fun LaserCropBoxEditor(
    cloud: FloatArray,
    groundNormal: FloatArray?, // 完成事件的地面法向(nx,ny,nz)；null/无效→默认 +Z
    initialBox: ScanCropBox?,
    onPreview: suspend (ScanCropBox) -> CropPreviewResult?,
    onSave: (ScanCropBox) -> Unit,
    onDismiss: () -> Unit,
) {
    // up 翻转状态（自动地面常拟到天花，需一键翻正）。
    var upSign by remember { mutableStateOf(initialUpSign(initialBox, groundNormal)) }

    // 投影预计算：按 cloud + upSign memo。up=upSign·法向；(right0,fwd0,up)=groundBasis；点投影到该基。
    val proj = remember(cloud, upSign, groundNormal) { projectTopView(cloud, groundNormal, upSign) }

    // 默认框拟合：无存框时自动套住点云主体（各轴 2–98 百分位），保证框内>0、黄框在视野内。
    // 「套框」重置键也复用它。[cU,cV,cH,hU,hV,hH]。
    val fit = remember(proj) { fitToCloud(proj) }

    // 编辑状态（基坐标系，mm）：框心 cU/cV/cH + 半尺 hU/hV/hH + 朝向 yaw。
    // 有存框→投影其 center/half；否则用拟合默认。
    var cU by remember(proj) { mutableStateOf(initialBox?.let { boxCenterAxis(it, proj, 0) } ?: fit[0]) }
    var cV by remember(proj) { mutableStateOf(initialBox?.let { boxCenterAxis(it, proj, 1) } ?: fit[1]) }
    var cH by remember(proj) { mutableStateOf(initialBox?.let { boxCenterAxis(it, proj, 2) } ?: fit[2]) }
    var hU by remember(proj) { mutableStateOf(initialBox?.half?.get(0) ?: fit[3]) }
    var hV by remember(proj) { mutableStateOf(initialBox?.half?.get(1) ?: fit[4]) }
    var hH by remember(proj) { mutableStateOf(initialBox?.half?.get(2) ?: fit[5]) }
    var yawDeg by remember(proj) { mutableStateOf(initialBox?.yawDeg ?: 0f) }

    // 当前世界系框（preview/save/本地判定共用）。
    val box = remember(proj, cU, cV, cH, hU, hV, hH, yawDeg) {
        worldBox(proj, cU, cV, cH, hU, hV, hH, yawDeg, upSign)
    }
    // 本地框内点数（子采样近似，拖动即时反馈）。
    val localIn = remember(proj, box) { countInBox(proj, box) }

    // 服务端预览（权威 inPoints + 尺寸），按框防抖 300ms。
    var preview by remember { mutableStateOf<CropPreviewResult?>(null) }
    var previewing by remember { mutableStateOf(false) }
    LaunchedEffect(box) {
        previewing = true
        delay(300)
        preview = onPreview(box)
        previewing = false
    }

    // 调整面板显隐：默认展开（首次圈选要见工具）；收起后点云顶视全屏，可一眼看全框与背景关系。
    var showControls by remember { mutableStateOf(true) }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xFF060912))) {
        // 捕获约束高度为局部 val：内层 ColumnScope 受 DSL marker 限制无法隐式访问 maxHeight。
        val maxH = maxHeight
        // 1) 顶视点云：占满整屏（底部浮层只局部遮挡，收起即全见）。
        if (proj.n == 0) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("无融合点云", fontSize = 12.sp, color = Gomob.colors.fg3)
            }
        } else {
            TopViewCanvas(
                proj = proj,
                boxUV = BoxUV(cU, cV, hU, hV, yawDeg),
                onMove = { du, dv -> cU += du; cV += dv },
                onResize = { hu, hv -> hU = hu; hV = hv },
                onRotate = { y -> yawDeg = y },
            )
        }

        // 2) 顶部浮层（常驻）：取消 + 轴向提示 + 调整面板显隐切换。
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayPill("取消", onClick = onDismiss)
            Text("↑前  →右 · 顶视", fontSize = 9.sp, color = Gomob.colors.fg2)
            OverlayPill(if (showControls) "收起调整 ▾" else "调整 ▴", accent = true) { showControls = !showControls }
        }

        // 3) 底部浮层（可显隐）：读数(固定顶) + 滑杆(中段滚动) + 翻转/保存(固定底)。
        // 读数与保存常驻可见——拖动时实时看框内点数、随时一键保存，不必滚动找按钮；只有滑杆超高才滚。
        if (showControls && proj.n > 0) {
            val topShape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .clip(topShape)
                    .background(Gomob.colors.bg0.copy(alpha = 0.95f))
                    .border(BorderStroke(1.dp, Gomob.colors.line2), topShape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 读数（固定顶）：本地框内点 + 服务端预览(点数 + L×W×H)。
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReadoutChip("框内(本地)", "$localIn", Gomob.colors.accent, Modifier.weight(1f))
                    val pv = preview
                    if (pv != null && pv.valid) {
                        ReadoutChip("框内/裁后", "${pv.inPoints}", Gomob.colors.ok, Modifier.weight(1f))
                        ReadoutChip("L×W×H m", dimStr(pv), Gomob.colors.fg1, Modifier.weight(1.6f))
                    } else {
                        ReadoutChip("裁后", if (previewing) "…" else (preview?.inPoints?.toString() ?: "—"), Gomob.colors.fg2, Modifier.weight(1f))
                        ReadoutChip("测量", if (previewing) "预览中…" else "点云不足", Gomob.colors.fg3, Modifier.weight(1.6f))
                    }
                }

                // 手势提示：平面尺寸/朝向直接在点云上拖（宽长靠拖角、转向靠拖柄、平移靠拖框）。
                Text(
                    "拖框移动 · 拖四角缩放 · 拖 ✛ 柄转向",
                    fontSize = 10.sp, color = Gomob.colors.fg3,
                )
                // 滑杆只留顶视拖不了的两项：框高(沿上厚度) + 离地中心。超高才滚。
                Column(
                    Modifier.fillMaxWidth().heightIn(max = maxH * 0.32f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LabeledSlider("框高", hH * 2, 200f, 4000f) { hH = it / 2 }
                    HeightCenterSlider("离地中心(沿上)", cH, proj.hMin, proj.hMax) { cH = it }
                }

                // 翻转 / 套框 / 保存（固定底，常驻）。套框=把框重置回自动拟合（拖飞了一键拉回）。
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.clip(Gomob.shapes.r2).background(Gomob.colors.bg1)
                            .border(BorderStroke(1.dp, Gomob.colors.line2), Gomob.shapes.r2)
                            .clickable { upSign = -upSign }.padding(horizontal = 12.dp, vertical = 9.dp),
                    ) { Text("翻转", fontSize = 12.sp, color = Gomob.colors.fg2) }
                    Box(
                        Modifier.clip(Gomob.shapes.r2).background(Gomob.colors.bg1)
                            .border(BorderStroke(1.dp, Gomob.colors.line2), Gomob.shapes.r2)
                            .clickable { cU = fit[0]; cV = fit[1]; cH = fit[2]; hU = fit[3]; hV = fit[4]; hH = fit[5]; yawDeg = 0f }
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                    ) { Text("套框", fontSize = 12.sp, color = Gomob.colors.fg2) }
                    Box(
                        Modifier.weight(1f).clip(CircleShape).background(Gomob.colors.accentSoft)
                            .border(BorderStroke(1.dp, Gomob.colors.accent), CircleShape)
                            .clickable { onSave(box) }.padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("保存车位框", fontSize = 14.sp, color = Gomob.colors.accent) }
                }
            }
        }
    }
}

/** 顶部浮层小药丸按钮（半透明底，叠在点云上）。accent=主色描边强调（如显隐切换）。 */
@Composable
private fun OverlayPill(label: String, accent: Boolean = false, onClick: () -> Unit) {
    val line = if (accent) Gomob.colors.accent else Gomob.colors.line2
    val fg = if (accent) Gomob.colors.accent else Gomob.colors.fg1
    Box(
        Modifier.clip(Gomob.shapes.r2).background(Gomob.colors.bg0.copy(alpha = 0.8f))
            .border(BorderStroke(1.dp, line), Gomob.shapes.r2)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(label, fontSize = 12.sp, color = fg) }
}

/** 框平面参数（基坐标 u/v，mm）：中心 cU/cV + 半尺 hU/hV + 朝向 yaw。供画布手势与手柄绘制。 */
private data class BoxUV(val cU: Float, val cV: Float, val hU: Float, val hV: Float, val yawDeg: Float)

private enum class DragMode { NONE, MOVE, RESIZE, ROTATE }

/**
 * 顶视画布：投影点 + 框内高亮 + 可直接拖的矩形。手势分流——拖框体平移、拖四角缩放（绕心对称）、
 * 拖旋转柄转向。命中判定与缩放都在 (u,v) 基坐标(mm)里算，与服务端一致。高度/离地中心顶视拖不了→留滑杆。
 */
@Composable
private fun TopViewCanvas(
    proj: TopProj,
    boxUV: BoxUV,
    onMove: (Float, Float) -> Unit,
    onResize: (Float, Float) -> Unit,
    onRotate: (Float) -> Unit,
) {
    val bp by rememberUpdatedState(boxUV)
    val d2r = (Math.PI / 180.0).toFloat()
    Canvas(
        Modifier.fillMaxSize().padding(10.dp).pointerInput(proj) {
            val uR = (proj.uMax - proj.uMin).coerceAtLeast(1f)
            val vR = (proj.vMax - proj.vMin).coerceAtLeast(1f)
            // 屏幕↔(u,v) 映射（与 DrawScope 同式，按 size 实时算）。返回 [s, offU, offV]。
            fun map3(): FloatArray {
                val s = min(size.width / uR, size.height / vR)
                return floatArrayOf(s, (size.width - uR * s) / 2f, (size.height - vR * s) / 2f)
            }
            fun toU(x: Float, s: Float, offU: Float) = (x - offU) / s + proj.uMin
            fun toV(y: Float, s: Float, offV: Float) = (size.height - y - offV) / s + proj.vMin
            var mode = DragMode.NONE
            detectDragGestures(
                onDragStart = { pos ->
                    val m = map3(); val s = m[0]
                    val fu = toU(pos.x, s, m[1]); val fv = toV(pos.y, s, m[2])
                    val b = bp
                    val cy = cos(b.yawDeg * d2r); val sy0 = sin(b.yawDeg * d2r)
                    val du = fu - b.cU; val dv = fv - b.cV
                    val lu = du * cy + dv * sy0          // 局部 u
                    val lv = -du * sy0 + dv * cy         // 局部 v
                    val hit = 44f / s                    // ~44px 命中半径→mm
                    val rotV = b.hV + 40f / s            // 旋转柄在局部 +v、离框 ~40px
                    mode = when {
                        abs(lu) < hit && abs(lv - rotV) < hit -> DragMode.ROTATE
                        abs(abs(lu) - b.hU) < hit && abs(abs(lv) - b.hV) < hit -> DragMode.RESIZE
                        else -> DragMode.MOVE
                    }
                },
                onDragEnd = { mode = DragMode.NONE },
                onDragCancel = { mode = DragMode.NONE },
            ) { change, drag ->
                val m = map3(); val s = m[0]
                val b = bp
                when (mode) {
                    DragMode.MOVE -> onMove(drag.x / s, -drag.y / s)
                    DragMode.RESIZE -> {
                        val fu = toU(change.position.x, s, m[1]); val fv = toV(change.position.y, s, m[2])
                        val cy = cos(b.yawDeg * d2r); val sy0 = sin(b.yawDeg * d2r)
                        val du = fu - b.cU; val dv = fv - b.cV
                        onResize(
                            abs(du * cy + dv * sy0).coerceAtLeast(150f),
                            abs(-du * sy0 + dv * cy).coerceAtLeast(150f),
                        )
                    }
                    DragMode.ROTATE -> {
                        val fu = toU(change.position.x, s, m[1]); val fv = toV(change.position.y, s, m[2])
                        val du = fu - b.cU; val dv = fv - b.cV
                        if (du * du + dv * dv > 1f) {
                            var y = atan2(dv, du) / d2r - 90f       // 局部 +v 指向手指
                            y = ((y % 180f) + 180f) % 180f
                            if (y > 90f) y -= 180f
                            onRotate(y)
                        }
                    }
                    DragMode.NONE -> {}
                }
            }
        },
    ) {
        val uR = (proj.uMax - proj.uMin).coerceAtLeast(1f)
        val vR = (proj.vMax - proj.vMin).coerceAtLeast(1f)
        val s = min(size.width / uR, size.height / vR)
        val offU = (size.width - uR * s) / 2f
        val offV = (size.height - vR * s) / 2f
        fun sx(u: Float) = offU + (u - proj.uMin) * s
        fun sy(v: Float) = size.height - (offV + (v - proj.vMin) * s)

        // 点：框外亮灰、框内 accent 高亮，加大尺寸保证稀疏云也看得清。
        val inPts = ArrayList<Offset>(); val outPts = ArrayList<Offset>()
        for (i in 0 until proj.n) {
            val o = Offset(sx(proj.u[i]), sy(proj.v[i]))
            if (proj.inMask[i]) inPts.add(o) else outPts.add(o)
        }
        drawPoints(outPts, PointMode.Points, Color(0xFF8A9BBD), strokeWidth = 3.2f, cap = StrokeCap.Round)
        drawPoints(inPts, PointMode.Points, Color(0xFF5BD6FF), strokeWidth = 4.5f, cap = StrokeCap.Round)

        // 框：由 boxUV 在 (u,v) 直接算四角 + 手柄。
        val b = boxUV
        val cy = cos(b.yawDeg * d2r); val sy0 = sin(b.yawDeg * d2r)
        fun corner(su: Int, sv: Int): Offset {
            val u = b.cU + su * b.hU * cy - sv * b.hV * sy0
            val v = b.cV + su * b.hU * sy0 + sv * b.hV * cy
            return Offset(sx(u), sy(v))
        }
        val c00 = corner(-1, -1); val c10 = corner(1, -1); val c11 = corner(1, 1); val c01 = corner(-1, 1)
        val accent = Color(0xFFFFC53D)
        val path = Path().apply { moveTo(c00.x, c00.y); lineTo(c10.x, c10.y); lineTo(c11.x, c11.y); lineTo(c01.x, c01.y); close() }
        drawPath(path, accent, style = Stroke(width = 4f))
        // 四角手柄
        for (c in listOf(c00, c10, c11, c01)) {
            drawCircle(Color(0xFF1A2030), radius = 11f, center = c)
            drawCircle(accent, radius = 11f, center = c, style = Stroke(width = 3f))
        }
        // 框心标记
        drawCircle(accent, radius = 6f, center = Offset(sx(b.cU), sy(b.cV)))
        // 旋转柄：局部 +v 方向(-sinY,cosY)，离框 40px。
        val rotV = b.hV + 40f / s
        val rh = Offset(sx(b.cU - sy0 * rotV), sy(b.cV + cy * rotV))
        val topMid = Offset((c01.x + c11.x) / 2f, (c01.y + c11.y) / 2f)
        drawLine(accent, topMid, rh, strokeWidth = 2f)
        drawCircle(Color(0xFF1A2030), radius = 13f, center = rh)
        drawCircle(accent, radius = 13f, center = rh, style = Stroke(width = 3f))
        // ✛ 标记
        drawLine(accent, Offset(rh.x - 6f, rh.y), Offset(rh.x + 6f, rh.y), strokeWidth = 2f)
        drawLine(accent, Offset(rh.x, rh.y - 6f), Offset(rh.x, rh.y + 6f), strokeWidth = 2f)
    }
}

@Composable
private fun ReadoutChip(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(Gomob.shapes.r2).background(Gomob.colors.bg1)
            .border(BorderStroke(1.dp, Gomob.colors.line2), Gomob.shapes.r2)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, fontSize = 9.sp, color = Gomob.colors.fg3)
        Text(value, fontSize = 14.sp, color = color)
    }
}

@Composable
private fun LabeledSlider(
    label: String, value: Float, minV: Float, maxV: Float,
    unit: String = "mm", display: Float? = null, onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 11.sp, color = Gomob.colors.fg2)
            Text("${(display ?: value).roundToInt()} $unit", fontSize = 11.sp, color = Gomob.colors.fg1)
        }
        Slider(value = value.coerceIn(minV, maxV), onValueChange = onChange, valueRange = minV..maxV)
    }
}

@Composable
private fun HeightCenterSlider(label: String, value: Float, minV: Float, maxV: Float, onChange: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 11.sp, color = Gomob.colors.fg2)
            Text("${value.roundToInt()} mm", fontSize = 11.sp, color = Gomob.colors.fg1)
        }
        Slider(value = value.coerceIn(minV, maxV), onValueChange = onChange, valueRange = minV..maxV)
    }
}

// --- 几何（镜像服务端 cropbox.go；单位 mm）---

/** 顶视投影结果：子采样点的基坐标 (u,v,h) + 框内 mask + 基向量 + 范围。 */
private class TopProj(
    val n: Int,
    val u: FloatArray, val v: FloatArray, val h: FloatArray,
    val right0: FloatArray, val fwd0: FloatArray, val up: FloatArray,
    val uMin: Float, val uMax: Float, val vMin: Float, val vMax: Float, val hMin: Float, val hMax: Float,
    val inMask: BooleanArray,
)

private fun normalize3(x: Float, y: Float, z: Float): FloatArray {
    val l = sqrt(x * x + y * y + z * z)
    return if (l < 1e-6f) floatArrayOf(0f, 0f, 1f) else floatArrayOf(x / l, y / l, z / l)
}

/** 由地面法向构造正交基(right,fwd,up)：镜像服务端 groundBasis。 */
private fun groundBasis(up: FloatArray): Pair<FloatArray, FloatArray> {
    val ux = up[0]; val uy = up[1]; val uz = up[2]
    val rf = if (abs(uz) < 0.9f) floatArrayOf(0f, 0f, 1f) else floatArrayOf(1f, 0f, 0f)
    var rx = uy * rf[2] - uz * rf[1]; var ry = uz * rf[0] - ux * rf[2]; var rz = ux * rf[1] - uy * rf[0]
    val rl = sqrt(rx * rx + ry * ry + rz * rz).coerceAtLeast(1e-6f)
    rx /= rl; ry /= rl; rz /= rl
    val fx = ry * uz - rz * uy; val fy = rz * ux - rx * uz; val fz = rx * uy - ry * ux
    return floatArrayOf(rx, ry, rz) to floatArrayOf(fx, fy, fz)
}

private fun projectTopView(cloud: FloatArray, groundNormal: FloatArray?, upSign: Int, maxPts: Int = 4000): TopProj {
    val total = cloud.size / 3
    if (total == 0) {
        return TopProj(0, FloatArray(0), FloatArray(0), FloatArray(0),
            floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f), floatArrayOf(0f, 0f, 1f), 0f, 1f, 0f, 1f, 0f, 1f, BooleanArray(0))
    }
    val n0 = groundNormal?.takeIf { it.size == 3 && (it[0] != 0f || it[1] != 0f || it[2] != 0f) } ?: floatArrayOf(0f, 0f, 1f)
    val up = normalize3(n0[0] * upSign, n0[1] * upSign, n0[2] * upSign)
    val (r0, f0) = groundBasis(up)
    val stride = if (total > maxPts) (total + maxPts - 1) / maxPts else 1
    val us = ArrayList<Float>(); val vs = ArrayList<Float>(); val hs = ArrayList<Float>()
    var i = 0
    while (i < total) {
        val b = i * 3; val x = cloud[b]; val y = cloud[b + 1]; val z = cloud[b + 2]
        if (x.isFinite() && y.isFinite() && z.isFinite() && abs(x) <= 50000f && abs(y) <= 50000f && abs(z) <= 50000f) {
            us.add(x * r0[0] + y * r0[1] + z * r0[2])
            vs.add(x * f0[0] + y * f0[1] + z * f0[2])
            hs.add(x * up[0] + y * up[1] + z * up[2])
        }
        i += stride
    }
    val n = us.size
    val uA = FloatArray(n) { us[it] }; val vA = FloatArray(n) { vs[it] }; val hA = FloatArray(n) { hs[it] }
    fun rng(a: FloatArray): Pair<Float, Float> {
        if (a.isEmpty()) return 0f to 1f
        var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY
        for (v in a) { if (v < lo) lo = v; if (v > hi) hi = v }
        return lo to hi
    }
    val (uMin, uMax) = rng(uA); val (vMin, vMax) = rng(vA); val (hMin, hMax) = rng(hA)
    return TopProj(n, uA, vA, hA, r0, f0, up, uMin, uMax, vMin, vMax, hMin, hMax, BooleanArray(n))
}

/** 把基坐标的框转成世界系 ScanCropBox（center=cU·right0+cV·fwd0+cH·up；up=upSign·法向；half=[hU,hV,hH]）。 */
private fun worldBox(proj: TopProj, cU: Float, cV: Float, cH: Float, hU: Float, hV: Float, hH: Float, yawDeg: Float, upSign: Int): ScanCropBox {
    val cx = cU * proj.right0[0] + cV * proj.fwd0[0] + cH * proj.up[0]
    val cy = cU * proj.right0[1] + cV * proj.fwd0[1] + cH * proj.up[1]
    val cz = cU * proj.right0[2] + cV * proj.fwd0[2] + cH * proj.up[2]
    return ScanCropBox(
        center = floatArrayOf(cx, cy, cz),
        up = proj.up.copyOf(),
        yawDeg = yawDeg,
        half = floatArrayOf(hU, hV, hH),
    )
}

/** 框的 yaw 后正交基(right,fwd)：镜像服务端 CropBox.Basis。 */
private fun yawedBasis(proj: TopProj, yawDeg: Float): Pair<FloatArray, FloatArray> {
    val c = cos(yawDeg * Math.PI.toFloat() / 180f); val s = sin(yawDeg * Math.PI.toFloat() / 180f)
    val r = floatArrayOf(
        proj.right0[0] * c + proj.fwd0[0] * s, proj.right0[1] * c + proj.fwd0[1] * s, proj.right0[2] * c + proj.fwd0[2] * s,
    )
    val f = floatArrayOf(
        -proj.right0[0] * s + proj.fwd0[0] * c, -proj.right0[1] * s + proj.fwd0[1] * c, -proj.right0[2] * s + proj.fwd0[2] * c,
    )
    return r to f
}

/** 本地框内点计数（子采样近似），同时回填 proj.inMask 供画布高亮。镜像服务端 toBoxFrame 判定。 */
private fun countInBox(proj: TopProj, box: ScanCropBox): Int {
    if (proj.n == 0) return 0
    val (r, f) = yawedBasis(proj, box.yawDeg)
    val up = box.up; val c = box.center; val hU = box.half[0]; val hV = box.half[1]; val hH = box.half[2]
    var cnt = 0
    for (i in 0 until proj.n) {
        // 由 (u,v,h) 基坐标还原世界点：p = u·right0 + v·fwd0 + h·up。
        val pu = proj.u[i]; val pv = proj.v[i]; val ph = proj.h[i]
        val px = pu * proj.right0[0] + pv * proj.fwd0[0] + ph * up[0]
        val py = pu * proj.right0[1] + pv * proj.fwd0[1] + ph * up[1]
        val pz = pu * proj.right0[2] + pv * proj.fwd0[2] + ph * up[2]
        val dx = px - c[0]; val dy = py - c[1]; val dz = pz - c[2]
        val bu = dx * r[0] + dy * r[1] + dz * r[2]
        val bv = dx * f[0] + dy * f[1] + dz * f[2]
        val bw = dx * up[0] + dy * up[1] + dz * up[2]
        val inside = abs(bu) <= hU && abs(bv) <= hV && abs(bw) <= hH
        proj.inMask[i] = inside
        if (inside) cnt++
    }
    return cnt
}

// --- 初值 ---

private fun initialUpSign(box: ScanCropBox?, n: FloatArray?): Int {
    if (box == null || n == null || n.size != 3) return 1
    val dot = box.up[0] * n[0] + box.up[1] * n[1] + box.up[2] * n[2]
    return if (dot < 0) -1 else 1
}

/** 存框 center 投影到基的某轴（axis 0=u,1=v,2=h）。 */
private fun boxCenterAxis(box: ScanCropBox, proj: TopProj, axis: Int): Float {
    val basis = when (axis) { 0 -> proj.right0; 1 -> proj.fwd0; else -> proj.up }
    return box.center[0] * basis[0] + box.center[1] * basis[1] + box.center[2] * basis[2]
}

/**
 * 拟合默认框：各轴取 2–98 百分位罩住点云主体（排远端离群），center=区间中点，half=半区间夹紧。
 * 保证开场框内>0、黄框落在视野内——用户从一个"已套住云"的框起步微调，而非空框。返回 [cU,cV,cH,hU,hV,hH]。
 */
private fun fitToCloud(proj: TopProj): FloatArray {
    if (proj.n == 0) return floatArrayOf(0f, 0f, 0f, 1300f, 2000f, 1000f)
    fun band(a: FloatArray): Pair<Float, Float> {
        val s = a.copyOf(); s.sort()
        val n = s.size
        fun q(p: Float) = s[((n - 1) * p).toInt().coerceIn(0, n - 1)]
        return q(0.02f) to q(0.98f)
    }
    val (u0, u1) = band(proj.u); val (v0, v1) = band(proj.v); val (h0, h1) = band(proj.h)
    return floatArrayOf(
        (u0 + u1) / 2f, (v0 + v1) / 2f, (h0 + h1) / 2f,
        ((u1 - u0) / 2f).coerceIn(400f, 6000f),
        ((v1 - v0) / 2f).coerceIn(400f, 8000f),
        ((h1 - h0) / 2f).coerceIn(150f, 2000f),
    )
}

private fun dimStr(p: CropPreviewResult): String =
    "%.2f×%.2f×%.2f".format(p.lengthMm / 1000f, p.widthMm / 1000f, p.heightMm / 1000f)
