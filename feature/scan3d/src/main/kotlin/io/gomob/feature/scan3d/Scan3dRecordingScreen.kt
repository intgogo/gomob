package io.gomob.feature.scan3d

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.theme.Gomob

const val SCAN3D_RECORDING_ROUTE = "scan3d/recording"

/**
 * 三维外廓扫描录制页 — DepthCameraScreen 上的 CTA 跳进来。
 *
 * 状态切换：
 *   Idle      → 引导文案 + 大圆 "开始扫描"
 *   Recording → 实时帧计数 + 关键帧 + 计时；底部红色 "停止扫描" 按钮
 *   Finalizing→ 进度圈 + "正在生成 mesh"
 *   Completed → 统计卡 + 路径 + "再扫一次" 按钮
 *   Error     → 错误信息 + "重试" 按钮
 *
 * 注意：本页不启停 SDK — 假定上一级 [DepthCameraRoute] 已 start()，所以进来就有 depthFrames 流。
 */
@Composable
fun Scan3dRecordingRoute(
    onBack: () -> Unit,
    vm: Scan3dRecordingViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = "三维外廓扫描",
            eyebrow = "TSDF 体素积分 · ICP 增量配准",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = Gomob.spacing.s16,
                vertical = Gomob.spacing.s16,
            ),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s16),
        ) {
            when (val s = state) {
                is ScanRecordingState.Idle -> item { IdlePanel(onStart = vm::start) }
                is ScanRecordingState.Recording -> item { RecordingPanel(s, onStop = vm::stop) }
                is ScanRecordingState.Finalizing -> item { FinalizingPanel(s) }
                is ScanRecordingState.Completed -> item { CompletedPanel(s, onAgain = vm::reset) }
                is ScanRecordingState.Error -> item { ErrorPanel(s, onRetry = vm::reset) }
            }
        }
    }
}

@Composable
private fun IdlePanel(onStart: () -> Unit) {
    HairlineCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(Gomob.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("准备开始扫描", style = Gomob.type.title, color = Gomob.colors.fg0)
            Text(
                "把 iHawk 对准物体，按下按钮，缓慢绕物体转一圈。\n保持镜头距物体 25–80 cm；\n相邻帧旋转角度建议 ≤ 10°。",
                style = Gomob.type.bodySm,
                color = Gomob.colors.fg2,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Gomob.spacing.s12))
            BigCircleButton(label = "开始", color = Gomob.colors.accent, onClick = onStart)
        }
    }
}

@Composable
private fun RecordingPanel(s: ScanRecordingState.Recording, onStop: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12)) {
        HairlineCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(Gomob.spacing.s16),
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                Text("正在录制", style = Gomob.type.eyebrow, color = Gomob.colors.accent)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatColumn(label = "帧数",    value = s.framesIngested.toString())
                    StatColumn(label = "关键帧",  value = s.keyframes.toString())
                    StatColumn(label = "时长",    value = formatElapsed(s.elapsedMs))
                }
            }
        }
        HairlineCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(Gomob.spacing.s16),
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "保持镜头匀速旋转，避免抖动",
                    style = Gomob.type.bodySm,
                    color = Gomob.colors.fg2,
                )
                BigCircleButton(label = "停止", color = Gomob.colors.danger, onClick = onStop)
            }
        }
    }
}

@Composable
private fun FinalizingPanel(s: ScanRecordingState.Finalizing) {
    HairlineCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(Gomob.spacing.s24),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(48.dp),
                color = Gomob.colors.accent,
            )
            Text("正在生成 Mesh", style = Gomob.type.title, color = Gomob.colors.fg0)
            Text(
                "Marching Tetrahedra 提取 + 文件落盘\n${s.framesIngested} 帧已积分到 TSDF",
                style = Gomob.type.bodySm,
                color = Gomob.colors.fg2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun CompletedPanel(s: ScanRecordingState.Completed, onAgain: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12)) {
        HairlineCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(Gomob.spacing.s16),
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                Text("✓ 扫描完成", style = Gomob.type.title, color = Gomob.colors.accent)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatColumn(label = "顶点",    value = s.vertexCount.toString())
                    StatColumn(label = "三角形",  value = s.triangleCount.toString())
                    StatColumn(label = "关键帧",  value = s.keyframes.toString())
                    StatColumn(label = "时长",    value = formatElapsed(s.durationMs))
                }
                Text(
                    "session: ${s.sessionId}\n→ ${s.outDir}",
                    style = Gomob.type.caption,
                    color = Gomob.colors.fg3,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
        HairlineCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(Gomob.spacing.s16),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BigCircleButton(label = "再扫", color = Gomob.colors.accent, onClick = onAgain)
            }
        }
    }
}

@Composable
private fun ErrorPanel(s: ScanRecordingState.Error, onRetry: () -> Unit) {
    HairlineCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(Gomob.spacing.s16),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("× 扫描失败", style = Gomob.type.title, color = Gomob.colors.danger)
            Text(
                s.msg,
                style = Gomob.type.bodySm,
                color = Gomob.colors.fg2,
                textAlign = TextAlign.Center,
            )
            BigCircleButton(label = "重试", color = Gomob.colors.accent, onClick = onRetry)
        }
    }
}

@Composable
private fun StatColumn(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
        Text(label, style = Gomob.type.eyebrow, color = Gomob.colors.fg3)
        Text(value, style = Gomob.type.metricMd, color = Gomob.colors.fg0,
             fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun BigCircleButton(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(Gomob.spacing.btnCircle72)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.18f))
            .border(Gomob.spacing.hairline * 2, color, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(Gomob.spacing.avatar48)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = Gomob.type.bodySm, color = Gomob.colors.bg0)
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000L
    val mm = totalSec / 60L
    val ss = totalSec % 60L
    return "%02d:%02d".format(mm, ss)
}
