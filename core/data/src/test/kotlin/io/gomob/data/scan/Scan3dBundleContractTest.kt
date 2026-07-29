package io.gomob.data.scan

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class Scan3dBundleContractTest {
    @Test
    fun calibrationEntry_固定根目录名称且字节Sha不变() {
        val source = ByteArray(DefaultCalibrationFileProvider.CALIBRATION_SIZE_BYTES) { index ->
            (index and 0xff).toByte()
        }
        val bundle = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                DefaultScan3dBundleUploader.writeCalibrationEntry(zip, source)
            }
        }.toByteArray()

        ZipInputStream(ByteArrayInputStream(bundle)).use { zip ->
            val entry = zip.nextEntry
            assertThat(entry.name).isEqualTo("calibration.bin")
            assertThat(entry.name).doesNotContain("/")
            val packed = zip.readBytes()
            assertThat(packed).isEqualTo(source)
            assertThat(sha256(packed)).isEqualTo(sha256(source))
            assertThat(zip.nextEntry).isNull()
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
