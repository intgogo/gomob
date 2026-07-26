package io.gomob.feature.scan3d

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import io.gomob.data.scan.VinRecognitionStatus
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.glass.glassChrome
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import java.util.Locale

/**
 * VIN 数码拓印：真实 RGBD 双预览、服务端权威正射与 OCR 结果在一屏闭环。
 *
 * 顶部两个横条 = 真实相机帧：彩色图（[VinCaptureViewModel.colorPreview]）+ 深度图（[VinCaptureViewModel.depthPreview]）。
 * 拓印纸面 = 服务端原厂式彩色正射还原图（拍照→上传→还原回显）；识别结果 = 外部算法真实 OCR。
 */
@Composable
fun ScanCaptureRoute(
    onBack: () -> Unit,
    onOpenDepthCamera: () -> Unit = {},
    vm: VinCaptureViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }
    LaunchedEffect(hasCameraPermission) {
        vm.setCameraPermissionGranted(hasCameraPermission)
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val colorBmp by vm.colorPreview.collectAsStateWithLifecycle()
    val depthBmp by vm.depthPreview.collectAsStateWithLifecycle()
    val previewAlignment by vm.previewAlignment.collectAsStateWithLifecycle()
    val rubbing by vm.rubbing.collectAsStateWithLifecycle()
    val capturing by vm.capturing.collectAsStateWithLifecycle()
    val captureMsg by vm.captureMsg.collectAsStateWithLifecycle()
    val captureReadiness by vm.captureReadiness.collectAsStateWithLifecycle()
    val captureQuality by vm.captureQuality.collectAsStateWithLifecycle()
    val autoCaptureDecision by vm.autoCaptureDecision.collectAsStateWithLifecycle()
    val depthRoiMetrics by vm.depthRoiMetrics.collectAsStateWithLifecycle()
    val vinState by vm.state.collectAsStateWithLifecycle()
    val restoreState by vm.restoreState.collectAsStateWithLifecycle()
    val cameraStopped = restoreState is VinRestoreState.Ready
    val effectiveCaptureQuality = if (captureReadiness.ready) captureQuality else VinCaptureQuality.Waiting
    val listState = rememberLazyListState()
    GlassHeaderScaffold(
        listState = listState,
        header = {
            BackHeader(
                title = "VIN 数码拓印",
                onBack = onBack,
                trailing = {
                    VinHeaderCameraButton(
                        readiness = captureReadiness,
                        onClick = onOpenDepthCamera,
                    )
                },
            )
        },
        overlay = { _ ->
            // 吸底拍摄栏 → 玻璃吸底条（规则 4）：内容从底下滚过透出模糊背景，导航栏 inset 吃在玻璃内侧。
            VinCaptureBar(
                capturing = capturing,
                captureReadiness = captureReadiness,
                captureQuality = effectiveCaptureQuality,
                restoreState = restoreState,
                vinState = vinState,
                onShutter = vm::capture,
                onRetake = vm::retake,
                onConfirm = vm::recognize,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .glassChrome(topEdge = true)
                    .navigationBarsPadding(),
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = padding.calculateTopPadding() + Gomob.spacing.s12,
                // 底部预留吸底拍摄栏高度，末尾内容不被压住
                bottom = padding.calculateBottomPadding() + 98.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.cardGap),
        ) {
            item {
                VinPreviewPanel(
                    depthBmp = depthBmp,
                    colorBmp = colorBmp,
                    alignment = previewAlignment,
                    captureQuality = effectiveCaptureQuality,
                    cameraStopped = cameraStopped,
                    onColorRoiChanged = vm::setPreviewRoi,
                )
            }
            if (depthRoiMetrics != null && (captureReadiness.ready || cameraStopped)) {
                item {
                    VinDepthMetricsStrip(
                        metrics = requireNotNull(depthRoiMetrics),
                        captured = cameraStopped,
                    )
                }
            }
            if (
                restoreState is VinRestoreState.Preview &&
                vinState is VinCaptureState.Preview &&
                captureReadiness.ready &&
                effectiveCaptureQuality is VinCaptureQuality.Ready
            ) {
                item { VinReadyGuide(autoCaptureDecision) }
            }
            vinNotice(
                captureMsg = captureMsg,
                captureReadiness = captureReadiness,
                captureQuality = captureQuality,
                restoreState = restoreState,
                vinState = vinState,
                hasCameraPermission = hasCameraPermission,
            )?.let { notice ->
                item {
                    VinActionNotice(
                        notice = notice,
                        onAction = notice.actionLabel?.let {
                            { permissionLauncher.launch(Manifest.permission.CAMERA) }
                        },
                    )
                }
            }
            if (restoreState is VinRestoreState.Ready && rubbing != null) {
                item {
                    VinOutcomePanel(
                        rubbing = requireNotNull(rubbing),
                        recognition = vinState as? VinCaptureState.Result,
                    )
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

private val VinViewportBg = Color(0xFF0B0E13)
private val VinViewportText = Color.White.copy(alpha = 0.72f)
private val VinViewportTagBg = Color.Black.copy(alpha = 0.42f)
private val VinCreatorRoiRed = Color(0xFFFF0000)
private val VinCreatorRoiGreen = Color(0xFF22C55E)

@Composable
private fun VinPreviewPanel(
    depthBmp: Bitmap?,
    colorBmp: Bitmap?,
    alignment: VinPreviewAlignmentState,
    captureQuality: VinCaptureQuality,
    cameraStopped: Boolean,
    onColorRoiChanged: (VinPreviewRoi) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
    ) {
        VinPreviewPane(
            bitmap = depthBmp,
            label = if (cameraStopped) "深度图 · 本次拍摄" else vinDepthPreviewLabel(alignment),
            waiting = if (cameraStopped) "还原完成，相机已停止" else "等待深度帧…",
        )
        Spacer(Modifier.fillMaxWidth().height(Gomob.spacing.hairline).background(Gomob.colors.line2))
        VinPreviewPane(
            bitmap = colorBmp,
            label = if (cameraStopped) "彩色图 · 本次拍摄" else "彩色图",
            waiting = if (cameraStopped) "重新扫描后启动相机" else "等待彩色帧…",
            showOcrFrame = !cameraStopped,
            captureQuality = captureQuality,
            onRoiChanged = onColorRoiChanged,
        )
    }
}

internal fun vinDepthPreviewLabel(alignment: VinPreviewAlignmentState): String = when (alignment) {
    VinPreviewAlignmentState.WaitingForRig -> "深度图"
    VinPreviewAlignmentState.Loading -> "深度图（标定加载中）"
    is VinPreviewAlignmentState.Ready -> "深度图"
    is VinPreviewAlignmentState.Unavailable -> "深度图（原始）"
}

internal fun vinDepthMetricsText(metrics: VinDepthRoiMetrics, captured: Boolean): String {
    val coverage = String.format(Locale.ROOT, "%.1f", metrics.coverageRatio.coerceIn(0.0, 1.0) * 100.0)
    val prefix = "${if (captured) "本次拍摄" else "实时"} · 深度有效率 $coverage%"
    val distance = metrics.distanceMedianMm
        ?.takeIf { vinHasReliableDistance(metrics) }
        ?: return prefix
    return "$prefix · 距离约 ${String.format(Locale.ROOT, "%.1f", distance / 10.0)}cm"
}

@Composable
private fun VinDepthMetricsStrip(metrics: VinDepthRoiMetrics, captured: Boolean) {
    Text(
        text = vinDepthMetricsText(metrics, captured),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.pageGutter)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1)
            .border(BorderStroke(Gomob.spacing.hairline, Gomob.colors.line2), Gomob.shapes.r2)
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        style = Gomob.type.micro,
        color = Gomob.colors.fg2,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun VinHeaderCameraButton(
    readiness: VinCaptureReadiness,
    onClick: () -> Unit,
) {
    val isError = readiness.message.contains("异常")
    val iconTint = when {
        readiness.ready -> Gomob.colors.accent
        isError -> Gomob.colors.danger
        else -> Gomob.colors.fg3
    }
    val dotColor = when {
        readiness.ready -> Gomob.colors.ok
        isError -> Gomob.colors.danger
        else -> Gomob.colors.fg3
    }
    val cameraDescription = if (readiness.ready) {
        "VIN RGBD 相机已就绪"
    } else {
        "VIN RGBD 相机未就绪：${readiness.message}"
    }
    Box(
        modifier = Modifier
            .size(Gomob.spacing.touchMin)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = cameraDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Gomob.colors.bg1.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = GomobIcons.USB,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(Gomob.spacing.icon20),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 6.dp)
                .size(Gomob.spacing.dot8)
                .clip(CircleShape)
                .background(dotColor)
                .border(BorderStroke(2.dp, Gomob.colors.bg1), CircleShape),
        )
    }
}

@Composable
private fun VinReadyGuide(decision: VinAutoCaptureDecision) {
    val detail = when (decision) {
        is VinAutoCaptureDecision.Stabilizing ->
            "取景已达标，稳定确认 ${decision.readyFrames.coerceAtMost(VIN_AUTO_CAPTURE_MIN_READY_FRAMES)}/$VIN_AUTO_CAPTURE_MIN_READY_FRAMES 后自动拍摄识别"
        VinAutoCaptureDecision.Trigger,
        VinAutoCaptureDecision.Triggered -> "稳定已确认，正在自动拍摄并识别"
        VinAutoCaptureDecision.Waiting -> "取景已达标，稳定后将自动拍摄并识别"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.pageGutter)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1)
            .border(BorderStroke(Gomob.spacing.hairline, Gomob.colors.line2), Gomob.shapes.r2)
            .semantics(mergeDescendants = true) {
                contentDescription = "车架号区域已达标，请稳住不动，将自动拍摄并识别，也可点击快门手动拍摄"
            }
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Gomob.colors.okSoft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = GomobIcons.Check,
                contentDescription = null,
                tint = Gomob.colors.ok,
                modifier = Modifier.size(Gomob.spacing.icon16),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2),
        ) {
            Text("请稳住不动", style = Gomob.type.caption, color = Gomob.colors.fg1)
            Text(
                detail,
                style = Gomob.type.micro,
                color = Gomob.colors.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 仅供 debug Activity 确定性验证红绿框、提示和快门门控；不进入生产导航。 */
@Composable
internal fun VinCaptureQualityTestSurface(quality: VinCaptureQuality) {
    val ready = quality is VinCaptureQuality.Ready
    val metrics = when (quality) {
        is VinCaptureQuality.Insufficient -> quality.metrics
        is VinCaptureQuality.TooFar -> quality.metrics
        is VinCaptureQuality.Ready -> quality.metrics
        VinCaptureQuality.Waiting -> null
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Gomob.colors.bg0)
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        VinPreviewPane(
            bitmap = null,
            label = "彩色图",
            waiting = "质量门测试",
            showOcrFrame = true,
            captureQuality = quality,
            onRoiChanged = {},
        )
        metrics?.let { VinDepthMetricsStrip(metrics = it, captured = false) }
        if (ready) {
            VinReadyGuide(VinAutoCaptureDecision.Stabilizing(readyFrames = 3, stableDurationUs = 400_000L))
        } else {
            VinActionNotice(VinNoticeUi(vinCaptureGuidance(quality), StatusTone.Warn))
        }
        VinShutterAction(
            capturing = false,
            enabled = ready,
            waitingForDevice = false,
            waitingForDistance = quality is VinCaptureQuality.TooFar,
            waitingForQuality = !ready && quality !is VinCaptureQuality.TooFar,
            onClick = {},
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }
}

@Composable
private fun VinPreviewPane(
    bitmap: Bitmap?,
    label: String,
    waiting: String,
    showOcrFrame: Boolean = false,
    captureQuality: VinCaptureQuality = VinCaptureQuality.Waiting,
    onRoiChanged: ((VinPreviewRoi) -> Unit)? = null,
) {
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    val insetPx = with(LocalDensity.current) { 20.dp.toPx() }
    val roi = remember(viewportSize, insetPx, showOcrFrame) {
        if (showOcrFrame) {
            vinPreviewRoi(
                viewportWidthPx = viewportSize.width.toFloat(),
                viewportHeightPx = viewportSize.height.toFloat(),
                imageAspect = VINCREATOR_STREAM_ASPECT,
                insetPx = insetPx,
            )
        } else {
            null
        }
    }
    val currentOnRoiChanged by rememberUpdatedState(onRoiChanged)
    LaunchedEffect(roi) {
        if (roi != null) currentOnRoiChanged?.invoke(roi)
    }
    val captureReady = captureQuality is VinCaptureQuality.Ready
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(VINCREATOR_VIEWPORT_ASPECT)
            .onSizeChanged { viewportSize = it }
            .semantics(mergeDescendants = true) {
                contentDescription = if (showOcrFrame) {
                    "VIN${label}预览框，拍照区域${if (captureReady) "已就绪" else "未就绪"}"
                } else {
                    "VIN${label}预览框"
                }
            }
            .background(Gomob.colors.bg2),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "VIN $label",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            PaneWaiting(waiting)
        }
        if (showOcrFrame && roi != null) {
            VinOcrFrame(
                imageAspect = VINCREATOR_STREAM_ASPECT,
                roi = roi,
                ready = captureReady,
            )
        }
        PaneTag(label)
    }
}

@Composable
private fun PaneWaiting(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = Gomob.type.caption, color = Gomob.colors.fg3)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PaneTag(label: String) {
    Text(
        label,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(Gomob.spacing.s8)
            .background(VinViewportTagBg)
            .padding(horizontal = Gomob.spacing.s6, vertical = Gomob.spacing.s2),
        style = Gomob.type.micro.copy(fontSize = 10.sp),
        color = VinViewportText,
    )
}

/** VINCreator 彩色取景指示：红框表示不可拍，框内深度达标后整体变绿。 */
@Composable
private fun VinOcrFrame(imageAspect: Float, roi: VinPreviewRoi, ready: Boolean) {
    val frameColor = if (ready) VinCreatorRoiGreen else VinCreatorRoiRed
    Canvas(Modifier.fillMaxSize()) {
        if (imageAspect <= 0f || !roi.isValid || size.width <= 0f || size.height <= 0f) return@Canvas
        val imageRect = vinFitImageRect(size.width, size.height, imageAspect)
        val stroke = 2.dp.toPx()
        drawRect(
            color = frameColor,
            topLeft = Offset(
                imageRect.left + roi.left * imageRect.width,
                imageRect.top + roi.top * imageRect.height,
            ),
            size = androidx.compose.ui.geometry.Size(
                ((roi.right - roi.left) * imageRect.width).coerceAtLeast(0f),
                ((roi.bottom - roi.top) * imageRect.height).coerceAtLeast(0f),
            ),
            style = Stroke(width = stroke),
        )
        val center = Offset(imageRect.left + imageRect.width / 2f, imageRect.top + imageRect.height / 2f)
        val outerRadius = 10.dp.toPx()
        val innerRadius = 8.dp.toPx()
        val ring = Path().apply {
            fillType = PathFillType.EvenOdd
            addOval(Rect(center, outerRadius))
            addOval(Rect(center, innerRadius))
        }
        drawPath(ring, frameColor)
        drawCircle(color = frameColor, radius = 2.dp.toPx(), center = center)
    }
}

/** StatusTone → 语义前景色。 */
@Composable
private fun statusToneFg(tone: StatusTone): Color = when (tone) {
    StatusTone.Neutral -> Gomob.colors.fg2
    StatusTone.Accent -> Gomob.colors.accent
    StatusTone.Warn -> Gomob.colors.warn
    StatusTone.Danger -> Gomob.colors.danger
    StatusTone.Ok -> Gomob.colors.ok
}

/** StatusTone → 语义 soft 底色。 */
@Composable
private fun statusToneSoftBg(tone: StatusTone): Color = when (tone) {
    StatusTone.Neutral -> Gomob.colors.bg2
    StatusTone.Accent -> Gomob.colors.accentSoft
    StatusTone.Warn -> Gomob.colors.warnSoft
    StatusTone.Danger -> Gomob.colors.dangerSoft
    StatusTone.Ok -> Gomob.colors.okSoft
}

private data class VinNoticeUi(
    val text: String,
    val tone: StatusTone,
    val actionLabel: String? = null,
)

private fun vinNotice(
    captureMsg: String?,
    captureReadiness: VinCaptureReadiness,
    captureQuality: VinCaptureQuality,
    restoreState: VinRestoreState,
    vinState: VinCaptureState,
    hasCameraPermission: Boolean,
): VinNoticeUi? = when {
    vinState is VinCaptureState.Error -> VinNoticeUi(vinState.msg, StatusTone.Danger)
    vinState is VinCaptureState.Recognizing -> VinNoticeUi("正在识别算法切割图…", StatusTone.Accent)
    restoreState is VinRestoreState.Rejected -> VinNoticeUi(restoreState.msg, StatusTone.Warn)
    restoreState is VinRestoreState.Error -> VinNoticeUi(restoreState.msg, StatusTone.Danger)
    restoreState is VinRestoreState.Processing -> VinNoticeUi(
        captureMsg ?: "正在生成规范化还原图…",
        StatusTone.Accent,
    )
    restoreState is VinRestoreState.Preview && !hasCameraPermission ->
        VinNoticeUi(captureMsg ?: "需要相机权限才能使用 VIN RGBD 相机", StatusTone.Warn, "重新授权")
    restoreState is VinRestoreState.Preview && !captureReadiness.ready ->
        VinNoticeUi(captureMsg ?: captureReadiness.message, StatusTone.Warn)
    restoreState is VinRestoreState.Preview && captureQuality !is VinCaptureQuality.Ready ->
        VinNoticeUi(vinCaptureGuidance(captureQuality), StatusTone.Warn)
    else -> null
}

@Composable
private fun VinActionNotice(notice: VinNoticeUi, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.pageGutter)
            .clip(Gomob.shapes.r2)
            .background(statusToneSoftBg(notice.tone))
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        Text(
            notice.text,
            modifier = Modifier.weight(1f),
            style = Gomob.type.caption,
            color = statusToneFg(notice.tone),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (notice.actionLabel != null && onAction != null) {
            Text(
                notice.actionLabel,
                modifier = Modifier
                    .clip(Gomob.shapes.r1)
                    .clickable(onClick = onAction)
                    .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s4),
                style = Gomob.type.caption,
                color = statusToneFg(notice.tone),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun VinOutcomePanel(
    rubbing: Bitmap,
    recognition: VinCaptureState.Result?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.pageGutter)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1)
            .border(BorderStroke(Gomob.spacing.hairline, Gomob.colors.line2), Gomob.shapes.r2)
            .semantics { contentDescription = "VIN 还原与识别结果" }
            .padding(Gomob.spacing.s8),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        recognition?.let { state ->
            VinRecognitionSummary(state)
            if (state.result.status == VinRecognitionStatus.NeedsReview) {
                Text(
                    "VIN 格式或字符数异常，请人工复核",
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Gomob.colors.warnSoft)
                        .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s6),
                    style = Gomob.type.micro,
                    color = Gomob.colors.warn,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Text(
            "规范化还原 · ${VINCREATOR_RESTORE_W}×$VINCREATOR_RESTORE_H",
            modifier = Modifier.fillMaxWidth(),
            style = Gomob.type.micro,
            color = Gomob.colors.fg3,
        )
        VinEvidenceImage(
            bitmap = rubbing,
            aspectRatio = VINCREATOR_RESTORE_ASPECT,
            contentDescription = "VIN规范化还原图",
        )
        recognition?.let { state ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Gomob.spacing.s2),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("单字符证据", style = Gomob.type.micro, color = Gomob.colors.fg2)
                Text(
                    "${state.crops.size}/17 · 左右滑动",
                    style = Gomob.type.micro,
                    color = Gomob.colors.fg3,
                )
            }
            VinCharacterCropStrip(state.crops)
        }
    }
}

@Composable
private fun VinRecognitionSummary(state: VinCaptureState.Result) {
    val result = state.result
    val weakest = state.crops.minByOrNull { it.confidence }
    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            Text(
                result.vin,
                modifier = Modifier
                    .weight(1f)
                    .semantics { contentDescription = "识别 VIN ${result.vin}" },
                style = Gomob.type.metricMd.copy(fontSize = 18.sp),
                color = Gomob.colors.fg0,
                maxLines = 1,
                overflow = TextOverflow.Clip,
            )
            VinRecognitionStatusBadge(result.status)
        }
        Text(
            buildString {
                append("%d/17 字符 · 平均 %.1f%%".format(result.characterCount, confidenceToPct(result.confidence)))
                weakest?.let {
                    append(
                        " · 最低 #%02d %s %.1f%%".format(
                            it.position,
                            it.character,
                            confidenceToPct(it.confidence),
                        ),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            style = Gomob.type.micro,
            color = Gomob.colors.fg2,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun VinRecognitionStatusBadge(status: VinRecognitionStatus) {
    val completed = status == VinRecognitionStatus.Completed
    val tone = if (completed) StatusTone.Ok else StatusTone.Warn
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(statusToneSoftBg(tone))
            .padding(horizontal = Gomob.spacing.s6, vertical = Gomob.spacing.s2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
    ) {
        Box(Modifier.size(Gomob.spacing.dot4).clip(CircleShape).background(statusToneFg(tone)))
        Text(
            if (completed) "已完成" else "需复核",
            style = Gomob.type.micro,
            color = statusToneFg(tone),
            maxLines = 1,
        )
    }
}

@Composable
internal fun VinCharacterCropStrip(crops: List<VinCharacterCropPreview>) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "VIN单字符切割图，共${crops.size}张，可左右滑动"
        },
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        items(items = crops, key = { it.position }) { crop ->
            VinCharacterCropCell(crop)
        }
    }
}

@Composable
private fun VinCharacterCropCell(crop: VinCharacterCropPreview) {
    val confidencePct = confidenceToPct(crop.confidence)
    Box(
        modifier = Modifier
            .width(42.dp)
            .aspectRatio(0.5f)
            .clip(Gomob.shapes.r1)
            .background(VinViewportBg)
            .border(BorderStroke(Gomob.spacing.hairline, Gomob.colors.line2), Gomob.shapes.r1)
            .semantics(mergeDescendants = true) {
                contentDescription = "第${crop.position}位字符${crop.character}，置信度%.1f%%".format(confidencePct)
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = crop.bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Text(
            "%02d".format(crop.position),
            modifier = Modifier
                .align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.52f))
                .padding(horizontal = Gomob.spacing.s4, vertical = Gomob.spacing.s2),
            style = Gomob.type.micro.copy(fontSize = 8.sp),
            color = Color.White.copy(alpha = 0.88f),
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.58f))
                .padding(horizontal = Gomob.spacing.s4, vertical = Gomob.spacing.s2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                crop.character,
                style = Gomob.type.metricMd.copy(fontSize = 13.sp),
                color = Color.White,
                maxLines = 1,
            )
            Text(
                "%.0f%%".format(confidencePct),
                style = Gomob.type.micro.copy(fontSize = 7.sp),
                color = Color.White.copy(alpha = 0.78f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun VinEvidenceImage(
    bitmap: Bitmap,
    aspectRatio: Float,
    contentDescription: String,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio.coerceIn(2f, 10f))
            .background(VinViewportBg)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}

// 新契约严格使用 0..1 OCR 置信度，展示时换算成百分比。
private fun confidenceToPct(value: Double): Float = (value * 100.0).toFloat()

// ─── 底部拍照栏：重拍 / 快门(→正射拓印) / 识别(→外部 OCR) ───
@Composable
private fun VinCaptureBar(
    capturing: Boolean,
    captureReadiness: VinCaptureReadiness,
    captureQuality: VinCaptureQuality,
    restoreState: VinRestoreState,
    vinState: VinCaptureState,
    onShutter: () -> Unit,
    onRetake: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 底色由调用处 glassChrome 玻璃负责，这里只留内边距（导航栏 inset 也在调用处吃掉）。
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, top = 10.dp, end = 24.dp, bottom = 12.dp),
    ) {
        when {
            restoreState is VinRestoreState.Preview || restoreState is VinRestoreState.Processing -> {
                val qualityReady = captureQuality is VinCaptureQuality.Ready
                VinShutterAction(
                    capturing = capturing,
                    enabled = restoreState is VinRestoreState.Preview &&
                        captureReadiness.ready && qualityReady && !capturing,
                    waitingForDevice = !captureReadiness.ready,
                    waitingForDistance = captureReadiness.ready && captureQuality is VinCaptureQuality.TooFar,
                    waitingForQuality = captureReadiness.ready &&
                        !qualityReady && captureQuality !is VinCaptureQuality.TooFar,
                    onClick = onShutter,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            restoreState is VinRestoreState.Rejected || restoreState is VinRestoreState.Error -> {
                VinRoundButton(
                    icon = GomobIcons.Refresh,
                    label = "调整后重拍",
                    onClick = onShutter,
                    primary = true,
                    enabled = captureReadiness.ready &&
                        captureQuality is VinCaptureQuality.Ready && !capturing,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            vinState is VinCaptureState.Result -> {
                VinRoundButton(
                    icon = GomobIcons.Refresh,
                    label = "重新扫描",
                    onClick = onRetake,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            vinState is VinCaptureState.Error -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
                ) {
                    VinRoundButton(
                        icon = GomobIcons.Refresh,
                        label = "重新扫描",
                        onClick = onRetake,
                        modifier = Modifier.weight(1f),
                    )
                    VinRoundButton(
                        icon = GomobIcons.Check,
                        label = "重试识别",
                        primary = true,
                        enabled = !capturing,
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
                ) {
                    VinRoundButton(
                        icon = GomobIcons.Refresh,
                        label = "重新扫描",
                        onClick = onRetake,
                        modifier = Modifier.weight(1f),
                    )
                    VinRoundButton(
                        icon = GomobIcons.Check,
                        label = "自动识别中",
                        primary = true,
                        enabled = false,
                        onClick = {},
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun VinRoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // 三态：主按钮启用 = 实心 accent + 白字；禁用 = line2 边 + 半透明 bg1 + fg3；次按钮 = lineStrong 边 + bg1
    val solidPrimary = primary && enabled
    val fg = when {
        solidPrimary -> Color.White
        !enabled -> Gomob.colors.fg3
        else -> Gomob.colors.fg1
    }
    val bg = when {
        solidPrimary -> Gomob.colors.accent
        !enabled -> Gomob.colors.bg1.copy(alpha = 0.6f)
        else -> Gomob.colors.bg1
    }
    val borderColor = when {
        solidPrimary -> Gomob.colors.accent
        !enabled -> Gomob.colors.line2
        else -> Gomob.colors.lineStrong
    }
    Row(
        modifier = modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(bg)
            .border(BorderStroke(1.dp, borderColor), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = fg,
            modifier = Modifier.size(14.dp),
        )
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = fg)
    }
}

@Composable
private fun VinShutterAction(
    capturing: Boolean,
    enabled: Boolean,
    waitingForDevice: Boolean,
    waitingForDistance: Boolean,
    waitingForQuality: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
    ) {
        VinShutterButton(capturing = capturing, enabled = enabled, onClick = onClick)
        Text(
            when {
                capturing -> "采集中"
                waitingForDevice -> "等待设备"
                waitingForDistance -> "调整距离"
                waitingForQuality -> "调整取景"
                else -> "稳住不动"
            },
            style = Gomob.type.micro,
            color = if (enabled) Gomob.colors.fg2 else Gomob.colors.fg3,
        )
    }
}

@Composable
private fun VinShutterButton(capturing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    // 外环 72dp accentLine 细环 + 内 58dp 实心 accent 圆（白描边）；拍照中/禁用降透明示意忙
    Box(
        modifier = Modifier
            .size(Gomob.spacing.btnCircle72)
            .clip(CircleShape)
            .border(BorderStroke(2.dp, Gomob.colors.accentLine), CircleShape)
            .semantics { contentDescription = "VIN拍照按钮，${if (enabled) "已激活" else "未激活"}" }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(Gomob.colors.accent.copy(alpha = if (enabled && !capturing) 1f else 0.35f))
                .border(BorderStroke(3.dp, Color.White.copy(alpha = 0.9f)), CircleShape),
        )
    }
}
