package io.gomob.feature.collaboration

import android.app.Application
import android.view.ViewGroup
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.message.MediaSessionRepository
import io.gomob.designsystem.component.LocalFeedbackTitleLongPress
import io.gomob.designsystem.component.feedbackTitleLongPress
import io.gomob.designsystem.theme.Gomob
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.PeerConnection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 第一视角观看页 — 抖音直播间风格。
 *
 * 顶部：← + 「查验员·名字 / 工号」 + 右上观看人数 pill（点击弹观众列表）
 * 视频铺满，右侧竖排 头像(点头像跳主播详情) + 介入/截图/切换/预警/通话
 * 底部：抖音直播评论栏 — 文字输入 pill + 麦克风按钮（发文字 / 录语音）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirstPersonViewerRoute(
    streamId: String,
    onBack: () -> Unit,
    onOpenInspector: (String) -> Unit = {},
    viewModel: FirstPersonViewerViewModel = hiltViewModel(),
) {
    // TODO(demo-data R1): 这是占位假主播身份(姓名/工号/观看数),未接 MediaSession + ws 推送的真实
    // publisher metadata,恒回退首条假数据;终态从 join 后的 ViewerLiveState/ws 元数据取真实身份。
    val s = STREAM_DETAILS[streamId] ?: STREAM_DETAILS.values.first()
    val mediaState by viewModel.state.collectAsStateWithLifecycle()
    val remoteTrack by viewModel.remoteTrack.collectAsStateWithLifecycle()
    val room by viewModel.room.collectAsStateWithLifecycle()

    LaunchedEffect(streamId) { viewModel.join(streamId) }

    var watchersOpen by remember { mutableStateOf(false) }
    var commentDraft by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // 点空白区域收起键盘 + 清除 TextField 焦点（抖音直播评论交互）
            .pointerInput(Unit) {
                detectTapGestures(onTap = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                })
            },
    ) {
        // ---- 视频底层 ----
        val track = remoteTrack
        if (track != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    TextureViewRenderer(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        room?.initVideoRenderer(this)
                        track.addRenderer(this)
                    }
                },
                onRelease = { view -> track.removeRenderer(view) },
            )
        } else {
            EmptyVideoStage(state = mediaState, fallbackTaskId = s.taskId)
        }

        // ---- 顶部 / 底部 渐变蒙版 ----
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(160.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent),
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(220.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f)),
                    ),
                ),
        )

        // ---- 顶部：返回 + 标题(查验员) + 观看 pill ----
        TopBar(
            inspector = s.inspector,
            employeeId = s.employeeId,
            watchers = s.watchers,
            onBack = {
                viewModel.leave()
                onBack()
            },
            onShowWatchers = { watchersOpen = true },
        )

        // ---- 右侧竖排 ----
        SideActions(
            inspectorInitial = s.inspector.take(1),
            onOpenInspector = {
                val pid = (mediaState as? ViewerLiveState.Ready)?.publisherId
                if (!pid.isNullOrBlank()) {
                    onOpenInspector(pid)
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(end = 12.dp, bottom = 96.dp),
        )

        // ---- 底部：评论输入栏 + 麦克风（抖音风） ----
        // R3: 评论/语音后端(ws live_annotations / 录音上传)未实现。为避免静默吞掉用户输入,
        // 这里把两个控件禁用并提示"未实现",不伪造发送成功。终态接 ws 实时评论 + 语音消息后再开放。
        CommentBar(
            value = commentDraft,
            enabled = false,
            onValueChange = { commentDraft = it },
            onSend = {
                Toast.makeText(context, "实时评论功能尚未实现", Toast.LENGTH_SHORT).show()
            },
            onRecordVoice = {
                Toast.makeText(context, "语音功能尚未实现", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }

    // ---- 观众列表 sheet ----
    if (watchersOpen) {
        ModalBottomSheet(
            onDismissRequest = { watchersOpen = false },
            sheetState = sheetState,
            // 拟玻璃面板：Dialog 独立 window 做不了真模糊，用高不透明 bg1 拟合
            containerColor = Gomob.colors.bg1.copy(alpha = 0.97f),
            contentColor = Color.White,
        ) {
            WatchersSheetContent(total = s.watchers)
        }
    }
}

// ============================================================================
// 顶部 bar
// ============================================================================
@Composable
private fun TopBar(
    inspector: String,
    employeeId: String,
    watchers: Int,
    onBack: () -> Unit,
    onShowWatchers: () -> Unit,
) {
    val feedbackTrigger = LocalFeedbackTitleLongPress.current
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.36f))
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.ArrowBackIosNew,
                contentDescription = "返回",
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(
            Modifier
                .weight(1f)
                .then(
                    if (feedbackTrigger != null) {
                        Modifier.feedbackTitleLongPress(inspector, feedbackTrigger)
                    } else {
                        Modifier
                    },
                ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                inspector,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                style = TextStyle(shadow = textShadow()),
            )
            Text(
                "查验员 · $employeeId",
                color = Color.White.copy(alpha = 0.88f),
                fontSize = 13.sp,
                style = TextStyle(shadow = textShadow()),
            )
        }
        WatchersPill(count = watchers, onClick = onShowWatchers)
    }
}

@Composable
private fun WatchersPill(count: Int, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.56f))
            .clickable(onClick = onClick)
            .padding(start = 11.dp, end = 7.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("观看 $count", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = "查看观众",
            tint = Color.White.copy(alpha = 0.78f),
            modifier = Modifier.size(16.dp),
        )
    }
}

// ============================================================================
// 右侧互动按钮纵列
// ============================================================================
@Composable
private fun SideActions(
    inspectorInitial: String,
    onOpenInspector: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color(0xFF2DD4BF))
                .clickable(onClick = onOpenInspector),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                inspectorInitial,
                color = Color.Black,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        SideActionButton(icon = Icons.Filled.Mic, label = "语音")
        SideActionButton(icon = Icons.Filled.CameraAlt, label = "截图")
        SideActionButton(icon = Icons.Filled.Videocam, label = "通话", tone = SideTone.Accent)
    }
}

private enum class SideTone { Default, Accent }

@Composable
private fun SideActionButton(
    icon: ImageVector,
    label: String,
    tone: SideTone = SideTone.Default,
) {
    val bg = when (tone) {
        SideTone.Default -> Color.Black.copy(alpha = 0.52f)
        SideTone.Accent -> Color(0xFF2DD4BF).copy(alpha = 0.92f)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(bg)
                .clickable {},
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(22.dp),
            )
        }
        Text(
            label,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            style = TextStyle(shadow = textShadow()),
        )
    }
}

// ============================================================================
// 底部抖音直播评论输入栏
// ============================================================================
@Composable
private fun CommentBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onRecordVoice: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 输入 pill
        Row(
            Modifier
                .weight(1f)
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Black.copy(alpha = if (enabled) 0.62f else 0.40f))
                // R3: 后端未就绪时禁用真实输入,点击直接提示未实现,不静默吞输入
                .then(
                    if (enabled) Modifier
                    else Modifier.clickable(onClick = onSend),
                )
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (enabled) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 14.sp,
                        ),
                        cursorBrush = SolidColor(Color(0xFF2DD4BF)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(
                            onSend = { if (value.isNotBlank()) onSend() },
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (value.isEmpty()) {
                        Text(
                            "说点什么...",
                            color = Color.White.copy(alpha = 0.68f),
                            fontSize = 14.sp,
                        )
                    }
                } else {
                    Text(
                        "评论功能暂未开放",
                        color = Color.White.copy(alpha = 0.50f),
                        fontSize = 14.sp,
                    )
                }
            }
            if (enabled && value.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2DD4BF))
                        .clickable(onClick = onSend),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Send,
                        contentDescription = "发送",
                        tint = Color.Black,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
        }
        // 语音按钮（按住录音 / 点击切换）
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = if (enabled) 0.62f else 0.40f))
                .clickable(onClick = onRecordVoice),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = "按住说话",
                tint = Color.White.copy(alpha = if (enabled) 1f else 0.50f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// ============================================================================
// 观众列表 sheet (mock)
// ============================================================================
@Composable
private fun WatchersSheetContent(total: Int) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "当前观众 · $total 人",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
        WATCHERS_MOCK.take(total.coerceAtLeast(0)).forEach { w ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(w.color),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        w.name.take(1),
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(w.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(w.role, color = Color.White.copy(alpha = 0.72f), fontSize = 12.sp)
                }
                if (w.muted) {
                    Text("已静默", color = Color.White.copy(alpha = 0.62f), fontSize = 12.sp)
                }
            }
        }
    }
}

private data class WatcherUi(val name: String, val role: String, val color: Color, val muted: Boolean = false)

private val WATCHERS_MOCK = listOf(
    WatcherUi("沈海明", "西湖区检测站 · 监管员", Color(0xFFEF4444)),
    WatcherUi("林知远", "外部专家 · 三维外廓", Color(0xFF22C55E)),
    WatcherUi("陈若愚", "外部专家 · VIN 拓印", Color(0xFFF59E0B)),
    WatcherUi("周一苇", "外部专家 · 设备链路", Color(0xFF2DD4BF)),
    WatcherUi("许明庭", "监管会审专家", Color(0xFFEC4899)),
    WatcherUi("省所复核", "省所 · 督导", Color(0xFF6366F1)),
    WatcherUi("江庆宇", "本站 · 查验员", Color(0xFFA855F7), muted = true),
    WatcherUi("吴风", "本站 · 复核员", Color(0xFF14B8A6)),
    WatcherUi("周科", "本站 · 复核员", Color(0xFFFB923C)),
    WatcherUi("刘冶", "本站 · 查验员", Color(0xFF94A3B8)),
)

// ============================================================================
// 空视频提示
// ============================================================================
@Composable
private fun EmptyVideoStage(state: ViewerLiveState, fallbackTaskId: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF050810)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val main = when (state) {
                is ViewerLiveState.Ready -> "等待发布者推视频"
                ViewerLiveState.Joining -> "正在接入媒体房间"
                is ViewerLiveState.Error -> "媒体房间不可用"
                ViewerLiveState.Idle -> "等待直播信息"
            }
            val sub = when (state) {
                is ViewerLiveState.Ready -> state.providerRoom
                is ViewerLiveState.Error -> state.message
                else -> fallbackTaskId
            }
            Text(main, color = Color.White.copy(alpha = 0.9f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Color.White.copy(alpha = 0.68f), fontSize = 13.sp)
        }
    }
}

// ============================================================================
// ViewModel — 接 LiveKit subscriber，订到远端 camera track
// ============================================================================
@HiltViewModel
class FirstPersonViewerViewModel @Inject constructor(
    private val mediaSessionRepository: MediaSessionRepository,
    private val app: Application,
) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<ViewerLiveState>(ViewerLiveState.Idle)
    val state: StateFlow<ViewerLiveState> = _state.asStateFlow()

    private val _remoteTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteTrack: StateFlow<VideoTrack?> = _remoteTrack.asStateFlow()

    private val _room = MutableStateFlow<Room?>(null)
    val room: StateFlow<Room?> = _room.asStateFlow()

    private var bound: Boolean = false

    fun join(streamId: String) {
        if (bound) return
        bound = true
        val liveSessionId = streamId.toLongOrNull()
        if (liveSessionId == null) {
            _state.value = ViewerLiveState.Error("直播会话参数无效")
            return
        }
        viewModelScope.launch {
            _state.value = ViewerLiveState.Joining
            val joined = runCatching { mediaSessionRepository.joinLiveSession(liveSessionId) }
                .getOrElse {
                    _state.value = ViewerLiveState.Error(
                        it.message?.takeIf { m -> m.isNotBlank() } ?: "接入直播失败",
                    )
                    return@launch
                }
            val r = LiveKit.create(
                appContext = app.applicationContext,
                overrides = LiveKitOverrides(),
            )
            _room.value = r
            launchEventCollector(r)
            runCatching {
                r.connect(
                    url = joined.url,
                    token = joined.token,
                    options = emulatorFriendlyConnectOptions(),
                )
                _state.value = ViewerLiveState.Ready(
                    title = joined.session.title,
                    providerRoom = joined.providerRoom,
                    url = joined.url,
                    publisherId = joined.session.publisherId.toString(),
                )
                refreshRemoteTrack()
            }.onFailure {
                _state.value = ViewerLiveState.Error(
                    it.message?.takeIf { m -> m.isNotBlank() } ?: "LiveKit 接入失败",
                )
            }
        }
    }

    fun leave() {
        viewModelScope.launch {
            val r = _room.value
            runCatching { r?.disconnect() }
            r?.release()
            _room.value = null
            _remoteTrack.value = null
        }
    }

    private fun launchEventCollector(r: Room) {
        viewModelScope.launch {
            r.events.collect { event ->
                when (event) {
                    is RoomEvent.TrackSubscribed,
                    is RoomEvent.TrackUnsubscribed,
                    is RoomEvent.ParticipantConnected,
                    is RoomEvent.ParticipantDisconnected,
                    is RoomEvent.TrackPublished,
                    is RoomEvent.TrackUnpublished -> refreshRemoteTrack()
                    is RoomEvent.Disconnected -> _remoteTrack.value = null
                    else -> Unit
                }
            }
        }
    }

    private fun refreshRemoteTrack() {
        val r = _room.value ?: return
        val track = r.remoteParticipants.values
            .asSequence()
            .mapNotNull { p ->
                p.getTrackPublication(Track.Source.CAMERA)?.track as? VideoTrack
            }
            .firstOrNull()
        _remoteTrack.value = track
    }

    override fun onCleared() {
        super.onCleared()
        _room.value?.release()
        _room.value = null
    }
}

sealed interface ViewerLiveState {
    data object Idle : ViewerLiveState
    data object Joining : ViewerLiveState
    data class Ready(
        val title: String,
        val providerRoom: String,
        val url: String,
        val publisherId: String,
    ) : ViewerLiveState
    data class Error(val message: String) : ViewerLiveState
}

private fun emulatorFriendlyConnectOptions(): ConnectOptions {
    val rtc = PeerConnection.RTCConfiguration(emptyList()).apply {
        tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
        iceTransportsType = PeerConnection.IceTransportsType.ALL
        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
    }
    return ConnectOptions(rtcConfig = rtc)
}

// ============================================================================
// 小件
// ============================================================================
private fun textShadow() = Shadow(
    color = Color.Black.copy(alpha = 0.82f),
    blurRadius = 2f,
)

// ============================================================================
// 装饰 mock 数据（待 ws 推真实 publisher metadata 时替换）
// ============================================================================
internal data class StreamDetail(
    val id: String,
    val inspector: String,
    val employeeId: String,
    val station: String,
    val taskId: String,
    val watchers: Int,
)

// TODO(demo-data R1): 以下整张表是占位假数据,未接 ws 推送的真实 publisher metadata,
// 终态由直播 join 后服务端下发的查验员身份替换(见上方 FirstPersonViewerRoute 的 R1 标注)。
private val STREAM_DETAILS = mapOf(
    "L1" to StreamDetail(
        id = "L1",
        inspector = "刘沿", employeeId = "ZAA0120230102",
        station = "杭州市西湖区车管所检测站",
        taskId = "LSVHM133022221761", watchers = 8,
    ),
    "L2" to StreamDetail(
        id = "L2",
        inspector = "陈工", employeeId = "ZAA0120230087",
        station = "杭州市余杭区检测站",
        taskId = "WJN1133022221761", watchers = 4,
    ),
    "L3" to StreamDetail(
        id = "L3",
        inspector = "周文俊", employeeId = "ZAA0120230054",
        station = "杭州市拱墅区检测站",
        taskId = "THGCM6263312345", watchers = 12,
    ),
    "L4" to StreamDetail(
        id = "L4",
        inspector = "吴敏", employeeId = "ZAA0120230033",
        station = "杭州市滨江区检测站",
        taskId = "LSVHM411821234", watchers = 2,
    ),
)
