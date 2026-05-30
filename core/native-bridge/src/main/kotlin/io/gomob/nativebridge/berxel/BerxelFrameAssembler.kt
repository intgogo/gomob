package io.gomob.nativebridge.berxel

/**
 * 把 BULK chunk 流拼装成 YUYV depth frame。
 *
 * Berxel firmware 不发 UVC 1.1 payload header（trace 验证），companion ep=0x82 直接出 raw
 * 16-bit depth 数据。两种 EOF 策略二选一：
 *
 *   - **byteCount**：累计 `expectedFrameSize` 字节即视为一帧（默认 640×401×2=513280）。
 *     适合 firmware 推连续无 padding 的纯数据流；如果实际有 padding，会撕成两半。
 *   - **shortRead**：累计任意字节，当收到的 chunk size 小于 `readSize` 阈值时视为帧末尾。
 *     适合 firmware 用 short transfer 表示 EOF（libuvc BULK 模式默认）。
 *
 * 当前实现支持 byteCount，shortRead 留 TODO。
 *
 * 线程安全：单生产者（pull 线程） + 单消费者（poll 线程）。`append` 和 `pollFrame` 必须
 * 各自从单一线程调用。
 */
class BerxelFrameAssembler(
    private val width: Int = DEFAULT_WIDTH,
    private val height: Int = DEFAULT_HEIGHT,
    private val bytesPerPixel: Int = 2,
    private val maxQueuedFrames: Int = 4,
    /** SHORT_READ 模式下的"满包"阈值；任意 chunk.size < 它的视为 EOF。默认跟 NativeStack BULK_READ_LEN 对齐。 */
    private val shortReadThreshold: Int = 16 * 1024,
) {

    private val expectedFrameSize: Int = width * height * bytesPerPixel

    enum class Mode { BYTE_COUNT, SHORT_READ }

    /** 默认 BYTE_COUNT（兼容旧 unit test）；NativeStack 真机走 SHORT_READ。 */
    var mode: Mode = Mode.BYTE_COUNT

    /** strict=true 丢 size 与 expected 偏差 > 1 行的"残帧"；false 全 emit（即使 height 异常）。
     *  2026-05-28 dump 分析后定性：长帧 = 启动 buffer 残留拼接（前 5 帧 1025292/1529100/988428）；
     *  之后稳定 513292B。default true 丢启动巨帧让 UI 不闪；strict 模式实际比对：
     *    strict: 必须 == 513280 严格相等 → firmware 永远 513292 永远 drop → 0 帧
     *    raw:    全 emit 含巨帧 → 启动闪烁
     *  当前实现：仍依赖 ±1 行 tolerance，让 513292 通过（差 12B 在 tolerance 内）但巨帧 drop。 */
    @Volatile var strictFrameSize: Boolean = true

    private val current = ByteArrayBuilder(expectedFrameSize)
    private val frameQueue: java.util.ArrayDeque<DepthFrame> = java.util.ArrayDeque()
    private val lock = Any()

    /** 统计：累计入站字节、累计帧数、被丢弃的帧（队列满）、字节计数撕裂次数。 */
    @Volatile var totalBytesIn: Long = 0
        private set
    @Volatile var totalFramesOut: Long = 0
        private set
    @Volatile var droppedFrames: Long = 0
        private set
    @Volatile var splitWarnings: Long = 0
        private set

    /**
     * 添加一段 BULK chunk。byteCount 模式：累计到 [expectedFrameSize] 即切帧；多余部分
     * 顺延到下一帧。shortRead 模式（未实现）：当 chunk.size < readSize 时视为帧末。
     */
    fun append(chunk: ByteArray) {
        if (chunk.isEmpty()) return
        totalBytesIn += chunk.size
        when (mode) {
            Mode.BYTE_COUNT -> appendByByteCount(chunk)
            Mode.SHORT_READ -> appendByShortRead(chunk)
        }
    }

    private fun appendByByteCount(chunk: ByteArray) {
        var offset = 0
        while (offset < chunk.size) {
            val needed = expectedFrameSize - current.size
            val take = minOf(needed, chunk.size - offset)
            current.append(chunk, offset, take)
            offset += take
            if (current.size == expectedFrameSize) {
                emitFrame(current.takeBytes())
            }
        }
    }

    private fun appendByShortRead(chunk: ByteArray) {
        current.append(chunk, 0, chunk.size)
        // chunk.size < shortReadThreshold → 这是 UVC BULK 的 short packet，标志当前帧结束。
        // 切帧：不强求 size == expectedFrameSize（firmware 输出可能多/少一行 metadata），
        // 而是相信 short-packet 边界，按真实大小转 DepthFrame；高度按 bytes / (width*bpp) 算。
        if (chunk.size < shortReadThreshold && current.size > 0) {
            val raw = current.takeBytes()
            // strict=true：±1 行 tolerance 内 emit、超出（启动巨帧 / 残帧）drop
            // strict=false：照单全收（让用户看到 firmware 推变长帧的真貌）
            val tolerance = width * bytesPerPixel + 16
            val deviation = kotlin.math.abs(raw.size - expectedFrameSize)
            if (deviation > tolerance) {
                splitWarnings++
                if (strictFrameSize) return
            } else if (raw.size != expectedFrameSize) {
                splitWarnings++
            }
            emitFrame(raw)
        }
    }

    private fun emitFrame(bytes: ByteArray) {
        // 用实际字节算 height（firmware 可能 +1 metadata 行也可能不带）— 不强求 width × height × bpp 严格相等
        val actualHeight = bytes.size / (width * bytesPerPixel)
        val frame = DepthFrame(data = bytes, width = width, height = actualHeight, bytesPerPixel = bytesPerPixel)
        synchronized(lock) {
            if (frameQueue.size >= maxQueuedFrames) {
                frameQueue.pollFirst()  // 丢最老
                droppedFrames++
            }
            frameQueue.addLast(frame)
            totalFramesOut++
        }
    }

    /** 取一帧；队列空返 null。消费者轮询。 */
    fun pollFrame(): DepthFrame? {
        synchronized(lock) { return frameQueue.pollFirst() }
    }

    /** 重置当前累积 buffer（不清 emitted 帧）。stream restart 时调用。 */
    fun resetCurrent() {
        if (current.size > 0) splitWarnings++
        current.clear()
    }

    /** 全清。 */
    fun reset() {
        synchronized(lock) {
            current.clear()
            frameQueue.clear()
            totalBytesIn = 0
            totalFramesOut = 0
            droppedFrames = 0
            splitWarnings = 0
        }
    }

    fun queueDepth(): Int = synchronized(lock) { frameQueue.size }

    /** 内置 byte builder：避免反复 ByteArray 拷贝。 */
    private class ByteArrayBuilder(initialCapacity: Int) {
        private var buf = ByteArray(initialCapacity)
        var size: Int = 0
            private set

        fun append(src: ByteArray, offset: Int, count: Int) {
            ensureCapacity(size + count)
            System.arraycopy(src, offset, buf, size, count)
            size += count
        }

        /** 取出当前内容（拷贝），清空 builder。 */
        fun takeBytes(): ByteArray {
            val out = buf.copyOf(size)
            size = 0
            return out
        }

        fun clear() { size = 0 }

        private fun ensureCapacity(needed: Int) {
            if (buf.size >= needed) return
            var c = buf.size * 2
            while (c < needed) c *= 2
            buf = buf.copyOf(c)
        }
    }

    companion object {
        // P100R3 companion frame index 2 默认：640 × 401（多 1 行 metadata），16-bit YUY2 packed
        // iHawkP100R3_descriptor.md → 0x47: VS_FRAME_UNCOMPRESSED 2 (640×401, 45fps)
        const val DEFAULT_WIDTH = 640
        const val DEFAULT_HEIGHT = 401
        const val DEFAULT_DEPTH_FRAME_SIZE = DEFAULT_WIDTH * DEFAULT_HEIGHT * 2
    }
}
