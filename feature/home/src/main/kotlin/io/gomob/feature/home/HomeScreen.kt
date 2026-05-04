package io.gomob.feature.home

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 首页路由 — App 启动落地页。
 *
 * 设计原则:
 *  - 以"开始扫描"为主 CTA, 占视觉中心
 *  - 三个副入口（历史/标定/设置）等权排列
 *  - 不堆 dashboard 信息密度: 这是工具型 App, 不是 feed
 */
const val HOME_ROUTE = "home"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeRoute(
    onStartScan: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = "gomob — 3D 扫描") })
        },
    ) { padding ->
        HomeContent(
            padding = padding,
            onStartScan = onStartScan,
            onOpenGallery = onOpenGallery,
            onOpenCalibration = onOpenCalibration,
            onOpenSettings = onOpenSettings,
        )
    }
}

@Composable
private fun HomeContent(
    padding: PaddingValues,
    onStartScan: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        PrimaryScanCard(onClick = onStartScan)

        Text(
            text = "辅助",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SecondaryCard(
                modifier = Modifier.weight(1f),
                title = "历史",
                subtitle = "回看",
                icon = Icons.Filled.History,
                onClick = onOpenGallery,
            )
            SecondaryCard(
                modifier = Modifier.weight(1f),
                title = "标定",
                subtitle = "双摄外参",
                icon = Icons.Filled.Tune,
                onClick = onOpenCalibration,
            )
        }
        SecondaryCard(
            modifier = Modifier.fillMaxWidth(),
            title = "设置",
            subtitle = "采集参数 / 设备 / 关于",
            icon = Icons.Filled.Settings,
            onClick = onOpenSettings,
        )
    }
}

@Composable
private fun PrimaryScanCard(onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().padding(20.dp)) {
            Column(modifier = Modifier.align(Alignment.CenterStart)) {
                Text(
                    text = "开始扫描",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "USB-C 接 Berxel 深度相机 + 主摄合一采集",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Icon(
                imageVector = Icons.Filled.ViewInAr,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(72.dp),
            )
        }
    }
}

@Composable
private fun SecondaryCard(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier.height(108.dp),
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.Center) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
