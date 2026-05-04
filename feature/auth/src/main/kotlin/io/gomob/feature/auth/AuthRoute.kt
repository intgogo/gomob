package io.gomob.feature.auth

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

const val AUTH_GRAPH_ROUTE = "auth"

fun NavGraphBuilder.loginScreen(
    onLoggedIn: () -> Unit,
    onGoRegister: () -> Unit,
) {
    composable(LOGIN_ROUTE) {
        LoginRoute(onLoggedIn = onLoggedIn, onGoRegister = onGoRegister)
    }
}
