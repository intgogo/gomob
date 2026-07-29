package io.gomob.feature.scan3d

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.vinrubbing.capture.VinCapture
import io.vinrubbing.capture.VinCaptureRequest

/** gomob 只负责路由；VIN 页面、采集、上传和结果展示由独立 AAR 管理。 */
@Composable
fun ScanCaptureRoute(
    onBack: () -> Unit,
    onOpenDepthCamera: () -> Unit = {},
) {
    val launcher = rememberLauncherForActivityResult(VinCapture.contract()) {
        onBack()
    }
    val request = remember {
        VinCaptureRequest("vin_${System.currentTimeMillis()}")
    }
    LaunchedEffect(Unit) {
        launcher.launch(request)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
