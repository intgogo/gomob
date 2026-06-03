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

/**
 * 端侧多视角 RGBD bundle 打包 + 上传器。
 *
 * zip 格式与 `server/fusion_service/rgbd_bundle.py` 是同一契约真理源：
 *   manifest.json + rgb_{i}.png + depth_{i}.u16(uint16 小端, 单位 mm) + conf_{i}.u8(可选)
 * 所有帧须共用同一分辨率/内参（RGB 已在端侧对齐到 depth 分辨率）。
 *
 * 与 [io.gomob.data.message.MediaAssetUploader] 分开实现：扫描 bundle 可达数十~数百 MB，
 * 走独立分块、独立暂存目录，不复用消息媒体器的 staging 逻辑。
 */
interface Scan3dBundleUploader {
    /**
     * 把 [shots]（已对齐到 [intrinsics] 分辨率的 RGBD 帧）打成 bundle zip 并以
     * kind=`scan3d_bundle` 分块上传；complete 时带 [sessionId] 与帧数触发服务端融合入队。
     */
    suspend fun upload(
        shots: List<RgbdShot>,
        intrinsics: BundleIntrinsics,
        sessionId: String,
    ): UploadedScanBundle
}

/**
 * 一张已对齐的 RGBD shot。
 * - [rgb]：彩色帧，**必须**已缩放到 depth 分辨率（[width]×[height]），PNG 编码进 bundle。
 * - [depth16]：深度裸字节，`width*height*2`，uint16 小端，单位 mm（即 DepthFrame.data 原样）。
 * - [confidence]：逐像素置信，`width*height`，uint8，0=无效；无则该帧不带 conf。
 */
data class RgbdShot(
    val rgb: Bitmap,
    val depth16: ByteArray,
    val confidence: ByteArray?,
    val width: Int,
    val height: Int,
)

/** bundle 共享内参（RGB 与 depth 已对齐，共用此内参）。 */
data class BundleIntrinsics(
    val width: Int,
    val height: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
)

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
) : Scan3dBundleUploader {

    override suspend fun upload(
        shots: List<RgbdShot>,
        intrinsics: BundleIntrinsics,
        sessionId: String,
    ): UploadedScanBundle {
        require(shots.isNotEmpty()) { "bundle 帧列表为空" }
        require(intrinsics.fx > 0.0 && intrinsics.fy > 0.0) {
            "bundle 内参 fx/fy 为 0 —— 设备未提供有效内参，融合无法反投影。需先标定/读出厂参数(M2)"
        }
        val dir = File(context.cacheDir, "scan_uploads").apply { mkdirs() }
        val bundle = File.createTempFile("scan_${sessionId}_", ".zip", dir)
        try {
            val sha256 = packBundle(bundle, shots, intrinsics, sessionId)
            val sizeBytes = bundle.length()

            val initResp = api.initUpload(
                AssetUploadInitRequest(
                    kind = "scan3d_bundle",
                    sizeBytes = sizeBytes,
                    sha256 = sha256,
                    mime = "application/zip",
                ),
            )
            val init = initResp.data ?: throw ApiException(50001, 500, "扫描上传初始化响应缺数据")
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
            val complete = completeResp.data ?: throw ApiException(50001, 500, "扫描上传完成响应缺数据")
            return UploadedScanBundle(
                assetId = complete.assetId,
                objectKey = complete.objectKey,
                sizeBytes = sizeBytes,
                sha256 = sha256,
            )
        } finally {
            bundle.delete()
        }
    }

    /** 打包 zip 到 [out]，返回文件 sha256(hex)。 */
    private fun packBundle(
        out: File,
        shots: List<RgbdShot>,
        intr: BundleIntrinsics,
        sessionId: String,
    ): String {
        val expectedDepthBytes = intr.width * intr.height * 2
        val expectedConfBytes = intr.width * intr.height
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            val shotsJson = JSONArray()
            shots.forEachIndexed { i, s ->
                require(s.width == intr.width && s.height == intr.height) {
                    "帧 $i 分辨率 ${s.width}x${s.height} 与内参 ${intr.width}x${intr.height} 不一致"
                }
                require(s.depth16.size == expectedDepthBytes) {
                    "帧 $i depth 字节数 ${s.depth16.size} != ${expectedDepthBytes}(w*h*2)"
                }
                val rgbName = "rgb_$i.png"
                val depthName = "depth_$i.u16"

                z.putNextEntry(ZipEntry(rgbName))
                // RGB 必须已对齐到 depth 分辨率；PNG 无损，fusion 端按 RGB 解。
                s.rgb.compress(Bitmap.CompressFormat.PNG, 100, z)
                z.closeEntry()

                z.putNextEntry(ZipEntry(depthName))
                z.write(s.depth16)
                z.closeEntry()

                var confName: String? = null
                val conf = s.confidence
                if (conf != null) {
                    require(conf.size == expectedConfBytes) {
                        "帧 $i conf 字节数 ${conf.size} != ${expectedConfBytes}(w*h)"
                    }
                    confName = "conf_$i.u8"
                    z.putNextEntry(ZipEntry(confName))
                    z.write(conf)
                    z.closeEntry()
                }

                shotsJson.put(
                    JSONObject().apply {
                        put("index", i)
                        put("rgb", rgbName)
                        put("depth", depthName)
                        put("conf", confName ?: JSONObject.NULL)
                        put("mask", JSONObject.NULL)
                    },
                )
            }
            val manifest = JSONObject().apply {
                put("session_key", sessionId)
                put("frame_count", shots.size)
                put("depth_unit_mm", 1.0)
                put(
                    "intrinsics",
                    JSONObject().apply {
                        put("width", intr.width)
                        put("height", intr.height)
                        put("fx", intr.fx)
                        put("fy", intr.fy)
                        put("cx", intr.cx)
                        put("cy", intr.cy)
                    },
                )
                put("shots", shotsJson)
            }
            z.putNextEntry(ZipEntry("manifest.json"))
            z.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }

        val digest = MessageDigest.getInstance("SHA-256")
        out.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val r = ins.read(buf)
                if (r <= 0) break
                digest.update(buf, 0, r)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class Scan3dBundleUploaderModule {
    @Binds
    abstract fun bindScan3dBundleUploader(impl: DefaultScan3dBundleUploader): Scan3dBundleUploader
}
