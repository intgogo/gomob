package io.gomob.media

import kotlinx.coroutines.flow.StateFlow

// TODO(deferred-structural): 空占位模块,未实现 —— 仅定义接口与状态类型,无任何实现 (LiveKit/WebRTC 房间客户端)。
//   当前被依赖方引用但拿不到可用实现。结构性处置(下沉到使用方 / 删空依赖 / 提供真实实现)留后续:
//   - 终态:接 LiveKit Android SDK 实现 connect/publishCamera/publishMicrophone/disconnect,见 docs/architecture 媒体面专题。
//   - 本轮不删模块/不改依赖以控爆炸半径,仅显式标注空占位避免被误当成已实现。
interface MediaRoomClient {
    val state: StateFlow<MediaRoomState>

    suspend fun connect(roomId: String, token: String)

    suspend fun publishCamera(enabled: Boolean)

    suspend fun publishMicrophone(enabled: Boolean)

    suspend fun disconnect(reason: String)
}

data class MediaRoomState(
    val roomId: String? = null,
    val connected: Boolean = false,
    val publishingCamera: Boolean = false,
    val publishingMicrophone: Boolean = false,
    val lastError: String? = null,
)
