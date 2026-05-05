package io.gomob.feature.message

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob

private data class Bubble(val id: String, val text: String, val mine: Boolean, val time: String)

private val DEMO_BUBBLES = listOf(
    Bubble("1", "你好,刚才那台 LSVHM133… 的 VIN 拓印我重传一下。", false, "16:08"),
    Bubble("2", "好的,工位 3 我马上看。", true, "16:08"),
    Bubble("3", "[图片] 第 3 工位 VIN 拓印", false, "16:11"),
    Bubble("4", "拓印有点模糊,字符 H 和 N 看不清,能再来一张正面 90° 的吗?", true, "16:13"),
    Bubble("5", "好,稍等一下。", false, "16:14"),
)

@Composable
fun ConversationRoute(conversationId: String, onBack: () -> Unit) {
    var draft by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        BackHeader(
            title = "刘沿",
            onBack = onBack,
            eyebrow = "会话 · #$conversationId",
            trailing = { StatusTag(text = "在线", tone = StatusTone.Ok, showDot = true) },
        )

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(
                horizontal = Gomob.spacing.s16,
                vertical = Gomob.spacing.s12,
            ),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            items(DEMO_BUBBLES, key = { it.id }) { BubbleRow(it) }
        }

        // 工具栏 + 输入
        Column(Modifier.fillMaxWidth().background(Gomob.colors.bg1)) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Gomob.spacing.hairline)
                    .background(Gomob.colors.line1),
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                ToolIcon(Icons.Filled.Image, "图片")
                ToolIcon(Icons.Filled.PhotoCamera, "拍摄")
                ToolIcon(Icons.Filled.Videocam, "视频通话")
                Box(
                    Modifier
                        .weight(1f)
                        .height(Gomob.spacing.touchMin)
                        .clip(Gomob.shapes.r2)
                        .background(Gomob.colors.bg2)
                        .border(Gomob.spacing.hairline, Gomob.colors.line2, Gomob.shapes.r2)
                        .padding(horizontal = Gomob.spacing.s12),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draft.isEmpty()) {
                        Text("发消息…", style = Gomob.type.bodySm, color = Gomob.colors.fg3)
                    }
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        textStyle = Gomob.type.bodySm.copy(color = Gomob.colors.fg0),
                        cursorBrush = SolidColor(Gomob.colors.accent),
                    )
                }
            }
        }
    }
}

@Composable
private fun BubbleRow(b: Bubble) {
    val alignment = if (b.mine) Alignment.End else Alignment.Start
    val bubbleBg = if (b.mine) Gomob.colors.accentSoft else Gomob.colors.bg1
    val bubbleLine = if (b.mine) Gomob.colors.accentLine else Gomob.colors.line1
    val textColor = if (b.mine) Gomob.colors.accent else Gomob.colors.fg0

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = alignment,
    ) {
        Box(
            Modifier
                .clip(Gomob.shapes.r3)
                .background(bubbleBg)
                .border(Gomob.spacing.hairline, bubbleLine, Gomob.shapes.r3)
                .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        ) {
            Text(b.text, style = Gomob.type.bodySm, color = textColor)
        }
        Text(
            b.time,
            style = Gomob.type.numInline,
            color = Gomob.colors.fg3,
            modifier = Modifier.padding(top = Gomob.spacing.s2, start = Gomob.spacing.s4, end = Gomob.spacing.s4),
        )
    }
}

@Composable
private fun ToolIcon(icon: ImageVector, label: String) {
    Box(
        Modifier
            .clip(Gomob.shapes.r2)
            .clickable {}
            .padding(Gomob.spacing.s8),
    ) {
        Icon(icon, contentDescription = label, tint = Gomob.colors.fg2)
    }
}
