package io.gomob.scan.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.gomob.designsystem.component.TabBarVector
import io.gomob.designsystem.component.TabItemVector
import io.gomob.designsystem.theme.Gomob
import io.gomob.feature.collaboration.CollaborationRoute
import io.gomob.feature.collaboration.FirstPersonViewerRoute
import io.gomob.feature.collaboration.ReviewDetailRoute
import io.gomob.feature.home.HomeRoute
import io.gomob.feature.home.InspectionDetailRoute
import io.gomob.feature.message.ConversationRoute
import io.gomob.feature.message.MessageRoute
import io.gomob.feature.profile.HistoryRoute
import io.gomob.feature.profile.ProfileAboutRoute
import io.gomob.feature.profile.ProfileAccountRoute
import io.gomob.feature.profile.ProfileNetworkRoute
import io.gomob.feature.profile.ProfileNotificationRoute
import io.gomob.feature.profile.ProfilePersonalRoute
import io.gomob.feature.profile.ProfileRoute
import io.gomob.feature.scan3d.CalibrationRoute
import io.gomob.feature.scan3d.DepthCameraCalibrationRoute
import io.gomob.feature.scan3d.DepthCameraControlsRoute
import io.gomob.feature.scan3d.DepthCameraInfoRoute
import io.gomob.feature.scan3d.DepthCameraRoute
import io.gomob.feature.scan3d.Scan3dRecordingRoute
import io.gomob.feature.scan3d.Scan3dRoute
import io.gomob.feature.scan3d.ScanCaptureRoute

private const val ROUTE_HOME = "home"
private const val ROUTE_MESSAGE = "message"
private const val ROUTE_SCAN3D = "scan3d"
private const val ROUTE_COLLAB = "collaboration"
private const val ROUTE_PROFILE = "profile"

private val TABS = listOf(
    TabItemVector(ROUTE_HOME, "首页", Icons.Filled.AutoAwesome),
    TabItemVector(ROUTE_MESSAGE, "消息", Icons.Filled.ChatBubble),
    TabItemVector(ROUTE_SCAN3D, "3D", Icons.Filled.ViewInAr),
    TabItemVector(ROUTE_COLLAB, "协作", Icons.Filled.GroupWork),
    TabItemVector(ROUTE_PROFILE, "我的", Icons.Filled.Person),
)

private val TAB_ROUTES = TABS.map { it.key }.toSet()

/**
 * 顶层 Shell — 5 tab(root) + 各 tab 内嵌二级页面。
 *
 * TabBar 仅在 root 路由显示;二级页面 push 到当前 tab 的栈,自带 BackHeader。
 */
@Composable
fun GomobNavHost() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val onTabRoot = currentRoute in TAB_ROUTES

    Column(modifier = Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            NavHost(navController = nav, startDestination = ROUTE_HOME) {
                // ---- 首页 + 二级 ----
                composable(ROUTE_HOME) {
                    HomeRoute(onOpenInspection = { id -> nav.navigate("home/inspection/$id") })
                }
                composable("home/inspection/{id}") { entry ->
                    InspectionDetailRoute(
                        inspectionId = entry.arguments?.getString("id") ?: "",
                        onBack = { nav.popBackStack() },
                    )
                }

                // ---- 消息 + 二级 ----
                composable(ROUTE_MESSAGE) {
                    MessageRoute(onOpenConversation = { id -> nav.navigate("message/conv/$id") })
                }
                composable("message/conv/{id}") { entry ->
                    ConversationRoute(
                        conversationId = entry.arguments?.getString("id") ?: "",
                        onBack = { nav.popBackStack() },
                    )
                }

                // ---- 3D + 二级 ----
                composable(ROUTE_SCAN3D) {
                    Scan3dRoute(
                        // 三维外廓扫描入口 → 直接进 RecordingScreen（自包含预览 + 开始/停止）
                        onOpenContourScan = { nav.navigate("scan3d/recording") },
                        // 设备卡入口 → 深度相机详情页（看设备 / 调控制 / 标定 — 不含扫描动作）
                        onOpenDepthCamera = { nav.navigate("scan3d/depth-camera") },
                        // VIN 数码拓印入口 → 暂用 ScanCaptureRoute (VIN 风格 stub)；M4 实施时换真路由
                        onOpenVinRectify = { nav.navigate("scan3d/scan") },
                    )
                }
                composable("scan3d/calibration") {
                    CalibrationRoute(onBack = { nav.popBackStack() })
                }
                composable("scan3d/scan") {
                    ScanCaptureRoute(onBack = { nav.popBackStack() })
                }
                composable("scan3d/depth-camera") {
                    DepthCameraRoute(
                        onBack = { nav.popBackStack() },
                        onOpenInfo = { nav.navigate("scan3d/depth-camera/info") },
                        onOpenControls = { nav.navigate("scan3d/depth-camera/controls") },
                        onOpenCalibration = { nav.navigate("scan3d/depth-camera/calibration") },
                    )
                }
                composable("scan3d/depth-camera/info") {
                    DepthCameraInfoRoute(onBack = { nav.popBackStack() })
                }
                composable("scan3d/depth-camera/controls") {
                    DepthCameraControlsRoute(onBack = { nav.popBackStack() })
                }
                composable("scan3d/depth-camera/calibration") {
                    DepthCameraCalibrationRoute(onBack = { nav.popBackStack() })
                }
                composable("scan3d/recording") {
                    Scan3dRecordingRoute(onBack = { nav.popBackStack() })
                }

                // ---- 协作 + 二级 ----
                composable(ROUTE_COLLAB) {
                    CollaborationRoute(
                        onOpenReview = { id -> nav.navigate("collaboration/review/$id") },
                        onOpenLiveStream = { id -> nav.navigate("collaboration/fpv/$id") },
                    )
                }
                composable("collaboration/review/{id}") { entry ->
                    ReviewDetailRoute(
                        reviewId = entry.arguments?.getString("id") ?: "",
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("collaboration/fpv/{id}") { entry ->
                    FirstPersonViewerRoute(
                        streamId = entry.arguments?.getString("id") ?: "",
                        onBack = { nav.popBackStack() },
                    )
                }

                // ---- 我的 + 二级 ----
                composable(ROUTE_PROFILE) {
                    ProfileRoute(
                        onOpenPersonal = { nav.navigate("profile/personal") },
                        onOpenAccount = { nav.navigate("profile/account") },
                        onOpenNetwork = { nav.navigate("profile/network") },
                        onOpenNotification = { nav.navigate("profile/notification") },
                        onOpenAbout = { nav.navigate("profile/about") },
                        onOpenHistory = { nav.navigate("profile/history") },
                    )
                }
                composable("profile/history") {
                    HistoryRoute(onBack = { nav.popBackStack() })
                }
                composable("profile/personal") {
                    ProfilePersonalRoute(onBack = { nav.popBackStack() })
                }
                composable("profile/account") {
                    ProfileAccountRoute(onBack = { nav.popBackStack() })
                }
                composable("profile/network") {
                    ProfileNetworkRoute(onBack = { nav.popBackStack() })
                }
                composable("profile/notification") {
                    ProfileNotificationRoute(onBack = { nav.popBackStack() })
                }
                composable("profile/about") {
                    ProfileAboutRoute(onBack = { nav.popBackStack() })
                }
            }
        }
        if (onTabRoot) {
            TabBarVector(
                items = TABS,
                selectedKey = currentRoute ?: ROUTE_HOME,
                onSelect = { key -> nav.switchTab(key) },
            )
        }
    }
}

/** 切 tab:回到目标 tab 的 root,清空当前 tab 内的二级栈,避免 backstack 累积。 */
private fun NavController.switchTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
