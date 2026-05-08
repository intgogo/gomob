package io.gomob.media

import kotlinx.coroutines.flow.StateFlow

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
