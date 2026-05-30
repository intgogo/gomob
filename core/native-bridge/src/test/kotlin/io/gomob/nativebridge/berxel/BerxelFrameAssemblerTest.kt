package io.gomob.nativebridge.berxel

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * BerxelFrameAssembler 单测：模拟 BULK chunk 流喂入，验证按 byteCount 切帧 + 多余字节顺延、
 * 队列丢弃旧帧、统计计数器正确。
 *
 * 不依赖真机；纯 JVM。
 */
class BerxelFrameAssemblerTest {

    /** 帮助：构造 10×5×2=100 字节帧大小的 assembler。 */
    private fun small() = BerxelFrameAssembler(width = 10, height = 5, bytesPerPixel = 2, maxQueuedFrames = 4)
    /** 帮助：构造 5×1×2=10 字节帧大小的 assembler。 */
    private fun tiny(maxQueued: Int = 2) =
        BerxelFrameAssembler(width = 5, height = 1, bytesPerPixel = 2, maxQueuedFrames = maxQueued)

    @Test
    fun byteCount_singleChunkExactSize_emitsOneFrame() {
        val a = small()
        a.append(ByteArray(100) { it.toByte() })

        val f = a.pollFrame()
        assertThat(f).isNotNull()
        assertThat(f!!.data.size).isEqualTo(100)
        assertThat(f.width).isEqualTo(10)
        assertThat(f.height).isEqualTo(5)
        assertThat(f.data[0]).isEqualTo(0.toByte())
        assertThat(f.data[99]).isEqualTo(99.toByte())
        assertThat(a.pollFrame()).isNull()
        assertThat(a.totalFramesOut).isEqualTo(1)
        assertThat(a.totalBytesIn).isEqualTo(100)
    }

    @Test
    fun byteCount_multipleSmallChunks_assemblesOneFrame() {
        val a = small()
        // 5 个 20 字节 chunk = 100 字节 = 1 帧
        repeat(5) { a.append(ByteArray(20) { (it + 1).toByte() }) }

        val f = a.pollFrame()
        assertThat(f).isNotNull()
        assertThat(f!!.data.size).isEqualTo(100)
        assertThat(a.pollFrame()).isNull()
        assertThat(a.totalFramesOut).isEqualTo(1)
    }

    @Test
    fun byteCount_oversizedChunk_spillsRemainderIntoNextFrame() {
        val a = small()
        // 单 chunk 250 字节 = 2 帧 + 50 字节
        a.append(ByteArray(250) { (it % 256).toByte() })

        val f1 = a.pollFrame()
        val f2 = a.pollFrame()
        assertThat(f1).isNotNull()
        assertThat(f2).isNotNull()
        assertThat(f1!!.data.size).isEqualTo(100)
        assertThat(f2!!.data.size).isEqualTo(100)
        assertThat(a.pollFrame()).isNull()  // 50 字节剩余还在 builder 里
        assertThat(a.totalFramesOut).isEqualTo(2)

        // 再喂 50 字节凑成第 3 帧
        a.append(ByteArray(50) { 0xff.toByte() })
        val f3 = a.pollFrame()
        assertThat(f3).isNotNull()
        assertThat(f3!!.data.size).isEqualTo(100)
        assertThat(a.totalFramesOut).isEqualTo(3)
    }

    @Test
    fun queueOverflow_dropsOldestFrame() {
        val a = tiny(maxQueued = 2)
        // 喂 5 帧 (10 字节 / 帧) — 队列只留最新 2 帧，前 3 帧丢
        repeat(5) { i ->
            a.append(ByteArray(10) { i.toByte() })
        }

        assertThat(a.totalFramesOut).isEqualTo(5)
        assertThat(a.droppedFrames).isEqualTo(3)

        val a0 = a.pollFrame()!!
        val a1 = a.pollFrame()!!
        assertThat(a0.data[0]).isEqualTo(3.toByte())  // 倒数第 2 帧
        assertThat(a1.data[0]).isEqualTo(4.toByte())  // 最新帧
        assertThat(a.pollFrame()).isNull()
    }

    @Test
    fun reset_clearsAllStateAndQueue() {
        val a = tiny()
        a.append(ByteArray(15))  // 1 帧 (10B) + 5 字节挂在 builder

        a.reset()

        assertThat(a.pollFrame()).isNull()
        assertThat(a.totalFramesOut).isEqualTo(0)
        assertThat(a.totalBytesIn).isEqualTo(0)
        assertThat(a.queueDepth()).isEqualTo(0)
    }

    @Test
    fun resetCurrent_keepsEmittedFramesButClearsBuilder() {
        val a = tiny()
        a.append(ByteArray(10))  // 1 帧入队
        a.append(ByteArray(7))   // 7 字节挂在 builder

        a.resetCurrent()

        assertThat(a.splitWarnings).isEqualTo(1)
        assertThat(a.pollFrame()).isNotNull()  // 之前那 1 帧还在
        // 后续 10 字节应该独立成帧（不被丢掉的 7 字节污染）
        a.append(ByteArray(10) { 0x42.toByte() })
        val f = a.pollFrame()!!
        assertThat(f.data[0]).isEqualTo(0x42.toByte())
    }

    @Test
    fun depthFrame_pixelAt_readsLittleEndianUint16() {
        val a = tiny()
        // 5 个 pixel = 10 字节：每像素 LE 0x0100, 0x0302, 0x0504, 0x0706, 0x0908
        val chunk = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        a.append(chunk)
        val f = a.pollFrame()!!
        assertThat(f.pixelAt(0, 0)).isEqualTo(0x0100)
        assertThat(f.pixelAt(1, 0)).isEqualTo(0x0302)
        assertThat(f.pixelAt(4, 0)).isEqualTo(0x0908)
    }

    @Test
    fun depthFrame_stats_skipsZerosAndComputesMinMaxMean() {
        val a = tiny()
        // 5 个像素：0, 100, 200, 0, 300 → nonzero=3 min=100 max=300 mean=200
        val chunk = byteArrayOf(0, 0, 100, 0, 200.toByte(), 0, 0, 0, 44, 1)  // 44+256=300
        a.append(chunk)
        val s = a.pollFrame()!!.stats()
        assertThat(s.nonzeroCount).isEqualTo(3)
        assertThat(s.min).isEqualTo(100)
        assertThat(s.max).isEqualTo(300)
        assertThat(s.meanNonzero).isWithin(0.01).of(200.0)
    }

    @Test
    fun defaultDepthFrameSize_matchesP100R3FrameIndex2() {
        // P100R3 companion frame index 2: 640 × 401 × 2 bytes (YUY2 packed depth, +1 metadata row) = 513280
        assertThat(BerxelFrameAssembler.DEFAULT_DEPTH_FRAME_SIZE).isEqualTo(513280)
    }

    @Test
    fun emptyChunkAppendIsNoop() {
        val a = BerxelFrameAssembler()
        a.append(ByteArray(0))
        assertThat(a.totalBytesIn).isEqualTo(0)
        assertThat(a.totalFramesOut).isEqualTo(0)
    }
}
