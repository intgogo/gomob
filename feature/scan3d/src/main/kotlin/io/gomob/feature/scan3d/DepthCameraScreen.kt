package io.gomob.feature.scan3d

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import io.gomob.nativebridge.berxel.BerxelStreamSpec

const val DEPTH_CAMERA_ROUTE = "scan3d/depth-camera"

/**
 * 深度相机详情子页。
 *
 * 内容（参考 Windows VinRectifyGui MainWindow.cpp 控件分组）：
 *  - LivePreviewRow：Color + Depth 双窗口实时预览
 *  - 设备信息（SN / VID/PID / SDK / FW / 流模式）
 *  - 内参（fx/fy/cx/cy + distortion）
 *  - 帧统计（实时 fps / frameIndex / timestamp）
 *  - Color 控制（自动曝光 / 镜像 / Registration）
 *  - Depth 控制（AE / 边缘优化 / 去噪 / 温度补偿）
 *
 * 当前 v1：开关型控制项（数值型 Exposure/Gain/Confidence/MaxDepth 等留 v2，需要数值输入控件）。
 */
@Composable
fun DepthCameraRoute(
    onBack: () -> Unit,
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
            item { LivePreviewRow(color = colorBmp, depth = depthBmp) }
            item { DeviceInfoCard(state = ui.device) }
            item { IntrinsicsCard(state = ui.device) }
            item { FrameStatsCard(color = ui.color, depth = ui.depth) }
            item { ColorControlsCard(vm = vm, ui = ui) }
            item { DepthControlsCard(vm = vm, ui = ui) }
        }
    }
}

// ─── 实时预览（Color + Depth） ────────────────────────────────────────────────
@Composable
private fun LivePreviewRow(color: Bitmap?, depth: Bitmap?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Gomob.spacing.s20),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        PreviewTile("COLOR", color, Modifier.weight(1f))
        PreviewTile("DEPTH", depth, Modifier.weight(1f))
    }
}

@Composable
private fun PreviewTile(label: String, bitmap: Bitmap?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .aspectRatio(1.6f)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg2)
            .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "$label preview",
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            Modifier
                .padding(Gomob.spacing.s4)
                .clip(Gomob.shapes.r1)
                .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = Gomob.spacing.s6, vertical = 1.dp),
        ) {
            Text(
                label,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.08.em,
                color = androidx.compose.ui.graphics.Color.White,
            )
        }
    }
}

// ─── 设备信息卡 ──────────────────────────────────────────────────────────────
@Composable
private fun DeviceInfoCard(state: BerxelDeviceState) {
    val info = (state as? BerxelDeviceState.Streaming)?.info
    SectionCard(title = "设备信息") {
        InfoRow("序列号", info?.serialNumber ?: "—")
        SettingRowDivider()
        InfoRow("VID / PID", info?.let {
            "0x${"%04x".format(it.vendorId)}  /  0x${"%04x".format(it.productId)}"
        } ?: "—")
        SettingRowDivider()
        InfoRow("SDK", info?.sdkVersion?.ifBlank { "—" } ?: "—")
        SettingRowDivider()
        InfoRow("Firmware", info?.firmwareVersion?.ifBlank { "—" } ?: "—")
        SettingRowDivider()
        InfoRow("Color 模式", info?.colorMode.formatStreamSpec())
        SettingRowDivider()
        InfoRow("Depth 模式", info?.depthMode.formatStreamSpec())
    }
}

private fun BerxelStreamSpec?.formatStreamSpec(): String {
    if (this == null) return "—"
    return "${width}×${height}@${fps}  ${pixelType.removePrefix("BERXEL_HAWK_PIXEL_TYPE_")}"
}

// ─── 内参卡 ───────────────────────────────────────────────────────────────────
@Composable
private fun IntrinsicsCard(state: BerxelDeviceState) {
    SectionCard(title = "内参（出厂值）") {
        // SDK 出厂参数 color/depth 共用同一组（详见 BerxelService.readIntrinsics 注释）
        // M1.3 实测精度时再决定要不要拆开
        val info = (state as? BerxelDeviceState.Streaming)?.info
        val color = info?.colorMode
        InfoRow("分辨率参考", color?.let { "${it.width}×${it.height}" } ?: "—")
        SettingRowDivider()
        // SDK getCameraIntriscParams 当前在 BerxelService 里读出后没透传到 info；
        // v1 子页先显示"使用 SDK 出厂"占位，M1.3 时把 fx/fy/cx/cy 透传到 BerxelDeviceInfo
        InfoRow("内参来源", "SDK 出厂参数（getCameraIntriscParams）")
        SettingRowDivider()
        InfoRow("自标定", "未启用（M2 标定向导待实施）")
    }
}

// ─── 帧统计卡 ────────────────────────────────────────────────────────────────
@Composable
private fun FrameStatsCard(color: BerxelFrameStat?, depth: BerxelFrameStat?) {
    SectionCard(title = "实时帧统计") {
        StatRow("Color", color)
        SettingRowDivider()
        StatRow("Depth", depth)
    }
}

@Composable
private fun StatRow(label: String, stat: BerxelFrameStat?) {
    val sub = if (stat == null) "等待首帧" else
        "frame#${stat.frameIndex}  ${stat.measuredFps} fps  t=${stat.timestampUs}μs"
    InfoRow(label, sub)
}

// ─── Color 控制卡 ────────────────────────────────────────────────────────────
@Composable
private fun ColorControlsCard(vm: DepthCameraViewModel, ui: DepthCameraUiState) {
    SectionCard(title = "Color 控制") {
        ToggleRow(
            title = "自动曝光",
            subtitle = "off 时按手动 Exposure / Gain（v2 加数值输入）",
            checked = ui.controls.colorAutoExposure,
            onCheckedChange = vm::setColorAutoExposure,
        )
        SettingRowDivider()
        ToggleRow(
            title = "镜像",
            subtitle = "Color + Depth 同步左右翻转",
            checked = ui.controls.streamMirror,
            onCheckedChange = vm::setStreamMirror,
        )
        SettingRowDivider()
        ToggleRow(
            title = "Depth → Color 配准",
            subtitle = "SDK 把 Depth 重投影到 Color 像素坐标；off 时 Depth 在自身坐标",
            checked = ui.controls.registrationEnable,
            onCheckedChange = vm::setRegistrationEnable,
        )
    }
}

// ─── Depth 控制卡 ────────────────────────────────────────────────────────────
@Composable
private fun DepthControlsCard(vm: DepthCameraViewModel, ui: DepthCameraUiState) {
    SectionCard(title = "Depth 控制") {
        ToggleRow(
            title = "自动曝光",
            subtitle = "Depth AE 由 SDK 自动调整投射器与积分时间",
            checked = ui.controls.depthAutoExposure,
            onCheckedChange = vm::setDepthAutoExposure,
        )
        SettingRowDivider()
        ToggleRow(
            title = "边缘优化",
            subtitle = "去除深度边缘抖动，但可能丢细节",
            checked = ui.controls.depthEdgeOptimization,
            onCheckedChange = vm::setDepthEdgeOptimization,
        )
        SettingRowDivider()
        ToggleRow(
            title = "基础去噪",
            subtitle = "SDK 内置时空域去噪",
            checked = ui.controls.depthDenoise,
            onCheckedChange = vm::setDepthDenoise,
        )
        SettingRowDivider()
        ToggleRow(
            title = "温度补偿",
            subtitle = "变温环境下保深度精度（推荐开启）",
            checked = ui.controls.depthTemperatureCompensation,
            onCheckedChange = vm::setDepthTemperatureCompensation,
        )
    }
}

// ─── 公共组件 ────────────────────────────────────────────────────────────────
@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.padding(horizontal = Gomob.spacing.s20),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Text(
            title,
            style = Gomob.type.eyebrow,
            color = Gomob.colors.fg2,
            modifier = Modifier.padding(start = Gomob.spacing.s4),
        )
        HairlineCard(padding = 0.dp) {
            Column { content() }
        }
    }
}

@Composable
private fun InfoRow(title: String, subtitle: String) {
    SettingRow(title = title, subtitle = subtitle)
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        trailing = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Gomob.colors.bg0,
                    checkedTrackColor = Gomob.colors.accent,
                    uncheckedThumbColor = Gomob.colors.fg2,
                    uncheckedTrackColor = Gomob.colors.bg2,
                ),
            )
        },
    )
    Spacer(Modifier.height(Gomob.spacing.s2))
}
