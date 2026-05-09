package io.gomob.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.theme.Gomob
import io.gomob.feature.auth.AuthGateViewModel
import io.gomob.feature.auth.LoginRoute
import io.gomob.feature.auth.RegisterRoute
import io.gomob.scan.navigation.GomobNavHost

/**
 * Auth gate:
 * - 未登录 → 登录页 (可切到注册页)
 * - 已登录 → 5 tab 主 Shell
 */
@Composable
fun AppRoot(vm: AuthGateViewModel = hiltViewModel()) {
    val loggedIn by vm.isLoggedIn.collectAsStateWithLifecycle(initialValue = null)
    val sessionNotice by vm.sessionNotice.collectAsStateWithLifecycle(initialValue = null)
    var registerMode by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(sessionNotice) {
        if (!sessionNotice.isNullOrBlank()) {
            registerMode = false
        }
    }
    when (loggedIn) {
        null -> SplashLoading()
        false -> if (registerMode) {
            RegisterRoute(
                onBack = { registerMode = false },
                onRegistered = { registerMode = false },
            )
        } else {
            LoginRoute(
                onLoggedIn = { /* isLoggedIn flow 自动重组 */ },
                onGoRegister = { registerMode = true },
                sessionNotice = sessionNotice,
                onSessionNoticeShown = vm::clearSessionNotice,
            )
        }
        true -> GomobNavHost()
    }
}

@Composable
private fun SplashLoading() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Gomob.colors.bg0),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = Gomob.colors.accent)
    }
}
