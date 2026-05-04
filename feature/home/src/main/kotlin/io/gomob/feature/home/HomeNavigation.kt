package io.gomob.feature.home

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

fun NavGraphBuilder.homeScreen(
    onStartScan: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenCalibration: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    composable(HOME_ROUTE) {
        HomeRoute(
            onStartScan = onStartScan,
            onOpenGallery = onOpenGallery,
            onOpenCalibration = onOpenCalibration,
            onOpenSettings = onOpenSettings,
        )
    }
}
