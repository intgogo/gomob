package io.gomob.feature.message

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.model.message.InspectionShareCard

@Composable
internal fun InspectionSharePicker(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSelect: (InspectionShareCard) -> Unit,
) {
    BackHandler(enabled = visible, onBack = onDismiss)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.52f))
                .clickable(onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            InspectionSharePickerContent(onDismiss = onDismiss, onSelect = onSelect)
        }
    }
}

@Composable
private fun InspectionSharePickerContent(
    onDismiss: () -> Unit,
    onSelect: (InspectionShareCard) -> Unit,
) {
    var filter by rememberSaveable { mutableStateOf(InspectionFilter.All) }
    val consumeClicks = remember { MutableInteractionSource() }
    // TODO(demo-data R1): inspectionShareCandidates 是占位假流水,未接真实查验流水仓,
    // 却被真实分享发送链路消费;终态从查验记录 Repository 拉真实可分享流水(见下方列表 R1 标注)。
    val rows = remember(filter) {
        inspectionShareCandidates.filter { filter.matches(it) }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.78f)
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg0)
            .clickable(
                interactionSource = consumeClicks,
                indication = null,
                onClick = {},
            )
            // edge-to-edge 后面板贴屏幕物理底边，内容自己避让导航栏
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = Gomob.spacing.s20, end = Gomob.spacing.s16, top = Gomob.spacing.s16, bottom = Gomob.spacing.s12),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    "INSPECTION",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 0.14.em,
                    color = Gomob.colors.fg3,
                )
                Spacer(Modifier.height(Gomob.spacing.s2))
                Text("选择业务流水", fontSize = 17.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
            }
            Text(
                "取消",
                fontSize = 13.sp,
                color = Gomob.colors.fg2,
                modifier = Modifier
                    .clip(Gomob.shapes.r1)
                    .clickable(onClick = onDismiss)
                    .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s6),
            )
        }
        FilterRow(selected = filter, onSelect = { filter = it })
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s12),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            itemsIndexed(rows, key = { _, item -> item.inspectionId }) { index, item ->
                Column {
                    InspectionShareRow(
                        item = item,
                        onClick = { onSelect(item) },
                    )
                    if (index != rows.lastIndex) {
                        InspectionShareDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun InspectionShareDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 70.dp, end = Gomob.spacing.s14)
            .height(Gomob.spacing.hairline)
            .background(Gomob.colors.line1),
    )
}

@Composable
private fun FilterRow(selected: InspectionFilter, onSelect: (InspectionFilter) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s20),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        InspectionFilter.entries.forEach { filter ->
            Row(
                Modifier
                    .height(28.dp)
                    .clip(Gomob.shapes.r1)
                    .background(if (filter == selected) Gomob.colors.accentSoft else Gomob.colors.bg2)
                    .clickable { onSelect(filter) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
            ) {
                Text(
                    filter.label,
                    fontSize = 11.sp,
                    color = if (filter == selected) Gomob.colors.accent else Gomob.colors.fg1,
                )
                Text(
                    inspectionShareCandidates.count(filter::matches).toString(),
                    style = Gomob.type.numInline.copy(fontSize = 11.sp),
                    color = if (filter == selected) Gomob.colors.accent.copy(alpha = 0.7f) else Gomob.colors.fg2,
                )
            }
        }
    }
}

@Composable
private fun InspectionShareRow(item: InspectionShareCard, onClick: () -> Unit) {
    val tone = item.status.toStatusTone()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        verticalAlignment = Alignment.Top,
    ) {
        Column(
            Modifier.width(42.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(item.timeLabel, style = Gomob.type.numInline.copy(fontSize = 12.sp), color = Gomob.colors.fg1)
            Spacer(Modifier.height(Gomob.spacing.s6))
            Box(
                Modifier
                    .size(Gomob.spacing.dot6)
                    .clip(Gomob.shapes.pill)
                    .background(tone.toDotColor()),
            )
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.vin,
                    style = Gomob.type.numInline.copy(fontSize = 13.sp),
                    color = Gomob.colors.fg0,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                StatusTag(text = item.status.toStatusText(), tone = tone, showDot = false)
            }
            Text(item.vehicleLine, fontSize = 11.sp, color = Gomob.colors.fg2, maxLines = 1)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                item.tags.take(3).forEach { tag ->
                    Box(
                        Modifier
                            .height(Gomob.spacing.chipHeight)
                            .clip(Gomob.shapes.r1)
                            .background(Gomob.colors.bg2)
                            .padding(horizontal = Gomob.spacing.s8),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(tag, fontSize = 10.sp, color = Gomob.colors.fg2)
                    }
                }
            }
        }
        Icon(
            GomobIcons.LinkShare,
            contentDescription = "发送这条流水",
            tint = Gomob.colors.accent,
            modifier = Modifier.size(17.dp).align(Alignment.CenterVertically),
        )
    }
}

private enum class InspectionFilter(val label: String) {
    All("全部"),
    Danger("异常"),
    Warn("预警"),
    Ok("正常");

    fun matches(card: InspectionShareCard): Boolean = when (this) {
        All -> true
        Danger -> card.status == "danger"
        Warn -> card.status == "warn"
        Ok -> card.status == "ok"
    }
}

private fun String.toStatusText(): String = when (this) {
    "ok" -> "已通过"
    "danger" -> "异常"
    else -> "预警"
}

private fun String.toStatusTone(): StatusTone = when (this) {
    "ok" -> StatusTone.Ok
    "danger" -> StatusTone.Danger
    else -> StatusTone.Warn
}

@Composable
private fun StatusTone.toDotColor(): Color = when (this) {
    StatusTone.Ok -> Gomob.colors.ok
    StatusTone.Danger -> Gomob.colors.danger
    StatusTone.Warn -> Gomob.colors.warn
    StatusTone.Accent -> Gomob.colors.accent
    StatusTone.Neutral -> Gomob.colors.fg3
}

// TODO(demo-data R1): 以下 6 条是占位假查验流水,未接真实流水仓,仅供分享 UI 演示;
// 终态由查验记录 Repository 提供真实可分享流水(见上方 InspectionSharePickerContent 的 R1 标注)。
private val inspectionShareCandidates = listOf(
    InspectionShareCard(
        inspectionId = "LSVHM98277661003",
        vin = "LSVHM98277661003",
        vehicleLine = "丰田系列 · 小型汽车 · 浙A88K90",
        timeLabel = "12:42",
        status = "ok",
        tags = listOf("已通过"),
    ),
    InspectionShareCard(
        inspectionId = "LSVHM41182123456",
        vin = "LSVHM41182123456",
        vehicleLine = "大众系列 · 小型汽车 · 沪A57Y0",
        timeLabel = "12:18",
        status = "warn",
        tags = listOf("VIN车架号", "出厂日期"),
    ),
    InspectionShareCard(
        inspectionId = "LSVHM133022221761",
        vin = "LSVHM133022221761",
        vehicleLine = "大众系列 · 小型汽车 · 沪A12345",
        timeLabel = "11:45",
        status = "danger",
        tags = listOf("OBD检验", "外廓尺寸"),
    ),
    InspectionShareCard(
        inspectionId = "LSVHM52017788321",
        vin = "LSVHM52017788321",
        vehicleLine = "本田系列 · 小型汽车 · 浙A91K20",
        timeLabel = "11:12",
        status = "ok",
        tags = listOf("已通过"),
    ),
    InspectionShareCard(
        inspectionId = "LSVHM77129003344",
        vin = "LSVHM77129003344",
        vehicleLine = "比亚迪 · 小型汽车 · 浙B22T01",
        timeLabel = "10:48",
        status = "warn",
        tags = listOf("OBD检验"),
    ),
    InspectionShareCard(
        inspectionId = "LSVHM33409912200",
        vin = "LSVHM33409912200",
        vehicleLine = "吉利系列 · 小型汽车 · 浙A30A10",
        timeLabel = "10:22",
        status = "ok",
        tags = listOf("已通过"),
    ),
)
