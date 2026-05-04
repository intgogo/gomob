package io.gomob.feature.scan3d

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

fun NavGraphBuilder.scan3dScreen() {
    composable(SCAN3D_ROUTE) { Scan3dRoute() }
}
