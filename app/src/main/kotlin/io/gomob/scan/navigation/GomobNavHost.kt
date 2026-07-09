package io.gomob.scan.navigation

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import dev.chrisbanes.haze.HazeState
import io.gomob.designsystem.component.TabBarVector
import io.gomob.designsystem.component.TabItemVector
import io.gomob.designsystem.glass.LocalContentBottomInset
import io.gomob.designsystem.glass.LocalHazeState
import io.gomob.designsystem.theme.Gomob
import io.gomob.feature.collaboration.CollaborationRoute
import io.gomob.feature.collaboration.FirstPersonViewerRoute
import io.gomob.feature.collaboration.ReviewDetailRoute
import io.gomob.feature.home.ChatHistoryRoute
import io.gomob.feature.home.HomeAiChatRoute
import io.gomob.feature.home.HomeRoute
import io.gomob.feature.home.InspectionDetailRoute
import io.gomob.feature.message.ConversationRoute
import io.gomob.feature.message.ConversationInfoRoute
import io.gomob.feature.message.ChatSearchRoute
import io.gomob.feature.message.ContactDetailRoute
import io.gomob.feature.message.ContactsRoute
import io.gomob.feature.message.ExpertDetailRoute
import io.gomob.feature.message.IncomingCallOverlay
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
import io.gomob.feature.profile.ThemeSettingsRoute
import io.gomob.feature.scan3d.CalibrationRoute
import io.gomob.feature.scan3d.DepthCameraCalibrationRoute
import io.gomob.feature.scan3d.DepthCameraControlsRoute
import io.gomob.feature.scan3d.DepthCameraInfoRoute
import io.gomob.feature.scan3d.DepthCameraRoute
import io.gomob.feature.scan3d.Scan3dRecordingRoute
import io.gomob.feature.scan3d.Scan3dRoute
import io.gomob.feature.scan3d.ScanCaptureRoute
import io.gomob.feature.scan3d.SonixDebugRoute
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
    TabItemVector(ROUTE_HOME, "助手", Icons.Filled.AutoAwesome),
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
fun GomobNavHost(
    debugRouteRequest: String? = null,
    onDebugRouteConsumed: () -> Unit = {},
    onSystemBarsPaddingRequiredChanged: (Boolean) -> Unit = {},
) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val currentOnSystemBarsPaddingRequiredChanged by rememberUpdatedState(onSystemBarsPaddingRequiredChanged)
    val systemBarsPaddingRequired = !currentRoute.isEdgeToEdgeVideoRoute()
    val onTabRoot = currentRoute in TAB_ROUTES
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    // TabBar 总高 = 56dp + 导航栏 inset（玻璃条延伸到导航栏底下）
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val tabChromeHeight = Gomob.spacing.tabBarHeight + navBarBottom
    val stableRootBottomPadding = tabChromeHeight
    val rootBottomPadding by animateDpAsState(
        targetValue = if (imeVisible) 0.dp else tabChromeHeight,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "root-bottom-padding",
    )
    // 全 App 共享 HazeState：采样源是各屏 GlassHeaderScaffold 的内容层(经 LocalHazeState 下发),
    // TabBar / Header / 来电浮窗都消费同一个 state。不在 NavHost 上再挂源 —— 源嵌套会互相录空。
    val shellHaze = remember { HazeState() }
    LaunchedEffect(debugRouteRequest) {
        val route = debugRouteRequest?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        nav.navigate(route) {
            launchSingleTop = true
        }
        onDebugRouteConsumed()
    }
    LaunchedEffect(systemBarsPaddingRequired) {
        currentOnSystemBarsPaddingRequiredChanged(systemBarsPaddingRequired)
    }

    CompositionLocalProvider(LocalHazeState provides shellHaze) {
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
                RootTabPage(bottomPadding = stableRootBottomPadding) {
                    HomeRoute(
                        onOpenInspection = { id -> nav.navigate("home/inspection/$id") },
                        onOpenNewChat = { prompt, token ->
                            if (token != null) {
                                nav.navigate("home/chat/${Uri.encode(prompt)}/img/${Uri.encode(token)}")
                            } else {
                                nav.navigate("home/chat/${Uri.encode(prompt)}")
                            }
                        },
                        onOpenAgent = { key ->
                            nav.navigate("home/agent/${Uri.encode(key)}")
                        },
                        onOpenHistory = { nav.navigate("home/history") },
                    )
                }
            }
            composable("home/history") {
                ChatHistoryRoute(onBack = { nav.popBackStack() })
            }
            composable("home/chat/{prompt}") { entry ->
                HomeAiChatRoute(
                    initialPrompt = Uri.decode(entry.arguments?.getString("prompt").orEmpty()),
                    imageToken = null,
                    onBack = { nav.popBackStack() },
                )
            }
            composable("home/chat/{prompt}/img/{image}") { entry ->
                HomeAiChatRoute(
                    initialPrompt = Uri.decode(entry.arguments?.getString("prompt").orEmpty()),
                    imageToken = Uri.decode(entry.arguments?.getString("image").orEmpty()),
                    onBack = { nav.popBackStack() },
                )
            }
            composable("home/agent/{key}") { entry ->
                HomeAiChatRoute(
                    initialPrompt = "",
                    imageToken = null,
                    agentKey = Uri.decode(entry.arguments?.getString("key").orEmpty()),
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
                        onOpenConversationTarget = { id, localKey ->
                            nav.navigate("message/conv/${Uri.encode(id)}/target/${Uri.encode(localKey)}")
                        },
                        onOpenHelpSearch = { id ->
                            nav.navigate("message/conv/${Uri.encode(id)}/search")
                        },
                        onOpenLocalVideo = { title ->
                            nav.navigate("message/local-video/${Uri.encode(title)}")
                        },
                        onOpenExpertDetail = { id ->
                            entry.savedStateHandle[MESSAGE_TAB_REQUEST] = MESSAGE_TAB_HELP
                            nav.navigate("message/expert/$id")
                        },
                        onOpenContactDetail = { id ->
                            nav.navigate("message/contact/${Uri.encode(id)}")
                        },
                        onOpenVideoCall = { roomId, title, mode ->
                            nav.navigate(
                                "message/video-call/${Uri.encode(roomId)}/${mode.routeValue}/${Uri.encode(title)}",
                            )
                        },
                        onOpenContacts = { nav.navigate("message/contacts") },
                    )
                }
            }
            composable("message/contacts") {
                ContactsRoute(
                    onBack = { nav.popBackStack() },
                    onOpenContactDetail = { id ->
                        nav.navigate("message/contact/${Uri.encode(id)}")
                    },
                )
            }
            composable("message/contact/{id}") {
                ContactDetailRoute(
                    onBack = { nav.popBackStack() },
                    onOpenConversation = { id -> nav.navigate("message/conv/$id") },
                    onOpenAudioVideo = { title ->
                        nav.navigate("message/local-video/${Uri.encode(title)}")
                    },
                )
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
                    targetLocalKey = null,
                    onBack = { nav.popBackStack() },
                    onOpenSearch = { id ->
                        nav.navigate("message/conv/${Uri.encode(id)}/search")
                    },
                    onOpenInfo = { id ->
                        nav.navigate("message/conv/${Uri.encode(id)}/info")
                    },
                    onOpenUserDetail = { id ->
                        nav.navigate("message/contact/${Uri.encode(id)}")
                    },
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
            composable("message/conv/{id}/target/{localKey}") { entry ->
                ConversationRoute(
                    conversationId = entry.arguments?.getString("id") ?: "",
                    targetLocalKey = Uri.decode(entry.arguments?.getString("localKey").orEmpty()),
                    onBack = { nav.popBackStack() },
                    onOpenSearch = { id ->
                        nav.navigate("message/conv/${Uri.encode(id)}/search")
                    },
                    onOpenInfo = { id ->
                        nav.navigate("message/conv/${Uri.encode(id)}/info")
                    },
                    onOpenUserDetail = { id ->
                        nav.navigate("message/contact/${Uri.encode(id)}")
                    },
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
            composable("message/conv/{id}/info") {
                ConversationInfoRoute(
                    onBack = { nav.popBackStack() },
                    onOpenSearch = { id ->
                        nav.navigate("message/conv/${Uri.encode(id)}/search")
                    },
                    onOpenUserDetail = { id ->
                        nav.navigate("message/contact/${Uri.encode(id)}")
                    },
                    onLeaveCompleted = {
                        if (!nav.popBackStack(ROUTE_MESSAGE, inclusive = false)) {
                            nav.navigate(ROUTE_MESSAGE) {
                                launchSingleTop = true
                            }
                        }
                    },
                )
            }
            composable("message/conv/{id}/search") { entry ->
                val conversationId = entry.arguments?.getString("id").orEmpty()
                ChatSearchRoute(
                    onBack = { nav.popBackStack() },
                    onOpenMessage = { localKey ->
                        nav.navigate(
                            "message/conv/${Uri.encode(conversationId)}/target/${Uri.encode(localKey)}",
                        ) {
                            popUpTo(ROUTE_MESSAGE)
                        }
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
                        // 设备卡长按 → 直达 Sonix 调试页，跳过 DepthCameraScreen 触发 BerxelService 双流导致的 vivo USB kill
                        onOpenSonixDebug = { nav.navigate("scan3d/depth-camera/sonix-debug") },
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
                    onOpenSonixDebug = { nav.navigate("scan3d/depth-camera/sonix-debug") },
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
            composable("scan3d/depth-camera/sonix-debug") {
                SonixDebugRoute(onBack = { nav.popBackStack() })
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
                    onOpenInspector = { userId ->
                        nav.navigate("message/contact/${Uri.encode(userId)}")
                    },
                )
            }

            // ---- 我的 + 二级 ----
            composable(ROUTE_PROFILE) {
                RootTabPage(bottomPadding = rootBottomPadding) {
                    ProfileRoute(
                        onOpenPersonal = { nav.navigate("profile/personal/0") },
                        onOpenCases = { nav.navigate("profile/personal/1") },
                        onOpenAccount = { nav.navigate("profile/account") },
                        onOpenNotification = { nav.navigate("profile/notification") },
                        onOpenAbout = { nav.navigate("profile/about") },
                        onOpenHistory = { nav.navigate("profile/history") },
                        onOpenTheme = { nav.navigate("profile/theme") },
                    )
                }
            }
            composable("profile/theme") {
                ThemeSettingsRoute(onBack = { nav.popBackStack() })
            }
            composable("profile/history") {
                HistoryRoute(onBack = { nav.popBackStack() })
            }
            composable("profile/personal") {
                ProfilePersonalRoute(onBack = { nav.popBackStack() }, initialTab = 0)
            }
            composable("profile/personal/{tab}") { entry ->
                ProfilePersonalRoute(
                    onBack = { nav.popBackStack() },
                    initialTab = entry.arguments?.getString("tab")?.toIntOrNull() ?: 0,
                )
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
        // 全局来电浮窗：无论当前在哪个 tab，都能弹接听 / 拒绝。
        // 当前是已登录主壳层，所以与 AuthGate 解耦放在这里，正好能拿到 navController 做 deep link。
        // 已在 video-call 路由上时不再叠加，避免接听后还看到 banner。
        if (currentRoute?.startsWith("message/video-call") != true) {
            IncomingCallOverlay(
                onAccept = { invite ->
                    val roomId = invite.roomId?.takeIf { it.isNotBlank() } ?: return@IncomingCallOverlay
                    nav.navigate(
                        "message/video-call/${Uri.encode(roomId)}/${VideoCallMode.Callee.routeValue}/${Uri.encode(invite.title)}",
                    )
                },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
    }
}

/**
 * root tab 屏容器：不再用 padding 挡住 TabBar —— 内容占满全屏从玻璃 TabBar
 * 底下穿过，避让高度经 [LocalContentBottomInset] 下发给各屏的滚动 contentPadding。
 */
@Composable
private fun RootTabPage(
    bottomPadding: Dp,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalContentBottomInset provides bottomPadding) {
        Box(Modifier.fillMaxSize()) {
            content()
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

private fun String?.isEdgeToEdgeVideoRoute(): Boolean =
    this?.startsWith("message/video-call") == true ||
        this?.startsWith("collaboration/fpv") == true
