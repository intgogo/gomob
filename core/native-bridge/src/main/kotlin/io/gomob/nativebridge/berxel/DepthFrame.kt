package io.gomob.nativebridge.berxel

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 一帧 raw depth 数据 + 元信息。
 *
 * companion ep=0x82 出的字节流（firmware 不发 UVC payload header），按 byte count 切片成
 * `data` 字节数组，由 [BerxelFrameAssembler] 拼装。
 *
 * 格式：YUYV 16-bit packed（每像素 2 字节，LE）。default P100R3 = 640×401。
 */
data class DepthFrame(
    val data: ByteArray,
    val width: Int,
    val height: Int,
    val bytesPerPixel: Int = 2,
    val timestampNs: Long = System.nanoTime(),
) {
    val pixelCount: Int get() = width * height
    val totalBytes: Int get() = pixelCount * bytesPerPixel

    init {
        // firmware short-read 切帧时实际 data.size 可能比 width×height×bpp 多 / 少几字节
        // （多 12B = UVC payload header；多 width×bpp = 多 1 行 metadata；少 width×bpp = 少 1 行）。
        // 容忍 ±1 行 +12B 内的偏差，超过才抛 — assembler 已记 splitWarnings 统计。
        val tolerance = width * bytesPerPixel + 16
        require(kotlin.math.abs(data.size - totalBytes) <= tolerance) {
            "size mismatch: data=${data.size} expected=$totalBytes ($width×$height×$bytesPerPixel) tol=$tolerance"
        }
        require(bytesPerPixel == 2 || bytesPerPixel == 1) { "bytesPerPixel must be 1 (IR grey) or 2 (depth/IR-packed) (got $bytesPerPixel)" }
    }

    /** 读取像素 (x,y) 的 raw 16-bit depth 值（LE）。 */
    fun pixelAt(x: Int, y: Int): Int {
        val offset = (y * width + x) * bytesPerPixel
        return (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)
    }

    /** 拿一个 LE ShortBuffer view（零拷贝），便于上层算法批量读 uint16。 */
    fun shortBuffer(): java.nio.ShortBuffer =
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

    /** 快速统计：min / max / mean / 非零像素百分比。诊断用。 */
    fun stats(): DepthStats {
        var min = Int.MAX_VALUE
        var max = Int.MIN_VALUE
        var sum = 0L
        var nonzero = 0
        val sb = shortBuffer()
        for (i in 0 until pixelCount) {
            val v = sb.get(i).toInt() and 0xffff
            if (v != 0) {
                nonzero++
                sum += v
                if (v < min) min = v
                if (v > max) max = v
            }
        }
        return if (nonzero == 0) {
            DepthStats(0, 0, 0.0, 0)
        } else {
            DepthStats(min, max, sum.toDouble() / nonzero, nonzero)
        }
    }

    // ByteArray equals/hashCode 默认是 reference equality；data class 自动生成的也用 reference。
    // 这里我们不强求 value equality（同一帧不应该被复制），保持默认 reference identity 即可。
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

/** Depth 像素值统计快照。`min`/`max`/`meanNonzero` 跳过零像素（视为 invalid / 远景）。 */
data class DepthStats(
    val min: Int,
    val max: Int,
    val meanNonzero: Double,
    val nonzeroCount: Int,
) {
    fun pretty(totalPixels: Int): String {
        val zeroPct = if (totalPixels > 0) {
            100.0 * (totalPixels - nonzeroCount) / totalPixels
        } else 0.0
        return "min=$min max=$max mean=${"%.1f".format(meanNonzero)} " +
            "nonzero=$nonzeroCount zero=${"%.1f".format(zeroPct)}%"
    }
}
