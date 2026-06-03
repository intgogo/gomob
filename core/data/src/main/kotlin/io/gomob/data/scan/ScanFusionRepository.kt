package io.gomob.data.scan

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.gomob.network.ApiException
import io.gomob.network.ScanApi
import io.gomob.realtime.RealtimeEvent
import io.gomob.realtime.RealtimeSocketClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 云端多视角融合完成结果（端侧回看用，剥离 core:realtime 类型，feature 只见此契约）。 */
data class FusionResult(
    val sessionKey: String,
    val resultObjectKey: String,
    val vertices: Int,
    val triangles: Int,
    val frameCount: Int,
)

/**
 * 端云融合回看仓库：把实时 `scan.fusion_done` 事件与融合结果 GLB 下载收口给 feature 层。
 *
 * feature:scan3d 不直接依赖 core:realtime / core:network，全部经此仓库 + [Scan3dBundleUploader]。
 */
@Singleton
class ScanFusionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val socket: RealtimeSocketClient,
    private val scanApi: ScanApi,
) {
    /** 所有融合完成事件流（未按 session 过滤；调用方自行匹配本次扫描的 sessionKey）。 */
    val fusionEvents: Flow<FusionResult> =
        socket.events
            .filterIsInstance<RealtimeEvent.ScanFusionDone>()
            .map {
                FusionResult(
                    sessionKey = it.sessionKey,
                    resultObjectKey = it.resultObjectKey,
                    vertices = it.vertices,
                    triangles = it.triangles,
                    frameCount = it.frameCount,
                )
            }

    /** 确保实时通道已连接（扫描页进场时调，保证能收到 fusion_done 推送）。 */
    fun ensureRealtimeConnected() = socket.connect()

    /**
     * 按 [sessionKey] 经 server 流式中转下载融合结果 GLB 到本地缓存，返回文件。
     * server 端未就绪（仍在融合）会抛 [ApiException]（HTTP 409）；调用方应先等 [fusionEvents]。
     */
    suspend fun downloadResultGlb(sessionKey: String): File {
        val body = scanApi.downloadFusionResult(sessionKey)
        val dir = File(context.cacheDir, "scan_results").apply { mkdirs() }
        val out = File(dir, "$sessionKey.glb")
        try {
            body.byteStream().use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Throwable) {
            // 流中断(网络超时/服务端关闭)会留下截断的 .glb,删掉避免下游 Filament 读到坏文件。
            out.delete()
            throw e
        }
        if (out.length() <= 0L) {
            out.delete()
            throw ApiException(50001, 500, "融合结果 GLB 为空")
        }
        return out
    }
}
