package io.gomob.feature.home

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

fun NavGraphBuilder.homeScreen() {
    composable(HOME_ROUTE) { HomeRoute() }
}
