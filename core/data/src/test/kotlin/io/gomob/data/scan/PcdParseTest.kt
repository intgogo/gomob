package io.gomob.data.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * parsePcdBinary{,WithAngles} 解析测试。锁 M9.11 回归：单元云改存 XYZI(16B/点，intensity=h_angle°)后，
 * 老的"仅 x y z"硬断言会让 App 下载单元云直接抛异常。两种 FIELDS 都要解对。
 */
class PcdParseTest {

    private fun buildPcd(fields: String, perPt: Int, data: FloatArray): ByteArray {
        val pts = data.size / perPt
        val header = buildString {
            append("# .PCD v0.7\n")
            append("VERSION 0.7\n")
            append("FIELDS $fields\n")
            append("SIZE ${"4 ".repeat(perPt).trim()}\n")
            append("TYPE ${"F ".repeat(perPt).trim()}\n")
            append("COUNT ${"1 ".repeat(perPt).trim()}\n")
            append("WIDTH $pts\n")
            append("HEIGHT 1\n")
            append("VIEWPOINT 0 0 0 1 0 0 0\n")
            append("POINTS $pts\n")
            append("DATA binary\n")
        }
        val bb = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in data) bb.putFloat(f)
        return ByteArrayOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII)); write(bb.array())
        }.toByteArray()
    }

    @Test
    fun `XYZ 融合云解析 xyz 正确且 angles 为空`() {
        val xyz = floatArrayOf(1f, 2f, 3f, -4f, 5.5f, 6f)
        val pcd = buildPcd("x y z", 3, xyz)
        val r = parsePcdBinaryWithAngles(pcd)
        assertArrayEquals(xyz, r.xyz, 1e-4f)
        assertEquals(0, r.angles.size)
    }

    @Test
    fun `XYZI 单元云解析 xyz 与 h_angle 都正确`() {
        // 每点 [x,y,z,intensity=h_angle°]
        val raw = floatArrayOf(
            10f, 0f, 100f, 0.5f,
            20f, 0f, 100f, 14.2f,
            -30f, 5f, 90f, -178.0f,
        )
        val pcd = buildPcd("x y z intensity", 4, raw)
        val r = parsePcdBinaryWithAngles(pcd)
        assertArrayEquals(floatArrayOf(10f, 0f, 100f, 20f, 0f, 100f, -30f, 5f, 90f), r.xyz, 1e-4f)
        assertArrayEquals(floatArrayOf(0.5f, 14.2f, -178.0f), r.angles, 1e-4f)
    }

    @Test
    fun `parsePcdBinary 对 XYZI 也只取 xyz 不抛`() {
        val raw = floatArrayOf(1f, 2f, 3f, 42f)
        val xyz = parsePcdBinary(buildPcd("x y z intensity", 4, raw))
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), xyz, 1e-4f)
    }

    @Test
    fun `不支持的 FIELDS 抛异常`() {
        var threw = false
        try {
            parsePcdBinary(buildPcd("x y z rgb", 4, floatArrayOf(1f, 2f, 3f, 4f)))
        } catch (e: IllegalArgumentException) {
            threw = true
        }
        assertTrue("未知 FIELDS 应抛 IllegalArgumentException", threw)
    }
}
