package io.gomob.data.scan

import android.content.Context
import android.graphics.Bitmap
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.gomob.network.ApiException
import io.gomob.network.AssetApi
import io.gomob.network.dto.AssetUploadCompleteRequest
import io.gomob.network.dto.AssetUploadInitRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** 原始 RGBD + 自包含 VINCreator BIN 的 bundle 上传器。 */
interface Scan3dBundleUploader {
    suspend fun upload(
        shots: List<RawRgbdShot>,
        calibration: LocalCalibrationFile,
        sourceProfile: RgbdSourceProfile,
        sessionId: String,
    ): UploadedScanBundle
}

/** 不做任何 RGB 缩放、不改变深度编码的采集帧。 */
data class RawRgbdShot(
    val rgb: Bitmap,
    val depth16: ByteArray,
    val confidence: ByteArray?,
    val colorTimestampUs: Long,
    val depthTimestampUs: Long,
)

data class RgbdSourceProfile(
    val depthWidth: Int,
    val depthHeight: Int,
    val depthEncoding: String,
    val colorWidth: Int,
    val colorHeight: Int,
    val depthDeviceId: String,
    val colorDeviceId: String,
) {
    val depthProfile: String get() = "${depthWidth}x${depthHeight}_mode25"
    val colorProfile: String get() = "${colorWidth}x${colorHeight}"
}

data class UploadedScanBundle(
    val assetId: String,
    val objectKey: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Singleton
class DefaultScan3dBundleUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AssetApi,
    private val calibrationFileProvider: CalibrationFileProvider,
) : Scan3dBundleUploader {

    override suspend fun upload(
        shots: List<RawRgbdShot>,
        calibration: LocalCalibrationFile,
        sourceProfile: RgbdSourceProfile,
        sessionId: String,
    ): UploadedScanBundle = withContext(Dispatchers.IO) {
        require(shots.isNotEmpty()) { "bundle 帧列表为空" }
        validateSourceProfile(sourceProfile, calibration)
        val dir = File(context.cacheDir, "scan_uploads").apply { mkdirs() }
        val bundle = File.createTempFile("scan_${sessionId}_", ".zip", dir)
        try {
            val sha256 = packBundle(bundle, shots, calibration, sourceProfile, sessionId)
            val sizeBytes = bundle.length()
            val initResp = api.initUpload(
                AssetUploadInitRequest(
                    kind = "scan3d_bundle",
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    mime = "application/zip",
                ),
            )
            val init = initResp.data ?: throw ApiException(50001, 500, "扫描上传初始化响应缺少数据")
            val chunkSize = init.chunkSize.coerceAtLeast(1)
            var totalChunks = 0
            bundle.inputStream().use { input ->
                val buffer = ByteArray(chunkSize)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    totalChunks += 1
                    api.uploadChunk(
                        uploadId = init.uploadId,
                        chunkNumber = totalChunks,
                        body = buffer.copyOf(read).toRequestBody("application/zip".toMediaTypeOrNull()),
                    )
                }
            }
            val completeResp = api.completeUpload(
                uploadId = init.uploadId,
                request = AssetUploadCompleteRequest(
                    totalChunks = totalChunks.coerceAtLeast(1),
                    scanSessionId = sessionId,
                    frameCount = shots.size,
                ),
            )
            val complete = completeResp.data ?: throw ApiException(50001, 500, "扫描上传完成响应缺少数据")
            UploadedScanBundle(complete.assetId, complete.objectKey, sizeBytes, sha256)
        } finally {
            bundle.delete()
        }
    }

    /** 写入新契约，calibration.bin 始终位于 zip 根目录。 */
    internal fun packBundleForTest(
        out: File,
        shots: List<RawRgbdShot>,
        calibration: LocalCalibrationFile,
        sourceProfile: RgbdSourceProfile,
        sessionId: String,
    ): String = packBundle(out, shots, calibration, sourceProfile, sessionId)

    private fun packBundle(
        out: File,
        shots: List<RawRgbdShot>,
        calibration: LocalCalibrationFile,
        sourceProfile: RgbdSourceProfile,
        sessionId: String,
    ): String {
        require(shots.isNotEmpty() && shots.size <= MAX_SHOTS) { "bundle 帧数必须在 1..$MAX_SHOTS" }
        require(SESSION_ID_REGEX.matches(sessionId)) { "sessionId 非法：$sessionId" }
        validateSourceProfile(sourceProfile, calibration)
        val calibrationBytes = calibration.file.readBytes()
        val snapshot = DefaultCalibrationFileProvider.validateBytes(
            calibration.file,
            calibration.depthDeviceId,
            calibrationBytes,
        )
        require(snapshot.sha256 == calibration.sha256) { "标定文件 SHA-256 与会话锁定值不一致" }
        calibrationFileProvider.verifyUnchanged(calibration)
        val expectedDepthBytes = sourceProfile.depthWidth * sourceProfile.depthHeight * 2
        val expectedConfBytes = sourceProfile.depthWidth * sourceProfile.depthHeight
        val shotEntries = shots.mapIndexed { index, shot ->
            require(shot.rgb.width == sourceProfile.colorWidth && shot.rgb.height == sourceProfile.colorHeight) {
                "帧 $index RGB 尺寸 ${shot.rgb.width}x${shot.rgb.height} 与原始 profile " +
                    "${sourceProfile.colorWidth}x${sourceProfile.colorHeight} 不一致"
            }
            require(shot.depth16.size == expectedDepthBytes) {
                "帧 $index depth 字节数 ${shot.depth16.size} != $expectedDepthBytes"
            }
            require(shot.colorTimestampUs > 0 && shot.depthTimestampUs > 0) {
                "帧 $index RGB/Depth 时间戳必须为正数"
            }
            val syncDeltaUs = if (shot.colorTimestampUs >= shot.depthTimestampUs) {
                shot.colorTimestampUs - shot.depthTimestampUs
            } else {
                shot.depthTimestampUs - shot.colorTimestampUs
            }
            require(syncDeltaUs <= MAX_SYNC_DELTA_US) {
                "帧 $index RGB/Depth 时间差超过 ${MAX_SYNC_DELTA_US}us"
            }
            val confName = shot.confidence?.let {
                require(it.size == expectedConfBytes) {
                    "帧 $index confidence 字节数 ${it.size} != $expectedConfBytes"
                }
                "conf_$index.u8"
            }
            ShotEntry(index, "rgb_$index.png", "depth_$index.u16", confName, shot)
        }
        ZipOutputStream(out.outputStream().buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifestJson(shotEntries, calibration, sourceProfile, sessionId).toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            writeCalibrationEntry(zip, calibrationBytes)
            shotEntries.forEach { entry ->
                zip.putNextEntry(ZipEntry(entry.rgb))
                require(entry.shot.rgb.compress(Bitmap.CompressFormat.PNG, 100, zip)) {
                    "帧 ${entry.index} RGB PNG 编码失败"
                }
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(entry.depth))
                zip.write(entry.shot.depth16)
                zip.closeEntry()
                entry.conf?.let { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(entry.shot.confidence!!)
                    zip.closeEntry()
                }
            }
        }
        calibrationFileProvider.verifyUnchanged(calibration)
        return sha256File(out)
    }

    private data class ShotEntry(
        val index: Int,
        val rgb: String,
        val depth: String,
        val conf: String?,
        val shot: RawRgbdShot,
    )

    private fun manifestJson(
        shots: List<ShotEntry>,
        calibration: LocalCalibrationFile,
        source: RgbdSourceProfile,
        sessionId: String,
    ): JSONObject = JSONObject().apply {
        put("schema_version", 2)
        put("session_key", sessionId)
        put("calibration", JSONObject().apply {
            put("format", calibration.format)
            put("depth_device_id", source.depthDeviceId)
            put("color_device_id", source.colorDeviceId)
            put("depth_profile", source.depthProfile)
            put("color_profile", source.colorProfile)
            put("sha256", calibration.sha256)
        })
        put("source", JSONObject().apply {
            put("depth_width", source.depthWidth)
            put("depth_height", source.depthHeight)
            put("depth_encoding", source.depthEncoding)
            put("color_width", source.colorWidth)
            put("color_height", source.colorHeight)
        })
        put("shots", JSONArray().apply {
            shots.forEach { shot -> put(JSONObject().apply {
                put("index", shot.index)
                put("rgb", shot.rgb)
                put("depth", shot.depth)
                put("conf", shot.conf ?: JSONObject.NULL)
                put("color_timestamp_us", shot.shot.colorTimestampUs)
                put("depth_timestamp_us", shot.shot.depthTimestampUs)
            }) }
        })
    }

    private fun validateSourceProfile(source: RgbdSourceProfile, calibration: LocalCalibrationFile) {
        require(source.depthWidth > 0 && source.depthHeight > 0 && source.colorWidth > 0 && source.colorHeight > 0)
        require(source.depthEncoding == "vin_creator_disparity_u16") { "不支持的深度编码：${source.depthEncoding}" }
        require(source.depthWidth == 640 && source.depthHeight == 128) { "vin_creator_v3 仅接受 640x128 mode25" }
        require(source.colorWidth == 4160 && source.colorHeight == 832) { "vin_creator_v3 仅接受 HLSD8 4160x832" }
        require(calibration.format == DefaultCalibrationFileProvider.CALIBRATION_FORMAT) { "标定格式错误" }
        require(calibration.version == DefaultCalibrationFileProvider.CALIBRATION_VERSION) { "标定版本错误" }
        require(
            DefaultCalibrationFileProvider.normalizeDeviceId(source.depthDeviceId) == source.depthDeviceId &&
                source.depthDeviceId == calibration.depthDeviceId,
        ) { "会话 Depth ID 与标定文件不一致或未规范化" }
        require(DefaultCalibrationFileProvider.normalizeDeviceId(source.colorDeviceId) == source.colorDeviceId) {
            "会话 Color ID 非法或未规范化"
        }
        require(source.depthWidth * 2L * source.depthHeight <= Int.MAX_VALUE)
    }

    private fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        internal const val CALIBRATION_ENTRY = "calibration.bin"
        private const val MAX_SHOTS = 32
        private const val MAX_SYNC_DELTA_US = 15_000L
        private val SESSION_ID_REGEX = Regex("[A-Za-z0-9_-]{1,128}")

        internal fun writeCalibrationEntry(zip: ZipOutputStream, calibrationBytes: ByteArray) {
            zip.putNextEntry(ZipEntry(CALIBRATION_ENTRY))
            calibrationBytes.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class Scan3dBundleUploaderModule {
    @Binds
    abstract fun bindScan3dBundleUploader(impl: DefaultScan3dBundleUploader): Scan3dBundleUploader
}
