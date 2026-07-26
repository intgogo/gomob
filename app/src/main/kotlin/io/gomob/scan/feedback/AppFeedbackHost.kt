package io.gomob.scan.feedback

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path as ComposePath
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.feedback.FeedbackBox
import io.gomob.data.feedback.FeedbackPathPoint
import io.gomob.data.feedback.FeedbackRepository
import io.gomob.designsystem.component.LocalFeedbackTitleTrigger
import io.gomob.designsystem.theme.Gomob
import io.gomob.scan.BuildConfig
import io.gomob.ui.feedback.FeedbackCaptureSurface
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
                    // 等五连击最后一帧完成，避免把按压态或尚未提交的绘制帧截进去。
                    withFrameNanos { }
                    val captured = captureWindowBitmap(activity)
                    captured.scaledForFeedback().also { scaled ->
                        if (scaled !== captured) captured.recycle()
                    }
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

    CompositionLocalProvider(LocalFeedbackTitleTrigger provides trigger) {
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
                        if (!current.screenshot.isRecycled) current.screenshot.recycle()
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

    internal fun submit(pageTitle: String, screenshot: Bitmap, markers: List<AppFeedbackMarker>) {
        if (
            markers.isEmpty() ||
            _submitState.value == FeedbackSubmitState.Submitting ||
            _submitState.value is FeedbackSubmitState.Submitted
        ) return
        _submitState.value = FeedbackSubmitState.Submitting
        viewModelScope.launch {
            runCatching {
                val scaled = withContext(Dispatchers.Default) { screenshot.scaledForFeedback() }
                try {
                    val annotated = withContext(Dispatchers.Default) { scaled.annotated(markers) }
                    val imageData = withContext(Dispatchers.Default) { scaled.toPngDataUrl() }
                    val annotatedData = try {
                        withContext(Dispatchers.Default) { annotated.toPngDataUrl() }
                    } finally {
                        annotated.recycle()
                    }
                    repository.submit(
                        title = "App 问题反馈 · $pageTitle",
                        pageUrl = "gomob://app/$pageTitle",
                        userAgent = androidUserAgent(),
                        imageDataUrl = imageData,
                        annotatedDataUrl = annotatedData,
                        boxes = markers.map { it.toFeedbackBox() },
                    )
                } finally {
                    if (scaled !== screenshot) scaled.recycle()
                }
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

internal data class AppFeedbackMarker(
    val id: Long,
    val points: List<FeedbackPoint>,
    val note: String,
) {
    val bounds: FeedbackBounds
        get() = requireNotNull(feedbackBounds(points)) { "圈画路径不能为空" }

    fun toFeedbackBox(): FeedbackBox {
        val box = bounds
        return FeedbackBox(
            x = box.x.toDouble(),
            y = box.y.toDouble(),
            w = box.w.toDouble(),
            h = box.h.toDouble(),
            note = note,
            points = points.map { FeedbackPathPoint(it.x.toDouble(), it.y.toDouble()) },
        )
    }
}

internal data class FeedbackEditorState(
    val pageTitle: String,
    val screenshot: Bitmap,
    val markers: List<AppFeedbackMarker> = emptyList(),
)

private data class FeedbackNoteDialogState(
    val markerId: Long?,
    val points: List<FeedbackPoint>,
    val initialNote: String,
)

@Composable
internal fun FeedbackEditorOverlay(
    state: FeedbackEditorState,
    submitState: FeedbackSubmitState,
    onStateChange: (FeedbackEditorState) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val dismissEnabled = submitState != FeedbackSubmitState.Submitting
    BackHandler(enabled = dismissEnabled, onBack = onDismiss)
    var noteDialog by remember(state.screenshot) { mutableStateOf<FeedbackNoteDialogState?>(null) }
    val canSubmit = state.markers.isNotEmpty() &&
        state.markers.all { it.note.isNotBlank() } &&
        submitState != FeedbackSubmitState.Submitting &&
        submitState !is FeedbackSubmitState.Submitted
    val drawingEnabled = noteDialog == null &&
        submitState != FeedbackSubmitState.Submitting &&
        submitState !is FeedbackSubmitState.Submitted

    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("feedback_editor")
            .background(Color(0xF20B1018))
            // edge-to-edge 后全屏浮层自己避让系统栏
            .windowInsetsPadding(WindowInsets.systemBars)
            .imePadding()
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
                TextButton(enabled = dismissEnabled, onClick = onDismiss) { Text("关闭") }
            }
            Text(
                "在截图上用手指圈出问题区域，松手后填写对应反馈",
                color = Color(0xFFB8C7D9),
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            FeedbackShotStage(
                bitmap = state.screenshot,
                markers = state.markers,
                enabled = drawingEnabled,
                onStrokeFinished = { points ->
                    noteDialog = FeedbackNoteDialogState(
                        markerId = null,
                        points = points,
                        initialNote = "",
                    )
                },
                onInvalidStroke = {
                    Toast.makeText(context, "请拖动手指完整圈出问题区域", Toast.LENGTH_SHORT).show()
                },
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
                onEdit = { marker ->
                    noteDialog = FeedbackNoteDialogState(
                        markerId = marker.id,
                        points = marker.points,
                        initialNote = marker.note,
                    )
                },
                onSubmit = onSubmit,
            )
        }
    }

    noteDialog?.let { dialog ->
        val markerNumber = dialog.markerId
            ?.let { id -> state.markers.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) }
            ?: (state.markers.size + 1)
        var note by remember(dialog) { mutableStateOf(dialog.initialNote) }
        AlertDialog(
            onDismissRequest = { noteDialog = null },
            containerColor = Gomob.colors.bg2.copy(alpha = 0.97f),
            shape = Gomob.shapes.r3,
            title = { Text("标注 $markerNumber 的反馈内容") },
            text = {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(500) },
                    minLines = 3,
                    maxLines = 5,
                    placeholder = { Text("这处哪里不对") },
                    modifier = Modifier.testTag("feedback_note_input"),
                )
            },
            confirmButton = {
                Button(
                    enabled = note.isNotBlank(),
                    modifier = Modifier.testTag("feedback_note_confirm"),
                    onClick = {
                        val cleanNote = note.trim()
                        val nextMarkers = if (dialog.markerId == null) {
                            state.markers + AppFeedbackMarker(
                                id = System.nanoTime(),
                                points = dialog.points,
                                note = cleanNote,
                            )
                        } else {
                            state.markers.map { marker ->
                                if (marker.id == dialog.markerId) marker.copy(note = cleanNote) else marker
                            }
                        }
                        onStateChange(state.copy(markers = nextMarkers))
                        noteDialog = null
                    },
                ) { Text(if (dialog.markerId == null) "添加" else "保存") }
            },
            dismissButton = {
                TextButton(onClick = { noteDialog = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun FeedbackShotStage(
    bitmap: Bitmap,
    markers: List<AppFeedbackMarker>,
    enabled: Boolean,
    onStrokeFinished: (List<FeedbackPoint>) -> Unit,
    onInvalidStroke: () -> Unit,
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
        var draftPoints by remember(bitmap) { mutableStateOf<List<FeedbackPoint>>(emptyList()) }
        Box(
            Modifier
                .width(stageWidth)
                .height(stageHeight)
                .testTag("feedback_shot_stage")
                .border(1.dp, Color(0xFF314155))
                .pointerInput(stageSize, enabled) {
                    if (!enabled || stageSize == IntSize.Zero) return@pointerInput
                    fun normalize(offset: Offset): FeedbackPoint = normalizedFeedbackPoint(
                        xPx = offset.x,
                        yPx = offset.y,
                        widthPx = stageSize.width,
                        heightPx = stageSize.height,
                    )
                    detectDragGestures(
                        onDragStart = { offset -> draftPoints = listOf(normalize(offset)) },
                        onDrag = { change, _ ->
                            change.consume()
                            draftPoints = appendFeedbackPoint(draftPoints, normalize(change.position))
                        },
                        onDragCancel = { draftPoints = emptyList() },
                        onDragEnd = {
                            val completed = draftPoints
                            draftPoints = emptyList()
                            if (isMeaningfulFeedbackStroke(completed)) {
                                onStrokeFinished(completed)
                            } else {
                                onInvalidStroke()
                            }
                        },
                    )
                },
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "当前页面截图",
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize()
                    .then(Modifier.onSizeChanged { stageSize = it }),
            )
            MarkerCanvas(
                markers = markers,
                draftPoints = draftPoints,
                modifier = Modifier.fillMaxSize().testTag("feedback_marker_canvas"),
            )
        }
    }
}

@Composable
private fun MarkerCanvas(
    markers: List<AppFeedbackMarker>,
    draftPoints: List<FeedbackPoint>,
    modifier: Modifier = Modifier,
) {
    ComposeCanvas(modifier) {
        val strokeWidth = 3.dp.toPx()
        val badgeRadius = 11.dp.toPx()
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 14.sp.toPx()
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        fun drawPath(points: List<FeedbackPoint>, color: Color, close: Boolean) {
            if (points.size < 2) return
            val path = ComposePath().apply {
                moveTo(points.first().x * size.width, points.first().y * size.height)
                points.drop(1).forEach { lineTo(it.x * size.width, it.y * size.height) }
                if (close) close()
            }
            drawPath(
                path = path,
                color = color,
                style = Stroke(
                    width = strokeWidth,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
        fun drawNumber(number: Int, bounds: FeedbackBounds, color: Color) {
            val center = Offset(
                x = (bounds.x * size.width).coerceIn(badgeRadius, size.width - badgeRadius),
                y = (bounds.y * size.height).coerceIn(badgeRadius, size.height - badgeRadius),
            )
            drawCircle(
                color = color,
                radius = badgeRadius,
                center = center,
            )
            val baseline = center.y - (labelPaint.ascent() + labelPaint.descent()) / 2f
            drawContext.canvas.nativeCanvas.drawText(number.toString(), center.x, baseline, labelPaint)
        }
        markers.forEachIndexed { index, marker ->
            drawPath(marker.points, Color(0xFFFF3B30), close = true)
            drawNumber(index + 1, marker.bounds, Color(0xFFFF3B30))
        }
        if (draftPoints.isNotEmpty()) {
            val draftBounds = feedbackBounds(draftPoints)
            drawPath(draftPoints, Color(0xFFFFB020), close = false)
            if (draftBounds != null) {
                drawNumber(markers.size + 1, draftBounds, Color(0xFFFFB020))
            }
        }
    }
}

@Composable
private fun FeedbackMarkerList(
    markers: List<AppFeedbackMarker>,
    submitState: FeedbackSubmitState,
    canSubmit: Boolean,
    onDelete: (Long) -> Unit,
    onEdit: (AppFeedbackMarker) -> Unit,
    onSubmit: () -> Unit,
) {
    val editingEnabled = submitState != FeedbackSubmitState.Submitting &&
        submitState !is FeedbackSubmitState.Submitted
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
            if (markers.isNotEmpty() && submitState !is FeedbackSubmitState.Submitted) {
                TextButton(
                    enabled = editingEnabled,
                    onClick = { onDelete(markers.last().id) },
                ) { Text("撤销") }
            }
            Button(
                enabled = canSubmit,
                onClick = onSubmit,
                modifier = Modifier.testTag("feedback_submit"),
            ) {
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
                .height(132.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (markers.isEmpty()) {
                Text(
                    "尚未标注，请先在截图上圈画问题区域",
                    color = Gomob.colors.fg3,
                    fontSize = 12.sp,
                )
            }
            markers.forEachIndexed { index, marker ->
                Row(
                    modifier = Modifier.fillMaxWidth().testTag("feedback_marker_${index + 1}"),
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
                    TextButton(
                        enabled = editingEnabled,
                        onClick = { onEdit(marker) },
                    ) { Text("修改") }
                    TextButton(
                        enabled = editingEnabled,
                        onClick = { onDelete(marker.id) },
                    ) { Text("删除") }
                }
            }
        }
    }
}

internal suspend fun captureWindowBitmap(activity: Activity): Bitmap {
    check(Looper.myLooper() == Looper.getMainLooper()) { "页面截图必须在主线程执行" }
    val view = activity.window.decorView.rootView
    val width = view.width
    val height = view.height
    if (width <= 0 || height <= 0) {
        throw IllegalStateException("窗口尺寸无效")
    }
    val surfaceViews = view.visibleSurfaceViews()
    if (surfaceViews.isEmpty()) {
        return runCatching { copyWindowBitmap(activity, width, height) }
            .getOrElse { drawViewBitmap(view) }
    }

    val captureSurfaces = surfaceViews.map { surfaceView ->
        surfaceView as? FeedbackCaptureSurface
            ?: throw IllegalStateException("当前独立渲染画面尚不支持可靠截图")
    }
    val pausedSurfaces = mutableListOf<FeedbackCaptureSurface>()
    try {
        captureSurfaces.forEach { surface ->
            pausedSurfaces += surface
            surface.pauseForFeedbackCapture()
        }
        val windowBitmap = drawViewBitmap(view)
        try {
            compositeVisibleSurfaces(view, surfaceViews, windowBitmap)
            return windowBitmap
        } catch (err: Throwable) {
            windowBitmap.recycle()
            throw err
        }
    } finally {
        pausedSurfaces.asReversed().forEach { surface ->
            runCatching { surface.resumeAfterFeedbackCapture() }
        }
    }
}

private suspend fun compositeVisibleSurfaces(
    rootView: View,
    surfaceViews: List<SurfaceView>,
    windowBitmap: Bitmap,
) {
    val width = windowBitmap.width
    val height = windowBitmap.height

    val canvas = Canvas(windowBitmap)
    val surfacePaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER)
    }
    val rootScreen = IntArray(2).also(rootView::getLocationOnScreen)
    val rootWindow = IntArray(2).also(rootView::getLocationInWindow)
    val screenToWindowX = rootScreen[0] - rootWindow[0]
    val screenToWindowY = rootScreen[1] - rootWindow[1]

    for (surfaceView in surfaceViews) {
        val surfaceBitmap = copySurfaceBitmap(surfaceView)
        try {
            val surfaceWindow = IntArray(2).also(surfaceView::getLocationInWindow)
            val visibleOnScreen = Rect()
            if (!surfaceView.getGlobalVisibleRect(visibleOnScreen)) continue
            val dst = Rect(
                visibleOnScreen.left - screenToWindowX,
                visibleOnScreen.top - screenToWindowY,
                visibleOnScreen.right - screenToWindowX,
                visibleOnScreen.bottom - screenToWindowY,
            )
            if (!dst.intersect(0, 0, width, height)) continue
            val src = Rect(
                dst.left - surfaceWindow[0],
                dst.top - surfaceWindow[1],
                dst.right - surfaceWindow[0],
                dst.bottom - surfaceWindow[1],
            )
            src.intersect(0, 0, surfaceBitmap.width, surfaceBitmap.height)
            if (src.isEmpty || dst.isEmpty) continue
            if (!hasTransparentSurfaceHole(windowBitmap, dst)) {
                throw IllegalStateException("当前独立渲染画面无法与页面控件可靠合成")
            }
            canvas.drawBitmap(surfaceBitmap, src, dst, surfacePaint)
        } finally {
            surfaceBitmap.recycle()
        }
    }
}

private fun hasTransparentSurfaceHole(bitmap: Bitmap, rect: Rect): Boolean {
    val stepX = (rect.width() / 64).coerceAtLeast(1)
    val stepY = (rect.height() / 64).coerceAtLeast(1)
    var sampled = 0
    var transparent = 0
    var y = rect.top
    while (y < rect.bottom) {
        var x = rect.left
        while (x < rect.right) {
            sampled++
            if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) < 250) transparent++
            x += stepX
        }
        y += stepY
    }
    return sampled > 0 && transparent.toFloat() / sampled >= 0.01f
}

private suspend fun copyWindowBitmap(activity: Activity, width: Int, height: Int): Bitmap =
    suspendCancellableCoroutine { cont ->
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        cont.invokeOnCancellation { bitmap.recycle() }
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
                    cont.resumeWithException(IllegalStateException("窗口 PixelCopy 失败：$result"))
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

private suspend fun copySurfaceBitmap(surfaceView: SurfaceView): Bitmap =
    suspendCancellableCoroutine { cont ->
        if (!surfaceView.holder.surface.isValid) {
            cont.resumeWithException(IllegalStateException("独立渲染画面尚未就绪，请稍后重试"))
            return@suspendCancellableCoroutine
        }
        val bitmap = Bitmap.createBitmap(surfaceView.width, surfaceView.height, Bitmap.Config.ARGB_8888)
        cont.invokeOnCancellation { bitmap.recycle() }
        PixelCopy.request(
            surfaceView,
            bitmap,
            { result ->
                if (!cont.isActive) return@request
                if (result == PixelCopy.SUCCESS) {
                    cont.resume(bitmap)
                } else {
                    bitmap.recycle()
                    cont.resumeWithException(IllegalStateException("独立渲染画面截图失败：$result"))
                }
            },
            Handler(Looper.getMainLooper()),
        )
    }

private fun View.visibleSurfaceViews(): List<SurfaceView> {
    val result = mutableListOf<SurfaceView>()
    fun collect(view: View) {
        if (!view.isShown || view.alpha <= 0f || view.width <= 0 || view.height <= 0) return
        if (view is SurfaceView && view.holder.surface.isValid) result += view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) collect(view.getChildAt(index))
        }
    }
    collect(this)
    return result
}

private fun drawViewBitmap(view: android.view.View): Bitmap {
    val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    view.draw(canvas)
    return bitmap
}

private fun Bitmap.scaledForFeedback(maxSide: Int = 1400): Bitmap {
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
    val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF3B30.toInt()
        style = Paint.Style.STROKE
        strokeWidth = (width / 420f).coerceAtLeast(3f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF3B30.toInt() }
    val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = (width / 70f).coerceAtLeast(18f)
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
    }
    val badgeRadius = (width / 55f).coerceAtLeast(18f)
    markers.forEachIndexed { index, marker ->
        val path = AndroidPath().apply {
            val first = marker.points.first()
            moveTo(first.x * width, first.y * height)
            marker.points.drop(1).forEach { lineTo(it.x * width, it.y * height) }
            close()
        }
        canvas.drawPath(path, stroke)
        val bounds = marker.bounds
        val centerX = (bounds.x * width).coerceIn(badgeRadius, width - badgeRadius)
        val centerY = (bounds.y * height).coerceIn(badgeRadius, height - badgeRadius)
        canvas.drawCircle(centerX, centerY, badgeRadius, labelBg)
        val baseline = centerY - (labelText.ascent() + labelText.descent()) / 2f
        canvas.drawText((index + 1).toString(), centerX, baseline, labelText)
    }
    return out
}

private fun Bitmap.toPngDataUrl(): String {
    val bytes = ByteArrayOutputStream().use { out ->
        check(compress(Bitmap.CompressFormat.PNG, 100, out)) { "PNG 编码失败" }
        out.toByteArray()
    }
    return "data:image/png;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
}

private fun androidUserAgent(): String =
    "gomob-android/${BuildConfig.VERSION_NAME} code=${BuildConfig.VERSION_CODE} " +
        "model=${Build.MODEL} manufacturer=${Build.MANUFACTURER} " +
        "android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT}"

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
