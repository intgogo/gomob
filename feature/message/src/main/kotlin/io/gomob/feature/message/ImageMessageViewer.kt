package io.gomob.feature.message

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import io.gomob.designsystem.theme.Gomob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal data class ImageMessagePreviewUi(
    val source: String,
    val mime: String?,
)

internal fun MessageBubbleUi.toImageMessagePreview(): ImageMessagePreviewUi? {
    val media = media ?: return null
    val source = media.imageSource?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return ImageMessagePreviewUi(source = source, mime = media.mime)
}

@Composable
internal fun ImageMessageViewer(
    preview: ImageMessagePreviewUi?,
    onDismiss: () -> Unit,
) {
    if (preview == null) return

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val imageState by rememberMessageImage(preview.source)
    var scale by remember { mutableFloatStateOf(1f) }
    var rotation by remember { mutableFloatStateOf(0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val shareImage: () -> Unit = {
        runCatching { context.shareImageSource(preview.source, preview.mime) }
            .onFailure { context.showMessageActionToast(it.message ?: "分享失败") }
    }

    fun resetTransform() {
        scale = 1f
        rotation = 0f
        offset = Offset.Zero
    }

    LaunchedEffect(preview.source) {
        resetTransform()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        ImageViewerSystemBars()
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(preview.source) {
                        detectTapGestures(
                            onTap = { onDismiss() },
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    scale = 2.5f
                                }
                            },
                        )
                    }
                    .pointerInput(preview.source) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            val nextScale = (scale * zoom).coerceIn(1f, 6f)
                            scale = nextScale
                            offset = if (nextScale > 1f) offset + pan else Offset.Zero
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                when (val state = imageState) {
                    is MessageImageLoadState.Ready -> Image(
                        bitmap = state.bitmap,
                        contentDescription = "照片预览",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                rotationZ = rotation
                                translationX = offset.x
                                translationY = offset.y
                            },
                    )
                    MessageImageLoadState.Empty,
                    MessageImageLoadState.Failed,
                    MessageImageLoadState.Loading -> {
                        val text = when (imageState) {
                            MessageImageLoadState.Loading -> "照片加载中"
                            else -> "照片暂不可显示"
                        }
                        androidx.compose.material3.Text(
                            text = text,
                            style = Gomob.type.bodySm,
                            color = Color.White.copy(alpha = 0.72f),
                        )
                    }
                }
            }

            ImageViewerBottomBar(
                imageState = imageState,
                onZoomOut = {
                    scale = (scale / 1.25f).coerceAtLeast(1f)
                    if (scale == 1f) offset = Offset.Zero
                },
                onZoomIn = { scale = (scale * 1.25f).coerceAtMost(6f) },
                onRotateLeft = { rotation -= 90f },
                onRotateRight = { rotation += 90f },
                onReset = ::resetTransform,
                onSave = {
                    val ready = imageState as? MessageImageLoadState.Ready
                    if (ready != null) {
                        coroutineScope.launch {
                            val saved = saveImageToAlbum(context, ready.bitmap)
                            context.showMessageActionToast(if (saved) "已保存到相册" else "保存失败")
                        }
                    }
                },
                onShare = shareImage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .background(Color.Black)
                    .navigationBarsPadding(),
            )
        }
    }
}

@Composable
@Suppress("DEPRECATION")
private fun ImageViewerSystemBars() {
    val context = LocalContext.current
    val view = LocalView.current
    val activity = context.findActivity()
    val activityWindow = activity?.window
    val dialogWindow = (view as? DialogWindowProvider)?.window
        ?: (view.parent as? DialogWindowProvider)?.window

    SideEffect {
        dialogWindow?.applyImageViewerWindowStyle(isDialogWindow = true)
        activityWindow?.applyImageViewerWindowStyle(isDialogWindow = false)
    }

    DisposableEffect(view, activityWindow, dialogWindow) {
        val activitySnapshot = activityWindow?.captureImageViewerWindowSnapshot()
        val dialogSnapshot = dialogWindow?.captureImageViewerWindowSnapshot()
        val activityBackdrop = activity?.installImageViewerActivityBackdrop()
        val applyStyle = Runnable {
            dialogWindow?.applyImageViewerWindowStyle(isDialogWindow = true)
            activityWindow?.applyImageViewerWindowStyle(isDialogWindow = false)
        }

        applyStyle.run()
        view.post(applyStyle)

        onDispose {
            view.removeCallbacks(applyStyle)
            activityBackdrop?.let { (it.parent as? ViewGroup)?.removeView(it) }
            dialogSnapshot?.restore(dialogWindow)
            activitySnapshot?.restore(activityWindow)
        }
    }
}

@Suppress("DEPRECATION")
private fun Window.applyImageViewerWindowStyle(isDialogWindow: Boolean) {
    WindowCompat.setDecorFitsSystemWindows(this, false)
    clearFlags(
        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS or
            WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION,
    )
    addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
    statusBarColor = AndroidColor.BLACK
    navigationBarColor = AndroidColor.BLACK
    decorView.systemUiVisibility = decorView.systemUiVisibility or
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        isStatusBarContrastEnforced = false
        isNavigationBarContrastEnforced = false
    }

    val controller = WindowCompat.getInsetsController(this, decorView)
    controller.isAppearanceLightStatusBars = false
    controller.isAppearanceLightNavigationBars = false

    if (isDialogWindow) {
        setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        setBackgroundDrawable(ColorDrawable(AndroidColor.BLACK))
        decorView.setBackgroundColor(AndroidColor.BLACK)
        clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
        attributes = attributes.apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun Window.captureImageViewerWindowSnapshot(): ImageViewerWindowSnapshot {
    val controller = WindowCompat.getInsetsController(this, decorView)
    return ImageViewerWindowSnapshot(
        statusBarColor = statusBarColor,
        navigationBarColor = navigationBarColor,
        systemUiVisibility = decorView.systemUiVisibility,
        lightStatusBars = controller.isAppearanceLightStatusBars,
        lightNavigationBars = controller.isAppearanceLightNavigationBars,
        statusBarContrastEnforced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isStatusBarContrastEnforced
        } else {
            null
        },
        navigationBarContrastEnforced = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isNavigationBarContrastEnforced
        } else {
            null
        },
    )
}

private data class ImageViewerWindowSnapshot(
    val statusBarColor: Int,
    val navigationBarColor: Int,
    val systemUiVisibility: Int,
    val lightStatusBars: Boolean,
    val lightNavigationBars: Boolean,
    val statusBarContrastEnforced: Boolean?,
    val navigationBarContrastEnforced: Boolean?,
) {
    @Suppress("DEPRECATION")
    fun restore(window: Window?) {
        if (window == null) return
        window.statusBarColor = statusBarColor
        window.navigationBarColor = navigationBarColor
        window.decorView.systemUiVisibility = systemUiVisibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            statusBarContrastEnforced?.let { window.isStatusBarContrastEnforced = it }
            navigationBarContrastEnforced?.let { window.isNavigationBarContrastEnforced = it }
        }
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = lightStatusBars
        controller.isAppearanceLightNavigationBars = lightNavigationBars
    }
}

private fun Activity.installImageViewerActivityBackdrop(): View? {
    val decor = window.decorView as? ViewGroup ?: return null
    return View(this).apply {
        setBackgroundColor(AndroidColor.BLACK)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        decor.addView(
            this,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP or Gravity.START,
            ),
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
private fun ImageViewerBottomBar(
    imageState: MessageImageLoadState,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onReset: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageReady = imageState is MessageImageLoadState.Ready
    Row(
        modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s12),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ImageViewerActionButton(Icons.Filled.ZoomOut, "缩小", imageReady, onZoomOut)
        ImageViewerActionButton(Icons.Filled.ZoomIn, "放大", imageReady, onZoomIn)
        ImageViewerActionButton(Icons.AutoMirrored.Filled.RotateLeft, "左转", imageReady, onRotateLeft)
        ImageViewerActionButton(Icons.AutoMirrored.Filled.RotateRight, "右转", imageReady, onRotateRight)
        ImageViewerActionButton(Icons.Filled.RestartAlt, "重置", imageReady, onReset)
        ImageViewerActionButton(Icons.Filled.FileDownload, "保存", imageReady, onSave)
        ImageViewerActionButton(Icons.Filled.Share, "分享", true, onShare)
    }
}

@Composable
private fun ImageViewerActionButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val fg = if (enabled) Color.White else Color.White.copy(alpha = 0.34f)
    Box(
        Modifier
            .size(44.dp)
            .clip(Gomob.shapes.r2)
            .background(Color.White.copy(alpha = if (enabled) 0.14f else 0.07f))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = fg,
            modifier = Modifier.size(22.dp),
        )
    }
}

private fun Context.shareImageSource(source: String, mime: String?) {
    val normalized = source.trim()
    val uri = runCatching { Uri.parse(normalized) }.getOrNull()
    val intent = when {
        normalized.startsWith("http://", ignoreCase = true) ||
            normalized.startsWith("https://", ignoreCase = true) -> Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, normalized)
        }
        uri?.scheme == "content" || uri?.scheme == "file" -> Intent(Intent.ACTION_SEND).apply {
            type = mime?.takeIf { it.isNotBlank() } ?: "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        File(normalized).exists() -> throw IllegalArgumentException("当前图片来源不能直接分享")
        else -> throw IllegalArgumentException("当前图片来源不能直接分享")
    }
    startActivity(Intent.createChooser(intent, "分享照片"))
}

private suspend fun saveImageToAlbum(context: Context, image: ImageBitmap): Boolean =
    withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val fileName = "gomob_${System.currentTimeMillis()}.jpg"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Gomob")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
            resolver.openOutputStream(uri)?.use { output ->
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, 95, output)
            } ?: return@runCatching false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            true
        }.getOrDefault(false)
    }
