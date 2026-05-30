package io.gomob.nativebridge.berxel

/**
 * 把 master UVC BULK payload 流拼装成 MJPEG frame（JPEG SOI..EOI）。
 *
 * master 0x0603 alt 0 EP 0x81 BULK MPS 512；UVC over BULK firmware 实际推的字节流是
 * 12 字节左右 UVC payload header（bHeaderLength + bmHeaderInfo + PTS / SCR）然后跟 JPEG payload。
 * 这里两种切帧策略：
 *
 *   - **UVC_EOF**：每个 UVC payload header 第 2 字节 bit1 是 EOF（end of frame）。
 *     一帧由若干 payload 组成，最后一段 header bit1=1。先剥 header 再拼 JPEG。
 *   - **SOI_EOI**：直接全 buffer 扫 0xFFD8 ... 0xFFD9 byte 对，简单但若 firmware 不发
 *     payload header 而是直接出 JPEG 流也能切。
 *
 * 当前 firmware 是 UVC over BULK，优先 UVC_EOF；如果 header 不像 UVC，再退回 SOI_EOI。
 *
 * 线程安全：单生产者（pull 线程） + 单消费者（poll 线程）。
 */
class BerxelMjpegAssembler(
    private val maxQueuedFrames: Int = 4,
    /** 单帧最大字节数；640×400 通常 < 512KB，留到 4MB 兼容后续 HD 档。 */
    private val maxFrameSize: Int = 4 * 1024 * 1024,
) {

    private val current = ByteArrayBuilder(64 * 1024)
    private val frameQueue: java.util.ArrayDeque<ByteArray> = java.util.ArrayDeque()
    private val lock = Any()

    /** SOI 检测状态：找到 0xFFD8 之后 inFrame=true，找到 0xFFD9 或 UVC EOF emit。 */
    @Volatile private var inFrame: Boolean = false
    private var prevScanByte: Int = -1
    private var prevFrameByte: Int = -1

    @Volatile var totalBytesIn: Long = 0
        private set
    @Volatile var totalFramesOut: Long = 0
        private set
    @Volatile var droppedFrames: Long = 0
        private set
    /** 因超出 maxFrameSize 或找不到 EOI 而被丢的不完整 buffer 次数。 */
    @Volatile var skippedFrames: Long = 0
        private set
    @Volatile var totalSoiMarkers: Long = 0
        private set
    @Volatile var totalEoiMarkers: Long = 0
        private set
    @Volatile var uvcPayloads: Long = 0
        private set
    @Volatile var uvcEofPayloads: Long = 0
        private set
    @Volatile var rawPayloads: Long = 0
        private set

    /** 追加一段 BULK payload；优先按 UVC header 剥头，再扫 SOI/EOI 切帧。 */
    fun append(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        totalBytesIn += chunk.size

        val headerLen = plausibleUvcHeaderLen(chunk)
        if (headerLen > 0) {
            uvcPayloads++
            val flags = chunk[1].toInt() and 0xff
            val eof = (flags and UVC_FLAG_EOF) != 0
            if (eof) uvcEofPayloads++
            appendPayloadBytes(chunk, headerLen, chunk.size)
            if (eof) finishFrameAtUvcEof()
            return
        }

        rawPayloads++
        appendPayloadBytes(chunk, 0, chunk.size)
    }

    private fun appendPayloadBytes(chunk: ByteArray, start: Int, end: Int) {
        var i = 0
        if (start > 0) i = start
        while (i < end) {
            val b = chunk[i].toInt() and 0xff
            if (!inFrame) {
                if (prevScanByte == 0xff && b == 0xd8) {
                    inFrame = true
                    totalSoiMarkers++
                    current.clear()
                    current.appendByte(0xFF.toByte())
                    current.appendByte(0xD8.toByte())
                    prevScanByte = -1
                    prevFrameByte = 0xd8
                } else {
                    prevScanByte = b
                }
            } else {
                current.appendByte(chunk[i])
                if (prevFrameByte == 0xff && b == 0xd9) {
                    totalEoiMarkers++
                    emitFrame(current.takeBytes())
                    inFrame = false
                    prevScanByte = -1
                    prevFrameByte = -1
                } else {
                    prevFrameByte = b
                }
                if (current.size > maxFrameSize) {
                    skippedFrames++
                    resetCurrentFrame()
                }
            }
            i++
        }
    }

    private fun finishFrameAtUvcEof() {
        if (!inFrame) return
        if (current.endsWithJpegEoi()) {
            totalEoiMarkers++
            emitFrame(current.takeBytes())
        } else {
            skippedFrames++
            current.clear()
        }
        inFrame = false
        prevScanByte = -1
        prevFrameByte = -1
    }

    private fun emitFrame(bytes: ByteArray) {
        synchronized(lock) {
            if (frameQueue.size >= maxQueuedFrames) {
                frameQueue.pollFirst()
                droppedFrames++
            }
            frameQueue.addLast(bytes)
            totalFramesOut++
        }
    }

    fun pollFrame(): ByteArray? {
        synchronized(lock) { return frameQueue.pollFirst() }
    }

    fun reset() {
        synchronized(lock) {
            current.clear()
            frameQueue.clear()
            inFrame = false
            prevScanByte = -1
            prevFrameByte = -1
            totalBytesIn = 0
            totalFramesOut = 0
            droppedFrames = 0
            skippedFrames = 0
            totalSoiMarkers = 0
            totalEoiMarkers = 0
            uvcPayloads = 0
            uvcEofPayloads = 0
            rawPayloads = 0
        }
    }

    fun queueDepth(): Int = synchronized(lock) { frameQueue.size }

    fun debugStats(): String = "soi=$totalSoiMarkers eoi=$totalEoiMarkers " +
        "uvc=$uvcPayloads eof=$uvcEofPayloads raw=$rawPayloads"

    private fun plausibleUvcHeaderLen(chunk: ByteArray): Int {
        if (chunk.size < 2) return -1
        val len = chunk[0].toInt() and 0xff
        if (len < 2 || len > MAX_UVC_HEADER_LEN || len > chunk.size) return -1

        val flags = chunk[1].toInt() and 0xff
        var minLen = 2
        if ((flags and UVC_FLAG_PTS) != 0) minLen += 4
        if ((flags and UVC_FLAG_SCR) != 0) minLen += 6
        if (len < minLen) return -1

        // 标准 UVC header 通常置 EOH(bit7)；若没置，也要求 payload 后面像 JPEG 开头。
        val payloadLooksJpeg = len + 1 < chunk.size &&
            (chunk[len].toInt() and 0xff) == 0xff &&
            (chunk[len + 1].toInt() and 0xff) == 0xd8
        if ((flags and UVC_FLAG_EOH) == 0 && !payloadLooksJpeg) return -1
        return len
    }

    private fun resetCurrentFrame() {
        current.clear()
        inFrame = false
        prevScanByte = -1
        prevFrameByte = -1
    }

    private class ByteArrayBuilder(initialCapacity: Int) {
        private var buf = ByteArray(initialCapacity)
        var size: Int = 0
            private set

        fun appendByte(b: Byte) {
            ensureCapacity(size + 1)
            buf[size++] = b
        }

        fun takeBytes(): ByteArray {
            val out = buf.copyOf(size)
            size = 0
            return out
        }

        fun endsWithJpegEoi(): Boolean =
            size >= 2 &&
                (buf[size - 2].toInt() and 0xff) == 0xff &&
                (buf[size - 1].toInt() and 0xff) == 0xd9

        fun clear() { size = 0 }

        private fun ensureCapacity(needed: Int) {
            if (buf.size >= needed) return
            var c = buf.size * 2
            while (c < needed) c *= 2
            buf = buf.copyOf(c)
        }
    }

    private companion object {
        const val UVC_FLAG_EOF = 0x02
        const val UVC_FLAG_PTS = 0x04
        const val UVC_FLAG_SCR = 0x08
        const val UVC_FLAG_EOH = 0x80
        const val MAX_UVC_HEADER_LEN = 64
    }
}
