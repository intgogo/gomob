package io.gomob.nativebridge.camera

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 双相机自动识别判型单测（M6.8b）。纯逻辑，不触 native。 */
class CameraModelTest {

    @Test
    fun fromUsbIds_eys3d() {
        val m = CameraModel.fromUsbIds(0x3438, 0x0206)
        assertThat(m).isEqualTo(CameraModel.Eys3d)
        assertThat(m.deviceTypeLabel).isEqualTo("eYs3D RS-D550")
        assertThat(m.fdCount).isEqualTo(1)
        assertThat(m.isSupported).isTrue()
    }

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
        assertThat(CameraModel.isRecognized(0x3438, 0x0206)).isTrue()
        assertThat(CameraModel.isRecognized(0x0603, 0x001f)).isTrue()
        assertThat(CameraModel.isRecognized(0x3558, 0x1012)).isTrue()
        assertThat(CameraModel.isRecognized(0xdead, 0xbeef)).isFalse()
    }
}
