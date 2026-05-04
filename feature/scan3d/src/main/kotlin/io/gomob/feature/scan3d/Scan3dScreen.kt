package io.gomob.feature.scan3d

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.ViewInAr
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
import io.gomob.designsystem.theme.BorderGlow
import io.gomob.designsystem.theme.Primary
import io.gomob.designsystem.theme.PrimaryDim
import io.gomob.designsystem.theme.StateInfo
import io.gomob.designsystem.theme.StateSuccess
import io.gomob.designsystem.theme.StateWarning
import io.gomob.designsystem.theme.SurfaceCard
import io.gomob.designsystem.theme.SurfaceCardHigh
import io.gomob.designsystem.theme.SurfaceDeep
import io.gomob.designsystem.theme.TextPrimary
import io.gomob.designsystem.theme.TextSecondary
import io.gomob.designsystem.theme.TextTertiary

const val SCAN3D_ROUTE = "scan3d"

@Composable
fun Scan3dRoute(
    onOpenCalibration: () -> Unit = {},
    onOpenScan: () -> Unit = {},
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDeep),
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { Header() }
        item { DeviceStatusCard() }
        item { TwoEntryCards(onOpenCalibration, onOpenScan) }
        item { CalibrationStatusCard() }
        item { RecentAssetsCard() }
    }
}

@Composable
private fun Header() {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Text(text = "3D 扫描", style = MaterialTheme.typography.headlineLarge)
        Text(
            text = "Berxel iHawk · 主摄合一采集",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun DeviceStatusCard() {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        background = Brush.linearGradient(
            listOf(SurfaceCard, AccentDim.copy(alpha = 0.4f)),
        ),
        borderColor = BorderGlow,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Primary.copy(alpha = 0.4f), Accent.copy(alpha = 0.3f)),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Usb, contentDescription = null, tint = Primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Berxel iHawk-072",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(StateSuccess.copy(alpha = 0.18f))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "已连接",
                            style = MaterialTheme.typography.labelSmall,
                            color = StateSuccess,
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "USB-C OTG · MixHD 1280×800 · 8 fps",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "FW 1.2.3 · SDK v2.0.190",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextTertiary,
                )
            }
        }
    }
}

@Composable
private fun TwoEntryCards(onOpenCalibration: () -> Unit, onOpenScan: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        EntryCard(
            modifier = Modifier.weight(1f),
            title = "标定",
            subtitle = "双摄外参 / 内参",
            description = "Charuco 标定向导\n3 分钟完成",
            icon = Icons.Filled.Tune,
            accent = Primary,
            onClick = onOpenCalibration,
        )
        EntryCard(
            modifier = Modifier.weight(1f),
            title = "扫描",
            subtitle = "RGBD 同步采集",
            description = "实时点云预览\n智能预审上链",
            icon = Icons.Filled.ViewInAr,
            accent = Accent,
            onClick = onOpenScan,
        )
    }
}

@Composable
private fun EntryCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    description: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
) {
    GlassCard(
        modifier = modifier.height(192.dp),
        onClick = onClick,
        background = Brush.verticalGradient(
            listOf(accent.copy(alpha = 0.22f), SurfaceCard),
        ),
        borderColor = accent.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accent.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent)
            }
            Column {
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = accent,
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = TextTertiary,
            )
        }
    }
}

@Composable
private fun CalibrationStatusCard() {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = StateSuccess)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "标定状态：已校准", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "上次标定 2024/05/09 18:32 · 重投影误差 0.42 px",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                text = "查看 →",
                style = MaterialTheme.typography.labelLarge,
                color = Primary,
            )
        }
    }
}

@Composable
private fun RecentAssetsCard() {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "最近 3D 资产", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Text(text = "查看全部 →", style = MaterialTheme.typography.labelLarge, color = Primary)
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) { idx ->
                AssetThumb(
                    modifier = Modifier.weight(1f),
                    label = "VIN-${idx + 1}",
                    badge = listOf("0.7M 点", "1.2M 点", "0.9M 点")[idx],
                    color = listOf(Primary, Accent, StateInfo)[idx],
                )
            }
        }
    }
}

@Composable
private fun AssetThumb(
    modifier: Modifier = Modifier,
    label: String,
    badge: String,
    color: Color,
) {
    GlassCard(
        modifier = modifier.height(108.dp),
        background = Brush.linearGradient(
            listOf(color.copy(alpha = 0.4f), SurfaceCardHigh),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(color.copy(alpha = 0.25f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(text = badge, style = MaterialTheme.typography.labelSmall, color = color)
            }
            Text(text = label, style = MaterialTheme.typography.titleSmall)
        }
    }
}
