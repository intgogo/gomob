package io.gomob.data.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class CalibrationFileProviderTest {
    @Test
    fun normalizeDepthDeviceId_去空白并转大写() {
        assertThat(DefaultCalibrationFileProvider.normalizeDepthDeviceId("  bf301208 ")).isEqualTo("BF301208")
    }

    @Test
    fun normalizeDepthDeviceId_拒绝路径穿越和非法字符() {
        listOf("../BF301208", "BF\\301208", "BF/301208", "BF.301208", "").forEach { value ->
            assertThrows(CalibrationFileException::class.java) {
                DefaultCalibrationFileProvider.normalizeDepthDeviceId(value)
            }
        }
    }

    @Test
    fun validateBytes_锁定序列号版本和Sha() {
        val bytes = validBytes()
        val result = DefaultCalibrationFileProvider.validateBytes(File("VIN_BF301208.bin"), "BF301208", bytes)
        assertThat(result.depthDeviceId).isEqualTo("BF301208")
        assertThat(result.version).isEqualTo(3)
        assertThat(result.format).isEqualTo("vin_creator_v3")
        assertThat(result.sha256).hasLength(64)
    }

    @Test
    fun validateBytes_拒绝大小序列号和版本错误() {
        assertThrows(CalibrationFileException::class.java) {
            DefaultCalibrationFileProvider.validateBytes(File("bad.bin"), "BF301208", ByteArray(10))
        }
        assertThrows(CalibrationFileException::class.java) {
            DefaultCalibrationFileProvider.validateBytes(File("bad.bin"), "BF999999", validBytes())
        }
        val version2 = validBytes().also { it[0x200] = 2 }
        assertThrows(CalibrationFileException::class.java) {
            DefaultCalibrationFileProvider.validateBytes(File("bad.bin"), "BF301208", version2)
        }
    }

    private fun validBytes(): ByteArray = ByteArray(DefaultCalibrationFileProvider.CALIBRATION_SIZE_BYTES).apply {
        "BF301208".toByteArray(Charsets.US_ASCII).copyInto(this)
        this[0x200] = 3
    }
}
