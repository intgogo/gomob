package io.gomob.feature.scan3d

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

/** 车辆外廓扫描的设备类型：激光双单元工位（3D 工位）或 Berxel RGBD 相机（3D 相机）。 */
enum class ScanDeviceMode {
    Laser,
    Berxel,
}

/** 设备中文展示名（下拉当前值/菜单项统一走这里）。 */
private fun ScanDeviceMode.displayName(): String = when (this) {
    ScanDeviceMode.Laser -> "3D 工位"
    ScanDeviceMode.Berxel -> "3D 相机"
}

/**
 * 顶栏右上角的设备下拉选（3D 工位 / 3D 相机）。放在 BackHeader 的 trailing 槽。
 * 芯片显示当前设备名 + 下拉箭头，点开列出两个设备；工位页 = 服务端瘦客户端点云页，相机页 = Berxel 界面。
 */
@Composable
fun DeviceSwitcher(
    mode: ScanDeviceMode,
    modifier: Modifier = Modifier,
    onSelect: (ScanDeviceMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            modifier = Modifier
                .clip(Gomob.shapes.r2)
                .background(Gomob.colors.bg2)
                .border(BorderStroke(1.dp, Gomob.colors.line1), Gomob.shapes.r2)
                .clickable { expanded = true }
                .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = mode.displayName(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Gomob.colors.accent,
            )
            Icon(
                GomobIcons.ChevronRight,
                contentDescription = "选择设备",
                tint = Gomob.colors.fg3,
                modifier = Modifier.size(14.dp).rotate(90f),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Gomob.colors.bg1),
        ) {
            DeviceMenuItem(ScanDeviceMode.Laser, mode) { onSelect(it); expanded = false }
            DeviceMenuItem(ScanDeviceMode.Berxel, mode) { onSelect(it); expanded = false }
        }
    }
}

@Composable
private fun DeviceMenuItem(
    device: ScanDeviceMode,
    current: ScanDeviceMode,
    onSelect: (ScanDeviceMode) -> Unit,
) {
    val selected = device == current
    DropdownMenuItem(
        text = {
            Text(
                device.displayName(),
                fontSize = 13.sp,
                color = if (selected) Gomob.colors.accent else Gomob.colors.fg1,
            )
        },
        onClick = { onSelect(device) },
        trailingIcon = if (selected) {
            { Icon(GomobIcons.Check, "已选", tint = Gomob.colors.accent, modifier = Modifier.size(14.dp)) }
        } else null,
    )
}
