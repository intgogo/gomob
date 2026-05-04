package io.gomob.feature.profile

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

fun NavGraphBuilder.profileScreen() {
    composable(PROFILE_ROUTE) { ProfileRoute() }
}
