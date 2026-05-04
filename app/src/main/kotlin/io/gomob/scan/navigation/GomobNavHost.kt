package io.gomob.scan.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.gomob.designsystem.component.TabBarVector
import io.gomob.designsystem.component.TabItemVector
import io.gomob.designsystem.theme.Gomob
import io.gomob.feature.collaboration.CollaborationRoute
import io.gomob.feature.home.HomeRoute
import io.gomob.feature.message.MessageRoute
import io.gomob.feature.profile.ProfileRoute
import io.gomob.feature.scan3d.Scan3dRoute

private val TABS = listOf(
    TabItemVector("home", "首页", Icons.Filled.AutoAwesome),
    TabItemVector("message", "消息", Icons.Filled.ChatBubble),
    TabItemVector("scan3d", "3D", Icons.Filled.ViewInAr),
    TabItemVector("collaboration", "协作", Icons.Filled.GroupWork),
    TabItemVector("profile", "我的", Icons.Filled.Person),
)

/**
 * 顶层 Shell — 5 tab + 工业仪表风 TabBar（无凸起 FAB，靠图标 + 文字色变化）。
 */
@Composable
fun GomobNavHost() {
    var current by rememberSaveable { mutableStateOf("home") }

    Column(modifier = Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (current) {
                "home" -> HomeRoute()
                "message" -> MessageRoute()
                "scan3d" -> Scan3dRoute()
                "collaboration" -> CollaborationRoute()
                "profile" -> ProfileRoute()
            }
        }
        TabBarVector(items = TABS, selectedKey = current, onSelect = { current = it })
    }
}
