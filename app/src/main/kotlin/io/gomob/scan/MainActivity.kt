package io.gomob.scan

import android.content.Intent
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.res.colorResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.gomob.data.prefs.ThemeMode
import io.gomob.designsystem.motion.DefaultPageDragBox
import io.gomob.designsystem.theme.Gomob
import io.gomob.designsystem.theme.GomobTheme
import io.gomob.feature.profile.AppearanceViewModel
import io.gomob.nativebridge.berxel.BerxelService
import javax.inject.Inject
import kotlinx.coroutines.delay

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var berxelService: BerxelService

    private var launchBackdropVisible by mutableStateOf(true)
    private var launchBackdropMayHide by mutableStateOf(true)
    private var appContentReady by mutableStateOf(false)
    private var holdLaunchBackdropForInspection by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Gomob)
        super.onCreate(savedInstanceState)
        holdLaunchBackdropForInspection = isLaunchBackdropHoldRequested(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false)
        }
        Log.i("GomobApplication", "MainActivity onCreate action=${intent?.action.orEmpty()}")
        consumeUsbAttachIntent(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val appearance: AppearanceViewModel = hiltViewModel()
            val mode by appearance.mode.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (mode) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.System -> systemDark
            }
            SideEffect {
                val transparent = android.graphics.Color.TRANSPARENT
                if (launchBackdropVisible || darkTheme) {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.dark(transparent),
                        navigationBarStyle = SystemBarStyle.dark(transparent),
                    )
                } else {
                    enableEdgeToEdge(
                        statusBarStyle = SystemBarStyle.light(transparent, transparent),
                        navigationBarStyle = SystemBarStyle.light(transparent, transparent),
                    )
                }
            }
            GomobTheme(darkTheme = darkTheme) {
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
                        .background(colorResource(R.color.splash_background)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Gomob.colors.bg0)
                            .windowInsetsPadding(WindowInsets.systemBars),
                    ) {
                        Box(Modifier.fillMaxSize().clipToBounds()) {
                            DefaultPageDragBox(Modifier.fillMaxSize()) {
                                AppRoot(
                                    onContentReadinessChanged = { ready ->
                                        appContentReady = ready
                                        if (!ready) {
                                            launchBackdropVisible = true
                                        }
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
        consumeUsbAttachIntent(intent)
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
    }
}
