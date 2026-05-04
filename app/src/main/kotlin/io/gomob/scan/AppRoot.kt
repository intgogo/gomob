package io.gomob.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.theme.Primary
import io.gomob.designsystem.theme.SurfaceDeep
import io.gomob.feature.auth.AuthGateViewModel
import io.gomob.feature.auth.LoginRoute
import io.gomob.scan.navigation.GomobNavHost

/**
 * Auth gate：
 * - 未登录 → 登录页
 * - 已登录 → 5 tab 主 Shell
 *
 * isLoggedIn 是 Flow<Boolean>，DataStore 第一次读取前会返回初始值 null（包成 null 看不到时）。
 * 这里用 collectAsStateWithLifecycle(initialValue = null) 表示"未知中"。
 */
@Composable
fun AppRoot(vm: AuthGateViewModel = hiltViewModel()) {
    val loggedIn by vm.isLoggedIn.collectAsStateWithLifecycle(initialValue = null)
    when (loggedIn) {
        null -> SplashLoading()
        false -> LoginRoute(onLoggedIn = { /* 自动响应 isLoggedIn → true，重组 */ })
        true -> GomobNavHost()
    }
}

@Composable
private fun SplashLoading() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDeep),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Primary)
    }
}
