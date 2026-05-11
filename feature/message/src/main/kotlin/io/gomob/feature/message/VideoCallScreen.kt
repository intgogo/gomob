package io.gomob.feature.message

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.message.MediaSessionRepository
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val context = LocalContext.current
    var micEnabled by remember { mutableStateOf(true) }
    var cameraEnabled by remember { mutableStateOf(true) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_FRONT) }
    var cameraError by remember { mutableStateOf<String?>(null) }
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
        if (!hasMediaPermission) cameraError = "需要相机和麦克风权限"
    }

    LaunchedEffect(roomId, title, mode) {
        viewModel.bind(roomId = roomId, title = title, mode = mode)
        if (!hasMediaPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO))
        }
    }

    BackHandler {
        viewModel.hangup()
        onBack()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        RemoteVideoStage(
            title = title,
            state = state,
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
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
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title.ifBlank { "视频通话" }, style = Gomob.type.body, color = Color.White, maxLines = 1)
                Text(state.subtitle, style = Gomob.type.caption, color = Color.White.copy(alpha = 0.68f), maxLines = 1)
            }
        }

        if (cameraEnabled && hasMediaPermission) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 86.dp, end = Gomob.spacing.s16)
                    .width(112.dp)
                    .height(154.dp)
                    .clip(Gomob.shapes.r3)
                    .background(Color(0xFF141821)),
            ) {
                CameraPreview(
                    lensFacing = lensFacing,
                    onError = { cameraError = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        cameraError?.let {
            StatusTag(
                text = it,
                tone = StatusTone.Warn,
                showDot = true,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 116.dp),
            )
        }

        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s24),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CallControlButton(
                active = micEnabled,
                icon = if (micEnabled) Icons.Filled.Mic else Icons.Filled.MicOff,
                label = if (micEnabled) "关闭麦克风" else "打开麦克风",
                onClick = { micEnabled = !micEnabled },
            )
            CallControlButton(
                active = cameraEnabled,
                icon = if (cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                label = if (cameraEnabled) "关闭摄像头" else "打开摄像头",
                onClick = {
                    cameraEnabled = !cameraEnabled
                    if (cameraEnabled) cameraError = null
                },
            )
            CallControlButton(
                active = true,
                icon = Icons.Filled.Cameraswitch,
                label = "切换摄像头",
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        CameraSelector.LENS_FACING_BACK
                    } else {
                        CameraSelector.LENS_FACING_FRONT
                    }
                    cameraError = null
                },
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

@Composable
private fun RemoteVideoStage(
    title: String,
    state: VideoCallUiState,
    modifier: Modifier = Modifier,
) {
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
            Text(
                title.ifBlank { "视频通话" },
                style = Gomob.type.title,
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Text(
                state.message,
                style = Gomob.type.bodySm,
                color = Color.White.copy(alpha = 0.68f),
                textAlign = TextAlign.Center,
            )
            if (state is VideoCallUiState.Connected) {
                Spacer(Modifier.height(Gomob.spacing.s4))
                StatusTag(text = "媒体房间 ${state.providerRoom}", tone = StatusTone.Ok, showDot = true)
            }
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

@HiltViewModel
class VideoCallViewModel @Inject constructor(
    private val mediaSessionRepository: MediaSessionRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val _state = MutableStateFlow<VideoCallUiState>(VideoCallUiState.Idle)
    val state: StateFlow<VideoCallUiState> = _state.asStateFlow()
    private var boundRoomId = savedStateHandle.get<String>("roomId").orEmpty()
    private var boundTitle = savedStateHandle.get<String>("title").orEmpty()
    private var boundMode = savedStateHandle.get<String>("mode").orEmpty()

    init {
        bind(boundRoomId, boundTitle, boundMode)
    }

    fun bind(roomId: String, title: String, mode: String) {
        if (roomId.isBlank()) {
            _state.value = VideoCallUiState.Error("媒体房间参数无效")
            return
        }
        if (boundRoomId == roomId && _state.value !is VideoCallUiState.Idle) return
        boundRoomId = roomId
        boundTitle = title
        boundMode = mode
        if (mode == VideoCallMode.Caller.routeValue) {
            waitForAnswer()
        } else {
            connect()
        }
    }

    fun hangup() {
        val roomId = boundRoomId.takeIf { it.isNotBlank() } ?: return
        viewModelScope.launch {
            runCatching { mediaSessionRepository.endVideoCall(roomId) }
            _state.value = VideoCallUiState.Ended
        }
    }

    private fun waitForAnswer() {
        _state.value = VideoCallUiState.Calling
        viewModelScope.launch {
            while (isActive && _state.value is VideoCallUiState.Calling) {
                delay(1_000)
                runCatching { mediaSessionRepository.videoCallRoomStatus(boundRoomId) }
                    .onSuccess { room ->
                        when {
                            room.status == "ended" -> _state.value = VideoCallUiState.Ended
                            room.callAccepted -> connect()
                        }
                    }
                    .onFailure { error ->
                        _state.value = VideoCallUiState.Error(error.readableCallMessage())
                    }
            }
        }
    }

    private fun connect() {
        if (_state.value is VideoCallUiState.Connecting || _state.value is VideoCallUiState.Connected) return
        _state.value = VideoCallUiState.Connecting
        viewModelScope.launch {
            _state.value = runCatching {
                val joined = mediaSessionRepository.joinVideoCall(boundRoomId)
                VideoCallUiState.Connected(providerRoom = joined.providerRoom)
            }.getOrElse { error ->
                VideoCallUiState.Error(error.readableCallMessage())
            }
        }
    }
}

sealed interface VideoCallUiState {
    val subtitle: String
    val message: String

    data object Idle : VideoCallUiState {
        override val subtitle: String = "准备中"
        override val message: String = "正在准备视频通话"
    }

    data object Calling : VideoCallUiState {
        override val subtitle: String = "正在呼叫"
        override val message: String = "等待对方接受后建立视频通话"
    }

    data object Connecting : VideoCallUiState {
        override val subtitle: String = "正在接通"
        override val message: String = "正在加入媒体房间"
    }

    data class Connected(val providerRoom: String) : VideoCallUiState {
        override val subtitle: String = "通话中"
        override val message: String = "已进入通话，等待 LiveKit 视频轨道渲染"
    }

    data object Ended : VideoCallUiState {
        override val subtitle: String = "已结束"
        override val message: String = "视频通话已结束"
    }

    data class Error(val reason: String) : VideoCallUiState {
        override val subtitle: String = "接通失败"
        override val message: String = reason
    }
}

private fun Throwable.readableCallMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "视频通话服务暂不可用"
