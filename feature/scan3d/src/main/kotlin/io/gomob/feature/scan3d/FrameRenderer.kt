package io.gomob.feature.scan3d

import android.graphics.Bitmap
import io.gomob.model.ColorFrame
import io.gomob.model.DepthFrame
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

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
        for (i in 0 until total) {
            val r = src.get().toInt() and 0xFF
            val g = src.get().toInt() and 0xFF
            val b = src.get().toInt() and 0xFF
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * 把 iHawk Depth 帧 16bit mm 字节流转成伪彩 ARGB_8888 Bitmap（turbo colormap）。
     *
     * 契约：[DepthFrame.data] 已由 [io.gomob.nativebridge.berxel.BerxelService] 一次性
     * 把 SDK 的 12.4 / 13.3 定点格式右移转成纯毫米整数 — 本函数直接当 mm 读，不再右移。
     *
     * 颜色映射：[minMm, maxMm] 区间线性映射到 turbo 256-stop colormap；0/无效像素出黑。
     *
     * confidence：[DepthFrame.confidence] 非空且 [maskByConfidence] 时，逐像素 conf==0（无效/飞点）
     * 或低于 [confidenceMin] 的不可靠像素一律出黑；[data] 原 mm 不被改写（量测真值由下游另取）。
     */
    fun depth16ToBitmap(
        frame: DepthFrame,
        minMm: Int? = null,
        maxMm: Int? = null,
        maskByConfidence: Boolean = true,
        confidenceMin: Int = PREVIEW_CONFIDENCE_MIN,
    ): Bitmap? {
        val w = frame.width
        val h = frame.height
        val src = frame.data.duplicate().order(ByteOrder.LITTLE_ENDIAN)
        src.rewind()
        val total = w * h
        if (src.remaining() < total * 2) return null

        // confidence（uint8，与 data 同尺寸）。预览默认按低置信遮罩：
        // depth mm 保持原始，不在渲染层补点；不可靠像素直接黑掉，避免假深度被伪彩强化。
        val conf: ByteArray? = frame.confidence?.let { cb ->
            val d = cb.duplicate(); d.rewind()
            if (d.remaining() >= total) ByteArray(total).also { d.get(it, 0, total) } else null
        }.takeIf { maskByConfidence }

        val autoRange = if (minMm == null || maxMm == null) {
            estimatePreviewRange(src, total, conf, confidenceMin)
        } else {
            minMm to maxMm
        }
        val lo = (minMm ?: autoRange.first).coerceIn(PREVIEW_MIN_MM, PREVIEW_MAX_MM - 1)
        val hi = (maxMm ?: autoRange.second).coerceIn(lo + 1, PREVIEW_MAX_MM)
        val pixels = IntArray(total)
        val span = (hi - lo).coerceAtLeast(1)
        src.rewind()
        for (i in 0 until total) {
            val mm = src.short.toInt() and 0xFFFF
            val masked = conf != null && (conf[i].toInt() and 0xFF) < confidenceMin
            pixels[i] = if (masked || mm == 0 || mm < lo || mm > hi) {
                0xFF000000.toInt()
            } else {
                val t = ((mm - lo).toFloat() / span).coerceIn(0f, 1f)
                turboColor(t)
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /**
     * 把 DepthFrame.data 按 8-bit grey 渲染（IR raw 路径）。
     * 2026-05-28 dump 分析确认 P100R3 companion 推 IR raw：byte0=phase code、byte1=IR luminance。
     * BerxelService NATIVE_REWRITE 路径已提取 byte1 → 8-bit grey data，width=640 height=401。
     */
    fun depthRawAsGrey(frame: DepthFrame): Bitmap? {
        val src = frame.data.duplicate()
        src.rewind()
        val w = frame.width
        val h = frame.height
        val total = w * h
        if (src.remaining() < total) return null
        val pixels = IntArray(total)
        for (i in 0 until total) {
            val g = src.get().toInt() and 0xff
            pixels[i] = (0xff shl 24) or (g shl 16) or (g shl 8) or g
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

    private fun estimatePreviewRange(
        src: ByteBuffer,
        total: Int,
        conf: ByteArray?,
        confidenceMin: Int,
    ): Pair<Int, Int> {
        val hist = IntArray(PREVIEW_MAX_MM + 1)
        val step = max(1, total / PREVIEW_SAMPLE_TARGET)
        var valid = 0
        var i = 0
        while (i < total) {
            val mm = src.getShort(i * 2).toInt() and 0xFFFF
            val masked = conf != null && (conf[i].toInt() and 0xFF) < confidenceMin
            if (!masked && mm in PREVIEW_MIN_MM..PREVIEW_MAX_MM) {
                hist[mm]++
                valid++
            }
            i += step
        }
        if (valid < PREVIEW_MIN_VALID_SAMPLES) return DEFAULT_MIN_MM to DEFAULT_MAX_MM

        val lowRank = max(1, (valid * 0.02f).toInt())
        val highRank = max(lowRank + 1, (valid * 0.98f).toInt())
        var acc = 0
        var low = DEFAULT_MIN_MM
        var high = DEFAULT_MAX_MM
        for (mm in PREVIEW_MIN_MM..PREVIEW_MAX_MM) {
            acc += hist[mm]
            if (acc >= lowRank) {
                low = mm
                break
            }
        }
        acc = 0
        for (mm in PREVIEW_MIN_MM..PREVIEW_MAX_MM) {
            acc += hist[mm]
            if (acc >= highRank) {
                high = mm
                break
            }
        }
        if (high - low < PREVIEW_MIN_SPAN_MM) {
            val mid = (low + high) / 2
            low = max(PREVIEW_MIN_MM, mid - PREVIEW_MIN_SPAN_MM / 2)
            high = min(PREVIEW_MAX_MM, low + PREVIEW_MIN_SPAN_MM)
        }
        return low to high
    }

    private const val PREVIEW_MIN_MM = 150
    private const val PREVIEW_MAX_MM = 8_000
    private const val DEFAULT_MIN_MM = 200
    private const val DEFAULT_MAX_MM = 2_500
    private const val PREVIEW_MIN_SPAN_MM = 350
    private const val PREVIEW_SAMPLE_TARGET = 32_000
    private const val PREVIEW_MIN_VALID_SAMPLES = 32
    private const val PREVIEW_CONFIDENCE_MIN = 64
}

/** 把 [ByteBuffer] 当连续字节看时的 short 读取扩展（小端）。 */
private val ByteBuffer.short: Short get() = getShort()
