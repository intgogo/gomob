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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.message.MediaSessionRepository
import io.gomob.designsystem.component.LocalFeedbackTitleTrigger
import io.gomob.designsystem.component.feedbackTitleFiveTap
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Composable
fun VideoCallRoute(
    roomId: String,
    title: String,
    mode: String,
    onBack: () -> Unit,
    viewModel: VideoCallViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val participants by viewModel.participants.collectAsStateWithLifecycle()
    val localControls by viewModel.localControls.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val pageTitle = title.ifBlank { "视频通话" }
    val feedbackTrigger = LocalFeedbackTitleTrigger.current
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

    LaunchedEffect(roomId, title, mode, hasMediaPermission) {
        viewModel.bind(roomId = roomId, title = title, mode = mode, mediaGranted = hasMediaPermission)
        if (!hasMediaPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    BackHandler {
        viewModel.hangup()
        onBack()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        ParticipantGrid(
            participants = participants,
            state = state,
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(150.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.56f), Color.Transparent),
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(190.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)),
                    ),
                ),
        )

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s20),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            Box(
                Modifier
                    .size(Gomob.spacing.touchMin)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.14f))
                    .clickable {
                        viewModel.hangup()
                        onBack()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    GomobIcons.ChevronLeft,
                    contentDescription = "返回",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(
                Modifier
                    .weight(1f)
                    .then(
                        if (feedbackTrigger != null) {
                            Modifier.feedbackTitleFiveTap(pageTitle, feedbackTrigger)
                        } else {
                            Modifier
                        },
                    ),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(pageTitle, style = Gomob.type.body, color = Color.White, maxLines = 1)
                Text(state.subtitle(participants.size), style = Gomob.type.caption, color = Color.White.copy(alpha = 0.68f), maxLines = 1)
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s24),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CallControlButton(
                active = localControls.micEnabled,
                icon = if (localControls.micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                label = if (localControls.micEnabled) "关闭麦克风" else "打开麦克风",
                onClick = { viewModel.toggleMicrophone() },
            )
            CallControlButton(
                active = localControls.cameraEnabled,
                icon = if (localControls.cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                label = if (localControls.cameraEnabled) "关闭摄像头" else "打开摄像头",
                onClick = { viewModel.toggleCamera() },
            )
            CallControlButton(
                active = true,
                icon = Icons.Filled.Cameraswitch,
                label = "切换摄像头",
                onClick = { viewModel.flipCamera() },
            )
            CallControlButton(
                active = true,
                icon = Icons.Filled.CallEnd,
                label = "挂断",
                danger = true,
                onClick = {
                    viewModel.hangup()
                    onBack()
                },
            )
        }
    }
}

/**
 * 按 participant 数自适应网格：1 全屏，2 上下平铺，3-4 → 2x2，5-9 → 3x3，>9 → 3 列纵向滚动。
 *
 * 不可见 tile 不会创建 TextureViewRenderer，省 WebRTC 内存（LazyVerticalGrid 自动 recycle）。
 */
@Composable
private fun ParticipantGrid(
    participants: List<ParticipantUi>,
    state: VideoCallUiState,
    modifier: Modifier = Modifier,
) {
    if (participants.isEmpty()) {
        EmptyCallStage(state = state, modifier = modifier)
        return
    }
    val columns = when {
        participants.size <= 2 -> 1
        participants.size <= 4 -> 2
        else -> 3
    }
    val rows = when {
        participants.size <= 1 -> 1
        participants.size <= 4 -> 2
        else -> 3
    }
    val spacing = 4.dp
    BoxWithConstraints(modifier) {
        val tileHeight = ((maxHeight - spacing * (rows - 1).toFloat()) / rows).coerceAtLeast(160.dp)
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
        ) {
            items(participants, key = { it.identity }) { p ->
                ParticipantTile(
                    participant = p,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(tileHeight),
                )
            }
        }
    }
}

@Composable
private fun ParticipantTile(
    participant: ParticipantUi,
    modifier: Modifier = Modifier
        .fillMaxWidth()
        .height(220.dp),
) {
    val track = participant.videoTrack
    Box(
        modifier
            .background(Color(0xFF141821)),
        contentAlignment = Alignment.Center,
    ) {
        if (track != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureViewRenderer(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        // LiveKit 2.x: 必须显式 initVideoRenderer，否则 SDK 会刷
                        // "Received frame when not initialized" 并黑屏。
                        participant.initRenderer?.invoke(this)
                        track.addRenderer(this)
                    }
                },
                onRelease = { view -> track.removeRenderer(view) },
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.10f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.VideocamOff,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    participant.displayName,
                    style = Gomob.type.caption,
                    color = Color.White.copy(alpha = 0.82f),
                )
            }
        }
        if (!participant.micEnabled) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.62f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.MicOff, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
        Text(
            participant.displayName + if (participant.isLocal) " · 我" else "",
            style = Gomob.type.caption,
            color = Color.White,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(Gomob.shapes.r1)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun EmptyCallStage(state: VideoCallUiState, modifier: Modifier = Modifier) {
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
            modifier = Modifier.padding(horizontal = Gomob.spacing.s32),
        ) {
            Box(
                Modifier
                    .size(84.dp)
                    .clip(Gomob.shapes.r3)
                    .background(Color.White.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Videocam,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.78f),
                    modifier = Modifier.size(34.dp),
                )
            }
            Text(state.message, style = Gomob.type.bodySm, color = Color.White.copy(alpha = 0.78f), textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CallControlButton(
    active: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        danger -> Color(0xFFE5484D)
        active -> Color.White.copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.10f)
    }
    val tint = if (danger) Color.White else Color.White.copy(alpha = if (active) 0.92f else 0.52f)
    Box(
        Modifier
            .size(58.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(25.dp))
    }
}

/**
 * 真正的 LiveKit Room 接入：连接、publish 本地 track、订阅远端 participant flow。
 *
 * 关键边界：
 * - Application context 持有 Room 生命周期（绑到 ViewModel scope）；离开页面 release。
 * - 本地 track 不 publish 直到 mediaGranted=true，避免没权限时 publish 失败。
 * - 远端 participant 通过 RoomEvent.ParticipantConnected/Disconnected 增量维护，
 *   每条 ParticipantUi 包含当前的 videoTrack（订阅了第一条 camera track）。
 */
@HiltViewModel
class VideoCallViewModel @Inject constructor(
    private val mediaSessionRepository: MediaSessionRepository,
    private val app: Application,
    savedStateHandle: SavedStateHandle,
) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<VideoCallUiState>(VideoCallUiState.Idle)
    val state: StateFlow<VideoCallUiState> = _state.asStateFlow()

    private val _participants = MutableStateFlow<List<ParticipantUi>>(emptyList())
    val participants: StateFlow<List<ParticipantUi>> = _participants.asStateFlow()

    private val _localControls = MutableStateFlow(LocalControlsUi())
    val localControls: StateFlow<LocalControlsUi> = _localControls.asStateFlow()

    private var room: Room? = null
    private var lensFront: Boolean = true
    private var mediaGranted: Boolean = false
    private var boundRoomId = savedStateHandle.get<String>("roomId").orEmpty()
    private var boundMode = savedStateHandle.get<String>("mode").orEmpty()

    fun bind(roomId: String, title: String, mode: String, mediaGranted: Boolean) {
        if (roomId.isBlank()) {
            _state.value = VideoCallUiState.Error("媒体房间参数无效")
            return
        }
        this.mediaGranted = mediaGranted
        if (room != null && boundRoomId == roomId) return
        boundRoomId = roomId
        boundMode = mode
        connect()
    }

    fun onPermissionGranted() {
        if (mediaGranted) return
        mediaGranted = true
        viewModelScope.launch {
            publishLocalTracks()
        }
    }

    fun toggleMicrophone() {
        val current = _localControls.value.micEnabled
        viewModelScope.launch {
            runCatching {
                room?.localParticipant?.setMicrophoneEnabled(!current)
            }
            _localControls.update { it.copy(micEnabled = !current) }
        }
    }

    fun toggleCamera() {
        val current = _localControls.value.cameraEnabled
        viewModelScope.launch {
            runCatching {
                room?.localParticipant?.setCameraEnabled(!current)
            }
            _localControls.update { it.copy(cameraEnabled = !current) }
            refreshParticipants()
        }
    }

    fun flipCamera() {
        val r = room ?: return
        viewModelScope.launch {
            runCatching {
                val cameraTrack = r.localParticipant
                    .getTrackPublication(Track.Source.CAMERA)
                    ?.track as? LocalVideoTrack
                cameraTrack?.switchCamera()
                lensFront = !lensFront
            }
        }
    }

    fun hangup() {
        viewModelScope.launch {
            val roomId = boundRoomId
            runCatching { room?.disconnect() }
            room?.release()
            room = null
            if (roomId.isNotBlank()) {
                runCatching { mediaSessionRepository.endVideoCall(roomId) }
            }
            _state.value = VideoCallUiState.Ended
        }
    }

    private fun connect() {
        if (_state.value is VideoCallUiState.Connecting || _state.value is VideoCallUiState.Connected) return
        _state.value = VideoCallUiState.Connecting
        viewModelScope.launch {
            val joinResult = runCatching { mediaSessionRepository.joinVideoCall(boundRoomId) }
                .getOrElse {
                    _state.value = VideoCallUiState.Error(it.readableCallMessage())
                    return@launch
                }
            val r = LiveKit.create(
                appContext = app.applicationContext,
                overrides = LiveKitOverrides(),
            )
            room = r
            launchEventCollector(r)
            runCatching {
                r.connect(url = joinResult.url, token = joinResult.token)
                _state.value = VideoCallUiState.Connected(joinResult.providerRoom)
                if (mediaGranted) {
                    publishLocalTracks()
                }
                refreshParticipants()
            }.onFailure {
                _state.value = VideoCallUiState.Error(it.readableCallMessage())
            }
        }
    }

    private suspend fun publishLocalTracks() {
        val r = room ?: return
        runCatching {
            r.localParticipant.setMicrophoneEnabled(_localControls.value.micEnabled)
            r.localParticipant.setCameraEnabled(_localControls.value.cameraEnabled)
        }
        refreshParticipants()
    }

    private fun launchEventCollector(r: Room) {
        viewModelScope.launch {
            r.events.collect { event ->
                when (event) {
                    is RoomEvent.ParticipantConnected,
                    is RoomEvent.ParticipantDisconnected,
                    is RoomEvent.TrackSubscribed,
                    is RoomEvent.TrackUnsubscribed,
                    is RoomEvent.TrackMuted,
                    is RoomEvent.TrackUnmuted,
                    is RoomEvent.TrackPublished,
                    is RoomEvent.TrackUnpublished -> refreshParticipants()
                    is RoomEvent.Disconnected -> _state.value = VideoCallUiState.Ended
                    else -> Unit
                }
            }
        }
    }

    private fun refreshParticipants() {
        val r = room ?: return
        val init: (TextureViewRenderer) -> Unit = { view -> r.initVideoRenderer(view) }
        val list = mutableListOf<ParticipantUi>()
        list += r.localParticipant.toUi(isLocal = true).copy(initRenderer = init)
        r.remoteParticipants.values.forEach { list += it.toUi(isLocal = false).copy(initRenderer = init) }
        _participants.value = list
    }

    override fun onCleared() {
        super.onCleared()
        room?.release()
        room = null
    }
}

private fun Participant.toUi(isLocal: Boolean): ParticipantUi {
    val camTrack = getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
    val micPub = getTrackPublication(Track.Source.MICROPHONE)
    val name = identity?.value?.takeIf { it.isNotBlank() } ?: name?.takeIf { it.isNotBlank() } ?: sid.value
    return ParticipantUi(
        identity = identity?.value ?: sid.value,
        displayName = name,
        isLocal = isLocal,
        videoTrack = camTrack,
        micEnabled = micPub?.muted?.not() ?: false,
    )
}

data class ParticipantUi(
    val identity: String,
    val displayName: String,
    val isLocal: Boolean,
    val videoTrack: VideoTrack?,
    val micEnabled: Boolean,
    val initRenderer: ((TextureViewRenderer) -> Unit)? = null,
)

data class LocalControlsUi(
    val micEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
)

sealed interface VideoCallUiState {
    fun subtitle(participantCount: Int): String
    val message: String

    data object Idle : VideoCallUiState {
        override fun subtitle(participantCount: Int) = "准备中"
        override val message: String = "正在准备视频通话"
    }

    data object Connecting : VideoCallUiState {
        override fun subtitle(participantCount: Int) = "正在接通"
        override val message: String = "正在加入媒体房间"
    }

    data class Connected(val providerRoom: String) : VideoCallUiState {
        override fun subtitle(participantCount: Int): String =
            if (participantCount <= 1) "等待对方加入" else "通话中 · $participantCount 人"
        override val message: String = "等待对方加入"
    }

    data object Ended : VideoCallUiState {
        override fun subtitle(participantCount: Int) = "已结束"
        override val message: String = "视频通话已结束"
    }

    data class Error(val reason: String) : VideoCallUiState {
        override fun subtitle(participantCount: Int) = "接通失败"
        override val message: String = reason
    }
}

private fun Throwable.readableCallMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "视频通话服务暂不可用"
