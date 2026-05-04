package io.gomob.scan.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.theme.Accent
import io.gomob.designsystem.theme.BorderSubtle
import io.gomob.designsystem.theme.Primary
import io.gomob.designsystem.theme.SurfaceCard
import io.gomob.designsystem.theme.SurfaceDeep
import io.gomob.designsystem.theme.TextSecondary
import io.gomob.designsystem.theme.TextTertiary
import io.gomob.feature.collaboration.CollaborationRoute
import io.gomob.feature.home.HomeRoute
import io.gomob.feature.message.MessageRoute
import io.gomob.feature.profile.ProfileRoute
import io.gomob.feature.scan3d.Scan3dRoute

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("首页", Icons.Filled.AutoAwesome),
    Message("消息", Icons.Filled.ChatBubble),
    Scan3d("3D", Icons.Filled.ViewInAr),
    Collaboration("协作", Icons.Filled.GroupWork),
    Profile("我的", Icons.Filled.Person),
}

/**
 * 顶层 Shell — 5 tab + 底部 Tab Bar（中间 3D 突出）。
 *
 * 5 tab 顺序：首页 / 消息 / 3D（中间凸起）/ 协作 / 我的
 * 中间 3D 是核心硬件入口（深度相机 + 主摄合一），用 FAB 风格突出显示。
 */
@Composable
fun GomobNavHost() {
    var current by rememberSaveable { mutableStateOf(Tab.Home) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDeep),
    ) {
        // 状态栏占位
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (current) {
                    Tab.Home -> HomeRoute()
                    Tab.Message -> MessageRoute()
                    Tab.Scan3d -> Scan3dRoute()
                    Tab.Collaboration -> CollaborationRoute()
                    Tab.Profile -> ProfileRoute()
                }
            }
            BottomBar(current = current, onSelect = { current = it })
        }
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
    ) {
        // 底部容器
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .background(SurfaceCard)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Tab.entries.forEach { tab ->
                if (tab == Tab.Scan3d) {
                    // 中间 FAB 风格凸起
                    CenterFab(
                        modifier = Modifier.weight(1f),
                        selected = current == tab,
                        onClick = { onSelect(tab) },
                    )
                } else {
                    TabItem(
                        modifier = Modifier.weight(1f),
                        tab = tab,
                        selected = current == tab,
                        onClick = { onSelect(tab) },
                    )
                }
            }
        }
        // 顶部 1px 描边
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BorderSubtle),
        )
    }
}

@Composable
private fun TabItem(
    modifier: Modifier = Modifier,
    tab: Tab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = tab.label,
            tint = if (selected) Primary else TextTertiary,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Primary else TextTertiary,
        )
    }
}

@Composable
private fun CenterFab(
    modifier: Modifier = Modifier,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier.clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // 凸起按钮
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(Primary, Accent),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Tab.Scan3d.icon,
                contentDescription = "3D 扫描",
                tint = Color.White,
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
