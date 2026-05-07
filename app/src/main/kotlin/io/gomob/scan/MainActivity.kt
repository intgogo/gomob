package io.gomob.scan

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.gomob.data.prefs.ThemeMode
import io.gomob.designsystem.theme.Gomob
import io.gomob.designsystem.theme.GomobTheme
import io.gomob.feature.profile.AppearanceViewModel
import io.gomob.nativebridge.berxel.BerxelService
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var berxelService: BerxelService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        consumeUsbAttachIntent(intent)
        enableEdgeToEdge()
        setContent {
            val appearance: AppearanceViewModel = hiltViewModel()
            val mode by appearance.mode.collectAsStateWithLifecycle()
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (mode) {
                ThemeMode.Dark -> true
                ThemeMode.Light -> false
                ThemeMode.System -> systemDark
            }
            GomobTheme(darkTheme = darkTheme) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Gomob.colors.bg0)
                        .windowInsetsPadding(WindowInsets.systemBars),
                ) {
                    AppRoot()
                }
            }
        }
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
        consumeUsbAttachIntent(intent)
    }

    private fun consumeUsbAttachIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        @Suppress("DEPRECATION")
        val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
        berxelService.attachAuthorizedDevice(device)
    }
}
