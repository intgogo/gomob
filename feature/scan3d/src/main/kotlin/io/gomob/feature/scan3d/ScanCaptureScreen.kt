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
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

// 占位 VIN（拓印纸面/字符比对/汇总 三块的演示值）。
// ★ TODO(逐项接真)：用户将逐个指定这些块怎么接真实数据（拍照→native 正射拓印 M4 + 服务端 vin_pipeline OCR/字形比对）。
//   当前先按原设计还原版式；顶部彩色/深度两个横条已接真实相机帧（见 VinColorPane/VinDepthPane）。
private const val VinValue = "LFV2A21K9P5012345"

/**
 * VIN 数码拓印（按原设计还原多面板版式）。
 *
 * 顶部两个横条 = 真实相机帧：彩色图（[VinCaptureViewModel.colorPreview]）+ 深度图（[VinCaptureViewModel.depthPreview]）。
 * 拓印纸面 / 单字符比对 / 汇总结论 = 原设计版式（演示数据，待逐项接真）。底部拍照栏。
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

    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(title = "VIN 数码拓印", eyebrow = "三维扫描", onBack = onBack)
        Column(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                item { VinDepthPane(depthBmp = depthBmp) }
                item { VinColorPane(colorBmp = colorBmp) }
                item { VinRubbing(rubbing = rubbing, captureMsg = captureMsg, processing = capturing) }
                item { Spacer(Modifier.height(8.dp)) }
            }
            VinCaptureBar(capturing = capturing, onShutter = vm::capture, onRetake = vm::retake)
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
private fun VinRubbing(rubbing: Bitmap?, captureMsg: String?, processing: Boolean) {
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
        // TODO(接真·OCR): VinCharCompare 改为服务端 vin_pipeline 逐字符比对结果。
        VinCharCompare(vin = VinValue)
        // TODO(接真·OCR): VinSummary 改为真实 verdict / 厂商库比对汇总。
        VinSummary(vin = VinValue)
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

@Composable
private fun VinCharCompare(vin: String) {
    val sims = listOf(99.4f, 98.7f, 97.2f, 96.5f, 99.1f, 98.0f, 95.4f, 97.8f, 92.1f, 98.3f, 96.7f, 91.8f, 88.3f, 94.6f, 97.5f, 96.2f, 98.9f)
    val ocr = listOf("L", "F", "V", "2", "A", "2", "1", "K", "9", "P", "5", "0", "1", "2", "3", "4", "5")
    Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("单字符切割 · 字形比对 · OCR", style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.14.em), color = Gomob.colors.fg3)
            Text("第 9 位校验", style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.06.em), color = Gomob.colors.ok)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            vin.forEachIndexed { index, ch ->
                VinCharCell(
                    modifier = Modifier.weight(1f),
                    ch = ch.toString(),
                    ocr = ocr[index],
                    sim = sims[index],
                    index = index,
                    isCheck = index == 8,
                )
            }
        }
    }
}

@Composable
private fun VinCharCell(
    modifier: Modifier,
    ch: String,
    ocr: String,
    sim: Float,
    index: Int,
    isCheck: Boolean,
) {
    val ok = sim >= 95f
    val warn = sim >= 90f && sim < 95f
    val tone = when {
        ok -> Gomob.colors.ok
        warn -> Gomob.colors.warn
        else -> Gomob.colors.danger
    }
    Column(
        modifier = modifier
            .clip(Gomob.shapes.r1)
            .background(Gomob.colors.bg2)
            .border(BorderStroke(1.dp, if (isCheck) Gomob.colors.okLine else Gomob.colors.line1), Gomob.shapes.r1),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            (index + 1).toString().padStart(2, '0'),
            modifier = Modifier
                .fillMaxWidth()
                .background(if (isCheck) Gomob.colors.okSoft else Gomob.colors.bg1)
                .padding(vertical = 2.dp),
            textAlign = TextAlign.Center,
            style = Gomob.type.numInline.copy(fontSize = 7.sp),
            color = if (isCheck) Gomob.colors.ok else Gomob.colors.fg3,
        )
        Text(
            ch,
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .background(Color(0xFFE2D3A9))
                .padding(top = 2.dp),
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Color(0xFF2E2417),
            maxLines = 1,
        )
        Text(
            ch,
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .padding(top = 2.dp),
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = Gomob.colors.fg1,
            maxLines = 1,
        )
        Text(
            ocr,
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .background(if (ocr == ch) Gomob.colors.accentSoft else Gomob.colors.dangerSoft)
                .padding(top = 1.dp),
            textAlign = TextAlign.Center,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            color = if (ocr == ch) Gomob.colors.accent else Gomob.colors.danger,
            maxLines = 1,
        )
        Text(
            String.format(java.util.Locale.US, "%.1f", sim),
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            textAlign = TextAlign.Center,
            style = Gomob.type.numInline.copy(fontSize = 8.sp),
            color = tone,
            maxLines = 1,
        )
        Box(Modifier.fillMaxWidth().height(2.dp).background(Gomob.colors.line1)) {
            Box(Modifier.fillMaxWidth(sim / 100f).height(2.dp).background(tone))
        }
    }
}

@Composable
private fun VinSummary(vin: String) {
    val rows = listOf(
        SummaryItem("厂商", vin.take(3), "一汽-大众", Gomob.colors.accent),
        SummaryItem("校验", "#9", "通过", Gomob.colors.ok),
        SummaryItem("年份", vin[9].toString(), "2023 款", Gomob.colors.accent),
        SummaryItem("字形", "96.4", "整体匹配", Gomob.colors.ok),
    )
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
                    "VIN ${vin.takeLast(6)} · 正射拓印可信",
                    style = Gomob.type.numInline.copy(fontSize = 8.sp, letterSpacing = 0.06.em),
                    color = Gomob.colors.fg3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(Gomob.colors.ok))
                Text("全部通过", style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.1.em), color = Gomob.colors.ok)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SummaryCell(rows[0], Modifier.weight(1f))
            SummaryCell(rows[1], Modifier.weight(1f))
            SummaryCell(rows[2], Modifier.weight(1f))
            SummaryCell(rows[3], Modifier.weight(1f))
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

// ─── 底部拍照栏：重拍 / 快门(→正射拓印) / 确认(→OCR，待接) ───
@Composable
private fun VinCaptureBar(
    capturing: Boolean,
    onShutter: () -> Unit,
    onRetake: () -> Unit,
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
            // TODO(接真·OCR): 「确认」接 vm.recognize()（拓印图喂 vin_pipeline）—— 待用户指定该步交互。
            VinRoundButton(icon = GomobIcons.Check, label = "确认", primary = true, onClick = {})
        }
    }
}

@Composable
private fun VinRoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (primary) Gomob.colors.accentSoft else Gomob.colors.bg1)
            .border(BorderStroke(1.dp, if (primary) Gomob.colors.accent else Gomob.colors.line2), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (primary) Gomob.colors.accent else Gomob.colors.fg1,
            modifier = Modifier.size(14.dp),
        )
        Text(label, fontSize = 12.sp, color = if (primary) Gomob.colors.accent else Gomob.colors.fg1)
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
