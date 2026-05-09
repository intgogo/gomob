package io.gomob.data.message

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

interface MediaAssetUploader {
    suspend fun upload(uri: Uri, kind: MediaAssetKind): UploadedMediaAsset
}

enum class MediaAssetKind(
    val serverKind: String,
    val fallbackMime: String,
) {
    Image("message_image", "image/jpeg"),
    Voice("message_voice", "audio/mp4"),
    VideoClip("message_video_clip", "video/mp4"),
}

data class UploadedMediaAsset(
    val assetId: String,
    val objectKey: String,
    val downloadUrl: String?,
    val mime: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Singleton
class DefaultMediaAssetUploader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AssetApi,
) : MediaAssetUploader {
    override suspend fun upload(uri: Uri, kind: MediaAssetKind): UploadedMediaAsset {
        val staged = stageUri(uri, kind)
        try {
            val initResp = api.initUpload(
                AssetUploadInitRequest(
                    kind = kind.serverKind,
                    sizeBytes = staged.file.length(),
                    sha256 = staged.sha256,
                    mime = staged.mime,
                ),
            )
            val init = initResp.data ?: throw ApiException(50001, 500, "上传初始化响应缺数据")
            val chunkSize = init.chunkSize.coerceAtLeast(1)
            var totalChunks = 0
            staged.file.inputStream().use { input ->
                val buffer = ByteArray(chunkSize)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    totalChunks += 1
                    val bytes = buffer.copyOf(read)
                    api.uploadChunk(
                        uploadId = init.uploadId,
                        chunkNumber = totalChunks,
                        body = bytes.toRequestBody(staged.mime.toMediaTypeOrNull()),
                    )
                }
            }
            val completeResp = api.completeUpload(
                uploadId = init.uploadId,
                request = AssetUploadCompleteRequest(totalChunks = totalChunks.coerceAtLeast(1)),
            )
            val complete = completeResp.data ?: throw ApiException(50001, 500, "上传完成响应缺数据")
            return UploadedMediaAsset(
                assetId = complete.assetId,
                objectKey = complete.objectKey,
                downloadUrl = complete.downloadUrl,
                mime = staged.mime,
                sizeBytes = staged.file.length(),
                sha256 = staged.sha256,
            )
        } finally {
            staged.file.delete()
        }
    }

    private fun stageUri(uri: Uri, kind: MediaAssetKind): StagedMediaAsset {
        val resolver = context.contentResolver
        val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: kind.fallbackMime
        val dir = File(context.cacheDir, "message_uploads").also { it.mkdirs() }
        val ext = mime.substringAfter('/', "bin").substringBefore(';').ifBlank { "bin" }
        val out = File.createTempFile("gomob_media_", ".$ext", dir)
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val input = if (uri.scheme == "file") {
            File(uri.path.orEmpty()).inputStream()
        } else {
            resolver.openInputStream(uri)
                ?: throw IllegalArgumentException("无法读取媒体文件")
        }
        input.use { source ->
            out.outputStream().use { target ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = source.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                    target.write(buffer, 0, read)
                    total += read
                }
            }
        }
        if (total <= 0L) {
            out.delete()
            throw IllegalArgumentException("媒体文件为空")
        }
        return StagedMediaAsset(
            file = out,
            mime = mime,
            displayName = displayName(uri),
            sha256 = digest.digest().joinToString("") { "%02x".format(it) },
        )
    }

    private fun displayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
}

private data class StagedMediaAsset(
    val file: File,
    val mime: String,
    val displayName: String?,
    val sha256: String,
)

@Module
@InstallIn(SingletonComponent::class)
abstract class MediaAssetUploaderModule {
    @Binds
    abstract fun bindMediaAssetUploader(impl: DefaultMediaAssetUploader): MediaAssetUploader
}
