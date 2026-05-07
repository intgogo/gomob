package io.gomob.feature.scan3d

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.SettingRow
import io.gomob.designsystem.component.SettingRowDivider
import io.gomob.designsystem.theme.Gomob
import io.gomob.nativebridge.berxel.BerxelDeviceState
import io.gomob.nativebridge.berxel.BerxelFrameStat

const val DEPTH_CAMERA_ROUTE = "scan3d/depth-camera"

/**
 * 深度相机详情子页 v2 — 大画面竖排预览 + 导航列表。
 *
 * 设计:
 *  - COLOR / DEPTH 各占满宽度独立成行（横排会让画面太小）
 *  - 详细信息 / 控制 / 标定 都收成 SettingRow → 三级页，避免主页过载
 */
@Composable
fun DepthCameraRoute(
    onBack: () -> Unit,
    onOpenInfo: () -> Unit = {},
    onOpenControls: () -> Unit = {},
    onOpenCalibration: () -> Unit = {},
    vm: DepthCameraViewModel = hiltViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val colorBmp by vm.colorPreview.collectAsStateWithLifecycle()
    val depthBmp by vm.depthPreview.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(title = "深度相机", eyebrow = "Berxel iHawk · 详情与控制", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = Gomob.spacing.s12,
                bottom = Gomob.spacing.s28,
            ),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            item { LargePreview(label = "COLOR", bitmap = colorBmp) }
            item { LargePreview(label = "DEPTH", bitmap = depthBmp) }
            item { LiveStatusStrip(ui = ui) }
            item { Spacer(Modifier.height(Gomob.spacing.s4)) }
            item {
                SectionList {
                    // 「开始三维外廓扫描」入口已上移到 3D 主页 ActionTile 01；本页只关心设备本身
                    NavRow(title = "设备详情", subtitle = "序列号 / 流模式 / 内参 / 帧统计", onClick = onOpenInfo)
                    SettingRowDivider()
                    NavRow(title = "成像控制", subtitle = "Color / Depth 曝光 · 去噪 · 配准", onClick = onOpenControls)
                    SettingRowDivider()
                    NavRow(title = "Color ↔ Depth 标定", subtitle = "外参微调 / Charuco 标定向导", onClick = onOpenCalibration)
                }
            }
        }
    }
}

// ─── 大画面单流预览（占满宽度） ──────────────────────────────────────────────
@Composable
private fun LargePreview(label: String, bitmap: Bitmap?) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s16)
            .aspectRatio(16f / 10f)  // 640×400 = 1.6 ≈ 16:10
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg2)
            .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r3),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "$label preview",
                contentScale = ContentScale.Fit,  // 不裁切，保留全画面
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .padding(Gomob.spacing.s8)
                .clip(Gomob.shapes.r1)
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = Gomob.spacing.s8, vertical = 2.dp),
        ) {
            Text(
                label,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.1.em,
                color = Color.White,
            )
        }
    }
}

// ─── 实时状态条（fps + frame 序号 + 状态点） ─────────────────────────────────
@Composable
private fun LiveStatusStrip(ui: DepthCameraUiState) {
    val streaming = ui.device is BerxelDeviceState.Streaming
    val color = ui.color
    val depth = ui.depth
    val fpsText = (depth?.measuredFps ?: color?.measuredFps)?.let { "$it fps" } ?: "—"
    val frameText = (depth?.frameIndex ?: color?.frameIndex)?.let { "frame#$it" } ?: "等待首帧"
    val syncText = if (color != null && depth != null && color.timestampUs == depth.timestampUs) {
        "RGBD 同步 ✓"
    } else {
        "未配对"
    }
    val tone = if (streaming) Gomob.colors.accent else Gomob.colors.fg3
    Box(Modifier.padding(horizontal = Gomob.spacing.s16)) {
        HairlineCard(padding = 0.dp) {
            Box(Modifier.fillMaxWidth().padding(Gomob.spacing.s12)) {
                Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4)) {
                    androidx.compose.foundation.layout.Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(fpsText, style = Gomob.type.numInline.copy(fontSize = 16.sp), color = tone)
                        Text(frameText, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = Gomob.colors.fg2)
                    }
                    Text(syncText, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = Gomob.colors.fg3)
                }
            }
        }
    }
}

// ─── 导航列表组件（导出给三级页复用） ─────────────────────────────────────────
@Composable
internal fun SectionList(content: @Composable () -> Unit) {
    Box(Modifier.padding(horizontal = Gomob.spacing.s16)) {
        HairlineCard(padding = 0.dp) {
            Column { content() }
        }
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, onClick: () -> Unit) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = onClick,
        trailing = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Gomob.colors.fg3,
            )
        },
    )
}

// ─── 工具：BerxelFrameStat 短文本（三级页复用） ──────────────────────────────
internal fun BerxelFrameStat?.shortLine(): String =
    if (this == null) "等待首帧"
    else "frame#$frameIndex · $measuredFps fps · t=${timestampUs}μs · ${width}×${height}"
