package io.gomob.scan.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import io.gomob.feature.calibration.CALIBRATION_ROUTE
import io.gomob.feature.calibration.calibrationScreen
import io.gomob.feature.gallery.GALLERY_ROUTE
import io.gomob.feature.gallery.galleryScreen
import io.gomob.feature.home.HOME_ROUTE
import io.gomob.feature.home.homeScreen
import io.gomob.feature.scan.SCAN_ROUTE
import io.gomob.feature.scan.scanScreen
import io.gomob.feature.settings.SETTINGS_ROUTE
import io.gomob.feature.settings.settingsScreen

/**
 * 顶层 NavHost — 把 feature 模块各自暴露的 NavGraph 拼装起来。
 *
 * 路由层级（M0 阶段，全部 top-level）:
 *   home (落地)
 *    ├─ scan
 *    ├─ gallery
 *    ├─ calibration
 *    └─ settings
 *
 * 后续 M1+ 加 sub-route 时，feature 模块各自维护自己的 nested NavGraph，
 * 这里只调它们的扩展函数；保持 app 模块的薄壳定位。
 */
@Composable
fun GomobNavHost() {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = HOME_ROUTE) {
        homeScreen(
            onStartScan = { navController.navigate(SCAN_ROUTE) },
            onOpenGallery = { navController.navigate(GALLERY_ROUTE) },
            onOpenCalibration = { navController.navigate(CALIBRATION_ROUTE) },
            onOpenSettings = { navController.navigate(SETTINGS_ROUTE) },
        )
        scanScreen(onBack = { navController.popBackStack() })
        galleryScreen(onBack = { navController.popBackStack() })
        calibrationScreen(onBack = { navController.popBackStack() })
        settingsScreen(onBack = { navController.popBackStack() })
    }
}
