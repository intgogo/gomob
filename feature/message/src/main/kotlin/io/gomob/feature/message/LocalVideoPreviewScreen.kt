package io.gomob.feature.message

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun LocalVideoPreviewRoute(
    title: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraEnabled by remember { mutableStateOf(true) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) cameraError = "未授予相机权限"
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = "本地视频",
            onBack = onBack,
            eyebrow = listOf(title.takeIf { it.isNotBlank() }, "本地预览", "未接通媒体房间")
                .filterNotNull()
                .joinToString(" · "),
            trailing = {
                StatusTag(
                    text = if (cameraEnabled && hasCameraPermission) "预览中" else "已暂停",
                    tone = if (cameraEnabled && hasCameraPermission) StatusTone.Ok else StatusTone.Neutral,
                    showDot = true,
                )
            },
        )

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            when {
                !hasCameraPermission -> PermissionBlock(
                    text = cameraError ?: "需要相机权限",
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                )
                !cameraEnabled -> PausedBlock()
                else -> CameraPreview(
                    lensFacing = lensFacing,
                    onError = { cameraError = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (cameraError != null && hasCameraPermission) {
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(Gomob.spacing.s12),
                ) {
                    StatusTag(text = cameraError.orEmpty(), tone = StatusTone.Warn, showDot = true)
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .background(Gomob.colors.bg1)
                .padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s12),
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            VideoControlIcon(
                active = cameraEnabled,
                label = if (cameraEnabled) "暂停本地视频" else "开启本地视频",
                icon = Icons.Filled.Videocam,
                onClick = {
                    cameraEnabled = !cameraEnabled
                    if (cameraEnabled) cameraError = null
                },
            )
            VideoControlIcon(
                active = true,
                label = "切换摄像头",
                icon = Icons.Filled.PhotoCamera,
                onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    }
                    cameraError = null
                },
            )
        }
    }
}

@Composable
private fun CameraPreview(
    lensFacing: Int,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember(context) {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val cameraProvider = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose { cameraProvider.value?.unbindAll() }
    }

    LaunchedEffect(lifecycleOwner, lensFacing, previewView) {
        runCatching {
            val provider = context.awaitCameraProvider()
            cameraProvider.value = provider
            val selector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, selector, preview)
        }.onFailure {
            onError(it.message?.takeIf { msg -> msg.isNotBlank() } ?: "相机预览启动失败")
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier,
    )
}

@Composable
private fun PermissionBlock(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .border(Gomob.spacing.hairline, Gomob.colors.line1, Gomob.shapes.r3)
            .clickable(onClick = onClick)
            .padding(Gomob.spacing.s16),
    ) {
        StatusTag(text = text, tone = StatusTone.Warn, showDot = true)
    }
}

@Composable
private fun PausedBlock() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Icon(
            Icons.Filled.Videocam,
            contentDescription = null,
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(30.dp),
        )
        Text("本地视频已暂停", style = Gomob.type.bodySm, color = Gomob.colors.fg3)
    }
}

@Composable
private fun VideoControlIcon(
    active: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(Gomob.spacing.touchMin)
            .clip(Gomob.shapes.r2)
            .background(if (active) Gomob.colors.accentSoft else Gomob.colors.bg2)
            .border(
                Gomob.spacing.hairline,
                if (active) Gomob.colors.accentLine else Gomob.colors.line2,
                Gomob.shapes.r2,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (active) Gomob.colors.accent else Gomob.colors.fg2,
            modifier = Modifier.size(18.dp),
        )
    }
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { provider ->
                        if (continuation.isActive) continuation.resume(provider)
                    }
                    .onFailure { error ->
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
            },
            ContextCompat.getMainExecutor(this),
        )
        continuation.invokeOnCancellation { future.cancel(true) }
    }
