package io.gomob.nativebridge.berxel

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BerxelDeviceParamsTest {

    @Test
    fun fromBytes_parsesAllFiveBlocks() {
        // 构造 156B 全 1.0 (color) / 2.0 (ir) / 3.0 (liteIr) / 4.0 (rot) / 5.0 (trans)
        val buf = ByteBuffer.allocate(156).order(ByteOrder.LITTLE_ENDIAN)
        repeat(9) { buf.putFloat(1.0f) }   // color
        repeat(9) { buf.putFloat(2.0f) }   // ir
        repeat(9) { buf.putFloat(3.0f) }   // liteIr
        repeat(9) { buf.putFloat(4.0f) }   // rotation
        repeat(3) { buf.putFloat(5.0f) }   // translation

        val p = BerxelDeviceParams.fromBytes(buf.array())

        assertThat(p.colorIntrinsic.fx).isEqualTo(1.0f)
        assertThat(p.colorIntrinsic.k3).isEqualTo(1.0f)
        assertThat(p.irIntrinsic.fy).isEqualTo(2.0f)
        assertThat(p.liteIrIntrinsic.cx).isEqualTo(3.0f)
        assertThat(p.colorToIrRotation.toList()).containsExactly(
            4f, 4f, 4f, 4f, 4f, 4f, 4f, 4f, 4f).inOrder()
        assertThat(p.colorToIrTranslation.toList()).containsExactly(5f, 5f, 5f).inOrder()
    }

    @Test
    fun fromBytes_realisticIntrinsic_roundtripsThroughCameraMatrix() {
        // 典型 P100R3 color 内参（假数据，仅验证结构）
        val buf = ByteBuffer.allocate(156).order(ByteOrder.LITTLE_ENDIAN)
        // color
        buf.putFloat(525.0f).putFloat(525.0f).putFloat(320.0f).putFloat(240.0f)  // fx fy cx cy
        buf.putFloat(0.01f).putFloat(-0.02f).putFloat(0.001f).putFloat(0.002f).putFloat(0.003f)  // distortion
        // 剩余 ir/liteIr/rot/trans 全 0
        while (buf.position() < 156) buf.put(0)

        val p = BerxelDeviceParams.fromBytes(buf.array())
        val K = p.colorIntrinsic.cameraMatrix()
        assertThat(K).hasLength(9)
        assertThat(K[0]).isEqualTo(525.0f)  // fx
        assertThat(K[2]).isEqualTo(320.0f)  // cx
        assertThat(K[4]).isEqualTo(525.0f)  // fy
        assertThat(K[5]).isEqualTo(240.0f)  // cy
        assertThat(K[8]).isEqualTo(1.0f)

        val d = p.colorIntrinsic.distCoeffs()
        assertThat(d).hasLength(5)
        assertThat(d[0]).isEqualTo(0.01f)
        assertThat(d[4]).isEqualTo(0.003f)
    }

    @Test
    fun fromBytes_wrongSize_throws() {
        try {
            BerxelDeviceParams.fromBytes(ByteArray(155))
            org.junit.Assert.fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // OK
        }
    }

    @Test
    fun constants_matchSdkLayout() {
        assertThat(BerxelDeviceParams.TOTAL_BYTES).isEqualTo(156)
        assertThat(BerxelDeviceParams.INTRINSIC_BYTES).isEqualTo(36)
        assertThat(BerxelDeviceParams.ROTATION_BYTES).isEqualTo(36)
        assertThat(BerxelDeviceParams.TRANSLATION_BYTES).isEqualTo(12)
        assertThat(CameraIntrinsic.BYTES).isEqualTo(36)
        // 36 * 4 + 12 = 156
        assertThat(BerxelDeviceParams.INTRINSIC_BYTES * 4 + BerxelDeviceParams.TRANSLATION_BYTES)
            .isEqualTo(BerxelDeviceParams.TOTAL_BYTES)
    }
}
