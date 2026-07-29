package io.gomob.data.scan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** App 侧 VINCreator 原始标定文件的唯一入口。 */
interface CalibrationFileProvider {
    fun load(depthDeviceId: String): LocalCalibrationFile

    /** 重新读取并确认文件仍是首次加载的同一份字节。 */
    fun verifyUnchanged(calibration: LocalCalibrationFile)

    fun hasExternalStorageAccess(): Boolean
}

data class LocalCalibrationFile(
    val file: File,
    val depthDeviceId: String,
    val sha256: String,
    val format: String,
    val version: Int,
)

class CalibrationFileException(message: String) : IllegalStateException(message)

@Singleton
class DefaultCalibrationFileProvider @Inject constructor() : CalibrationFileProvider {
    override fun hasExternalStorageAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    override fun load(depthDeviceId: String): LocalCalibrationFile {
        val normalized = normalizeDepthDeviceId(depthDeviceId)
        if (!hasExternalStorageAccess()) {
            throw CalibrationFileException(
                "未授予所有文件访问权限；请允许 App 访问共享目录后重试。" +
                    "设置入口：${CALIBRATION_DIR.absolutePath}",
            )
        }
        val file = calibrationFile(normalized)
        if (!file.exists()) {
            throw CalibrationFileException("缺少标定文件：${file.absolutePath}")
        }
        if (!file.isFile) {
            throw CalibrationFileException("标定路径不是普通文件：${file.absolutePath}")
        }
        val bytes = runCatching { file.readBytes() }
            .getOrElse { throw CalibrationFileException("读取标定文件失败 ${file.absolutePath}：${it.message}") }
        return validateBytes(file, normalized, bytes)
    }

    override fun verifyUnchanged(calibration: LocalCalibrationFile) {
        val now = load(calibration.depthDeviceId)
        if (now.file.absolutePath != calibration.file.absolutePath || now.sha256 != calibration.sha256) {
            throw CalibrationFileException(
                "扫描期间标定文件发生变化：${calibration.file.absolutePath}",
            )
        }
    }

    companion object {
        const val CALIBRATION_FORMAT = "vin_creator_v3"
        const val CALIBRATION_VERSION = 3
        const val CALIBRATION_SIZE_BYTES = 2420
        const val CALIBRATION_RELATIVE_DIR = "VIN/param"

        val CALIBRATION_DIR: File
            get() = File(Environment.getExternalStorageDirectory(), CALIBRATION_RELATIVE_DIR)

        fun calibrationFile(depthDeviceId: String): File =
            File(CALIBRATION_DIR, "VIN_${normalizeDepthDeviceId(depthDeviceId)}.bin")

        fun normalizeDepthDeviceId(value: String): String {
            val normalized = normalizeDeviceId(value)
            if (normalized.isEmpty() || !DEVICE_ID_REGEX.matches(normalized) ||
                normalized.contains("..") || normalized.contains('/') || normalized.contains('\\')
            ) {
                throw CalibrationFileException("非法 Depth 设备 ID：$value")
            }
            return normalized
        }

        /** 会话身份统一使用大写，避免 USB 层大小写差异造成同一设备被判成两台。 */
        fun normalizeDeviceId(value: String): String {
            val normalized = value.trim().uppercase(Locale.US)
            if (normalized.isEmpty() || !DEVICE_ID_REGEX.matches(normalized) || normalized.contains("..")) {
                throw CalibrationFileException("非法设备 ID：$value")
            }
            return normalized
        }

        fun validateBytes(file: File, normalizedId: String, bytes: ByteArray): LocalCalibrationFile {
            if (bytes.size != CALIBRATION_SIZE_BYTES) {
                throw CalibrationFileException(
                    "标定文件大小错误：${file.absolutePath} 为 ${bytes.size} bytes，期望 $CALIBRATION_SIZE_BYTES bytes",
                )
            }
            val serial = bytes.copyOfRange(0, 8).toString(Charsets.US_ASCII).trimEnd('\u0000', ' ')
            if (serial != normalizedId) {
                throw CalibrationFileException(
                    "标定文件序列号不匹配：${file.absolutePath} 内为 $serial，当前 Depth=$normalizedId",
                )
            }
            val version = readLeInt(bytes, 0x200)
            if (version != CALIBRATION_VERSION) {
                throw CalibrationFileException(
                    "标定文件版本错误：${file.absolutePath} 为 $version，期望 $CALIBRATION_VERSION",
                )
            }
            return LocalCalibrationFile(
                file = file,
                depthDeviceId = normalizedId,
                sha256 = sha256(bytes),
                format = CALIBRATION_FORMAT,
                version = version,
            )
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }

        fun allFilesAccessIntent(context: Context): Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            )
        } else {
            Intent(Settings.ACTION_SETTINGS)
        }

        private fun readLeInt(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or
                ((bytes[offset + 1].toInt() and 0xff) shl 8) or
                ((bytes[offset + 2].toInt() and 0xff) shl 16) or
                ((bytes[offset + 3].toInt() and 0xff) shl 24)

        private val DEVICE_ID_REGEX = Regex("[A-Z0-9_-]+")
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class CalibrationFileProviderModule {
    @Binds
    abstract fun bindCalibrationFileProvider(impl: DefaultCalibrationFileProvider): CalibrationFileProvider
}
