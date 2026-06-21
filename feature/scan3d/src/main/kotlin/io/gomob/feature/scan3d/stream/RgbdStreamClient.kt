package io.gomob.feature.scan3d.stream

import android.util.Log
import io.gomob.model.CameraIntrinsics
import io.gomob.model.ColorFrame
import io.gomob.model.DepthFrame
import io.gomob.nativebridge.camera.CameraSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicLong

/**
 * RgbdStreamClient —— 把 Berxel RGBD 帧实时流给 gorob 边缘 (机器人视觉源)。
 *
 * 链路 (用户拍板 2026-06-02): 手机(本类) --WebSocket 二进制 protobuf RgbdFrame--> gorob `cmd/robot-edge`
 * 的 `/rgbd` 端点 → rgbdwire 解码 → ICP+TSDF 融合 → /world 流给 Pico4。先 WebSocket 验证, 后续可切 WebRTC。
 *
 * 数据约定 (对齐 gorob `proto/rgbd.proto` + `pkg/rgbdwire`):
 *   - 深度: DepthFrame.data = uint16 小端毫米 (0=无效) → DEPTH_U16_MM, gorob 转米。
 *   - 彩色: ColorFrame.data = BGR888 (NativeBridge 已转) → COLOR_BGR8, gorob 翻回 RGB; 尺寸不符则不发彩色。
 *   - 置信: DepthFrame.confidence = uint8 (0=无效) → conf, gorob 归一 [0,1]。
 *   - 位姿: [PoseProvider] (默认单位/未跟踪 → gorob 纯 ICP; 接 ARCore 后出 VIO 先验)。
 *   - 内参: DepthFrame.intrinsics (含畸变, gorob 重映射成针孔)。
 *
 * ⚠️ 未在安卓设备实测 (本环境无 SDK/设备)。验证: 接好 UI 开关后 `./dev.sh install` 推真机, 连 gorob
 *    `cmd/robot-edge --addr :8111`, 看 robot-edge 日志"RGBD 融合"帧数增长 + Pico4 `/vr` 实时点云生长。
 */
// TODO(R6): 本类零调用方且未在真机实测 —— 尚未接入任何 UI 入口/开关, start()/stop() 当前无人触发,
//   gorob robot-edge `/rgbd` 端到端融合也未真机联调验证。保留不删(终态见上方链路注释 + gorob proto/rgbd.proto)。
//   联调步骤见类头 ⚠️ 段:接好 UI 开关 → ./dev.sh install → 连 robot-edge → 看融合帧增长 + Pico4 /vr 点云生长。
class RgbdStreamClient(
    private val url: String,
    private val scope: CoroutineScope,
    private val poseProvider: PoseProvider = IdentityPoseProvider,
) {
    private val client = OkHttpClient.Builder().pingInterval(java.time.Duration.ofSeconds(15)).build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var latestColor: ColorFrame? = null
    @Volatile private var connected = false
    private var colorJob: Job? = null
    private var depthJob: Job? = null
    private val frameCounter = AtomicLong(0)
    private var sent = 0L
    private var dropped = 0L

    /** 启动: acquire 相机源, 开 WS, 收彩色(缓存最近一帧) + 收深度(逐帧编码上行)。 */
    fun start(source: CameraSource) {
        source.acquire()
        ws = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                connected = true
                Log.i(TAG, "RGBD 流已连接 $url")
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                connected = false
                Log.w(TAG, "RGBD 流断开: ${t.message}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                connected = false
            }
        })
        colorJob = source.colorFrames.onEach { latestColor = it }.launchIn(scope)
        depthJob = source.depthFrames.onEach { sendDepth(it) }.launchIn(scope)
    }

    /** 停止: 取消收集, 关 WS, release 相机源。 */
    fun stop(source: CameraSource) {
        colorJob?.cancel(); depthJob?.cancel()
        ws?.close(1000, "stop"); ws = null
        source.release()
        Log.i(TAG, "RGBD 流停止: 发 $sent 帧, 丢 $dropped 帧")
    }

    fun stats(): Pair<Long, Long> = sent to dropped

    private fun sendDepth(depth: DepthFrame) {
        val w = ws ?: return
        if (!connected) { dropped++; return }
        val depthBytes = toByteArray(depth.data, depth.width * depth.height * 2)
        if (depthBytes == null) { dropped++; return }
        val confBytes = depth.confidence?.let { toByteArray(it, depth.width * depth.height) }

        // 彩色: 仅当与本深度帧【同时间戳同尺寸】且字节数为 W*H*3 (BGR888) 才附带, 否则发深度无彩色。
        val c = latestColor
        val colorBytes = if (c != null && c.timestampUs == depth.timestampUs &&
            c.width == depth.width && c.height == depth.height
        ) toByteArray(c.data, depth.width * depth.height * 3) else null

        val pose = poseProvider.poseFor(depth.timestampUs)
        val msg = encode(frameCounter.getAndIncrement(), depth.timestampUs, depth.width, depth.height,
            depth.intrinsics, pose, depthBytes, colorBytes, confBytes)
        if (w.send(msg.toByteString(0, msg.size))) sent++ else dropped++ // send 队列满返回 false → 计丢 (实时性优先)
    }

    /** 读出 DirectByteBuffer 的有效字节; 若可用字节 < 期望长度则返回 null (尺寸不符, 丢帧不毒化融合)。 */
    private fun toByteArray(b: ByteBuffer, expect: Int): ByteArray? {
        val dup = b.duplicate()
        dup.rewind()
        if (dup.remaining() < expect) return null
        val arr = ByteArray(expect)
        dup.get(arr, 0, expect)
        return arr
    }

    companion object {
        private const val TAG = "RgbdStreamClient"
        private const val DEPTH_U16_MM = 1
        private const val COLOR_NONE = 1
        private const val COLOR_BGR8 = 3

        /** 编码一帧 RgbdFrame (字段号对齐 proto/rgbd.proto)。 */
        fun encode(
            frameIndex: Long, timestampUs: Long, width: Int, height: Int,
            intr: CameraIntrinsics, pose: Pose6,
            depth: ByteArray, color: ByteArray?, conf: ByteArray?,
        ): ByteArray {
            val intrMsg = ProtoWriter().apply {
                double(1, intr.fx); double(2, intr.fy); double(3, intr.cx); double(4, intr.cy)
                // CameraIntrinsics.width/height = 内参【标定时】分辨率 (非帧分辨率)。fx/fy/cx/cy 以此为基准;
                // 若标定分辨率 != 帧分辨率, gorob/消费端须据二者比例缩放内参 (Berxel 每帧内参通常即帧分辨率, 相等)。
                int32(5, intr.width); int32(6, intr.height); packedDoubles(7, intr.distortion)
            }.toByteArray()
            val poseMsg = ProtoWriter().apply {
                double(1, pose.qx); double(2, pose.qy); double(3, pose.qz); double(4, pose.qw)
                double(5, pose.tx); double(6, pose.ty); double(7, pose.tz); bool(8, pose.tracking)
            }.toByteArray()
            return ProtoWriter().apply {
                int64(1, frameIndex)
                int64(2, timestampUs)
                int32(3, width)
                int32(4, height)
                message(5, intrMsg)
                message(6, poseMsg)
                enum(7, DEPTH_U16_MM)
                enum(8, if (color != null) COLOR_BGR8 else COLOR_NONE)
                bytes(9, depth)
                bytes(10, color)
                bytes(11, conf)
            }.toByteArray()
        }
    }
}
