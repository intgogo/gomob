package io.gomob.feature.collaboration

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

fun NavGraphBuilder.collaborationScreen() {
    composable(COLLAB_ROUTE) { CollaborationRoute() }
}
