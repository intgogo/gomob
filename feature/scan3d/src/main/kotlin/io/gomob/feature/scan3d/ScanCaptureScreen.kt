package io.gomob.feature.scan3d

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.data.scan.VinCharResult
import io.gomob.data.scan.VinResult
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.nativebridge.camera.CameraSourceState

@Composable
fun ScanCaptureRoute(
    onBack: () -> Unit,
    vm: VinCaptureViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val colorBmp by vm.colorPreview.collectAsStateWithLifecycle()
    val deviceState by vm.deviceState.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(title = "VIN 数码拓印", eyebrow = "三维扫描", onBack = onBack)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (val s = state) {
                VinCaptureState.Preview -> PreviewBody(
                    colorBmp = colorBmp,
                    deviceReady = deviceState is CameraSourceState.Streaming,
                    onCapture = vm::capture,
                )
                VinCaptureState.Recognizing -> RecognizingBody()
                is VinCaptureState.Result -> ResultBody(state = s, onRetake = vm::retake)
                is VinCaptureState.Error -> ErrorBody(msg = s.msg, onRetake = vm::retake)
            }
        }
    }
}

@Composable
private fun PreviewBody(
    colorBmp: android.graphics.Bitmap?,
    deviceReady: Boolean,
    onCapture: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(16.dp)
                .clip(Gomob.shapes.r3)
                .background(Color(0xFF0F1117)),
        ) {
            if (colorBmp != null) {
                Image(
                    bitmap = colorBmp.asImageBitmap(),
                    contentDescription = "VIN 取景",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("等待相机…", style = Gomob.type.numInline.copy(fontSize = 12.sp), color = Gomob.colors.fg3)
                }
            }
            // 取景引导框：提示用户把 VIN 钢印放进框内。
            Canvas(Modifier.fillMaxSize().padding(24.dp)) {
                drawRoundRect(
                    color = Color(0xFF6BD6FF).copy(alpha = 0.7f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx(), 8.dp.toPx()),
                    style = Stroke(width = 1.2.dp.toPx()),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, size.height * 0.38f),
                    size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.24f),
                )
            }
            Text(
                "对准车架号(VIN)钢印，放入框内拍照",
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .clip(Gomob.shapes.r1)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                style = Gomob.type.numInline.copy(fontSize = 10.sp),
                color = Gomob.colors.accent,
            )
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
            VinShutter(enabled = deviceReady, onClick = onCapture)
        }
    }
}

@Composable
private fun RecognizingBody() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = Gomob.colors.accent)
            Text("整图识别 + 厂家字形比对中…", style = Gomob.type.numInline.copy(fontSize = 13.sp), color = Gomob.colors.fg1)
        }
    }
}

@Composable
private fun ResultBody(
    state: VinCaptureState.Result,
    onRetake: () -> Unit,
) {
    val r = state.result
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Image(
                    bitmap = state.capture.asImageBitmap(),
                    contentDescription = "VIN 拍照",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(Gomob.shapes.r3)
                        .background(Color(0xFF0F1117)),
                    contentScale = ContentScale.Fit,
                )
            }
            item { VerdictCard(r) }
            if (r.characters.isNotEmpty()) {
                item {
                    Text("字符比对（${r.scored}/${r.detections}）", style = Gomob.type.numInline.copy(fontSize = 11.sp, letterSpacing = 0.06.em), color = Gomob.colors.fg2)
                }
                items(r.characters.size) { i -> CharRow(r.characters[i]) }
            }
            if (r.reasons.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("说明", style = Gomob.type.numInline.copy(fontSize = 11.sp), color = Gomob.colors.fg2)
                        r.reasons.forEach {
                            Text("· $it", style = Gomob.type.numInline.copy(fontSize = 11.sp), color = Gomob.colors.fg3)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(4.dp)) }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
            PillButton(icon = GomobIcons.Refresh, label = "重拍", onClick = onRetake)
        }
    }
}

@Composable
private fun VerdictCard(r: VinResult) {
    val (color, label) = when (r.verdict) {
        "pass" -> Gomob.colors.ok to "通过"
        "warning" -> Color(0xFFE0A030) to "存疑"
        else -> Gomob.colors.danger to "不通过"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .border(BorderStroke(1.dp, color.copy(alpha = 0.5f)), Gomob.shapes.r3)
            .background(color.copy(alpha = 0.08f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.clip(Gomob.shapes.r1).background(color.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = color)
            }
            Text("厂家字形一致性核验", style = Gomob.type.numInline.copy(fontSize = 11.sp), color = Gomob.colors.fg2)
        }
        Text(
            text = r.recognizedVin.ifBlank { "（未识别到字符）" },
            style = Gomob.type.numInline.copy(fontSize = 20.sp, letterSpacing = 0.12.em),
            fontFamily = FontFamily.Monospace,
            color = Gomob.colors.fg1,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Metric("平均相似度", "%.1f%%".format(r.avgSimilarity * 100))
            Metric("最低相似度", "%.1f%%".format(r.minSimilarity * 100))
            Metric("识别字符", "${r.scored}/${r.detections}")
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(value, style = Gomob.type.numInline.copy(fontSize = 13.sp), color = Gomob.colors.accentStrong)
        Text(label, style = Gomob.type.numInline.copy(fontSize = 9.sp), color = Gomob.colors.fg3)
    }
}

@Composable
private fun CharRow(c: VinCharResult) {
    val ok = c.status == "scored"
    val barColor = when {
        !ok -> Gomob.colors.fg3
        c.similarity >= 0.85 -> Gomob.colors.ok
        c.similarity >= 0.60 -> Color(0xFFE0A030)
        else -> Gomob.colors.danger
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.size(26.dp).clip(Gomob.shapes.r1).background(Gomob.colors.bg2),
            contentAlignment = Alignment.Center,
        ) {
            Text(c.character.ifBlank { "?" }, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = Gomob.colors.fg1)
        }
        Box(Modifier.weight(1f).height(6.dp).clip(Gomob.shapes.r1).background(Gomob.colors.bg3)) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(if (ok) c.similarity.coerceIn(0.0, 1.0).toFloat() else 0f)
                    .clip(Gomob.shapes.r1)
                    .background(barColor),
            )
        }
        Text(
            if (ok) "%.0f%%".format(c.similarity * 100) else c.status,
            style = Gomob.type.numInline.copy(fontSize = 10.sp),
            color = barColor,
        )
    }
}

@Composable
private fun ErrorBody(msg: String, onRetake: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(msg, style = Gomob.type.numInline.copy(fontSize = 13.sp), color = Gomob.colors.danger, textAlign = TextAlign.Center)
            PillButton(icon = GomobIcons.Refresh, label = "重拍", onClick = onRetake)
        }
    }
}

@Composable
private fun VinShutter(enabled: Boolean, onClick: () -> Unit) {
    val ring = if (enabled) Gomob.colors.accent else Gomob.colors.line2
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(Gomob.colors.bg0)
            .border(BorderStroke(2.dp, ring), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(ring))
    }
}

@Composable
private fun PillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(Gomob.colors.accentSoft)
            .border(BorderStroke(1.dp, Gomob.colors.accent), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = label, tint = Gomob.colors.accent, modifier = Modifier.size(14.dp))
        Text(label, fontSize = 13.sp, color = Gomob.colors.accent)
    }
}
