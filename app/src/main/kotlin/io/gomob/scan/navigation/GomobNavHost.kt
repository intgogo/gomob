package io.gomob.scan.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavBackStackEntry
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
import io.gomob.feature.home.HomeAiChatRoute
import io.gomob.feature.home.HomeRoute
import io.gomob.feature.home.InspectionDetailRoute
import io.gomob.feature.message.ConversationRoute
import io.gomob.feature.message.ExpertDetailRoute
import io.gomob.feature.message.LocalVideoPreviewRoute
import io.gomob.feature.message.MessageEntryTab
import io.gomob.feature.message.MessageRoute
import io.gomob.feature.message.VideoCallMode
import io.gomob.feature.message.VideoCallRoute
import io.gomob.feature.profile.HistoryRoute
import io.gomob.feature.profile.ProfileAboutRoute
import io.gomob.feature.profile.ProfileAccountRoute
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
import io.gomob.feature.scan3d.VehicleContourScanRoute

private const val ROUTE_HOME = "home"
private const val ROUTE_MESSAGE = "message"
private const val ROUTE_SCAN3D = "scan3d"
private const val ROUTE_COLLAB = "collaboration"
private const val ROUTE_PROFILE = "profile"
private const val MESSAGE_TAB_REQUEST = "message_tab_request"
private const val MESSAGE_TAB_HELP = "help"
private const val MESSAGE_TAB_LIST = "list"
private const val IOS_PUSH_DURATION_MS = 400
private const val IOS_TAB_DURATION_MS = 140
private const val IOS_UNDERLAY_PARALLAX_DIVISOR = 3
private val IosEasing = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)

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
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val rootBottomPadding = if (imeVisible) 0.dp else Gomob.spacing.tabBarHeight

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Gomob.colors.bg0)
            .iosInteractiveBackGesture(enabled = !onTabRoot),
    ) {
        NavHost(
            navController = nav,
            startDestination = ROUTE_HOME,
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopStart,
            enterTransition = { gomobEnterTransition() },
            exitTransition = { gomobExitTransition() },
            popEnterTransition = { gomobPopEnterTransition() },
            popExitTransition = { gomobPopExitTransition() },
            sizeTransform = { null },
        ) {
            // ---- 首页 + 二级 ----
            composable(ROUTE_HOME) {
                RootTabPage(bottomPadding = rootBottomPadding) {
                    HomeRoute(
                        onOpenInspection = { id -> nav.navigate("home/inspection/$id") },
                        onOpenNewChat = { prompt ->
                            nav.navigate("home/chat/${Uri.encode(prompt)}")
                        },
                    )
                }
            }
            composable("home/chat/{prompt}") { entry ->
                HomeAiChatRoute(
                    initialPrompt = Uri.decode(entry.arguments?.getString("prompt").orEmpty()),
                    onBack = { nav.popBackStack() },
                )
            }
            composable("home/inspection/{id}") { entry ->
                InspectionDetailRoute(
                    inspectionId = entry.arguments?.getString("id") ?: "",
                    onBack = { nav.popBackStack() },
                )
            }

            // ---- 消息 + 二级 ----
            composable(ROUTE_MESSAGE) { entry ->
                RootTabPage(bottomPadding = rootBottomPadding) {
                    val requestedTabValue by entry.savedStateHandle
                        .getStateFlow(MESSAGE_TAB_REQUEST, "")
                        .collectAsStateWithLifecycle()
                    val requestedTab = when (requestedTabValue) {
                        MESSAGE_TAB_HELP -> MessageEntryTab.Help
                        MESSAGE_TAB_LIST -> MessageEntryTab.List
                        else -> null
                    }
                    MessageRoute(
                        requestedTab = requestedTab,
                        onRequestedTabConsumed = {
                            entry.savedStateHandle[MESSAGE_TAB_REQUEST] = ""
                        },
                        onOpenConversation = { id -> nav.navigate("message/conv/$id") },
                        onOpenLocalVideo = { title ->
                            nav.navigate("message/local-video/${Uri.encode(title)}")
                        },
                        onOpenExpertDetail = { id ->
                            entry.savedStateHandle[MESSAGE_TAB_REQUEST] = MESSAGE_TAB_HELP
                            nav.navigate("message/expert/$id")
                        },
                    )
                }
            }
            composable("message/expert/{id}") {
                ExpertDetailRoute(
                    onBack = { nav.popBackStack() },
                    onOpenConversation = { id -> nav.navigate("message/conv/$id") },
                    onOpenAudioVideo = { title ->
                        nav.navigate("message/local-video/${Uri.encode(title)}")
                    },
                )
            }
            composable("message/conv/{id}") { entry ->
                ConversationRoute(
                    conversationId = entry.arguments?.getString("id") ?: "",
                    onBack = { nav.popBackStack() },
                    onOpenLocalVideo = { title ->
                        nav.navigate("message/local-video/${Uri.encode(title)}")
                    },
                    onOpenVideoCall = { roomId, title, mode ->
                        nav.navigate(
                            "message/video-call/${Uri.encode(roomId)}/${mode.routeValue}/${Uri.encode(title)}",
                        )
                    },
                    onOpenInspection = { id ->
                        nav.navigate("home/inspection/${Uri.encode(id)}")
                    },
                )
            }
            composable("message/video-call/{roomId}/{mode}/{title}") { entry ->
                VideoCallRoute(
                    roomId = Uri.decode(entry.arguments?.getString("roomId").orEmpty()),
                    mode = Uri.decode(entry.arguments?.getString("mode").orEmpty())
                        .ifBlank { VideoCallMode.Callee.routeValue },
                    title = Uri.decode(entry.arguments?.getString("title").orEmpty()),
                    onBack = { nav.popBackStack() },
                )
            }
            composable("message/local-video/{title}") { entry ->
                LocalVideoPreviewRoute(
                    title = Uri.decode(entry.arguments?.getString("title").orEmpty()),
                    onBack = { nav.popBackStack() },
                )
            }

            // ---- 3D + 二级 ----
            composable(ROUTE_SCAN3D) {
                RootTabPage(bottomPadding = rootBottomPadding) {
                    Scan3dRoute(
                        // 车辆外廓扫描入口 → 8 角度 RGBD 采集子页
                        onOpenContourScan = { nav.navigate("scan3d/vehicle") },
                        // 设备卡入口 → 深度相机详情页（看设备 / 调控制 / 标定 — 不含扫描动作）
                        onOpenDepthCamera = { nav.navigate("scan3d/depth-camera") },
                        // VIN 数码拓印入口
                        onOpenVinRectify = { nav.navigate("scan3d/scan") },
                    )
                }
            }
            composable("scan3d/calibration") {
                CalibrationRoute(onBack = { nav.popBackStack() })
            }
            composable("scan3d/scan") {
                ScanCaptureRoute(onBack = { nav.popBackStack() })
            }
            composable("scan3d/vehicle") {
                VehicleContourScanRoute(onBack = { nav.popBackStack() })
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
                RootTabPage(bottomPadding = rootBottomPadding) {
                    CollaborationRoute(
                        onOpenReview = { id -> nav.navigate("collaboration/review/$id") },
                        onOpenLiveStream = { id -> nav.navigate("collaboration/fpv/$id") },
                    )
                }
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
                RootTabPage(bottomPadding = rootBottomPadding) {
                    ProfileRoute(
                        onOpenPersonal = { nav.navigate("profile/personal") },
                        onOpenAccount = { nav.navigate("profile/account") },
                        onOpenNotification = { nav.navigate("profile/notification") },
                        onOpenAbout = { nav.navigate("profile/about") },
                        onOpenHistory = { nav.navigate("profile/history") },
                    )
                }
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
            composable("profile/notification") {
                ProfileNotificationRoute(onBack = { nav.popBackStack() })
            }
            composable("profile/about") {
                ProfileAboutRoute(onBack = { nav.popBackStack() })
            }
        }
        AnimatedVisibility(
            visible = onTabRoot && !imeVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(animationSpec = tween(IOS_TAB_DURATION_MS, easing = IosEasing)),
            exit = fadeOut(animationSpec = tween(IOS_TAB_DURATION_MS, easing = IosEasing)),
        ) {
            TabBarVector(
                items = TABS,
                selectedKey = currentRoute ?: ROUTE_HOME,
                onSelect = { key -> nav.switchTab(key) },
            )
        }
    }
}

@Composable
private fun RootTabPage(
    bottomPadding: Dp,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(bottom = bottomPadding)) {
        content()
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

private fun AnimatedContentTransitionScope<NavBackStackEntry>.gomobEnterTransition(): EnterTransition =
    if (isRootTabTransition()) {
        fadeIn(animationSpec = tween(IOS_TAB_DURATION_MS, easing = IosEasing))
    } else {
        slideInHorizontally(
            animationSpec = tween(IOS_PUSH_DURATION_MS, easing = IosEasing),
            initialOffsetX = { width -> width },
        )
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.gomobExitTransition(): ExitTransition =
    if (isRootTabTransition()) {
        fadeOut(animationSpec = tween(IOS_TAB_DURATION_MS, easing = IosEasing))
    } else {
        slideOutHorizontally(
            animationSpec = tween(IOS_PUSH_DURATION_MS, easing = IosEasing),
            targetOffsetX = { width -> -width / IOS_UNDERLAY_PARALLAX_DIVISOR },
        )
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.gomobPopEnterTransition(): EnterTransition =
    if (isRootTabTransition()) {
        fadeIn(animationSpec = tween(IOS_TAB_DURATION_MS, easing = IosEasing))
    } else {
        slideInHorizontally(
            animationSpec = tween(IOS_PUSH_DURATION_MS, easing = LinearEasing),
            initialOffsetX = { width -> -width / IOS_UNDERLAY_PARALLAX_DIVISOR },
        )
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.gomobPopExitTransition(): ExitTransition =
    if (isRootTabTransition()) {
        fadeOut(animationSpec = tween(IOS_TAB_DURATION_MS, easing = IosEasing))
    } else {
        slideOutHorizontally(
            animationSpec = tween(IOS_PUSH_DURATION_MS, easing = LinearEasing),
            targetOffsetX = { width -> width },
        )
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.isRootTabTransition(): Boolean =
    initialState.destination.route.isRootTabRoute() && targetState.destination.route.isRootTabRoute()

private fun String?.isRootTabRoute(): Boolean = this in TAB_ROUTES
