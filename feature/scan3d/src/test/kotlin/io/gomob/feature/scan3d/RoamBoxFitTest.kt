package io.gomob.feature.scan3d

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * walk→OBB 几何验收（Stage 4）：模拟"走一圈"足迹 → [fitRoamBox] 拟合最小面积矩形 → 该镜头系 ScanCropBox。
 * 锁定：① 框尺寸≈真实 footprint；② 框紧包合成"车"点云、远点被排除；③ 退化路径回 null。
 *
 * 坐标约定（up=+Z）：groundBasis(+Z) → right0=(0,1,0)、fwd0=(1,0,0)、up=(0,0,1)，
 * 故 (u,v,h) = (y, x, z)；合成世界点 (x=v, y=u, z=h)。
 */
class RoamBoxFitTest {

    /** 生成一个旋转矩形 footprint 的周边足迹路径 [u0,v0,...]（(u,v) 世界原点系）。 */
    private fun footprintPath(cu: Float, cv: Float, halfA: Float, halfB: Float, yawRad: Float, perSide: Int = 12): FloatArray {
        val c = cos(yawRad); val s = sin(yawRad)
        // 矩形局部 (a,b) → (u,v)：axis1=(c,s)、axis2=(-s,c)。
        fun toUV(a: Float, b: Float): Pair<Float, Float> =
            (cu + a * c + b * (-s)) to (cv + a * s + b * c)
        val out = ArrayList<Float>()
        fun add(a: Float, b: Float) { val (u, v) = toUV(a, b); out.add(u); out.add(v) }
        for (i in 0..perSide) { val t = -halfA + 2 * halfA * i / perSide; add(t, -halfB) }
        for (i in 0..perSide) { val t = -halfB + 2 * halfB * i / perSide; add(halfA, t) }
        for (i in 0..perSide) { val t = halfA - 2 * halfA * i / perSide; add(t, halfB) }
        for (i in 0..perSide) { val t = halfB - 2 * halfB * i / perSide; add(-halfA, t) }
        return out.toFloatArray()
    }

    /** 合成"车"点云：footprint 内 × 高度 [0,height] 体素填充，世界 (x=v,y=u,z=h)。 */
    private fun vehicleCloud(cu: Float, cv: Float, halfA: Float, halfB: Float, yawRad: Float, height: Float): FloatArray {
        val c = cos(yawRad); val s = sin(yawRad)
        val out = ArrayList<Float>()
        var a = -halfA
        while (a <= halfA) {
            var b = -halfB
            while (b <= halfB) {
                val u = cu + a * c + b * (-s); val v = cv + a * s + b * c
                var h = 0f
                while (h <= height) { out.add(v); out.add(u); out.add(h); h += 300f } // 世界 (x=v,y=u,z=h)
                b += 250f
            }
            a += 250f
        }
        return out.toFloatArray()
    }

    private fun halvesSorted(box: io.gomob.data.scan.ScanCropBox): Pair<Float, Float> {
        val a = box.half[0]; val b = box.half[1]
        return minOf(a, b) to maxOf(a, b)
    }

    @Test
    fun axisAlignedFootprint_recoversDims() {
        // footprint 2000(沿 a) × 5000(沿 b)，轴对齐。
        val path = footprintPath(cu = 0f, cv = 0f, halfA = 1000f, halfB = 2500f, yawRad = 0f)
        val cloud = vehicleCloud(0f, 0f, 1000f, 2500f, 0f, height = 1800f)
        val box = fitRoamBox(path, cloud, floatArrayOf(0f, 0f, 1f))
        assertNotNull("轴对齐 footprint 应拟出框", box)
        val (sh, lo) = halvesSorted(box!!)
        assertEquals("短半轴≈1000mm", 1000f, sh, 80f)
        assertEquals("长半轴≈2500mm", 2500f, lo, 80f)
        assertEquals("半高≈900mm", 900f, box.half[2], 200f)
    }

    @Test
    fun rotatedFootprint_recoversDimsAndContains() {
        val yaw = Math.toRadians(28.0).toFloat()
        val path = footprintPath(cu = 1500f, cv = -800f, halfA = 900f, halfB = 2400f, yawRad = yaw)
        val cloud = vehicleCloud(1500f, -800f, 900f, 2400f, yaw, height = 1600f)
        val box = fitRoamBox(path, cloud, floatArrayOf(0f, 0f, 1f))
        assertNotNull("旋转 footprint 应拟出框", box)
        val (sh, lo) = halvesSorted(box!!)
        assertEquals("短半轴≈900mm", 900f, sh, 90f)
        assertEquals("长半轴≈2400mm", 2400f, lo, 90f)
        // 框应紧包合成车：≥95% 车点在框内。
        val proj = projectTopView(cloud, floatArrayOf(0f, 0f, 1f), 1)
        val inN = countInBox(proj, box)
        assertTrue("框应含≥95%车点（含 $inN / ${proj.n}）", inN >= (proj.n * 0.95).toInt())
    }

    @Test
    fun farPointsExcluded() {
        val path = footprintPath(0f, 0f, 1000f, 2500f, 0f)
        // 车点 + 远处背景点（5万 mm 内、框外）。
        val veh = vehicleCloud(0f, 0f, 1000f, 2500f, 0f, 1800f)
        val bg = floatArrayOf(20000f, 20000f, 500f, -18000f, 15000f, 300f)
        val cloud = veh + bg
        val box = fitRoamBox(path, cloud, floatArrayOf(0f, 0f, 1f))!!
        val proj = projectTopView(cloud, floatArrayOf(0f, 0f, 1f), 1)
        val inN = countInBox(proj, box)
        // 框内不应含远点（远点 2 个全在框外）。
        assertTrue("远点应被框排除（含 $inN < 总 ${proj.n}）", inN <= proj.n - 1)
    }

    @Test
    fun degeneratePath_returnsNull() {
        assertNull("点不足应回 null", fitRoamBox(floatArrayOf(0f, 0f, 100f, 100f), FloatArray(0), null))
        // 共线足迹（零面积）应回 null。
        val collinear = FloatArray(20) { i -> if (i % 2 == 0) (i * 100).toFloat() else 0f }
        assertNull("共线零面积应回 null", fitRoamBox(collinear, FloatArray(0), null))
    }
}
