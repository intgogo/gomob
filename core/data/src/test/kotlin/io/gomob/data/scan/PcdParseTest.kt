package io.gomob.data.scan

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response
import java.io.ByteArrayOutputStream
import java.io.InputStream
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
    fun `XYZRGB 解析 xyz 与每点颜色`() {
        val raw = floatArrayOf(
            1f, 2f, 3f, java.lang.Float.intBitsToFloat(0x00ff3300),
            4f, 5f, 6f, java.lang.Float.intBitsToFloat(0x000077ff),
        )
        val cloud = parsePcdBinaryRenderData(buildPcd("x y z rgb", 4, raw))
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), cloud.xyz, 1e-4f)
        assertArrayEquals(intArrayOf(0x00ff3300, 0x000077ff), cloud.rgb!!)
    }

    @Test
    fun `渲染采样 PCD 保留权威源点数`() {
        val sampled = buildPcd("x y z", 3, floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        val withSourceCount = "# GOMOB_SOURCE_POINTS 4450000\n".toByteArray(Charsets.US_ASCII) + sampled
        val cloud = parsePcdBinaryRenderData(withSourceCount)

        assertEquals(4_450_000, cloud.pointCount)
        assertEquals(2, cloud.renderPointCount)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `源点数小于渲染点数时拒绝损坏 PCD`() {
        val sampled = buildPcd("x y z", 3, floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        parsePcdBinaryRenderData("# GOMOB_SOURCE_POINTS 1\n".toByteArray(Charsets.US_ASCII) + sampled)
    }

    @Test
    fun `响应头校验 canonical 与返回采样点数`() {
        val sampled = buildPcd("x y z", 3, floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        val body = ("# GOMOB_SOURCE_POINTS 4450000\n".toByteArray(Charsets.US_ASCII) + sampled)
            .toResponseBody("application/octet-stream".toMediaType())
        val response = Response.success(
            body,
            Headers.headersOf(
                "X-Gomob-Source-Points", "4450000",
                "X-Gomob-Render-Points", "2",
            ),
        )

        val cloud = parseCloudResponse(response, maxRenderPoints = 2, requirePointHeaders = true, endpoint = "test")

        assertEquals(4_450_000, cloud.sourcePointCount)
        assertEquals(2, cloud.renderPointCount)
    }

    @Test
    fun `响应头与 PCD 源点数不一致时拒绝`() {
        val sampled = buildPcd("x y z", 3, floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        val body = ("# GOMOB_SOURCE_POINTS 4450000\n".toByteArray(Charsets.US_ASCII) + sampled)
            .toResponseBody("application/octet-stream".toMediaType())
        val response = Response.success(
            body,
            Headers.headersOf(
                "X-Gomob-Source-Points", "4449999",
                "X-Gomob-Render-Points", "2",
            ),
        )

        try {
            parseCloudResponse(response, maxRenderPoints = 2, requirePointHeaders = true, endpoint = "test")
            fail("应拒绝响应头与 PCD 注释不一致")
        } catch (_: IllegalArgumentException) {
            Unit
        }
    }

    @Test
    fun `百万点流式 PCD 只驻留指定渲染预算`() {
        val sourcePoints = 1_000_000
        val cloud = parsePcdBinaryRenderData(
            input = ZeroFilledPcdInputStream(sourcePoints),
            maxRenderPoints = 4096,
        )

        assertEquals(sourcePoints, cloud.sourcePointCount)
        assertEquals(4096, cloud.renderPointCount)
        assertEquals(4096 * 3, cloud.xyz.size)
    }

    @Test
    fun `XYZRGBI 同时保留颜色与角度`() {
        val raw = floatArrayOf(
            1f, 2f, 3f, java.lang.Float.intBitsToFloat(0x00112233), 17f,
        )
        val pcd = buildPcd("x y z rgb intensity", 5, raw)
        val render = parsePcdBinaryRenderData(pcd)
        val angles = parsePcdBinaryWithAngles(pcd)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), render.xyz, 1e-4f)
        assertArrayEquals(intArrayOf(0x00112233), render.rgb!!)
        assertArrayEquals(floatArrayOf(17f), render.angles, 1e-4f)
        assertArrayEquals(floatArrayOf(17f), angles.angles, 1e-4f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `不支持的 FIELDS 抛异常`() {
        parsePcdBinary(buildPcd("x y z normal", 4, floatArrayOf(1f, 2f, 3f, 4f)))
    }
}

private class ZeroFilledPcdInputStream(points: Int) : InputStream() {
    private val header = buildString {
        append("# .PCD v0.7\n")
        append("VERSION 0.7\n")
        append("FIELDS x y z\n")
        append("SIZE 4 4 4\n")
        append("TYPE F F F\n")
        append("COUNT 1 1 1\n")
        append("WIDTH $points\n")
        append("HEIGHT 1\n")
        append("VIEWPOINT 0 0 0 1 0 0 0\n")
        append("POINTS $points\n")
        append("DATA binary\n")
    }.toByteArray(Charsets.US_ASCII)
    private var headerOffset = 0
    private var bodyRemaining = points.toLong() * 12L

    override fun read(): Int {
        if (headerOffset < header.size) return header[headerOffset++].toInt() and 0xff
        if (bodyRemaining <= 0L) return -1
        bodyRemaining--
        return 0
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (headerOffset < header.size) {
            val count = minOf(length, header.size - headerOffset)
            System.arraycopy(header, headerOffset, target, offset, count)
            headerOffset += count
            return count
        }
        if (bodyRemaining <= 0L) return -1
        val count = minOf(length.toLong(), bodyRemaining).toInt()
        java.util.Arrays.fill(target, offset, offset + count, 0.toByte())
        bodyRemaining -= count
        return count
    }
}
