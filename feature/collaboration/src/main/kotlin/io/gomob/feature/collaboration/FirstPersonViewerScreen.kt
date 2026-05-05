package io.gomob.feature.collaboration

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob

/**
 * 第一视角观看页 — 监管员/复核员视角实时观看某位查验员的工作画面。
 *
 * 布局:
 *   - 顶 hairline header: ← / LIVE 红点 + 标题 / 观看数 + 信号
 *   - 中部: 全屏 RGB 预览(占位 — 真实接入走 WebRTC 流)
 *     - 左上 overlay: 采集者卡片(姓名 / 检测站 / 当前工单)
 *     - 右下 overlay: 实时指标(扫描部位 / AI 打分 / 异常计数)
 *   - 底部 hairline action bar: 5 按钮(介入语音 / 切视角 / 截图 / 标预警 / 通话)
 */
@Composable
fun FirstPersonViewerRoute(streamId: String, onBack: () -> Unit) {
    val s = STREAM_DETAILS[streamId] ?: STREAM_DETAILS.values.first()

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        // 顶部 hairline header
        Row(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.headerHeight)
                .padding(horizontal = Gomob.spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
            ) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = Gomob.colors.fg0,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Gomob.colors.danger),
                        )
                        Text("LIVE · ${s.duration}", style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
                    }
                    Text("第一视角 · ${s.inspector}", style = Gomob.type.title, color = Gomob.colors.fg0)
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
            ) {
                Text("观看 ${s.watchers}", style = Gomob.type.numInline, color = Gomob.colors.accent)
                SignalBars(level = s.signalLevel)
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.hairline)
                .background(Gomob.colors.line1),
        )

        // 视频区 — weight 占满中部
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Gomob.colors.bg2),
        ) {
            // 占位提示
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
            ) {
                Text(
                    "WebRTC 视频流",
                    style = Gomob.type.metricMd,
                    color = Gomob.colors.fg3,
                )
                Text(
                    s.taskId + " · " + s.vehicleModel,
                    style = Gomob.type.numInline,
                    color = Gomob.colors.fg2,
                )
            }

            // 左上 overlay: 采集者信息
            HairlineCard(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(Gomob.spacing.s12)
                    .width(220.dp),
                padding = Gomob.spacing.s12,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Gomob.colors.accentSoft)
                                .border(Gomob.spacing.hairline, Gomob.colors.accentLine, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                s.inspector.take(1),
                                style = Gomob.type.numInline,
                                color = Gomob.colors.accent,
                            )
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(s.inspector, style = Gomob.type.body, color = Gomob.colors.fg0)
                            Text(s.employeeId, style = Gomob.type.caption, color = Gomob.colors.fg3)
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(Gomob.spacing.hairline)
                            .background(Gomob.colors.line1),
                    )
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
                    .width(200.dp),
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
                    .width(220.dp),
                padding = Gomob.spacing.s12,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
                    ) {
                        Box(
                            Modifier
                                .size(6.dp)
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

        // 底部 hairline + action bar
        Box(
            Modifier
                .fillMaxWidth()
                .height(Gomob.spacing.hairline)
                .background(Gomob.colors.line1),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .background(Gomob.colors.bg0)
                .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s12),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionButton(
                icon = Icons.Filled.Mic,
                label = "介入语音",
                tone = ActionTone.Accent,
            )
            ActionButton(
                icon = Icons.Filled.SwapHoriz,
                label = "切视角",
                tone = ActionTone.Neutral,
            )
            ActionButton(
                icon = Icons.Filled.CameraAlt,
                label = "截图存档",
                tone = ActionTone.Neutral,
            )
            ActionButton(
                icon = Icons.Filled.WarningAmber,
                label = "标记预警",
                tone = ActionTone.Danger,
            )
            ActionButton(
                icon = Icons.Filled.VideoCall,
                label = "视频通话",
                tone = ActionTone.Accent,
            )
        }
    }
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

@Composable
private fun SignalBars(level: Int) {
    Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        for (i in 0 until 4) {
            val active = i < level
            Box(
                Modifier
                    .width(3.dp)
                    .height((4 + i * 3).dp)
                    .background(if (active) Gomob.colors.ok else Gomob.colors.line2),
            )
        }
        Spacer(Modifier.width(4.dp))
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
    val (iconColor, lineColor, fillColor) = when (tone) {
        ActionTone.Accent -> Triple(Gomob.colors.accent, Gomob.colors.accentLine, Gomob.colors.accentSoft)
        ActionTone.Danger -> Triple(Gomob.colors.danger, Gomob.colors.danger.copy(alpha = 0.32f), Gomob.colors.dangerSoft)
        ActionTone.Neutral -> Triple(Gomob.colors.fg1, Gomob.colors.line2, Gomob.colors.bg2)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
        modifier = Modifier.clickable {},
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(fillColor)
                .border(Gomob.spacing.hairline, lineColor, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = iconColor,
                modifier = Modifier.size(20.dp),
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
