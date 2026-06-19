package io.gomob.feature.scan3d

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.data.scan.VinResult
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

/**
 * VIN 数码拓印（按原设计还原多面板版式）。
 *
 * 顶部两个横条 = 真实相机帧：彩色图（[VinCaptureViewModel.colorPreview]）+ 深度图（[VinCaptureViewModel.depthPreview]）。
 * 拓印纸面 = 服务端原厂全保真还原签名（拍照→上传→还原回显）；单字符比对 / 汇总结论 = 真实 vin_pipeline OCR（点「确认」识别）。
 */
@Composable
fun ScanCaptureRoute(
    onBack: () -> Unit,
    vm: VinCaptureViewModel = hiltViewModel(),
) {
    val colorBmp by vm.colorPreview.collectAsStateWithLifecycle()
    val depthBmp by vm.depthPreview.collectAsStateWithLifecycle()
    val rubbing by vm.rubbing.collectAsStateWithLifecycle()
    val capturing by vm.capturing.collectAsStateWithLifecycle()
    val captureMsg by vm.captureMsg.collectAsStateWithLifecycle()
    val vinState by vm.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(title = "VIN 数码拓印", eyebrow = "三维扫描", onBack = onBack)
        Column(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                item { VinDepthPane(depthBmp = depthBmp) }
                item { VinColorPane(colorBmp = colorBmp) }
                item { VinRubbing(rubbing = rubbing, captureMsg = captureMsg, processing = capturing, vinState = vinState) }
                item { Spacer(Modifier.height(8.dp)) }
            }
            VinCaptureBar(
                capturing = capturing,
                recognizing = vinState is VinCaptureState.Recognizing,
                canConfirm = rubbing != null,
                onShutter = vm::capture,
                onRetake = vm::retake,
                onConfirm = vm::recognize,
            )
        }
    }
}

// 面板固定 3.5:1：原帧约 5:1，Crop 按高填满、左右多余视野裁掉（只取中间需要的部分，不留黑边）。
private const val PANE_ASPECT = 3.5f

// ─── 横条：深度图（真实深度帧 turbo 伪彩）───
@Composable
private fun VinDepthPane(depthBmp: Bitmap?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .aspectRatio(PANE_ASPECT)
            .clip(Gomob.shapes.r3)
            .background(Color(0xFF06090E)),
    ) {
        if (depthBmp != null) {
            Image(
                bitmap = depthBmp.asImageBitmap(),
                contentDescription = "VIN 深度",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            PaneWaiting("等待深度帧…")
        }
        PaneTag("深度图")
    }
}

// ─── 横条：彩色图（真实彩色帧 HLSD8 RGB + OCR 取景框）───
@Composable
private fun VinColorPane(colorBmp: Bitmap?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .aspectRatio(PANE_ASPECT)
            .clip(Gomob.shapes.r3)
            .background(Color(0xFF080A0E)),
    ) {
        if (colorBmp != null) {
            Image(
                bitmap = colorBmp.asImageBitmap(),
                contentDescription = "VIN 彩色取景",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            PaneWaiting("等待彩色帧…")
        }
        VinOcrFrame()
        PaneTag("彩色图")
    }
}

@Composable
private fun PaneWaiting(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = Gomob.type.numInline.copy(fontSize = 11.sp), color = Gomob.colors.fg3)
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PaneTag(label: String) {
    Text(
        label,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(8.dp)
            .clip(Gomob.shapes.r1)
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.08.em),
        color = Gomob.colors.fg2,
    )
}

@Composable
private fun VinOcrFrame() {
    val acc = Gomob.colors.accent
    Canvas(Modifier.fillMaxSize()) {
        val left = size.width * 0.1375f
        val top = size.height * 0.47f
        val right = size.width * 0.8625f
        val bottom = size.height * 0.61f
        val len = 7.dp.toPx()
        listOf(
            Offset(left, top) to Pair(1f, 1f),
            Offset(right, top) to Pair(-1f, 1f),
            Offset(left, bottom) to Pair(1f, -1f),
            Offset(right, bottom) to Pair(-1f, -1f),
        ).forEach { (p, dir) ->
            drawLine(acc, p, Offset(p.x + len * dir.first, p.y), strokeWidth = 0.7.dp.toPx())
            drawLine(acc, p, Offset(p.x, p.y + len * dir.second), strokeWidth = 0.7.dp.toPx())
        }
        drawLine(acc.copy(alpha = 0.5f), Offset(left, (top + bottom) / 2f), Offset(right, (top + bottom) / 2f), strokeWidth = 0.3.dp.toPx())
    }
}

// ─── 数码拓印块：拍照 → native 正射 → 还原图显示 + 字符比对/汇总（后两块仍演示数据，待逐项接真）───
@Composable
private fun VinRubbing(rubbing: Bitmap?, captureMsg: String?, processing: Boolean, vinState: VinCaptureState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp)
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Gomob.colors.bg1)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("数码拓印", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
                Text(
                    "1:1 还原",
                    style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.14.em),
                    color = Gomob.colors.fg3,
                    modifier = Modifier
                        .clip(Gomob.shapes.r1)
                        .border(BorderStroke(1.dp, Gomob.colors.line2), Gomob.shapes.r1)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            // 状态：拍照中=accent「正在处理」/ 已出图=ok「已生成」/ 否则 fg3「待拍照」。
            val (dot, label) = when {
                processing -> Gomob.colors.accent to "正在处理"
                rubbing != null -> Gomob.colors.ok to "已生成"
                else -> Gomob.colors.fg3 to "待拍照"
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(dot))
                Text(label, style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.04.em), color = dot)
            }
        }
        // 拓印还原图：native 双相机正射输出（透明处透出纸面纹理）。
        RubbingPaper(rubbing = rubbing)
        if (captureMsg != null) {
            Text(
                captureMsg,
                style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.04.em),
                color = Gomob.colors.fg3,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
        // 真实 vin_pipeline OCR：点「确认」后出逐字符相似度 + verdict 汇总（待识别/识别中/结果/错误四态）。
        val result = (vinState as? VinCaptureState.Result)?.result
        VinCharCompare(result = result, recognizing = vinState is VinCaptureState.Recognizing)
        VinSummary(result = result, vinState = vinState)
    }
}

@Composable
private fun RubbingPaper(rubbing: Bitmap?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(2f)  // 正射输出 1024×512 = 2:1
            .background(Color(0xFFE8DFC0)),
        contentAlignment = Alignment.Center,
    ) {
        // 纸面纹理（恒在；还原图透明处透出，营造拓印纸感）。
        Canvas(Modifier.fillMaxSize()) {
            repeat(160) { i ->
                drawCircle(
                    color = Color(0xFF503C28).copy(alpha = 0.16f),
                    radius = 0.4.dp.toPx(),
                    center = Offset(size.width * ((i * 7) % 200) / 200f, size.height * ((i * 11) % 120) / 120f),
                )
            }
        }
        if (rubbing != null) {
            Image(
                bitmap = rubbing.asImageBitmap(),
                contentDescription = "VIN 拓印还原图",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                "点击下方快门，深度正射生成拓印还原图",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = Color(0xFF6E5A46),
            )
        }
    }
}

// vin_pipeline 相似度可能是 0..1 或 0..100，归一到 0..100 百分比显示（≤1 视为 0..1）。
private fun simToPct(v: Double): Float = (if (v <= 1.0) v * 100.0 else v).toFloat()

@Composable
private fun VinCharCompare(result: VinResult?, recognizing: Boolean) {
    Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("单字符切割 · 字形比对 · OCR", style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.14.em), color = Gomob.colors.fg3)
            val (tagColor, tag) = when {
                recognizing -> Gomob.colors.accent to "识别中…"
                result != null -> Gomob.colors.ok to "检出 ${result.detections} · 比对 ${result.scored}"
                else -> Gomob.colors.fg3 to "待识别"
            }
            Text(tag, style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.06.em), color = tagColor)
        }
        if (result != null && result.characters.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                result.characters.forEach { c ->
                    VinCharCell(
                        modifier = Modifier.weight(1f),
                        ch = c.character,
                        simPct = simToPct(c.similarity),
                        index = c.index,
                    )
                }
            }
        } else {
            Text(
                if (recognizing) "正在识别还原签名…" else "拍照生成还原图后，点下方「确认」识别",
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                textAlign = TextAlign.Center,
                style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.04.em),
                color = Gomob.colors.fg3,
            )
        }
    }
}

@Composable
private fun VinCharCell(
    modifier: Modifier,
    ch: String,
    simPct: Float,
    index: Int,
) {
    val tone = when {
        simPct >= 95f -> Gomob.colors.ok
        simPct >= 90f -> Gomob.colors.warn
        else -> Gomob.colors.danger
    }
    Column(
        modifier = modifier
            .clip(Gomob.shapes.r1)
            .background(Gomob.colors.bg2)
            .border(BorderStroke(1.dp, Gomob.colors.line1), Gomob.shapes.r1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            (index + 1).toString().padStart(2, '0'),
            modifier = Modifier
                .fillMaxWidth()
                .background(Gomob.colors.bg1)
                .padding(vertical = 2.dp),
            textAlign = TextAlign.Center,
            style = Gomob.type.numInline.copy(fontSize = 7.sp),
            color = Gomob.colors.fg3,
        )
        // OCR 识别字符（vin_pipeline 真返字符；缺字符显「·」）。
        Text(
            ch.ifEmpty { "·" },
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp)
                .background(Color(0xFFE2D3A9))
                .padding(top = 4.dp),
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            color = Color(0xFF2E2417),
            maxLines = 1,
        )
        Text(
            String.format(java.util.Locale.US, "%.0f", simPct),
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            textAlign = TextAlign.Center,
            style = Gomob.type.numInline.copy(fontSize = 8.sp),
            color = tone,
            maxLines = 1,
        )
        Box(Modifier.fillMaxWidth().height(2.dp).background(Gomob.colors.line1)) {
            Box(Modifier.fillMaxWidth((simPct / 100f).coerceIn(0f, 1f)).height(2.dp).background(tone))
        }
    }
}

@Composable
private fun VinSummary(result: VinResult?, vinState: VinCaptureState) {
    // verdict 状态点：pass/通过=ok，其余=danger；未识别=fg3。
    val pass = result != null && (result.verdict.equals("pass", true) || result.verdict == "通过")
    val (tone, label) = when {
        result == null -> Gomob.colors.fg3 to "待识别"
        pass -> Gomob.colors.ok to "通过"
        else -> Gomob.colors.danger to "未通过"
    }
    val sub = when {
        vinState is VinCaptureState.Error -> vinState.msg
        vinState is VinCaptureState.Recognizing -> "识别中…"
        result != null -> "VIN ${result.recognizedVin.ifBlank { "—" }}"
        else -> "拍照还原后，点确认识别"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg2)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text("汇总结论", fontSize = 10.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg1)
                Text(
                    sub,
                    style = Gomob.type.numInline.copy(fontSize = 8.sp, letterSpacing = 0.06.em),
                    color = Gomob.colors.fg3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(tone))
                Text(label, style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.1.em), color = tone)
            }
        }
        if (result != null) {
            // 只渲染 vin_pipeline 真返字段（检出/比对/字形相似/verdict），不编造厂商/年份解码。
            val avgTone = if (simToPct(result.avgSimilarity) >= 95f) Gomob.colors.ok else Gomob.colors.warn
            val reason = result.reasons.firstOrNull() ?: if (pass) "字形匹配" else "—"
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SummaryCell(SummaryItem("检出", result.detections.toString(), "字符检出", Gomob.colors.accent), Modifier.weight(1f))
                SummaryCell(SummaryItem("比对", result.scored.toString(), "已比对", Gomob.colors.accent), Modifier.weight(1f))
                SummaryCell(SummaryItem("字形", String.format(java.util.Locale.US, "%.1f", simToPct(result.avgSimilarity)), "均值相似", avgTone), Modifier.weight(1f))
                SummaryCell(SummaryItem("结论", result.verdict, reason, tone), Modifier.weight(1f))
            }
        }
    }
}

private data class SummaryItem(
    val label: String,
    val tag: String,
    val value: String,
    val tone: Color,
)

@Composable
private fun SummaryCell(item: SummaryItem, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(Gomob.shapes.r1)
            .background(item.tone.copy(alpha = 0.08f))
            .padding(horizontal = 7.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                item.label,
                modifier = Modifier.weight(1f),
                style = Gomob.type.numInline.copy(fontSize = 8.sp, letterSpacing = 0.02.em),
                color = Gomob.colors.fg3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.tag,
                style = Gomob.type.numInline.copy(fontSize = 8.sp, letterSpacing = 0.02.em),
                color = item.tone,
                modifier = Modifier
                    .clip(Gomob.shapes.r1)
                    .background(item.tone.copy(alpha = 0.12f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            item.value,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = Gomob.colors.fg0,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ─── 底部拍照栏：重拍 / 快门(→正射拓印) / 确认(→vin_pipeline OCR) ───
@Composable
private fun VinCaptureBar(
    capturing: Boolean,
    recognizing: Boolean,
    canConfirm: Boolean,
    onShutter: () -> Unit,
    onRetake: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Gomob.colors.bg0), startY = 0f, endY = 90f))
            .padding(start = 24.dp, top = 10.dp, end = 24.dp, bottom = 22.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            VinRoundButton(icon = GomobIcons.Refresh, label = "重拍", onClick = onRetake)
            VinShutterButton(capturing = capturing, onClick = onShutter)
            // 确认 → vm.recognize()：还原图喂 vin_pipeline。无还原图或识别中时禁用。
            VinRoundButton(
                icon = GomobIcons.Check,
                label = if (recognizing) "识别中" else "确认",
                primary = true,
                enabled = canConfirm && !recognizing,
                onClick = onConfirm,
            )
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
) {
    val fg = when {
        !enabled -> Gomob.colors.fg3
        primary -> Gomob.colors.accent
        else -> Gomob.colors.fg1
    }
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (primary && enabled) Gomob.colors.accentSoft else Gomob.colors.bg1)
            .border(BorderStroke(1.dp, if (primary && enabled) Gomob.colors.accent else Gomob.colors.line2), CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = fg,
            modifier = Modifier.size(14.dp),
        )
        Text(label, fontSize = 12.sp, color = fg)
    }
}

@Composable
private fun VinShutterButton(capturing: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(Gomob.colors.bg0)
            .border(BorderStroke(2.dp, Gomob.colors.accent), CircleShape)
            .clickable(enabled = !capturing, onClick = onClick)
            .padding(4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .border(BorderStroke(2.dp, Gomob.colors.accent), CircleShape)
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            // 拍照中：中心点降透明示意忙；空闲：实心 accent。
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(Gomob.colors.accent.copy(alpha = if (capturing) 0.35f else 1f)),
            )
        }
    }
}
