package io.gomob.feature.scan3d

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

private const val VinValue = "LFV2A21K9P5012345"

@Composable
fun ScanCaptureRoute(
    onBack: () -> Unit,
    cameraSlot: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(title = "VIN 数码拓印", eyebrow = "三维扫描", onBack = onBack)
        Column(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = 8.dp),
            ) {
                item { VinRgbPane(vin = VinValue) }
                item { VinDepthPane(vin = VinValue) }
                item { VinRubbing(vin = VinValue) }
                item { Spacer(Modifier.height(8.dp)) }
            }
            VinCaptureBar()
        }
    }
}

@Composable
private fun VinRgbPane(vin: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
            .aspectRatio(16f / 5.4f)
            .clip(Gomob.shapes.r3)
            .background(Color(0xFF080A0E)),
    ) {
        VinPlateRgbCanvas(vin = vin)
        VinOcrFrame()
    }
}

@Composable
private fun VinPlateRgbCanvas(vin: String) {
    Canvas(Modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(listOf(Color(0xFF1A1D24), Color(0xFF070910))), size = size)
        drawPath(
            Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height * 0.16f)
                lineTo(size.width * 0.62f, size.height * 0.24f)
                lineTo(size.width * 0.38f, size.height * 0.20f)
                lineTo(0f, size.height * 0.30f)
                close()
            },
            Color.White.copy(alpha = 0.02f),
        )
        drawPath(
            Path().apply {
                moveTo(0f, size.height * 0.78f)
                lineTo(size.width * 0.25f, size.height * 0.73f)
                lineTo(size.width * 0.75f, size.height * 0.82f)
                lineTo(size.width, size.height * 0.78f)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            },
            Color.Black.copy(alpha = 0.35f),
        )

        val plateLeft = size.width * 0.125f
        val plateTop = size.height * 0.33f
        val plateW = size.width * 0.75f
        val plateH = size.height * 0.36f
        drawRoundRect(
            color = Color.Black.copy(alpha = 0.45f),
            topLeft = Offset(plateLeft + 1.dp.toPx(), plateTop + 2.dp.toPx()),
            size = Size(plateW, plateH),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )
        drawRoundRect(
            brush = Brush.linearGradient(listOf(Color(0xFF5E6772), Color(0xFF7D8794), Color(0xFF3F4754))),
            topLeft = Offset(plateLeft, plateTop),
            size = Size(plateW, plateH),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )
        drawRect(
            color = Color.Black.copy(alpha = 0.18f),
            topLeft = Offset(plateLeft + 2.dp.toPx(), plateTop + 2.dp.toPx()),
            size = Size(plateW - 4.dp.toPx(), 4.dp.toPx()),
        )
        listOf(
            Offset(plateLeft + 4.dp.toPx(), plateTop + plateH - 4.dp.toPx()),
            Offset(plateLeft + plateW - 4.dp.toPx(), plateTop + plateH - 4.dp.toPx()),
        ).forEach { drawCircle(Color(0xFF1A1D22), radius = 0.8.dp.toPx(), center = it) }
        drawRoundRect(
            color = Color.White.copy(alpha = 0.18f),
            topLeft = Offset(plateLeft, plateTop),
            size = Size(plateW, 3.dp.toPx()),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )
        repeat(vin.length) { i ->
            val x = plateLeft + plateW * (0.07f + i * 0.86f / 16f)
            val y = plateTop + plateH * 0.58f
            drawRect(
                color = Color(0xFF0F1218).copy(alpha = 0.85f),
                topLeft = Offset(x, y - 7.dp.toPx()),
                size = Size(2.2.dp.toPx(), 9.dp.toPx()),
            )
            drawRect(
                color = Color.White.copy(alpha = 0.16f),
                topLeft = Offset(x - 0.4.dp.toPx(), y - 7.7.dp.toPx()),
                size = Size(2.2.dp.toPx(), 9.dp.toPx()),
            )
        }
        repeat(70) { i ->
            drawCircle(
                color = if (i % 2 == 0) Color.White.copy(alpha = 0.03f) else Color.Black.copy(alpha = 0.15f),
                radius = 0.35.dp.toPx(),
                center = Offset(size.width * ((i * 17) % 160) / 160f, size.height * ((i * 13) % 90) / 90f),
            )
        }
    }
}

@Composable
private fun VinOcrFrame() {
    val acc = Gomob.colors.accent
    Canvas(Modifier.fillMaxSize()) {
        val left = size.width * 0.1375f
        val top = size.height * 0.47f
        val right = size.width * 0.8625f
        val bottom = size.height * 0.61f
        drawRect(Color.Black.copy(alpha = 0.28f), size = size)
        drawRoundRect(
            color = Color.Transparent,
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
            blendMode = androidx.compose.ui.graphics.BlendMode.Clear,
        )
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

@Composable
private fun VinDepthPane(vin: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, bottom = 10.dp)
            .aspectRatio(16f / 5.4f)
            .clip(Gomob.shapes.r3)
            .background(Color(0xFF06090E)),
    ) {
        VinDepthCanvas(vin = vin)
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("浮雕", style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.1.em), color = Gomob.colors.fg3)
            Box(
                Modifier
                    .width(80.dp)
                    .height(5.dp)
                    .clip(Gomob.shapes.r1)
                    .background(Brush.horizontalGradient(listOf(Color(0xFF315080), Gomob.colors.ok, Gomob.colors.accentStrong))),
            )
            Text("0.60 mm", style = Gomob.type.numInline.copy(fontSize = 9.sp), color = Gomob.colors.fg3)
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Text("采样 1.2M 点", style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.06.em), color = Gomob.colors.fg3)
            Text("RMSE 0.04 mm", style = Gomob.type.numInline.copy(fontSize = 9.sp, letterSpacing = 0.06.em), color = Gomob.colors.ok)
        }
    }
}

@Composable
private fun VinDepthCanvas(vin: String) {
    val accentStrong = Gomob.colors.accentStrong
    Canvas(Modifier.fillMaxSize()) {
        drawRect(
            Brush.radialGradient(
                listOf(Color(0xFF2967A6), Color(0xFF1B2E6A), Color(0xFF1A133D)),
                center = Offset(size.width * 0.5f, size.height * 0.5f),
                radius = size.maxDimension * 0.7f,
            ),
            size = size,
        )
        repeat(9) { i ->
            val y = size.height * i / 8f
            drawLine(Color(0xFF78B4DC).copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), strokeWidth = 0.3.dp.toPx())
        }
        repeat(16) { i ->
            val x = size.width * i / 15f
            drawLine(Color(0xFF78B4DC).copy(alpha = 0.04f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.3.dp.toPx())
        }
        val plateLeft = size.width * 0.125f
        val plateTop = size.height * 0.33f
        val plateW = size.width * 0.75f
        val plateH = size.height * 0.36f
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(accentStrong, Color(0xFF4FAFD8))),
            topLeft = Offset(plateLeft, plateTop),
            size = Size(plateW, plateH),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )
        repeat(vin.length) { i ->
            val x = plateLeft + plateW * (0.07f + i * 0.86f / 16f)
            val y = plateTop + plateH * 0.58f
            drawRect(Color(0xFF1A1745).copy(alpha = 0.82f), Offset(x, y - 7.dp.toPx()), Size(2.2.dp.toPx(), 9.dp.toPx()))
            drawRect(Color(0xFFE1F8FF).copy(alpha = 0.62f), Offset(x - 0.5.dp.toPx(), y - 8.dp.toPx()), Size(2.2.dp.toPx(), 9.dp.toPx()))
        }
        drawOval(
            color = Color(0xFF8CDCFF).copy(alpha = 0.15f),
            topLeft = Offset(size.width * 0.11f, size.height * 0.31f),
            size = Size(size.width * 0.78f, size.height * 0.40f),
            style = Stroke(width = 0.4.dp.toPx()),
        )
        drawOval(
            color = Color(0xFF8CDCFF).copy(alpha = 0.10f),
            topLeft = Offset(size.width * 0.20f, size.height * 0.36f),
            size = Size(size.width * 0.60f, size.height * 0.31f),
            style = Stroke(width = 0.4.dp.toPx()),
        )
        repeat(50) { i ->
            drawCircle(
                color = Color(0xFFB4DCFF).copy(alpha = 0.35f),
                radius = 0.35.dp.toPx(),
                center = Offset(size.width * ((i * 17) % 160) / 160f, size.height * ((i * 13) % 90) / 90f),
            )
        }
    }
}

@Composable
private fun VinRubbing(vin: String) {
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(Gomob.colors.accent))
                Text("正在处理", style = Gomob.type.numInline.copy(fontSize = 10.sp, letterSpacing = 0.04.em), color = Gomob.colors.accent)
            }
        }
        RubbingPaper(vin = vin)
        VinCharCompare(vin = vin)
        VinSummary(vin = vin)
    }
}

@Composable
private fun RubbingPaper(vin: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0xFFE8DFC0))
            .padding(horizontal = 14.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            repeat(90) { i ->
                drawCircle(
                    color = Color(0xFF503C28).copy(alpha = 0.18f),
                    radius = 0.4.dp.toPx(),
                    center = Offset(size.width * ((i * 7) % 200) / 200f, size.height * ((i * 11) % 60) / 60f),
                )
            }
            repeat(18) { i ->
                val x = size.width * i / 17f
                drawLine(Color(0xFF6E5A46).copy(alpha = 0.06f), Offset(x, 0f), Offset(x - 6.dp.toPx(), size.height), strokeWidth = 0.3.dp.toPx())
            }
            drawLine(Color(0xFF3C2814).copy(alpha = 0.40f), Offset(0f, size.height - 7.dp.toPx()), Offset(30.dp.toPx(), size.height - 7.dp.toPx()), strokeWidth = 1.dp.toPx())
            val tickY = size.height - 7.dp.toPx()
            listOf(0f, 15.dp.toPx(), 30.dp.toPx()).forEach { x ->
                drawLine(Color(0xFF3C2814).copy(alpha = 0.40f), Offset(x, tickY - 2.dp.toPx()), Offset(x, tickY + 2.dp.toPx()), strokeWidth = 1.dp.toPx())
            }
        }
        Text(
            vin,
            fontFamily = FontFamily.Monospace,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF2E2417),
            letterSpacing = 0.18.em,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
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

@Composable
private fun VinCaptureBar(modifier: Modifier = Modifier) {
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
            VinRoundButton(icon = GomobIcons.Refresh, label = "重拍")
            VinShutterButton()
            VinRoundButton(icon = GomobIcons.Check, label = "确认", primary = true)
        }
    }
}

@Composable
private fun VinRoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean = false,
) {
    Row(
        modifier = Modifier
            .height(44.dp)
            .clip(CircleShape)
            .background(if (primary) Gomob.colors.accentSoft else Gomob.colors.bg1)
            .border(BorderStroke(1.dp, if (primary) Gomob.colors.accent else Gomob.colors.line2), CircleShape)
            .clickable {}
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
private fun VinShutterButton() {
    Box(
        modifier = Modifier
            .size(68.dp)
            .clip(CircleShape)
            .background(Gomob.colors.bg0)
            .border(BorderStroke(2.dp, Gomob.colors.accent), CircleShape)
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
            Box(Modifier.fillMaxSize().clip(CircleShape).background(Gomob.colors.accent))
        }
    }
}
