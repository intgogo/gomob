package io.gomob.feature.scan3d

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.SettingRow
import io.gomob.designsystem.component.SettingRowDivider
import io.gomob.designsystem.theme.Gomob
import io.gomob.nativebridge.berxel.BerxelDeviceControls
import io.gomob.nativebridge.berxel.BerxelDeviceState
import io.gomob.nativebridge.berxel.BerxelStreamFlagProfile
import io.gomob.nativebridge.berxel.BerxelStreamSpec

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

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(title = "设备详情", eyebrow = "iHawk · 信息 / 内参 / 帧统计", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = Gomob.spacing.s12, bottom = Gomob.spacing.s28),
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

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(title = "成像控制", eyebrow = "Color / Depth · 实时调参", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = Gomob.spacing.s12, bottom = Gomob.spacing.s28),
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
fun DepthCameraCalibrationRoute(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(title = "Color ↔ Depth 标定", eyebrow = "iHawk 自身两路传感器", onBack = onBack)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = Gomob.spacing.s12, bottom = Gomob.spacing.s28),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s16),
        ) {
            item {
                SectionTitle("当前外参")
                SectionList {
                    InfoRow("外参来源", "SDK 出厂 + setRegistrationEnable")
                    SettingRowDivider()
                    InfoRow("自标定", "未启用（M2 标定向导待实施）")
                    SettingRowDivider()
                    InfoRow("外参 R / t", "—")
                }
            }
            item {
                SectionTitle("RGB 内参标定")
                SectionList {
                    InfoRow("方法", "OpenCV cv::calibrateCamera（M2.x）")
                    SettingRowDivider()
                    InfoRow("标定板", "Charuco（运动鲁棒）")
                    SettingRowDivider()
                    InfoRow("采集张数", "建议 12 角度（前/左/右/上/下/倾斜各 2 张）")
                    SettingRowDivider()
                    InfoRow("验收阈值", "Color reprojection ≤ 1.0 px / Depth ≤ 0.5 mm")
                }
            }
            item {
                SectionTitle("Color ↔ Depth 外参标定")
                SectionList {
                    InfoRow("方法", "OpenCV cv::stereoCalibrate（M2.x）")
                    SettingRowDivider()
                    InfoRow("输入", "RGB 内参 + Depth 内参 + N 帧 Charuco 双流配对")
                    SettingRowDivider()
                    InfoRow("输出", "Depth → Color 旋转 R + 平移 t（mm）")
                }
            }
            item {
                SectionTitle("决策门")
                SectionHint(
                    "实测 SDK 出厂参数 + setRegistrationEnable 在 30/50/100cm 距离的精度，达标 → 跳过自标定；" +
                        "不达标才启动向导。详见 docs/architecture/05-calibration-pipeline.md。",
                )
            }
        }
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
