package io.gomob.feature.message

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.message.LiveSessionStartResult
import io.gomob.data.message.MediaSessionRepository
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.theme.Gomob
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.PeerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 第一视角发布页 — 直接接 LiveKit Room 作为 publisher，
 * 不再用 CameraX 单独预览。本地预览复用 LiveKit 的 LocalVideoTrack。
 */
@Composable
fun LocalVideoPreviewRoute(
    title: String,
    onBack: () -> Unit,
    viewModel: LocalVideoPreviewViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val controls by viewModel.controls.collectAsStateWithLifecycle()
    val localTrack by viewModel.localTrack.collectAsStateWithLifecycle()
    val room by viewModel.room.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var hasMediaPermission by remember {
        mutableStateOf(
            listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all {
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            },
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        hasMediaPermission = grants.values.all { it }
        if (hasMediaPermission) viewModel.onPermissionGranted()
    }

    LaunchedEffect(title, hasMediaPermission) {
        viewModel.start(title, hasMediaPermission)
        if (!hasMediaPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    BackHandler {
        viewModel.stop()
        onBack()
    }

    GlassHeaderScaffold(
        header = {
            BackHeader(
                title = "第一视角",
                onBack = {
                    viewModel.stop()
                    onBack()
                },
                eyebrow = listOf(
                    title.takeIf { it.isNotBlank() },
                    "正在向团队直播",
                    when (state) {
                        is LocalLiveState.Ready -> "LiveKit 已接通"
                        LocalLiveState.Starting -> "连接中"
                        is LocalLiveState.Unavailable -> "媒体不可用"
                        is LocalLiveState.Error -> "连接失败"
                        LocalLiveState.Idle -> "准备中"
                    },
                ).filterNotNull().joinToString(" · "),
                trailing = {
                    val (text, tone) = when (val s = state) {
                        LocalLiveState.Idle -> "准备中" to StatusTone.Neutral
                        LocalLiveState.Starting -> "连接中" to StatusTone.Neutral
                        is LocalLiveState.Ready -> "直播中" to StatusTone.Ok
                        is LocalLiveState.Unavailable -> "媒体不可用" to StatusTone.Warn
                        is LocalLiveState.Error -> "连接失败" to StatusTone.Danger
                    }
                    StatusTag(text = text, tone = tone, showDot = true)
                },
            )
        },
    ) { padding ->
        // 非滚动整页: 预览区 + 控制条整体避让玻璃 header 与导航栏
        Column(Modifier.fillMaxSize().padding(padding)) {
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val track = localTrack
                when {
                    !hasMediaPermission -> PermissionBlock(
                        text = "需要相机 + 录音权限才能开播",
                        onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) },
                    )
                    !controls.cameraEnabled -> PausedBlock()
                    track != null -> AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            TextureViewRenderer(ctx).apply {
                                layoutParams = ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                )
                                // LiveKit 2.x: 必须显式初始化 renderer 才能收到帧
                                room?.initVideoRenderer(this)
                                track.addRenderer(this)
                            }
                        },
                        onRelease = { view -> track.removeRenderer(view) },
                    )
                    else -> StatusTag(
                        text = "等待采集本地视频轨道",
                        tone = StatusTone.Neutral,
                        showDot = true,
                    )
                }
                val msg = when (val s = state) {
                    is LocalLiveState.Unavailable -> s.message
                    is LocalLiveState.Error -> s.message
                    is LocalLiveState.Ready -> "房间 ${s.providerRoom}"
                    else -> null
                }
                if (msg != null) {
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(Gomob.spacing.s12),
                    ) {
                        StatusTag(
                            text = msg,
                            tone = when (state) {
                                is LocalLiveState.Ready -> StatusTone.Ok
                                is LocalLiveState.Error -> StatusTone.Danger
                                else -> StatusTone.Warn
                            },
                            showDot = true,
                        )
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Gomob.colors.bg1)
                    .padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s12),
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlButton(
                    active = controls.micEnabled,
                    icon = if (controls.micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                    label = if (controls.micEnabled) "静音" else "解除静音",
                    onClick = { viewModel.toggleMic() },
                )
                ControlButton(
                    active = controls.cameraEnabled,
                    icon = if (controls.cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                    label = if (controls.cameraEnabled) "关闭摄像头" else "打开摄像头",
                    onClick = { viewModel.toggleCamera() },
                )
                ControlButton(
                    active = true,
                    icon = Icons.Filled.Cameraswitch,
                    label = "切换摄像头",
                    onClick = { viewModel.flipCamera() },
                )
            }
        }
    }
}

@HiltViewModel
class LocalVideoPreviewViewModel @Inject constructor(
    private val mediaSessionRepository: MediaSessionRepository,
    private val app: Application,
) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<LocalLiveState>(LocalLiveState.Idle)
    val state: StateFlow<LocalLiveState> = _state.asStateFlow()

    private val _controls = MutableStateFlow(LiveControlsUi())
    val controls: StateFlow<LiveControlsUi> = _controls.asStateFlow()

    private val _localTrack = MutableStateFlow<VideoTrack?>(null)
    val localTrack: StateFlow<VideoTrack?> = _localTrack.asStateFlow()

    private val _room = MutableStateFlow<Room?>(null)
    val room: StateFlow<Room?> = _room.asStateFlow()

    private var roomId: String? = null
    private var mediaGranted: Boolean = false
    private var bound: Boolean = false

    fun start(title: String, mediaGranted: Boolean) {
        this.mediaGranted = mediaGranted
        if (bound) return
        bound = true
        viewModelScope.launch { startFirstPersonLive(title) }
    }

    fun onPermissionGranted() {
        if (mediaGranted) return
        mediaGranted = true
        viewModelScope.launch { publishLocalTracks() }
    }

    fun toggleMic() {
        val want = !_controls.value.micEnabled
        viewModelScope.launch {
            runCatching { _room.value?.localParticipant?.setMicrophoneEnabled(want) }
            _controls.update { it.copy(micEnabled = want) }
        }
    }

    fun toggleCamera() {
        val want = !_controls.value.cameraEnabled
        viewModelScope.launch {
            runCatching { _room.value?.localParticipant?.setCameraEnabled(want) }
            _controls.update { it.copy(cameraEnabled = want) }
            refreshLocalTrack()
        }
    }

    fun flipCamera() {
        val r = _room.value ?: return
        viewModelScope.launch {
            runCatching {
                val cam = r.localParticipant
                    .getTrackPublication(Track.Source.CAMERA)
                    ?.track as? LocalVideoTrack
                cam?.switchCamera()
            }
        }
    }

    fun stop() {
        viewModelScope.launch {
            val rid = roomId
            val r = _room.value
            runCatching { r?.disconnect() }
            r?.release()
            _room.value = null
            _localTrack.value = null
            if (rid != null) {
                runCatching { mediaSessionRepository.endVideoCall(rid) }
            }
            _state.value = LocalLiveState.Idle
        }
    }

    private suspend fun startFirstPersonLive(title: String) {
        _state.value = LocalLiveState.Starting
        val started = runCatching {
            mediaSessionRepository.startFirstPersonLive(title.ifBlank { "第一视角直播" })
        }.getOrElse {
            _state.value = LocalLiveState.Error(it.message?.takeIf { m -> m.isNotBlank() } ?: "启动直播失败")
            return
        }
        when (started) {
            is LiveSessionStartResult.Unavailable -> {
                _state.value = LocalLiveState.Unavailable("媒体房间未就绪：${started.title}")
                return
            }
            is LiveSessionStartResult.Ready -> {
                val r = LiveKit.create(
                    appContext = app.applicationContext,
                    overrides = LiveKitOverrides(),
                )
                _room.value = r
                roomId = started.session.mediaRoomId.toString()
                launchEventCollector(r)
                runCatching {
                    r.connect(
                        url = started.url,
                        token = started.token,
                        options = emulatorFriendlyConnectOptions(),
                    )
                    _state.value = LocalLiveState.Ready(
                        providerRoom = started.providerRoom,
                        url = started.url,
                    )
                    if (mediaGranted) publishLocalTracks()
                }.onFailure {
                    _state.value = LocalLiveState.Error(
                        it.message?.takeIf { m -> m.isNotBlank() } ?: "LiveKit 连接失败",
                    )
                }
            }
        }
    }

    private suspend fun publishLocalTracks() {
        val r = _room.value ?: return
        runCatching {
            r.localParticipant.setMicrophoneEnabled(_controls.value.micEnabled)
            r.localParticipant.setCameraEnabled(_controls.value.cameraEnabled)
        }
        refreshLocalTrack()
    }

    private fun launchEventCollector(r: Room) {
        viewModelScope.launch {
            r.events.collect { event ->
                when (event) {
                    is RoomEvent.TrackPublished,
                    is RoomEvent.TrackUnpublished,
                    is RoomEvent.TrackMuted,
                    is RoomEvent.TrackUnmuted -> refreshLocalTrack()
                    is RoomEvent.Disconnected -> _state.value = LocalLiveState.Idle
                    else -> Unit
                }
            }
        }
    }

    private fun refreshLocalTrack() {
        val r = _room.value ?: return
        val track = r.localParticipant
            .getTrackPublication(Track.Source.CAMERA)
            ?.track as? VideoTrack
        _localTrack.value = track
    }

    override fun onCleared() {
        super.onCleared()
        _room.value?.release()
        _room.value = null
    }
}

/**
 * 让 LiveKit 客户端 ICE 同时尝试 TCP candidate（默认是 DISABLED）。
 * emulator 通过 adb reverse 暴露 host LiveKit 时 UDP 走不通，必须 TCP fallback。
 */
private fun emulatorFriendlyConnectOptions(): ConnectOptions {
    val rtc = PeerConnection.RTCConfiguration(emptyList()).apply {
        tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        iceTransportsType = PeerConnection.IceTransportsType.ALL
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }
    return ConnectOptions(rtcConfig = rtc)
}

sealed interface LocalLiveState {
    data object Idle : LocalLiveState
    data object Starting : LocalLiveState
    data class Ready(val providerRoom: String, val url: String) : LocalLiveState
    data class Unavailable(val message: String) : LocalLiveState
    data class Error(val message: String) : LocalLiveState
}

data class LiveControlsUi(
    val micEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
)

@Composable
private fun PermissionBlock(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .clickable(onClick = onClick)
            .padding(Gomob.spacing.s16),
    ) {
        StatusTag(text = text, tone = StatusTone.Warn, showDot = true)
    }
}

@Composable
private fun PausedBlock() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Icon(
            Icons.Filled.VideocamOff,
            contentDescription = null,
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(30.dp),
        )
        Text("摄像头已关闭", style = Gomob.type.bodySm, color = Gomob.colors.fg3)
    }
}

@Composable
private fun ControlButton(
    active: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(Gomob.spacing.touchMin)
            .clip(CircleShape)
            .background(if (active) Gomob.colors.accentSoft else Gomob.colors.bg2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) Gomob.colors.accent else Gomob.colors.fg2,
            modifier = Modifier.size(20.dp),
        )
    }
}
