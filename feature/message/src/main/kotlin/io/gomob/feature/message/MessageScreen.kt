package io.gomob.feature.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.gomob.designsystem.component.HairlineCard
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.theme.Gomob

const val MESSAGE_ROUTE = "message"

private enum class BadgeTone { Accent, Warn, Danger }

private data class MessageItem(
    val id: String,
    val title: String,
    val preview: String,
    val time: String,
    val unread: Int,
    val tone: BadgeTone,
)

private val MESSAGES = listOf(
    MessageItem("1", "监管中心", "[实时审核并异常补拍提醒]", "17:02", 2, BadgeTone.Danger),
    MessageItem("2", "周科", "[视频通话 56:02]", "17:11", 0, BadgeTone.Accent),
    MessageItem("3", "系统消息", "专家会审 CLCY2025052089757 已完成", "16:34", 0, BadgeTone.Accent),
    MessageItem("4", "吴风", "[视频消息]", "16:20", 0, BadgeTone.Accent),
    MessageItem("5", "刘沿", "[图片] 第 3 工位 VIN 拓印", "16:11", 1, BadgeTone.Warn),
    MessageItem("6", "江庆幸", "这是一条文字消息", "16:08", 0, BadgeTone.Accent),
    MessageItem("7", "张老师", "今晚培训记得参加", "16:20", 0, BadgeTone.Accent),
)

@Composable
fun MessageRoute(onOpenConversation: (String) -> Unit = {}) {
    Column(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        ScreenHeader(
            title = "消息中心",
            eyebrow = "通知 · 实时协同",
            trailing = { StatusTag(text = "3 未读", tone = StatusTone.Danger, showDot = true) },
        )

        LazyColumn(
            contentPadding = PaddingValues(
                start = Gomob.spacing.s16,
                end = Gomob.spacing.s16,
                bottom = Gomob.spacing.s24,
            ),
            verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            items(MESSAGES, key = { it.id }) {
                MessageRow(it, onClick = { onOpenConversation(it.id) })
            }
        }
    }
}

@Composable
private fun MessageRow(m: MessageItem, onClick: () -> Unit) {
    val badgeColor: Color = when (m.tone) {
        BadgeTone.Accent -> Gomob.colors.accent
        BadgeTone.Warn -> Gomob.colors.warn
        BadgeTone.Danger -> Gomob.colors.danger
    }
    HairlineCard(padding = Gomob.spacing.s12, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s4),
            ) {
                Text(m.title, style = Gomob.type.body, color = Gomob.colors.fg0)
                Text(m.preview, style = Gomob.type.caption, color = Gomob.colors.fg2, maxLines = 1)
                Text(m.time, style = Gomob.type.numInline, color = Gomob.colors.fg3)
            }
            if (m.unread > 0) {
                Box(
                    Modifier
                        .padding(start = Gomob.spacing.s12)
                        .defaultMinSize(minWidth = Gomob.spacing.icon16, minHeight = Gomob.spacing.icon16)
                        .clip(CircleShape)
                        .background(badgeColor)
                        .padding(horizontal = Gomob.spacing.s4),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = m.unread.toString(),
                        style = Gomob.type.numInline.copy(color = Gomob.colors.bg0),
                    )
                }
            }
        }
    }
}
