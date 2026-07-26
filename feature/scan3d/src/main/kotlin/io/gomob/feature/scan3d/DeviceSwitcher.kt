package io.gomob.feature.scan3d

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * 3D 工位（激光双单元站）。
 * TODO(工位注册表终态): 多工位部署后列表由服务端下发（gateway 工位列表 API），
 * 客户端静态表仅为单工位期过渡；选中 id 届时接 endpoint / 单元 IP 切换。
 */
data class LaserStation(val id: String, val name: String)

/** 静态工位表 —— 目前仅 .160 一个真实部署工位，不放未部署的假工位。 */
val LaserStations: List<LaserStation> = listOf(
    LaserStation(id = "bay-1", name = "1 号工位"),
)

/**
 * 顶栏右上角的 3D 工位下拉选。放在 BackHeader 的 trailing 槽。
 * 芯片显示当前工位名 + 下拉箭头，点开列出全部工位。
 */
@Composable
fun StationSwitcher(
    stationId: String,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    val current = LaserStations.firstOrNull { it.id == stationId } ?: LaserStations.first()
    SwitcherDropdown(label = current.name, contentDescription = "选择工位", modifier = modifier) { dismiss ->
        LaserStations.forEach { station ->
            SwitcherMenuItem(name = station.name, selected = station.id == current.id) {
                onSelect(station.id)
                dismiss()
            }
        }
    }
}

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
 * 设备下拉选（3D 工位 / 3D 相机）。3D 相机入口暂时隐藏（route 固定 Laser 模式），
 * 恢复入口时把它放回 BackHeader 的 trailing 槽即可。
 */
@Composable
fun DeviceSwitcher(
    mode: ScanDeviceMode,
    modifier: Modifier = Modifier,
    onSelect: (ScanDeviceMode) -> Unit,
) {
    SwitcherDropdown(label = mode.displayName(), contentDescription = "选择设备", modifier = modifier) { dismiss ->
        ScanDeviceMode.entries.forEach { device ->
            SwitcherMenuItem(name = device.displayName(), selected = device == mode) {
                onSelect(device)
                dismiss()
            }
        }
    }
}

/** 下拉选通用骨架：芯片（当前值 + 箭头）+ DropdownMenu，菜单项由 menuItems 槽提供。 */
@Composable
private fun SwitcherDropdown(
    label: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    menuItems: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        // 胶囊无边框 chip：fg0@5% 底（浅色页上是深色 5%，随主题反转）。
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(Gomob.colors.fg0.copy(alpha = 0.05f))
                .clickable { expanded = true }
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Gomob.colors.accent,
            )
            Icon(
                GomobIcons.ChevronRight,
                contentDescription = contentDescription,
                tint = Gomob.colors.fg3,
                modifier = Modifier.size(14.dp).rotate(90f),
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Gomob.colors.bg1),
        ) {
            menuItems { expanded = false }
        }
    }
}

@Composable
private fun SwitcherMenuItem(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = {
            Text(
                name,
                fontSize = 13.sp,
                color = if (selected) Gomob.colors.accent else Gomob.colors.fg1,
            )
        },
        onClick = onClick,
        trailingIcon = if (selected) {
            { Icon(GomobIcons.Check, "已选", tint = Gomob.colors.accent, modifier = Modifier.size(14.dp)) }
        } else null,
    )
}
