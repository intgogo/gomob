package io.gomob.scan.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController

/**
 * 顶层导航。后续每个 feature 模块各自暴露 `<feature>NavGraph` 扩展函数，由这里串起来。
 *
 * 当前是占位骨架；feature 模块就绪后逐个接入：
 *   - feature:scan        扫描主流程
 *   - feature:gallery     历史扫描
 *   - feature:calibration 标定
 *   - feature:settings    设置
 */
@Composable
fun GomobNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "placeholder") {
        composable("placeholder") {
            io.gomob.ui.PlaceholderScreen(title = "gomob — 3D 扫描")
        }
    }
}
