package io.gomob.feature.scan3d

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.SettingRow
import io.gomob.designsystem.component.SettingRowDivider
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.theme.Gomob
import io.gomob.nativebridge.berxel.BerxelDeviceState
import io.gomob.nativebridge.berxel.BerxelStreamFlagProfile
import io.gomob.nativebridge.berxel.BerxelStreamSpec
import io.gomob.nativebridge.camera.CameraModel

const val DEPTH_CAMERA_INFO_ROUTE = "scan3d/depth-camera/info"

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
// 公共组件
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun SectionTitle(text: String) {
    Box(Modifier.padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s4)) {
        Text(text, style = Gomob.type.eyebrow, color = Gomob.colors.fg2)
    }
}

@Composable
private fun InfoRow(title: String, subtitle: String) {
    SettingRow(title = title, subtitle = subtitle)
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
