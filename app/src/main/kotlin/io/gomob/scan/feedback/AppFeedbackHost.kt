package io.gomob.scan.feedback

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.feedback.FeedbackBox
import io.gomob.data.feedback.FeedbackRepository
import io.gomob.designsystem.component.LocalFeedbackTitleLongPress
import io.gomob.designsystem.theme.Gomob
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Composable
fun AppFeedbackHost(
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val vm: AppFeedbackViewModel = hiltViewModel()
    val submitState by vm.submitState.collectAsStateWithLifecycle()
    var editor by remember { mutableStateOf<FeedbackEditorState?>(null) }
    var captureInFlight by remember { mutableStateOf(false) }

    LaunchedEffect(submitState) {
        val s = submitState
        if (s is FeedbackSubmitState.Submitted) {
            Toast.makeText(context, "问题反馈已提交：${s.id}", Toast.LENGTH_SHORT).show()
        }
    }

    val trigger: (String) -> Unit = { title ->
        if (activity == null || editor != null || captureInFlight) {
            Unit
        } else {
            captureInFlight = true
            vm.reset()
            scope.launch {
                val result = runCatching {
                    captureWindowBitmap(activity).scaledForFeedback()
                }
                result
                    .onSuccess { shot -> editor = FeedbackEditorState(pageTitle = title, screenshot = shot) }
                    .onFailure { err ->
                        Toast.makeText(context, "截图失败：${err.message ?: err}", Toast.LENGTH_SHORT).show()
                    }
                captureInFlight = false
            }
        }
    }

    CompositionLocalProvider(LocalFeedbackTitleLongPress provides trigger) {
        Box(Modifier.fillMaxSize()) {
            content()
            editor?.let { current ->
                FeedbackEditorOverlay(
                    state = current,
                    submitState = submitState,
                    onStateChange = { editor = it },
                    onSubmit = {
                        vm.submit(
                            pageTitle = current.pageTitle,
                            screenshot = current.screenshot,
                            markers = current.markers,
                        )
                    },
                    onDismiss = {
                        vm.reset()
                        editor = null
                    },
                )
            }
        }
    }
}

@HiltViewModel
class AppFeedbackViewModel @Inject constructor(
    private val repository: FeedbackRepository,
) : ViewModel() {
    private val _submitState = MutableStateFlow<FeedbackSubmitState>(FeedbackSubmitState.Idle)
    val submitState: StateFlow<FeedbackSubmitState> = _submitState.asStateFlow()

    fun reset() {
        _submitState.value = FeedbackSubmitState.Idle
    }

    fun submit(pageTitle: String, screenshot: Bitmap, markers: List<AppFeedbackMarker>) {
        if (
            markers.isEmpty() ||
            _submitState.value == FeedbackSubmitState.Submitting ||
            _submitState.value is FeedbackSubmitState.Submitted
        ) return
        _submitState.value = FeedbackSubmitState.Submitting
        viewModelScope.launch {
            runCatching {
                val scaled = withContext(Dispatchers.Default) { screenshot.scaledForFeedback() }
                val annotated = withContext(Dispatchers.Default) { scaled.annotated(markers) }
                val imageData = withContext(Dispatchers.Default) { scaled.toPngDataUrl() }
                val annotatedData = withContext(Dispatchers.Default) { annotated.toPngDataUrl() }
                repository.submit(
                    title = "App 问题反馈 · $pageTitle",
                    pageUrl = "gomob://app/$pageTitle",
                    userAgent = androidUserAgent(),
                    imageDataUrl = imageData,
                    annotatedDataUrl = annotatedData,
                    boxes = markers.map { it.toFeedbackBox() },
                )
            }.onSuccess { result ->
                _submitState.value = FeedbackSubmitState.Submitted(result.id)
            }.onFailure { err ->
                _submitState.value = FeedbackSubmitState.Error(err.message ?: "反馈提交失败")
            }
        }
    }
}

sealed interface FeedbackSubmitState {
    data object Idle : FeedbackSubmitState
    data object Submitting : FeedbackSubmitState
    data class Submitted(val id: String) : FeedbackSubmitState
    data class Error(val message: String) : FeedbackSubmitState
}

data class AppFeedbackMarker(
    val id: Long,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val note: String,
) {
    fun toFeedbackBox(): FeedbackBox = FeedbackBox(
        x = x.toDouble(),
        y = y.toDouble(),
        w = w.toDouble(),
        h = h.toDouble(),
        note = note,
    )
}

private data class FeedbackEditorState(
    val pageTitle: String,
    val screenshot: Bitmap,
    val markers: List<AppFeedbackMarker> = emptyList(),
)

@Composable
private fun CaptureProgress() {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(Gomob.shapes.r3)
                .background(Gomob.colors.bg1)
                .border(1.dp, Gomob.colors.line2, Gomob.shapes.r3)
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text("正在截图", color = Gomob.colors.fg0, fontSize = 14.sp)
        }
    }
}

@Composable
private fun FeedbackEditorOverlay(
    state: FeedbackEditorState,
    submitState: FeedbackSubmitState,
    onStateChange: (FeedbackEditorState) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var pendingPoint by remember(state.screenshot) { mutableStateOf<Offset?>(null) }
    var pendingNote by remember(pendingPoint) { mutableStateOf("") }
    val canSubmit = state.markers.isNotEmpty() &&
        submitState != FeedbackSubmitState.Submitting &&
        submitState !is FeedbackSubmitState.Submitted

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF20B1018))
            // edge-to-edge 后全屏浮层自己避让系统栏
            .windowInsetsPadding(WindowInsets.systemBars)
            .padding(12.dp),
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("问题反馈", color = Color.White, fontSize = 18.sp)
                    Text(state.pageTitle, color = Color(0xFFB8C7D9), fontSize = 12.sp, maxLines = 1)
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
            Spacer(Modifier.height(10.dp))
            FeedbackShotStage(
                bitmap = state.screenshot,
                markers = state.markers,
                onTap = { point -> pendingPoint = point },
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            )
            Spacer(Modifier.height(10.dp))
            FeedbackMarkerList(
                markers = state.markers,
                submitState = submitState,
                canSubmit = canSubmit,
                onDelete = { id -> onStateChange(state.copy(markers = state.markers.filterNot { it.id == id })) },
                onSubmit = onSubmit,
            )
        }
    }

    if (pendingPoint != null) {
        AlertDialog(
            onDismissRequest = { pendingPoint = null },
            containerColor = Gomob.colors.bg2.copy(alpha = 0.97f),
            shape = Gomob.shapes.r3,
            title = { Text("问题描述") },
            text = {
                OutlinedTextField(
                    value = pendingNote,
                    onValueChange = { pendingNote = it.take(500) },
                    minLines = 3,
                    maxLines = 5,
                    placeholder = { Text("这处哪里不对") },
                )
            },
            confirmButton = {
                Button(
                    enabled = pendingNote.isNotBlank(),
                    onClick = {
                        val p = pendingPoint ?: return@Button
                        val marker = markerFromPoint(
                            id = System.nanoTime(),
                            point = p,
                            note = pendingNote.trim(),
                        )
                        onStateChange(state.copy(markers = state.markers + marker))
                        pendingPoint = null
                    },
                ) { Text("添加") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPoint = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun FeedbackShotStage(
    bitmap: Bitmap,
    markers: List<AppFeedbackMarker>,
    onTap: (Offset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ratio = bitmap.width.toFloat() / bitmap.height.toFloat()
    BoxWithConstraints(
        modifier = modifier
            .clip(Gomob.shapes.r2)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val maxRatio = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
        val stageWidth = if (maxRatio > ratio) maxHeight * ratio else maxWidth
        val stageHeight = if (maxRatio > ratio) maxHeight else maxWidth / ratio
        var stageSize by remember { mutableStateOf(IntSize.Zero) }
        Box(
            Modifier
                .width(stageWidth)
                .height(stageHeight)
                .border(1.dp, Color(0xFF314155))
                .pointerInput(stageSize) {
                    detectTapGestures { offset ->
                        if (stageSize.width > 0 && stageSize.height > 0) {
                            onTap(
                                Offset(
                                    x = (offset.x / stageSize.width).coerceIn(0f, 1f),
                                    y = (offset.y / stageSize.height).coerceIn(0f, 1f),
                                ),
                            )
                        }
                    }
                },
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "当前页面截图",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
                    .then(Modifier.onSizeChanged { stageSize = it }),
            )
            MarkerCanvas(markers = markers, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun MarkerCanvas(markers: List<AppFeedbackMarker>, modifier: Modifier = Modifier) {
    ComposeCanvas(modifier) {
        val stroke = 2.5.dp.toPx()
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 14.sp.toPx()
            typeface = Typeface.DEFAULT_BOLD
        }
        markers.forEachIndexed { index, box ->
            val left = box.x * size.width
            val top = box.y * size.height
            val w = box.w * size.width
            val h = box.h * size.height
            drawRect(
                color = Color(0x33FF3B30),
                topLeft = Offset(left, top),
                size = Size(w, h),
            )
            drawRect(
                color = Color(0xFFFF3B30),
                topLeft = Offset(left, top),
                size = Size(w, h),
                style = Stroke(width = stroke),
            )
            drawCircle(
                color = Color(0xFFFF3B30),
                radius = 10.dp.toPx(),
                center = Offset(left, top),
            )
            drawContext.canvas.nativeCanvas.drawText("${index + 1}", left - 4.dp.toPx(), top + 5.dp.toPx(), labelPaint)
        }
    }
}

@Composable
private fun FeedbackMarkerList(
    markers: List<AppFeedbackMarker>,
    submitState: FeedbackSubmitState,
    canSubmit: Boolean,
    onDelete: (Long) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .border(1.dp, Gomob.colors.line2, Gomob.shapes.r3)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已标注 ${markers.size} 处", color = Gomob.colors.fg0, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Button(enabled = canSubmit, onClick = onSubmit) {
                if (submitState == FeedbackSubmitState.Submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text("提交")
            }
        }
        when (submitState) {
            is FeedbackSubmitState.Error -> Text(submitState.message, color = Gomob.colors.danger, fontSize = 12.sp)
            is FeedbackSubmitState.Submitted -> Text("已提交：${submitState.id}", color = Gomob.colors.ok, fontSize = 12.sp)
            else -> Unit
        }
        Column(
            modifier = Modifier
                .height(96.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            markers.forEachIndexed { index, marker ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFFF3B30)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${index + 1}", color = Color.White, fontSize = 12.sp)
                    }
                    Text(
                        marker.note,
                        color = Gomob.colors.fg1,
                        fontSize = 13.sp,
                        modifier = Modifier.weight(1f).widthIn(min = 0.dp),
                        maxLines = 2,
                    )
                    TextButton(onClick = { onDelete(marker.id) }) { Text("删除") }
                }
            }
        }
    }
}

private fun markerFromPoint(id: Long, point: Offset, note: String): AppFeedbackMarker {
    val w = 0.14f
    val h = 0.08f
    val x = (point.x - w / 2f).coerceIn(0f, 1f - w)
    val y = (point.y - h / 2f).coerceIn(0f, 1f - h)
    return AppFeedbackMarker(id = id, x = x, y = y, w = w, h = h, note = note)
}

private suspend fun captureWindowBitmap(activity: Activity): Bitmap = suspendCancellableCoroutine { cont ->
    val view = activity.window.decorView.rootView
    val width = view.width
    val height = view.height
    if (width <= 0 || height <= 0) {
        cont.resumeWithException(IllegalStateException("窗口尺寸无效"))
        return@suspendCancellableCoroutine
    }
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        PixelCopy.request(
            activity.window,
            Rect(0, 0, width, height),
            bitmap,
            { result ->
                if (!cont.isActive) return@request
                if (result == PixelCopy.SUCCESS) {
                    cont.resume(bitmap)
                } else {
                    bitmap.recycle()
                    runCatching { drawViewBitmap(view) }
                        .onSuccess { cont.resume(it) }
                        .onFailure { cont.resumeWithException(it) }
                }
            },
            Handler(Looper.getMainLooper()),
        )
    } else {
        bitmap.recycle()
        runCatching { drawViewBitmap(view) }
            .onSuccess { cont.resume(it) }
            .onFailure { cont.resumeWithException(it) }
    }
}

private fun drawViewBitmap(view: android.view.View): Bitmap {
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    view.draw(canvas)
    return bitmap
}

private fun Bitmap.scaledForFeedback(maxSide: Int = 1600): Bitmap {
    val side = maxOf(width, height)
    if (side <= maxSide) return this
    val scale = maxSide.toFloat() / side.toFloat()
    val outW = (width * scale).toInt().coerceAtLeast(1)
    val outH = (height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, outW, outH, true)
}

private fun Bitmap.annotated(markers: List<AppFeedbackMarker>): Bitmap {
    val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawBitmap(this, 0f, 0f, null)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33FF3B30 }
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
        style = Paint.Style.STROKE
        strokeWidth = (width / 420f).coerceAtLeast(3f)
    }
    val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }
    val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = (width / 70f).coerceAtLeast(18f)
        typeface = Typeface.DEFAULT_BOLD
    }
    markers.forEachIndexed { index, box ->
        val left = box.x * width
        val top = box.y * height
        val right = left + box.w * width
        val bottom = top + box.h * height
        canvas.drawRect(left, top, right, bottom, fill)
        canvas.drawRect(left, top, right, bottom, stroke)
        val label = "${index + 1} ${box.note}".trim()
        val labelHeight = (width / 50f).coerceAtLeast(26f)
        val labelWidth = (labelText.measureText(label) + 18f).coerceAtMost(width - left - 8f).coerceAtLeast(32f)
        val labelTop = (top - labelHeight).coerceAtLeast(0f)
        canvas.drawRect(left, labelTop, left + labelWidth, labelTop + labelHeight, labelBg)
        canvas.drawText(label, left + 8f, labelTop + labelHeight - 8f, labelText)
    }
    return out
}

private fun Bitmap.toPngDataUrl(): String {
    val bytes = ByteArrayOutputStream().use { out ->
        compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }
    return "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}

private fun androidUserAgent(): String =
    "gomob-android model=${Build.MODEL} manufacturer=${Build.MANUFACTURER} android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}"

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
