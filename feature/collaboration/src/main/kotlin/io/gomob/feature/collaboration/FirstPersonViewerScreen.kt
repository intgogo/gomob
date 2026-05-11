package io.gomob.feature.collaboration

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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.message.MediaSessionRepository
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 第一视角观看页 — 监管员/复核员视角实时观看某位查验员的工作画面。
 *
 * 布局:
 *   - 顶 BackHeader: ← / 标题 / eyebrow=LIVE+时长 / trailing=观看数+信号
 *   - 中部: 全屏 RGB 预览，媒体房间由 LiveKit 控制面签发 token
 *     - 左上 overlay: 采集者卡片(姓名 / 检测站 / 当前工单)
 *     - 右上 overlay: 实时指标(扫描部位 / AI 打分 / 异常计数)
 *     - 左下 overlay: 实时批注
 *   - 底部 hairline action bar: 5 圆按钮(介入语音 / 切视角 / 截图 / 标预警 / 通话)
 */
@Composable
fun FirstPersonViewerRoute(
    streamId: String,
    onBack: () -> Unit,
    viewModel: FirstPersonViewerViewModel = hiltViewModel(),
) {
    val s = STREAM_DETAILS[streamId] ?: STREAM_DETAILS.values.first()
    val mediaState by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(streamId) {
        viewModel.join(streamId)
    }

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = when (val state = mediaState) {
                is ViewerLiveState.Ready -> "第一视角 · ${state.title}"
                else -> "第一视角 · ${s.inspector}"
            },
            onBack = onBack,
            eyebrow = when (mediaState) {
                is ViewerLiveState.Ready -> "LIVE · 媒体房间已接通"
                ViewerLiveState.Joining -> "正在接入 LiveKit"
                is ViewerLiveState.Error -> "媒体房间不可用"
                ViewerLiveState.Idle -> "等待媒体房间"
            },
            trailing = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                ) {
                    StatusTag(
                        text = "观看 ${s.watchers}",
                        tone = StatusTone.Accent,
                        showDot = true,
                    )
                    SignalBars(level = s.signalLevel)
                }
            },
        )

        // 视频区 — weight 占满中部
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Gomob.colors.bg2),
        ) {
            // 真实视频轨道接入前只展示媒体控制面状态，不用假画面冒充远端视频。
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
            ) {
                val state = mediaState
                Text(
                    when (state) {
                        is ViewerLiveState.Ready -> "LiveKit 房间已接通"
                        ViewerLiveState.Joining -> "正在接入媒体房间"
                        is ViewerLiveState.Error -> "媒体房间不可用"
                        ViewerLiveState.Idle -> "等待直播信息"
                    },
                    style = Gomob.type.metricMd,
                    color = Gomob.colors.fg3,
                )
                Text(
                    when (state) {
                        is ViewerLiveState.Ready -> state.providerRoom
                        is ViewerLiveState.Error -> state.message
                        else -> s.taskId + " · " + s.vehicleModel
                    },
                    style = Gomob.type.numInline,
                    color = Gomob.colors.fg2,
                )
            }

            // 左上 overlay: 采集者信息
            HairlineCard(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(Gomob.spacing.s12)
                    .width(Gomob.spacing.overlayCardWMd),
                padding = Gomob.spacing.s12,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                    ) {
                        Box(
                            Modifier
                                .size(Gomob.spacing.avatar28)
                                .clip(CircleShape)
                                .background(Gomob.colors.accentSoft),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                s.inspector.take(1),
                                style = Gomob.type.numInline,
                                color = Gomob.colors.accent,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
                            Text(s.inspector, style = Gomob.type.body, color = Gomob.colors.fg0)
                            Text(s.employeeId, style = Gomob.type.caption, color = Gomob.colors.fg3)
                        }
                    }
                    Spacer(Modifier.height(Gomob.spacing.s4))
                    Text("检测站", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    Text(s.station, style = Gomob.type.bodySm, color = Gomob.colors.fg1)
                    Text("当前工单", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    Text(s.taskId, style = Gomob.type.numInline, color = Gomob.colors.fg0)
                }
            }

            // 右上 overlay: 实时指标 (与左上 inspector 分居左右)
            HairlineCard(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Gomob.spacing.s12)
                    .width(Gomob.spacing.overlayCardWSm),
                padding = Gomob.spacing.s12,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
                    MetricInline(
                        label = "扫描部位",
                        value = s.currentPart,
                        valueColor = Gomob.colors.accent,
                    )
                    MetricInline(
                        label = "AI 打分",
                        value = "%.2f".format(s.aiScore),
                        valueColor = if (s.aiScore < 0.6f) Gomob.colors.danger
                        else if (s.aiScore < 0.85f) Gomob.colors.warn
                        else Gomob.colors.ok,
                    )
                    MetricInline(
                        label = "已检出异常",
                        value = "${s.anomalyCount}",
                        valueColor = if (s.anomalyCount > 0) Gomob.colors.danger else Gomob.colors.fg0,
                    )
                }
            }

            // 左下 overlay: 实时弹幕(简化为 1 条注释)
            HairlineCard(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(Gomob.spacing.s12)
                    .width(Gomob.spacing.overlayCardWMd),
                padding = Gomob.spacing.s12,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
                    ) {
                        Box(
                            Modifier
                                .size(Gomob.spacing.dot6)
                                .clip(CircleShape)
                                .background(Gomob.colors.accent),
                        )
                        Text("最新批注", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    }
                    Text(s.latestNote, style = Gomob.type.bodySm, color = Gomob.colors.fg1)
                    Text(s.noteFrom + " · " + s.noteTime, style = Gomob.type.caption, color = Gomob.colors.fg3)
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(Gomob.colors.bg0)
                .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s12),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButton(icon = Icons.Filled.Mic, label = "介入语音", tone = ActionTone.Accent)
            ActionButton(icon = Icons.Filled.SwapHoriz, label = "切视角", tone = ActionTone.Neutral)
            ActionButton(icon = Icons.Filled.CameraAlt, label = "截图存档", tone = ActionTone.Neutral)
            ActionButton(icon = Icons.Filled.WarningAmber, label = "标记预警", tone = ActionTone.Danger)
            ActionButton(icon = Icons.Filled.VideoCall, label = "视频通话", tone = ActionTone.Accent)
        }
    }
}

@HiltViewModel
class FirstPersonViewerViewModel @Inject constructor(
    private val mediaSessionRepository: MediaSessionRepository,
) : ViewModel() {
    private val _state = MutableStateFlow<ViewerLiveState>(ViewerLiveState.Idle)
    val state: StateFlow<ViewerLiveState> = _state.asStateFlow()

    fun join(streamId: String) {
        val liveSessionId = streamId.toLongOrNull()
        if (liveSessionId == null) {
            _state.value = ViewerLiveState.Error("直播会话参数无效")
            return
        }
        viewModelScope.launch {
            _state.value = ViewerLiveState.Joining
            _state.value = runCatching {
                val joined = mediaSessionRepository.joinLiveSession(liveSessionId)
                ViewerLiveState.Ready(
                    title = joined.session.title,
                    providerRoom = joined.providerRoom,
                    url = joined.url,
                )
            }.getOrElse {
                ViewerLiveState.Error(it.message?.takeIf { msg -> msg.isNotBlank() } ?: "接入直播失败")
            }
        }
    }
}

sealed interface ViewerLiveState {
    data object Idle : ViewerLiveState
    data object Joining : ViewerLiveState
    data class Ready(val title: String, val providerRoom: String, val url: String) : ViewerLiveState
    data class Error(val message: String) : ViewerLiveState
}

@Composable
private fun MetricInline(label: String, value: String, valueColor: Color) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = Gomob.type.caption, color = Gomob.colors.fg3)
        Text(value, style = Gomob.type.numInline, color = valueColor)
    }
}

// 4 阶信号条的几何细节 — 仅本地复用一次, 不上升为设计 token
private val SIGNAL_BAR_W = 3.dp
private val SIGNAL_BAR_H_BASE = 4.dp
private val SIGNAL_BAR_H_STEP = 3.dp
private val SIGNAL_BAR_GAP = 2.dp

@Composable
private fun SignalBars(level: Int) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(SIGNAL_BAR_GAP),
    ) {
        for (i in 0 until 4) {
            val active = i < level
            Box(
                Modifier
                    .width(SIGNAL_BAR_W)
                    .height(SIGNAL_BAR_H_BASE + SIGNAL_BAR_H_STEP * i)
                    .background(if (active) Gomob.colors.ok else Gomob.colors.bg3),
            )
        }
        Spacer(Modifier.width(Gomob.spacing.s4))
        Text(
            text = listOf("差", "弱", "中", "良", "强")[level.coerceIn(0, 4)],
            style = Gomob.type.caption,
            color = Gomob.colors.fg2,
        )
    }
}

private enum class ActionTone { Accent, Neutral, Danger }

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    tone: ActionTone,
) {
    val (iconColor, fillColor) = when (tone) {
        ActionTone.Accent -> Gomob.colors.accent to Gomob.colors.accentSoft
        ActionTone.Danger -> Gomob.colors.danger to Gomob.colors.dangerSoft
        ActionTone.Neutral -> Gomob.colors.fg1 to Gomob.colors.bg2
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
        modifier = Modifier.clickable {},
    ) {
        Box(
            Modifier
                .size(Gomob.spacing.avatar48)
                .clip(CircleShape)
                .background(fillColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconColor,
                modifier = Modifier.size(Gomob.spacing.icon20),
            )
        }
        Text(label, style = Gomob.type.caption, color = Gomob.colors.fg2)
    }
}

// ---- mock 数据 ----
internal data class StreamDetail(
    val id: String,
    val inspector: String,
    val employeeId: String,
    val station: String,
    val duration: String,
    val watchers: Int,
    val signalLevel: Int,        // 0..4
    val taskId: String,
    val vehicleModel: String,
    val currentPart: String,
    val aiScore: Float,
    val anomalyCount: Int,
    val latestNote: String,
    val noteFrom: String,
    val noteTime: String,
)

private val STREAM_DETAILS = mapOf(
    "L1" to StreamDetail(
        id = "L1",
        inspector = "刘沿", employeeId = "ZAA0120230102", station = "杭州市西湖区车管所检测站",
        duration = "12:34", watchers = 8, signalLevel = 4,
        taskId = "LSVHM133022221761", vehicleModel = "上汽大众 · 小型轿车",
        currentPart = "VIN 区域", aiScore = 0.92f, anomalyCount = 0,
        latestNote = "VIN 字符清晰,可继续扫铭牌。", noteFrom = "沈海明", noteTime = "1 分钟前",
    ),
    "L2" to StreamDetail(
        id = "L2",
        inspector = "陈工", employeeId = "ZAA0120230087", station = "杭州市余杭区检测站",
        duration = "08:21", watchers = 4, signalLevel = 3,
        taskId = "WJN1133022221761", vehicleModel = "日产系列 · 中型轿车",
        currentPart = "外观尺寸", aiScore = 0.54f, anomalyCount = 1,
        latestNote = "前保险杠右下方加装件需复核登记。", noteFrom = "沈海明", noteTime = "30 秒前",
    ),
    "L3" to StreamDetail(
        id = "L3",
        inspector = "周文俊", employeeId = "ZAA0120230054", station = "杭州市拱墅区检测站",
        duration = "23:07", watchers = 12, signalLevel = 4,
        taskId = "THGCM6263312345", vehicleModel = "丰田系列 · SUV",
        currentPart = "OBD 接口", aiScore = 0.88f, anomalyCount = 0,
        latestNote = "OBD 数据读取正常,无 DTC。", noteFrom = "刘沿", noteTime = "刚刚",
    ),
    "L4" to StreamDetail(
        id = "L4",
        inspector = "吴敏", employeeId = "ZAA0120230033", station = "杭州市滨江区检测站",
        duration = "01:42", watchers = 2, signalLevel = 2,
        taskId = "LSVHM411821234", vehicleModel = "大众系列 · 紧凑轿车",
        currentPart = "车架号铭牌", aiScore = 0.78f, anomalyCount = 0,
        latestNote = "信号略弱,正在切换 5G 通道。", noteFrom = "系统", noteTime = "刚刚",
    ),
)
