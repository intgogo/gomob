package io.gomob.feature.scan3d

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.theme.Gomob
import io.gomob.nativebridge.berxel.BerxelDeviceState

const val SCAN3D_RECORDING_ROUTE = "scan3d/recording"

/**
 * 三维外廓扫描录制页 — 自包含全套：SDK lifecycle + Color/Depth 实时预览 + 录制状态机。
 *
 * 布局：
 *   - 顶栏 BackHeader + SDK 设备状态 tag
 *   - Color 预览（大）
 *   - Depth 预览（小、紧贴 Color 下）
 *   - 状态卡：随 ScanRecordingState 切（Idle 引导 / Recording 帧计数 / Finalizing 转圈
 *     / Completed 统计 / Error 文案）
 *   - 底部：开始 / 停止 / 再扫 / 重试 圆按钮
 */
@Composable
fun Scan3dRecordingRoute(
    onBack: () -> Unit,
    vm: Scan3dRecordingViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val device by vm.deviceState.collectAsStateWithLifecycle()
    val colorBmp by vm.colorPreview.collectAsStateWithLifecycle()
    val depthBmp by vm.depthPreview.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = "三维外廓扫描",
            eyebrow = "TSDF 体素积分 · ICP 增量配准",
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = Gomob.spacing.s12,
                bottom = Gomob.spacing.s28,
            ),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            item { DeviceStatusStrip(device) }
            item { PreviewCard(label = "COLOR", bitmap = colorBmp) }
            item { PreviewCard(label = "DEPTH", bitmap = depthBmp, shorter = true) }
            item { Spacer(Modifier.height(Gomob.spacing.s4)) }
            when (val s = state) {
                is ScanRecordingState.Idle ->
                    item { IdleStatePanel(deviceReady = device is BerxelDeviceState.Streaming, onStart = vm::start) }
                is ScanRecordingState.Recording -> item { RecordingStatePanel(s, onStop = vm::stop) }
                is ScanRecordingState.Finalizing -> item { FinalizingStatePanel(s) }
                is ScanRecordingState.Completed -> item { CompletedStatePanel(s, onAgain = vm::reset) }
                is ScanRecordingState.Error -> item { ErrorStatePanel(s, onRetry = vm::reset) }
            }
        }
    }
}

// ─── 设备状态条 ─────────────────────────────────────────────────────────────

@Composable
private fun DeviceStatusStrip(state: BerxelDeviceState) {
    val (text, tone) = when (state) {
        is BerxelDeviceState.Streaming -> {
            val color = state.info.colorMode?.let { "${it.width}×${it.height}@${it.fps}" } ?: "?"
            val depth = state.info.depthMode?.let { "${it.width}×${it.height}@${it.fps}" } ?: "?"
            "iHawk 在线 · Color $color · Depth $depth" to Gomob.colors.accent
        }
        is BerxelDeviceState.Initializing -> "正在初始化 SDK..." to Gomob.colors.fg2
        is BerxelDeviceState.Opening -> "打开 iHawk 中..." to Gomob.colors.fg2
        is BerxelDeviceState.WaitingPermission -> "等待 USB 权限授权..." to Gomob.colors.accentStrong
        is BerxelDeviceState.NoDevice -> "未检测到 iHawk — 请插 USB-C OTG" to Gomob.colors.danger
        is BerxelDeviceState.Error -> "SDK 错误：${state.reason}" to Gomob.colors.danger
        BerxelDeviceState.Idle -> "等待设备..." to Gomob.colors.fg2
    }
    Box(Modifier.padding(horizontal = Gomob.spacing.s16)) {
        HairlineCard(modifier = Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(Gomob.spacing.s12),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(tone),
                )
                Text(text, style = Gomob.type.bodySm, color = Gomob.colors.fg1)
            }
        }
    }
}

// ─── 预览卡（COLOR / DEPTH） ────────────────────────────────────────────────

@Composable
private fun PreviewCard(label: String, bitmap: Bitmap?, shorter: Boolean = false) {
    Box(Modifier.padding(horizontal = Gomob.spacing.s16)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (shorter) 16f / 9f else 16f / 10f)
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg2)
                .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.Low,
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("等待 $label 帧", style = Gomob.type.caption, color = Gomob.colors.fg3)
                }
            }
            // 角标
            Text(
                label,
                style = Gomob.type.eyebrow,
                color = Gomob.colors.fg2,
                modifier = Modifier
                    .padding(Gomob.spacing.s8)
                    .clip(Gomob.shapes.r1)
                    .background(Gomob.colors.bg0.copy(alpha = 0.7f))
                    .padding(horizontal = Gomob.spacing.s6, vertical = 2.dp),
            )
        }
    }
}

// ─── 状态面板 ────────────────────────────────────────────────────────────────

@Composable
private fun IdleStatePanel(deviceReady: Boolean, onStart: () -> Unit) {
    PanelCard {
        Text("准备开始扫描", style = Gomob.type.title, color = Gomob.colors.fg0)
        Text(
            "把 iHawk 对准物体，按下按钮，缓慢绕物体转一圈。\n保持镜头距物体 25–80 cm；相邻帧旋转角度 ≤ 10°。",
            style = Gomob.type.bodySm,
            color = Gomob.colors.fg2,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Gomob.spacing.s8))
        BigCircleButton(
            label = "开始",
            color = if (deviceReady) Gomob.colors.accent else Gomob.colors.fg3,
            enabled = deviceReady,
            onClick = onStart,
        )
        if (!deviceReady) {
            Text("设备未就绪 — 等待 SDK 启动", style = Gomob.type.caption, color = Gomob.colors.fg3)
        }
    }
}

@Composable
private fun RecordingStatePanel(s: ScanRecordingState.Recording, onStop: () -> Unit) {
    PanelCard {
        Text("正在录制", style = Gomob.type.eyebrow, color = Gomob.colors.accent)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatColumn(label = "帧数",   value = s.framesIngested.toString())
            StatColumn(label = "关键帧", value = s.keyframes.toString())
            StatColumn(label = "时长",   value = formatElapsed(s.elapsedMs))
        }
        Spacer(Modifier.height(Gomob.spacing.s4))
        Text(
            "保持镜头匀速旋转，避免抖动",
            style = Gomob.type.bodySm,
            color = Gomob.colors.fg2,
        )
        BigCircleButton(label = "停止", color = Gomob.colors.danger, onClick = onStop)
    }
}

@Composable
private fun FinalizingStatePanel(s: ScanRecordingState.Finalizing) {
    PanelCard {
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

@Composable
private fun CompletedStatePanel(s: ScanRecordingState.Completed, onAgain: () -> Unit) {
    PanelCard {
        Text("✓ 扫描完成", style = Gomob.type.title, color = Gomob.colors.accent)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            StatColumn(label = "顶点",   value = s.vertexCount.toString())
            StatColumn(label = "三角形", value = s.triangleCount.toString())
            StatColumn(label = "关键帧", value = s.keyframes.toString())
            StatColumn(label = "时长",   value = formatElapsed(s.durationMs))
        }
        Text(
            "session: ${s.sessionId}\n→ ${s.outDir}",
            style = Gomob.type.caption,
            color = Gomob.colors.fg3,
            fontFamily = FontFamily.Monospace,
        )
        BigCircleButton(label = "再扫", color = Gomob.colors.accent, onClick = onAgain)
    }
}

@Composable
private fun ErrorStatePanel(s: ScanRecordingState.Error, onRetry: () -> Unit) {
    PanelCard {
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

// ─── 工具组件 ──────────────────────────────────────────────────────────────

@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Box(Modifier.padding(horizontal = Gomob.spacing.s16)) {
        HairlineCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(Gomob.spacing.s16),
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content() }
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
private fun BigCircleButton(
    label: String,
    color: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(Gomob.spacing.btnCircle72)
            .clip(CircleShape)
            .background(color.copy(alpha = if (enabled) 0.18f else 0.10f))
            .border(Gomob.spacing.hairline * 2, color, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
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
