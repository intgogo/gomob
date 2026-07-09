package io.gomob.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.gomob.data.prefs.ThemeMode
import io.gomob.designsystem.motion.DefaultPageDragBox
import io.gomob.designsystem.theme.Gomob
import io.gomob.designsystem.theme.GomobTheme
import io.gomob.feature.profile.AppearanceViewModel
import io.gomob.nativebridge.berxel.BerxelService
import io.gomob.nativebridge.berxel.BerxelStreamProfiles
import javax.inject.Inject
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var berxelService: BerxelService

    private var launchBackdropVisible by mutableStateOf(true)
    private var launchBackdropMayHide by mutableStateOf(true)
    private var appContentReady by mutableStateOf(false)
    private var holdLaunchBackdropForInspection by mutableStateOf(false)
    private var debugRouteRequest by mutableStateOf<String?>(null)
    private var systemBarsPaddingRequired by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Gomob)
        super.onCreate(savedInstanceState)
        holdLaunchBackdropForInspection = isLaunchBackdropHoldRequested(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
        Log.i("GomobApplication", "MainActivity onCreate action=${intent?.action.orEmpty()}")
        consumeDebugBerxelIntent(intent)
        consumeUsbAttachIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val appearance: AppearanceViewModel = hiltViewModel()
            val mode by appearance.mode.collectAsStateWithLifecycle()
            val colorScheme by appearance.colorScheme.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (mode) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.System -> systemDark
            }
            // 注意：launchBackdropVisible 必须在 composition 作用域读取，
            // 在 SideEffect lambda 内读不会建立 snapshot 订阅，splash 关闭时不会重跑。
            val effectiveDark = launchBackdropVisible || darkTheme || !systemBarsPaddingRequired
            SideEffect {
                val transparent = android.graphics.Color.TRANSPARENT
                // 用当前真实背景语义作为 detectDarkMode，避免系统暗黑模式和 App 浅色模式错配。
                // 视频铺到系统栏背后时底层是黑色视频，需要保持浅色状态栏图标。
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(transparent, transparent) { effectiveDark },
                    navigationBarStyle = SystemBarStyle.auto(transparent, transparent) { effectiveDark },
                )
            }
            GomobTheme(darkTheme = darkTheme, colorScheme = colorScheme) {
                // 真 edge-to-edge：不再全局吃 systemBars —— 玻璃 TabBar / Header 延伸到
                // 系统栏底下，各屏经 GlassHeaderScaffold / TabBar 自己处理 inset。
                // systemBarsPaddingRequired 仅保留"视频沉浸页"语义，驱动状态栏图标配色。
                val appFrameModifier = Modifier.fillMaxSize()
                LaunchedEffect(
                    launchBackdropVisible,
                    launchBackdropMayHide,
                    appContentReady,
                    holdLaunchBackdropForInspection,
                ) {
                    if (
                        launchBackdropVisible &&
                        launchBackdropMayHide &&
                        appContentReady &&
                        !holdLaunchBackdropForInspection
                    ) {
                        withFrameNanos { }
                        delay(850)
                        if (launchBackdropMayHide && appContentReady && !holdLaunchBackdropForInspection) {
                            launchBackdropVisible = false
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Gomob.colors.bg0),
                ) {
                    Box(
                        modifier = appFrameModifier,
                    ) {
                        Box(Modifier.fillMaxSize().clipToBounds()) {
                            DefaultPageDragBox(Modifier.fillMaxSize()) {
                                AppRoot(
                                    onContentReadinessChanged = { ready ->
                                        appContentReady = ready
                                        if (!ready) {
                                            launchBackdropVisible = true
                                            systemBarsPaddingRequired = true
                                        }
                                    },
                                    debugRouteRequest = debugRouteRequest,
                                    onDebugRouteConsumed = { debugRouteRequest = null },
                                    onSystemBarsPaddingRequiredChanged = { required ->
                                        systemBarsPaddingRequired = required
                                    },
                                )
                            }
                        }
                    }
                    if (launchBackdropVisible) {
                        SplashLoading()
                    }
                }
            }
        }
    }

    override fun onUserLeaveHint() {
        launchBackdropMayHide = false
        launchBackdropVisible = true
        super.onUserLeaveHint()
    }

    override fun onResume() {
        super.onResume()
        launchBackdropMayHide = true
    }

    override fun onStop() {
        launchBackdropMayHide = false
        launchBackdropVisible = true
        super.onStop()
    }

    /**
     * 处理 USB_DEVICE_ATTACHED intent —— manifest filter 路径下 Android 把 UsbDevice 塞 extras 里。
     *
     * 关键: HONOR Magic OS / Android 15 实测，`usbManager.deviceList` 取到的 UsbDevice **没**
     * USB 读权限（即使 dumpsys 看到 device_permissions 有我们的 uid），但 intent extras 里的
     * UsbDevice **有**权限。所以必须从这里抽出来，喂给 BerxelService。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        holdLaunchBackdropForInspection = isLaunchBackdropHoldRequested(intent)
        if (holdLaunchBackdropForInspection) {
            launchBackdropVisible = true
        }
        consumeDebugBerxelIntent(intent)
        consumeUsbAttachIntent(intent)
    }

    private fun consumeDebugBerxelIntent(intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        when (intent.action) {
            ACTION_DEBUG_BERXEL_START -> {
                val stream = intent.getStringExtra(EXTRA_DEBUG_STREAM)?.lowercase()
                val profileName = intent.getStringExtra(EXTRA_DEBUG_PROFILE)?.lowercase()
                if (!ensureCameraPermissionForDebugBerxel()) return
                if (intent.hasExtra(EXTRA_DEBUG_MASTER_RGB)) {
                    berxelService.setNativeMasterStreamForDebug(
                        intent.getBooleanExtra(EXTRA_DEBUG_MASTER_RGB, false),
                    )
                }
                if (intent.hasExtra(EXTRA_DEBUG_MIX_STRATEGY)) {
                    berxelService.setNativeMixStrategyForDebug(
                        intent.getStringExtra(EXTRA_DEBUG_MIX_STRATEGY),
                    )
                }
                intent.getStringExtra(EXTRA_DEBUG_ROUTE)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { debugRouteRequest = it }
                profileName?.let(BerxelStreamProfiles::fromName)?.let(berxelService::setStreamProfile)
                Log.i(DEBUG_BERXEL_TAG, "debug 启动 Berxel stream=${stream ?: "dual"} profile=${profileName ?: "default"}")
                when (stream) {
                    "color" -> berxelService.startColorOnlyForDebug()
                    "depth" -> berxelService.startDepthOnlyForDebug()
                    else -> berxelService.start()
                }
            }
            ACTION_DEBUG_BERXEL_STOP -> {
                Log.i(DEBUG_BERXEL_TAG, "debug 停止 Berxel")
                berxelService.stop()
            }
        }
    }

    private fun ensureCameraPermissionForDebugBerxel(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return true
        Log.i(DEBUG_BERXEL_TAG, "debug Berxel 需要 CAMERA 权限，拉起系统授权弹窗")
        requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_DEBUG_CAMERA_PERMISSION)
        return false
    }

    private fun consumeUsbAttachIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        berxelService.attachAuthorizedDevice(device)
    }

    private fun isLaunchBackdropHoldRequested(intent: Intent?): Boolean =
        BuildConfig.DEBUG && intent?.getBooleanExtra(EXTRA_HOLD_SPLASH, false) == true

    private companion object {
        private const val EXTRA_HOLD_SPLASH = "gomob.debug.HOLD_SPLASH"
        private const val DEBUG_BERXEL_TAG = "DebugBerxelActivity"
        private const val ACTION_DEBUG_BERXEL_START = "io.gomob.scan.debug.DEBUG_BERXEL_START"
        private const val ACTION_DEBUG_BERXEL_STOP = "io.gomob.scan.debug.DEBUG_BERXEL_STOP"
        private const val EXTRA_DEBUG_STREAM = "stream"
        private const val EXTRA_DEBUG_PROFILE = "profile"
        private const val EXTRA_DEBUG_MASTER_RGB = "master_rgb"
        private const val EXTRA_DEBUG_MIX_STRATEGY = "mix_strategy"
        private const val EXTRA_DEBUG_ROUTE = "route"
        private const val REQUEST_DEBUG_CAMERA_PERMISSION = 0xB3
    }
}
