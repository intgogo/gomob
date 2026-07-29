package io.gomob.nativebridge.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Berxel 设备判型单测。纯逻辑，不触 native。 */
class CameraModelTest {

    @Test
    fun fromUsbIds_berxelMasterAndCompanion() {
        // master 节点
        assertThat(CameraModel.fromUsbIds(0x0603, 0x001f)).isEqualTo(CameraModel.Berxel)
        // companion 节点也归 Berxel
        assertThat(CameraModel.fromUsbIds(0x3558, 0x1012)).isEqualTo(CameraModel.Berxel)
        assertThat(CameraModel.Berxel.fdCount).isEqualTo(2)
        assertThat(CameraModel.Berxel.deviceTypeLabel).isEqualTo("Berxel iHawk P100R3")
    }

    @Test
    fun fromUsbIds_unknown() {
        val m = CameraModel.fromUsbIds(0x1234, 0x5678)
        assertThat(m).isInstanceOf(CameraModel.Unknown::class.java)
        assertThat(m.isSupported).isFalse()
        assertThat(m.fdCount).isEqualTo(0)
    }

    @Test
    fun isRecognized_matchesSupportedOnly() {
        assertThat(CameraModel.isRecognized(0x0603, 0x001f)).isTrue()
        assertThat(CameraModel.isRecognized(0x3558, 0x1012)).isTrue()
        assertThat(CameraModel.isRecognized(0xdead, 0xbeef)).isFalse()
    }
}
