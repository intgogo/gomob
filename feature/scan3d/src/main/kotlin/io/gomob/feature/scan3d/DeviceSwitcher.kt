package io.gomob.feature.scan3d

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.gomob.designsystem.theme.Gomob

/** 车辆外廓扫描的设备类型：激光双单元 LIDAR-PTZ 或 Berxel RGBD 相机。 */
enum class ScanDeviceMode {
    Laser,
    Berxel,
}

/**
 * 顶栏右上角的设备切换段控（激光 / 相机）。放在 BackHeader 的 trailing 槽。
 * 用户拍板：激光页 = 融合点云 + 两镜头点云 + 操作键；相机页 = 现有 Berxel 界面。
 */
@Composable
fun DeviceSwitcher(
    mode: ScanDeviceMode,
    modifier: Modifier = Modifier,
    onSelect: (ScanDeviceMode) -> Unit,
) {
    Row(
        modifier = modifier
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg2)
            .border(BorderStroke(1.dp, Gomob.colors.line1), Gomob.shapes.r2)
            .padding(2.dp),
    ) {
        SwitchSegment("激光", selected = mode == ScanDeviceMode.Laser) { onSelect(ScanDeviceMode.Laser) }
        SwitchSegment("相机", selected = mode == ScanDeviceMode.Berxel) { onSelect(ScanDeviceMode.Berxel) }
    }
}

@Composable
private fun SwitchSegment(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        modifier = Modifier
            .clip(Gomob.shapes.r1)
            .background(if (selected) Gomob.colors.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 5.dp),
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        color = if (selected) Gomob.colors.accent else Gomob.colors.fg2,
    )
}
