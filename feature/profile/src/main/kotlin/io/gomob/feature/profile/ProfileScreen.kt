package io.gomob.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.gomob.designsystem.component.GlassCard
import io.gomob.designsystem.theme.Accent
import io.gomob.designsystem.theme.AccentDim
import io.gomob.designsystem.theme.BorderSubtle
import io.gomob.designsystem.theme.Primary
import io.gomob.designsystem.theme.StateDanger
import io.gomob.designsystem.theme.SurfaceCard
import io.gomob.designsystem.theme.SurfaceDeep
import io.gomob.designsystem.theme.TextSecondary
import io.gomob.designsystem.theme.TextTertiary

const val PROFILE_ROUTE = "profile"

private data class SettingsItem(
    val title: String,
    val icon: ImageVector,
    val accent: Color = Primary,
    val trailing: String? = null,
)

private val SETTINGS_ITEMS = listOf(
    SettingsItem("个人信息", Icons.Filled.Person),
    SettingsItem("账号与安全", Icons.Filled.Lock),
    SettingsItem("通用 · 缓存", Icons.Filled.Storage, trailing = "31.2 MB"),
    SettingsItem("网络设置", Icons.Filled.Wifi, trailing = "112.145.10.91:8808"),
    SettingsItem("通知设置", Icons.Filled.Notifications),
    SettingsItem("关于 gomob", Icons.Filled.Info, trailing = "v0.1.0"),
)

@Composable
fun ProfileRoute() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDeep),
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ProfileHeader() }
        item { ShiftStatsCard() }
        item { SettingsList() }
        item { BottomActions() }
    }
}

@Composable
private fun ProfileHeader() {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        background = Brush.linearGradient(
            listOf(Primary.copy(alpha = 0.25f), AccentDim.copy(alpha = 0.4f), SurfaceCard),
        ),
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(listOf(Primary, Accent)),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "沈",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "沈海明",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Primary.copy(alpha = 0.2f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "查验员",
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "工号 ZAA0120230001",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "杭州市西湖区车管所检测站",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun ShiftStatsCard() {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Primary.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Bolt, contentDescription = null, tint = Primary)
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "本班次工作量", style = MaterialTheme.typography.labelMedium)
                Text(text = "已查验 27 辆 · 预警 8", style = MaterialTheme.typography.titleMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "08:30 - 17:30",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextTertiary,
                )
                Text(
                    text = "在岗中",
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                )
            }
        }
    }
}

@Composable
private fun SettingsList() {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SETTINGS_ITEMS.forEach { item ->
            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                background = Brush.verticalGradient(
                    listOf(SurfaceCard, SurfaceCard),
                ),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(item.accent.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(item.icon, contentDescription = null, tint = item.accent)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.trailing != null) {
                        Text(
                            text = item.trailing,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextTertiary,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = TextTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun BottomActions() {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ActionRow(
            text = "切换账号",
            icon = Icons.Filled.SwapHoriz,
            color = TextSecondary,
        )
        ActionRow(
            text = "退出登录",
            icon = Icons.Filled.Settings,
            color = StateDanger,
        )
    }
}

@Composable
private fun ActionRow(text: String, icon: ImageVector, color: Color) {
    GlassCard(
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = null, tint = color)
            Spacer(Modifier.width(8.dp))
            Text(text = text, color = color, style = MaterialTheme.typography.titleMedium)
        }
    }
}
