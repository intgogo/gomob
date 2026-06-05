package io.gomob.feature.scan3d

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import io.gomob.data.scan.CropPreviewResult
import io.gomob.data.scan.ScanCropBox
import io.gomob.designsystem.theme.Gomob
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 第一视角漫游标注屏（M10.4）：在某镜头点云里以"人站地面"第一视角走动——左虚拟摇杆走前后左右、
 * 右半屏拖动转头/抬头低头；进"标注"后走过的地面足迹连成路径，"完成"时凸包拟合最小面积矩形 →
 * 反算为该镜头系 [ScanCropBox] → 转交顶视 [LaserCropBoxEditor] 微调（含翻转）后保存。
 *
 * 几何与服务端 cropbox.go 一致：路径 (u,v) 在 groundBasis(up) 世界原点系，与 [projectTopView]/[worldBox]
 * 同源——漫游 view 的地面基由 [PointCloudSurfaceView.setGround] 用同一 up 构造，故路径直接喂 worldBox。
 */
@Composable
fun RoamAnnotationScreen(
    cloud: FloatArray,
    groundNormal: FloatArray?, // null/无效→默认 +Z（unitB 设备系近似竖直）
    onPreview: suspend (ScanCropBox) -> CropPreviewResult?,
    onSave: (ScanCropBox) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    // 地面取向（up + offset）：用云 h 低分位当地面，让网格/眼高贴合云；upSign=1（信 groundNormal/+Z 朝上，
    // 翻转交给编辑器的「翻转」按钮兜底）。
    val ground = remember(cloud, groundNormal) { roamGround(cloud, groundNormal) }
    val view = remember { PointCloudSurfaceView(context, ground.centerZ, autoFit = true) }

    LaunchedEffect(Unit) {
        view.setGround(ground.up, ground.d, show = true)
        view.setPoints(cloud)
        view.enterRoamMode(eyeHeight = 1600f)
    }
    androidx.compose.runtime.DisposableEffect(view) { onDispose { view.destroy() } }

    var annotating by remember { mutableStateOf(false) }
    var sampleCount by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var fitted by remember { mutableStateOf<ScanCropBox?>(null) }

    // 标注中轮询采样计数（view 内部按位移采样，UI 轮询显示进度）。
    LaunchedEffect(annotating) {
        while (annotating) { sampleCount = view.pathSampleCount(); delay(150) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF060912))) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())

        // look-pad（全屏，最底层 overlay）：拖动转头/抬头。摇杆/按钮在其上、各自消费指针，互不抢。
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    view.applyLook(dYaw = drag.x * 0.004f, dPitch = -drag.y * 0.004f)
                }
            },
        )

        // 左虚拟摇杆（走动）。
        RoamJoystick(
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 28.dp, bottom = 44.dp),
            onMove = { strafe, forward, mag -> view.setMoveInput(strafe, forward, mag) },
        )

        // 顶部 HUD：取消 / 提示 / 标注 toggle。
        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayPill("取消") { onDismiss() }
            Text(
                if (annotating) "走一圈圈出车位 · 已采 $sampleCount 点" else "第一视角 · 左摇杆走动，右侧拖动转头",
                fontSize = 9.sp, color = Gomob.colors.fg2,
            )
            OverlayPill(if (annotating) "标注中 ●" else "开始标注 ▴", accent = annotating) {
                annotating = !annotating
                view.setAnnotating(annotating)
            }
        }

        // 完成圈选（标注中且采样≥4 才可用）：拟合最小面积矩形 → 顶视编辑器微调保存。
        if (annotating) {
            val enough = sampleCount >= 4
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 44.dp)
                    .clip(CircleShape)
                    .background(if (enough) Gomob.colors.accentSoft else Gomob.colors.bg1)
                    .border(BorderStroke(1.dp, if (enough) Gomob.colors.accent else Gomob.colors.line2), CircleShape)
                    .clickable(enabled = enough) {
                        val box = fitRoamBox(view.pathSamplesUV(), cloud, groundNormal)
                        if (box != null) {
                            fitted = box; editing = true
                            annotating = false; view.setAnnotating(false); view.setMoveInput(0f, 0f, 0f)
                        }
                    }
                    .padding(horizontal = 28.dp, vertical = 12.dp),
            ) {
                Text("完成圈选", fontSize = 14.sp, color = if (enough) Gomob.colors.accent else Gomob.colors.fg3)
            }
        }

        // 重走（标注中常驻，清空已画路径重来）。
        if (annotating && sampleCount > 0) {
            Box(
                Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 44.dp)
                    .clip(CircleShape).background(Gomob.colors.bg1)
                    .border(BorderStroke(1.dp, Gomob.colors.line2), CircleShape)
                    .clickable { view.resetPath(); sampleCount = 0 }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) { Text("重走", fontSize = 13.sp, color = Gomob.colors.fg2) }
        }
    }

    // 顶视编辑器叠层（不透明，盖住漫游层）：微调 + 翻转 + 保存。返回=回漫游继续走。
    if (editing && fitted != null) {
        Box(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
            LaserCropBoxEditor(
                cloud = cloud,
                groundNormal = ground.up,
                initialBox = fitted,
                onPreview = onPreview,
                onSave = onSave,
                onDismiss = { editing = false },
            )
        }
    }
}

/** 左虚拟摇杆：固定底盘，摇杆头随触摸偏移（累计 drag，clamp 半径）。deadzone 12%。消费自身指针。 */
@Composable
private fun RoamJoystick(
    modifier: Modifier = Modifier,
    onMove: (strafe: Float, forward: Float, mag: Float) -> Unit,
) {
    val baseDp = 132.dp
    val radiusPx = with(LocalDensity.current) { 56.dp.toPx() }
    var knob by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.size(baseDp).clip(CircleShape)
            .background(Gomob.colors.bg0.copy(alpha = 0.55f))
            .border(BorderStroke(1.dp, Gomob.colors.line2), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { knob = Offset.Zero },
                    onDragEnd = { knob = Offset.Zero; onMove(0f, 0f, 0f) },
                    onDragCancel = { knob = Offset.Zero; onMove(0f, 0f, 0f) },
                ) { change, drag ->
                    change.consume()
                    knob = clampLen(knob + drag, radiusPx)
                    emitStick(knob, radiusPx, onMove)
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color(0xFF5BD6FF).copy(alpha = 0.15f), radius = radiusPx, center = center)
            drawCircle(Color(0xFF5BD6FF), radius = 24f, center = center + knob)
        }
    }
}

private fun clampLen(v: Offset, r: Float): Offset {
    val len = hypot(v.x, v.y)
    return if (len <= r || len < 1e-4f) v else v * (r / len)
}

/** 摇杆向量 → 移动输入：strafe=+x(右)、forward=-y(上=前)、mag 含 deadzone 重标定。 */
private fun emitStick(knob: Offset, radius: Float, onMove: (Float, Float, Float) -> Unit) {
    val len = hypot(knob.x, knob.y)
    val dz = radius * 0.12f
    if (len < dz) { onMove(0f, 0f, 0f); return }
    val nx = knob.x / len; val ny = knob.y / len
    val mag = ((len - dz) / (radius - dz)).coerceIn(0f, 1f)
    onMove(nx, -ny, mag)
}

// ───── walk → OBB 几何（凸包 + 最小面积外接矩形；纯函数，供 harness 验） ─────

/** 地面取向结果：定向 up + 平面 offset d（up·x+d=0）+ 取景用 centerZ。 */
private class RoamGround(val up: FloatArray, val d: Float, val centerZ: Float)

private fun roamGround(cloud: FloatArray, groundNormal: FloatArray?): RoamGround {
    val n0 = if (groundNormal != null && groundNormal.size == 3 &&
        (groundNormal[0] != 0f || groundNormal[1] != 0f || groundNormal[2] != 0f)
    ) groundNormal else floatArrayOf(0f, 0f, 1f)
    val l = sqrt(n0[0] * n0[0] + n0[1] * n0[1] + n0[2] * n0[2]).coerceAtLeast(1e-6f)
    val up = floatArrayOf(n0[0] / l, n0[1] / l, n0[2] / l)
    val hFloor = lowPercentileH(cloud, up, 0.05f)
    return RoamGround(up, -hFloor, meanZRoam(cloud))
}

/** 云沿 up 的 5% 低分位（地面高度估计），抗离群。 */
private fun lowPercentileH(cloud: FloatArray, up: FloatArray, p: Float): Float {
    val n = cloud.size / 3
    if (n == 0) return 0f
    val hs = ArrayList<Float>(n)
    var i = 0
    while (i < cloud.size) {
        val x = cloud[i]; val y = cloud[i + 1]; val z = cloud[i + 2]
        if (x.isFinite() && y.isFinite() && z.isFinite() && kotlin.math.abs(x) <= 50000f && kotlin.math.abs(y) <= 50000f && kotlin.math.abs(z) <= 50000f) {
            hs.add(x * up[0] + y * up[1] + z * up[2])
        }
        i += 3
    }
    if (hs.isEmpty()) return 0f
    hs.sort()
    return hs[((hs.size - 1) * p).toInt().coerceIn(0, hs.size - 1)]
}

private fun meanZRoam(cloud: FloatArray): Float {
    if (cloud.size < 3) return 0f
    var s = 0.0; var c = 0; var i = 2
    while (i < cloud.size) { s += cloud[i]; c++; i += 3 }
    return if (c == 0) 0f else (s / c).toFloat()
}

/**
 * 走过的足迹 (u,v) → 最小面积外接矩形 → 该镜头系 [ScanCropBox]。
 * 凸包(Andrew monotone chain) + 逐 hull 边对齐求最小面积矩形（最小矩形必有一边与凸包边共线）。
 * 高度取 footprint 内点的 up 跨度（不足回退默认带）。路径退化（点不足 / 零面积）回 null。
 */
internal fun fitRoamBox(uv: FloatArray, cloud: FloatArray, groundNormal: FloatArray?): ScanCropBox? {
    val k = uv.size / 2
    if (k < 3) return null
    val pts = ArrayList<DoubleArray>(k)
    for (i in 0 until k) pts.add(doubleArrayOf(uv[i * 2].toDouble(), uv[i * 2 + 1].toDouble()))
    val hull = convexHull(pts)
    if (hull.size < 3) return null

    var bestArea = Double.MAX_VALUE
    var bCx = 0.0; var bCy = 0.0; var bW = 0.0; var bH = 0.0; var bUx = 1.0; var bUy = 0.0
    for (i in hull.indices) {
        val a = hull[i]; val b = hull[(i + 1) % hull.size]
        val elen = hypot(b[0] - a[0], b[1] - a[1])
        if (elen < 1e-6) continue
        val ux = (b[0] - a[0]) / elen; val uy = (b[1] - a[1]) / elen // 矩形一轴
        val vx = -uy; val vy = ux                                     // 垂直轴
        var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE
        var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
        for (p in hull) {
            val du = p[0] * ux + p[1] * uy
            val dv = p[0] * vx + p[1] * vy
            if (du < minU) minU = du; if (du > maxU) maxU = du
            if (dv < minV) minV = dv; if (dv > maxV) maxV = dv
        }
        val w = maxU - minU; val h = maxV - minV
        val area = w * h
        if (area < bestArea) {
            bestArea = area
            val cu = (minU + maxU) / 2; val cv = (minV + maxV) / 2
            bCx = cu * ux + cv * vx; bCy = cu * uy + cv * vy // 中心回 (u,v) 世界基坐标
            bW = w; bH = h; bUx = ux; bUy = uy
        }
    }
    if (bestArea >= Double.MAX_VALUE) return null

    // (u,v) = (沿 right0, 沿 fwd0)。约定：fwd=矩形 axis1=(bUx,bUy)、hV=bW/2；right=垂直轴、hU=bH/2。
    // worldBox 的 yaw 使 box fwd(u,v)=(-sin,cos)。令其=(bUx,bUy) → cos=bUy, sin=-bUx → yaw=atan2(-bUx,bUy)。
    var yawDeg = Math.toDegrees(atan2(-bUx, bUy)).toFloat()
    // 框对 180° 旋转同形 → 归一到 (-90,90]，贴合编辑器约定。
    while (yawDeg > 90f) yawDeg -= 180f
    while (yawDeg <= -90f) yawDeg += 180f
    val hU = (bH / 2.0).toFloat().coerceAtLeast(150f)
    val hV = (bW / 2.0).toFloat().coerceAtLeast(150f)
    if (bestArea < 0.25e6) return null // <0.25 m² 视为退化（没真正圈出区域）

    // 高度：footprint 内点的 up 跨度。proj 与路径同源（同 up、同 groundBasis）。
    val proj = projectTopView(cloud, groundNormal, upSign = 1)
    val (cH, hH) = footprintHeight(proj, bCx, bCy, bUx, bUy, bW / 2, bH / 2)
    return worldBox(proj, bCx.toFloat(), bCy.toFloat(), cH, hU, hV, hH, yawDeg, upSign = 1)
}

/** footprint(矩形) 内点的 up 跨度 → (cH, hH)；不足回退默认带（地面上方 1m，半高 1m）。 */
private fun footprintHeight(
    proj: TopProj, cx: Double, cy: Double, ux: Double, uy: Double, halfA: Double, halfB: Double,
): Pair<Float, Float> {
    val vx = -uy; val vy = ux
    var hMin = Float.POSITIVE_INFINITY; var hMax = Float.NEGATIVE_INFINITY; var inN = 0
    for (i in 0 until proj.n) {
        val du = (proj.u[i] - cx) * ux + (proj.v[i] - cy) * uy
        val dv = (proj.u[i] - cx) * vx + (proj.v[i] - cy) * vy
        if (kotlin.math.abs(du) <= halfA && kotlin.math.abs(dv) <= halfB) {
            val h = proj.h[i]
            if (h < hMin) hMin = h; if (h > hMax) hMax = h; inN++
        }
    }
    if (inN < 10 || !hMin.isFinite() || !hMax.isFinite()) {
        val floor = if (proj.n > 0) proj.hMin else 0f
        return (floor + 1000f) to 1000f
    }
    val cH = (hMin + hMax) / 2f
    val hH = ((hMax - hMin) / 2f).coerceIn(150f, 2500f)
    return cH to hH
}

/** Andrew monotone chain 凸包（逆时针，不含重复端点）。输入可乱序；<3 点回原样。 */
private fun convexHull(input: List<DoubleArray>): List<DoubleArray> {
    val pts = input.distinctBy { it[0] to it[1] }.sortedWith(compareBy({ it[0] }, { it[1] }))
    if (pts.size < 3) return pts
    fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray): Double =
        (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    val lower = ArrayList<DoubleArray>()
    for (p in pts) {
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
        lower.add(p)
    }
    val upper = ArrayList<DoubleArray>()
    for (p in pts.asReversed()) {
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
        upper.add(p)
    }
    lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
    return lower + upper
}
