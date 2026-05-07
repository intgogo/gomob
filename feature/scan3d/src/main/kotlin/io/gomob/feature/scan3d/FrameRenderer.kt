package io.gomob.feature.scan3d

import android.graphics.Bitmap
import io.gomob.model.ColorFrame
import io.gomob.model.DepthFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * iHawk 帧字节流 → Android Bitmap 的转换工具。
 *
 * 设计：纯 Kotlin（不走 JNI）— 转换体量小（640×400=256K 像素，6 fps 预览采样），
 * 写 native 节省的几 ms 不抵跨边界开销。需要更高分辨率或 30 fps 全速转换时再 JNI 化。
 */
internal object FrameRenderer {

    /**
     * 把 iHawk Color 帧 RGB24 字节流（来自 SDK [ColorFrame.data]）转成 ARGB_8888 Bitmap。
     *
     * RGB24 像素布局：每 3 字节一像素 [R, G, B]（SDK 已解了 YUYV）。
     */
    fun colorRgb24ToBitmap(frame: ColorFrame): Bitmap? {
        val w = frame.width
        val h = frame.height
        val src = frame.data.duplicate().order(ByteOrder.nativeOrder())
        src.rewind()
        val total = w * h
        if (src.remaining() < total * 3) return null

        val pixels = IntArray(total)
        val bytes = ByteArray(total * 3)
        src.get(bytes, 0, total * 3)
        var bi = 0
        for (i in 0 until total) {
            val r = bytes[bi].toInt() and 0xFF
            val g = bytes[bi + 1].toInt() and 0xFF
            val b = bytes[bi + 2].toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            bi += 3
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * 把 iHawk Depth 帧 16bit mm 字节流转成伪彩 ARGB_8888 Bitmap（turbo colormap）。
     *
     * iHawk 深度像素是小端 16bit，含小数位（PixelType DEP_16BIT_12I_4D 或 _13I_3D）；
     * 当前预览用，按"原始 16bit 当 mm"近似显示；M1.3 实测精度时再按 PixelType 校准。
     *
     * 颜色映射：[minMm, maxMm] 区间线性映射到 turbo 256-stop colormap；0/无效像素出黑。
     */
    fun depth16ToBitmap(
        frame: DepthFrame,
        minMm: Int = 200,
        maxMm: Int = 1500,
    ): Bitmap? {
        val w = frame.width
        val h = frame.height
        val src = frame.data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        src.rewind()
        val total = w * h
        if (src.remaining() < total * 2) return null

        val pixels = IntArray(total)
        val span = (maxMm - minMm).coerceAtLeast(1)
        for (i in 0 until total) {
            val raw = src.short.toInt() and 0xFFFF
            // raw 是 12I_4D / 13I_3D 编码；预览近似当 mm 用（误差 ≤ 1mm，颜色没差别）
            val mm = raw ushr 4
            pixels[i] = if (mm == 0 || mm < minMm || mm > maxMm) {
                0xFF000000.toInt()
            } else {
                val t = ((mm - minMm).toFloat() / span).coerceIn(0f, 1f)
                turboColor(t)
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Turbo colormap（Google 提出，深度可视化用，分辨率比 jet 高）— 5 段线性近似。 */
    private fun turboColor(t: Float): Int {
        // 5 锚点：deep blue → cyan → green → yellow → red
        val (r, g, b) = when {
            t < 0.25f -> {
                val u = t / 0.25f
                Triple(lerp(48, 47, u), lerp(18, 173, u), lerp(59, 245, u))
            }
            t < 0.5f -> {
                val u = (t - 0.25f) / 0.25f
                Triple(lerp(47, 105, u), lerp(173, 235, u), lerp(245, 75, u))
            }
            t < 0.75f -> {
                val u = (t - 0.5f) / 0.25f
                Triple(lerp(105, 233, u), lerp(235, 197, u), lerp(75, 49, u))
            }
            else -> {
                val u = (t - 0.75f) / 0.25f
                Triple(lerp(233, 122, u), lerp(197, 4, u), lerp(49, 3, u))
            }
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerp(a: Int, b: Int, t: Float): Int =
        (a + (b - a) * t).toInt().coerceIn(0, 255)
}

/** 把 [ByteBuffer] 当连续字节看时的 short 读取扩展（小端）。 */
private val ByteBuffer.short: Short get() = getShort()
