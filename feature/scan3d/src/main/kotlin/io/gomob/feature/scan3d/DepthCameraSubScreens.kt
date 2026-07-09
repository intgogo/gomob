package io.gomob.feature.scan3d

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.SettingRow
import io.gomob.designsystem.component.SettingRowDivider
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.glass.glassChrome
import io.gomob.designsystem.theme.Gomob
import io.gomob.nativebridge.berxel.BerxelDeviceControls
import io.gomob.nativebridge.berxel.BerxelDeviceState
import io.gomob.nativebridge.berxel.BerxelStreamFlagProfile
import io.gomob.nativebridge.berxel.BerxelStreamSpec
import io.gomob.nativebridge.camera.CameraModel

const val DEPTH_CAMERA_INFO_ROUTE = "scan3d/depth-camera/info"
const val DEPTH_CAMERA_CONTROLS_ROUTE = "scan3d/depth-camera/controls"
const val DEPTH_CAMERA_CALIBRATION_ROUTE = "scan3d/depth-camera/calibration"

// ═══════════════════════════════════════════════════════════════════════════
// 三级页 1 — 设备详情（信息 + 内参 + 帧统计）
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun DepthCameraInfoRoute(
    onBack: () -> Unit,
    vm: DepthCameraViewModel = hiltViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    GlassHeaderScaffold(
        listState = listState,
        header = { BackHeader(title = "设备详情", eyebrow = "iHawk · 信息 / 内参 / 帧统计", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + Gomob.spacing.s12,
                bottom = padding.calculateBottomPadding() + Gomob.spacing.s28,
            ),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s16),
        ) {
            item { DeviceInfoSection(state = ui.device) }
            item { IntrinsicsSection(state = ui.device) }
            item {
                val color = ui.color
                val depth = ui.depth
                FrameStatsSection(
                    colorLine = color.shortLine(),
                    depthLine = depth.shortLine(),
                    syncOk = color != null && depth != null && color.timestampUs == depth.timestampUs,
                )
            }
        }
    }
}

@Composable
private fun DeviceInfoSection(state: BerxelDeviceState) {
    val info = (state as? BerxelDeviceState.Streaming)?.info
    SectionTitle("设备信息")
    SectionList {
        // 型号按 vid:pid 自动识别(Berxel iHawk P100R3 / eYs3D RS-D550),双相机统一展示。
        InfoRow("型号", info?.let { CameraModel.fromUsbIds(it.vendorId, it.productId).deviceTypeLabel } ?: "—")
        SettingRowDivider()
        InfoRow("序列号", info?.serialNumber.orDash())
        SettingRowDivider()
        InfoRow(
            "VID / PID",
            info?.let { "0x${"%04x".format(it.vendorId)}  /  0x${"%04x".format(it.productId)}" } ?: "—",
        )
        SettingRowDivider()
        InfoRow("SDK", info?.sdkVersion.orDash())
        SettingRowDivider()
        InfoRow("Firmware", info?.firmwareVersion.orDash())
        SettingRowDivider()
        InfoRow("Flag 模式", info?.streamFlagMode?.displayTitle() ?: "—")
        SettingRowDivider()
        InfoRow("Color 模式", info?.colorMode.formatStreamSpec())
        SettingRowDivider()
        InfoRow("Depth 模式", info?.depthMode.formatStreamSpec())
    }
}

@Composable
private fun IntrinsicsSection(state: BerxelDeviceState) {
    val info = (state as? BerxelDeviceState.Streaming)?.info
    val color = info?.colorMode
    SectionTitle("内参（出厂值）")
    SectionList {
        InfoRow("Color 分辨率", color?.let { "${it.width}×${it.height}" } ?: "—")
        SettingRowDivider()
        InfoRow("内参来源", "SDK getCameraIntriscParams")
        SettingRowDivider()
        InfoRow(
            "实测精度门",
            "M1.3 棋盘格 30/50/100cm 验深度边缘投到 Color ≤ 2 px",
        )
        SettingRowDivider()
        InfoRow("自标定状态", "未启用（M2 标定向导待实施）")
    }
}

@Composable
private fun FrameStatsSection(colorLine: String, depthLine: String, syncOk: Boolean) {
    SectionTitle("实时帧统计")
    SectionList {
        InfoRow("Color", colorLine)
        SettingRowDivider()
        InfoRow("Depth", depthLine)
        SettingRowDivider()
        InfoRow(
            "RGBD 同步",
            if (syncOk) "frameIndex + timestampUs 配对成功（硬件级同步）" else "未配对",
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 三级页 2 — 成像控制（Color + Depth）
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun DepthCameraControlsRoute(
    onBack: () -> Unit,
    vm: DepthCameraViewModel = hiltViewModel(),
) {
    val ui by vm.uiState.collectAsStateWithLifecycle()
    val controls = ui.controls

    val listState = rememberLazyListState()
    GlassHeaderScaffold(
        listState = listState,
        header = { BackHeader(title = "成像控制", eyebrow = "Color / Depth · 实时调参", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + Gomob.spacing.s12,
                bottom = padding.calculateBottomPadding() + Gomob.spacing.s28,
            ),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s16),
        ) {
            item { ColorControlsSection(controls, vm) }
            item { DepthControlsSection(controls, vm) }
            item { GeneralControlsSection(controls, vm) }
        }
    }
}

@Composable
private fun ColorControlsSection(c: BerxelDeviceControls, vm: DepthCameraViewModel) {
    SectionTitle("Color 控制")
    SectionList {
        ToggleRow(
            "自动曝光",
            "off 时按手动 Exposure / Gain（v2 加数值输入）",
            c.colorAutoExposure, vm::setColorAutoExposure,
        )
    }
    SectionHint("数值型参数（手动 Exposure / Gain / ColorQuality）需要 v2 NumberField 组件。")
}

@Composable
private fun DepthControlsSection(c: BerxelDeviceControls, vm: DepthCameraViewModel) {
    SectionTitle("Depth 控制")
    SectionList {
        ToggleRow(
            "自动曝光",
            "Depth AE 由 SDK 自动调投射器和积分时间",
            c.depthAutoExposure, vm::setDepthAutoExposure,
        )
        SettingRowDivider()
        ToggleRow(
            "边缘优化",
            "去除深度边缘抖动，但可能丢细节",
            c.depthEdgeOptimization, vm::setDepthEdgeOptimization,
        )
        SettingRowDivider()
        ToggleRow(
            "基础去噪",
            "SDK 内置时空域去噪",
            c.depthDenoise, vm::setDepthDenoise,
        )
        SettingRowDivider()
        ToggleRow(
            "温度补偿",
            "变温环境下保深度精度（推荐开启）",
            c.depthTemperatureCompensation, vm::setDepthTemperatureCompensation,
        )
    }
    SectionHint("数值型参数（Exposure / Gain / Confidence / MaxDepth）需要 v2 NumberField 组件。")
}

@Composable
private fun GeneralControlsSection(c: BerxelDeviceControls, vm: DepthCameraViewModel) {
    SectionTitle("通用")
    SectionList {
        ToggleRow(
            "镜像",
            "Color + Depth 同步左右翻转",
            c.streamMirror, vm::setStreamMirror,
        )
        SettingRowDivider()
        ToggleRow(
            "Depth → Color 配准",
            "SDK 把 Depth 重投影到 Color 像素坐标",
            c.registrationEnable, vm::setRegistrationEnable,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 三级页 3 — 标定（Color ↔ Depth 外参 + 内参标定向导入口）
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun DepthCameraCalibrationRoute(
    onBack: () -> Unit,
    vm: StereoCalibViewModel = hiltViewModel(),
) {
    val hlsd8 by vm.hlsd8Preview.collectAsStateWithLifecycle()
    val lprime by vm.lprimePreview.collectAsStateWithLifecycle()
    val count by vm.calibCount.collectAsStateWithLifecycle()
    val msg by vm.msg.collectAsStateWithLifecycle()
    val capturing by vm.capturing.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    GlassHeaderScaffold(
        listState = listState,
        header = { BackHeader(title = "HLSD8 ↔ Depth 标定", eyebrow = "ChArUco 双相机标定采集", onBack = onBack) },
        overlay = { _ ->
            // 吸底采集按钮 → 玻璃吸底条（规则 4）：列表从底下滚过透出模糊背景。
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .glassChrome(topEdge = true)
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
            ) {
                CalibCaptureButton(count = count, enabled = vm.hasRgb && !capturing, onClick = vm::captureCalib)
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                // 底部预留吸底采集按钮高度
                bottom = padding.calculateBottomPadding() + 88.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                HairlineCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("对准 ChArUco 标定板采集", color = Gomob.colors.fg1, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "采集时已自动关 IR 投射器 → L' 出无散斑灰度（标定才能检角点）。" +
                                "多姿态/倾角各采一张：俯仰/左右偏摆/平面内旋 ±25–30°、远近 20–35cm、板挪到画面上/中/下，建议 ≥20 张。",
                            color = Gomob.colors.fg3, fontSize = 12.sp,
                        )
                    }
                }
            }
            if (!vm.hasRgb) {
                item {
                    HairlineCard {
                        Text(
                            "未检测到 HLSD8 彩色相机——双相机标定需要它。插好 HLSD8 再进本页。",
                            color = Gomob.colors.danger, fontSize = 13.sp, modifier = Modifier.padding(14.dp),
                        )
                    }
                }
            }
            item { CalibPreviewPane("HLSD8 彩色（13MP，检角点）", hlsd8) }
            item { CalibPreviewPane("eYs3D L'（无散斑，检角点）", lprime) }
            item {
                Text(
                    msg ?: "对准标定板，点下方按钮采集",
                    color = if (msg != null) Gomob.colors.accent else Gomob.colors.fg3,
                    fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun CalibPreviewPane(label: String, bmp: Bitmap?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = Gomob.colors.fg2, fontSize = 12.sp)
        Box(
            Modifier.fillMaxWidth().aspectRatio(4f).clip(RoundedCornerShape(10.dp)).background(Gomob.colors.bg1),
            contentAlignment = Alignment.Center,
        ) {
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = label,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text("等待画面…", color = Gomob.colors.fg3, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun CalibCaptureButton(count: Int, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(CircleShape)
            .background(if (enabled) Gomob.colors.accentSoft else Gomob.colors.bg1)
            .border(BorderStroke(1.dp, if (enabled) Gomob.colors.accent else Gomob.colors.line2), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("标定采集    已采 $count 张", color = if (enabled) Gomob.colors.accent else Gomob.colors.fg3,
            fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// 公共组件
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun SectionTitle(text: String) {
    Box(Modifier.padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s4)) {
        Text(text, style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
    }
}

@Composable
private fun SectionHint(text: String) {
    Box(Modifier.padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s2)) {
        Text(text, style = Gomob.type.caption, color = Gomob.colors.fg3)
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
}

private fun BerxelStreamSpec?.formatStreamSpec(): String {
    if (this == null) return "—"
    return "${width}×${height}@${fps}  ${pixelType.removePrefix("BERXEL_HAWK_PIXEL_TYPE_")}"
}

private fun BerxelStreamFlagProfile.displayTitle(): String {
    return when (this) {
        BerxelStreamFlagProfile.SINGULAR -> "SINGULAR"
        BerxelStreamFlagProfile.MIX -> "MIX"
        BerxelStreamFlagProfile.MIX_HD -> "MIX_HD"
        BerxelStreamFlagProfile.MIX_QVGA -> "MIX_QVGA"
    }
}

private fun String?.orDash(): String =
    if (isNullOrBlank()) "—" else this
