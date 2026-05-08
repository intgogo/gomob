package io.gomob.feature.scan3d

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
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
 * 三维外廓扫描录制页 — 自包含全套：SDK lifecycle + 实时点云累积预览 + 录制状态机。
 *
 * 不显示原始 Color/Depth 摄像机画面（设计上扫描页只关心扫描进度，原始预览在深度相机详情页）。
 *
 * 布局：
 *   - 顶栏 BackHeader
 *   - 大画面：累积点云 top-view 投影（开始后实时刷新；未开始 / 完成 / 出错时显示对应文案）
 *   - 状态/CTA 卡：随 ScanRecordingState 切（Idle 引导 + 开始按钮 / Recording 帧计数 + 停止按钮 /
 *     Finalizing 转圈 / Completed 统计 + 再扫 / Error 重试）
 */
@Composable
fun Scan3dRecordingRoute(
    onBack: () -> Unit,
    vm: Scan3dRecordingViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val device by vm.deviceState.collectAsStateWithLifecycle()
    val cloud by vm.pointCloudPreview.collectAsStateWithLifecycle()
    val mesh by vm.meshPreview.collectAsStateWithLifecycle()
    val colorBmp by vm.colorPreview.collectAsStateWithLifecycle()
    val depthBmp by vm.depthPreview.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = "三维外廓扫描",
            eyebrow = "TSDF 体素积分 · ICP 增量配准",
            onBack = onBack,
        )
        // 整页布局，不滚动：RGB|Depth 横排（固定 4:3）+ 点云 weight=1 占剩余 + 状态卡（包内容自适应）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = Gomob.spacing.s8,
                    bottom = Gomob.spacing.s8,
                ),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            LiveStreamRow(colorBmp = colorBmp, depthBmp = depthBmp)
            PointCloudPreview(
                points = cloud,
                mesh = mesh,
                state = state,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            when (val s = state) {
                is ScanRecordingState.Idle ->
                    IdleStatePanel(deviceReady = device is BerxelDeviceState.Streaming, deviceText = deviceShortText(device), onStart = vm::start)
                is ScanRecordingState.Recording -> RecordingStatePanel(s, cloud.size / 3, onStop = vm::stop)
                is ScanRecordingState.Finalizing -> FinalizingStatePanel(s)
                is ScanRecordingState.Completed -> CompletedStatePanel(s, onAgain = vm::reset)
                is ScanRecordingState.Error -> ErrorStatePanel(s, onRetry = vm::reset)
            }
        }
    }
}

// ─── RGB / Depth 横排实时小窗 ────────────────────────────────────────────────

@Composable
private fun LiveStreamRow(colorBmp: Bitmap?, depthBmp: Bitmap?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Gomob.spacing.s12),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        // 16:10 而非 4:3 — 减矮一点给点云预览更多空间
        StreamCell(label = "RGB",   bitmap = colorBmp, modifier = Modifier.weight(1f))
        StreamCell(label = "DEPTH", bitmap = depthBmp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StreamCell(label: String, bitmap: Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(16f / 10f)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg2)
            .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r2),
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
                Text("等待 $label", style = Gomob.type.caption, color = Gomob.colors.fg3)
            }
        }
        Text(
            label,
            style = Gomob.type.eyebrow,
            color = Gomob.colors.fg2,
            modifier = Modifier
                .padding(Gomob.spacing.s6)
                .clip(Gomob.shapes.r1)
                .background(Gomob.colors.bg0.copy(alpha = 0.7f))
                .padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

// ─── 累积点云预览（top-view 2D 投影） ────────────────────────────────────────

@Composable
private fun PointCloudPreview(
    points: FloatArray,
    mesh: ScanMeshData?,
    state: ScanRecordingState,
    modifier: Modifier = Modifier,
    centerZmm: Float = 750f,  // 与 Scan3dRecordingViewModel.scanSessionCreate gridCenterZMm 对齐 (grid z[0,1500]mm)
) {
    Box(modifier.padding(horizontal = Gomob.spacing.s12)) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg2)
                .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3),
        ) {
            // Filament 3D 预览（手势 orbit/zoom）：
            //   - Recording 全程挂载（即使点云空也保持挂载）→ 避免 peek 偶发返空时
            //     SurfaceView destroy + Filament Engine 重建造成的"画面闪烁"
            //   - Completed 且 mesh 非空 → 显示 mesh 实体面（lit + 方向光）
            //   - 其它状态（Idle / Finalizing / Error / Completed-empty）不挂载，
            //     DisposableEffect 自动 destroy 释放 GPU。
            val showAsPoints = state is ScanRecordingState.Recording
            val showAsMesh = state is ScanRecordingState.Completed && mesh != null
            val showFilament = showAsPoints || showAsMesh
            if (showFilament) {
                PointCloud3dView(
                    points = if (showAsMesh) FloatArray(0) else points,
                    mesh = if (showAsMesh) mesh else null,
                    modifier = Modifier.fillMaxSize(),
                    gridCenterZmm = centerZmm,
                )
            }
            // 角标
            Text(
                text = when (state) {
                    is ScanRecordingState.Recording -> "3D · ${points.size / 3} 点 · 拖动旋转"
                    is ScanRecordingState.Completed -> if (mesh != null) {
                        "Mesh · ${mesh.vertices.size / 3} 顶点 · ${mesh.indices.size / 3} 面 · 拖动旋转"
                    } else "扫描完成"
                    is ScanRecordingState.Finalizing -> "提取 mesh 中"
                    is ScanRecordingState.Error -> "错误"
                    is ScanRecordingState.Idle -> "3D · 等待开始"
                },
                style = Gomob.type.eyebrow,
                color = Gomob.colors.fg2,
                modifier = Modifier
                    .padding(Gomob.spacing.s8)
                    .clip(Gomob.shapes.r1)
                    .background(Gomob.colors.bg0.copy(alpha = 0.7f))
                    .padding(horizontal = Gomob.spacing.s6, vertical = 2.dp),
            )
            // 中心引导文案（点云 / mesh 为空时）
            if (!showFilament) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = when (state) {
                            is ScanRecordingState.Idle -> "按下开始按钮，绕物体转一圈"
                            is ScanRecordingState.Recording -> "正在采集首帧深度…"
                            is ScanRecordingState.Finalizing -> ""
                            is ScanRecordingState.Completed -> "mesh 为空（扫描没积分到任何表面）"
                            is ScanRecordingState.Error -> ""
                        },
                        style = Gomob.type.bodySm,
                        color = Gomob.colors.fg3,
                    )
                }
            }
        }
    }
}

// ─── 状态面板 ────────────────────────────────────────────────────────────────

private fun deviceShortText(state: BerxelDeviceState): String = when (state) {
    is BerxelDeviceState.Streaming -> "iHawk 在线"
    is BerxelDeviceState.Initializing -> "SDK 初始化中..."
    is BerxelDeviceState.Opening -> "打开 iHawk..."
    is BerxelDeviceState.WaitingPermission -> "等待 USB 权限"
    is BerxelDeviceState.NoDevice -> "未检测到 iHawk — 请插 USB-C OTG"
    is BerxelDeviceState.Error -> "SDK 错误：${state.reason}"
    BerxelDeviceState.Idle -> "等待设备..."
}

@Composable
private fun IdleStatePanel(deviceReady: Boolean, deviceText: String, onStart: () -> Unit) {
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Gomob.spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
                Text("准备开始扫描", style = Gomob.type.bodySm, color = Gomob.colors.fg0)
                Text(
                    "对准物体 → 转一圈 · 25–80 cm",
                    style = Gomob.type.caption,
                    color = Gomob.colors.fg2,
                )
                Text(
                    deviceText,
                    style = Gomob.type.caption,
                    color = if (deviceReady) Gomob.colors.accent else Gomob.colors.danger,
                )
            }
            BigCircleButton(
                label = "开始",
                color = if (deviceReady) Gomob.colors.accent else Gomob.colors.fg3,
                enabled = deviceReady,
                onClick = onStart,
            )
        }
    }
}

@Composable
private fun RecordingStatePanel(s: ScanRecordingState.Recording, previewPoints: Int, onStop: () -> Unit) {
    PanelCard {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
        ) {
            // 首帧提示：native ingest 第一次调用要分配 64MB TSDF + page fault 冷启，几百 ms~秒级
            // 这期间帧/KF 仍是 0，加这行兜底告诉用户"在等首帧而非卡死"
            if (s.awaitingFirstFrame) {
                Text(
                    "等待首帧深度…(TSDF 体素冷启)",
                    style = Gomob.type.caption,
                    color = Gomob.colors.fg3,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = Gomob.spacing.s4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
            ) {
                Row(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
                ) {
                    StatColumn(label = "帧",   value = s.framesIngested.toString())
                    StatColumn(label = "KF",   value = s.keyframes.toString())
                    StatColumn(label = "点",   value = previewPoints.toString())
                    StatColumn(label = "时长", value = formatElapsed(s.elapsedMs))
                }
                BigCircleButton(label = "停止", color = Gomob.colors.danger, onClick = onStop)
            }
        }
    }
}

@Composable
private fun FinalizingStatePanel(s: ScanRecordingState.Finalizing) {
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Gomob.spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = Gomob.colors.accent,
                strokeWidth = 3.dp,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
                Text("正在生成 Mesh", style = Gomob.type.bodySm, color = Gomob.colors.fg0)
                Text(
                    "Marching Tetrahedra · ${s.framesIngested} 帧已积分",
                    style = Gomob.type.caption,
                    color = Gomob.colors.fg2,
                )
            }
        }
    }
}

@Composable
private fun CompletedStatePanel(s: ScanRecordingState.Completed, onAgain: () -> Unit) {
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Gomob.spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
                Text("✓ 扫描完成", style = Gomob.type.bodySm, color = Gomob.colors.accent)
                Text(
                    "顶点 ${s.vertexCount} · 三角形 ${s.triangleCount} · 关键帧 ${s.keyframes} · ${formatElapsed(s.durationMs)}",
                    style = Gomob.type.caption,
                    color = Gomob.colors.fg2,
                )
                Text(
                    s.outDir,
                    style = Gomob.type.caption,
                    color = Gomob.colors.fg3,
                    fontFamily = FontFamily.Monospace,
                )
            }
            BigCircleButton(label = "再扫", color = Gomob.colors.accent, onClick = onAgain)
        }
    }
}

@Composable
private fun ErrorStatePanel(s: ScanRecordingState.Error, onRetry: () -> Unit) {
    PanelCard {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = Gomob.spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
                Text("× 扫描失败", style = Gomob.type.bodySm, color = Gomob.colors.danger)
                Text(s.msg, style = Gomob.type.caption, color = Gomob.colors.fg2)
            }
            BigCircleButton(label = "重试", color = Gomob.colors.accent, onClick = onRetry)
        }
    }
}

// ─── 工具组件 ──────────────────────────────────────────────────────────────

@Composable
private fun PanelCard(content: @Composable () -> Unit) {
    Box(Modifier.padding(horizontal = Gomob.spacing.s12)) {
        HairlineCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
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
            .size(64.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = if (enabled) 0.18f else 0.10f))
            .border(Gomob.spacing.hairline * 2, color, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = Gomob.type.caption, color = Gomob.colors.bg0)
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000L
    val mm = totalSec / 60L
    val ss = totalSec % 60L
    return "%02d:%02d".format(mm, ss)
}
