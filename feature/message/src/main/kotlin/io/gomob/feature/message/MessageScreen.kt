package io.gomob.feature.message

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.gomob.designsystem.component.GlassCard
import io.gomob.designsystem.theme.Accent
import io.gomob.designsystem.theme.AccentDim
import io.gomob.designsystem.theme.Primary
import io.gomob.designsystem.theme.StateDanger
import io.gomob.designsystem.theme.StateSuccess
import io.gomob.designsystem.theme.SurfaceCard
import io.gomob.designsystem.theme.SurfaceDeep
import io.gomob.designsystem.theme.TextSecondary
import io.gomob.designsystem.theme.TextTertiary

const val MESSAGE_ROUTE = "message"

private data class Conversation(
    val name: String,
    val preview: String,
    val time: String,
    val unread: Int,
    val avatar: AvatarData,
)

private sealed interface AvatarData {
    data class Initial(val text: String, val color: Color) : AvatarData
    data class System(val type: String) : AvatarData // "supervise" / "broadcast"
}

private val CONVERSATIONS = listOf(
    Conversation(
        name = "监管中心",
        preview = "[实时审核并异常补拍提醒]",
        time = "17:02",
        unread = 2,
        avatar = AvatarData.System("supervise"),
    ),
    Conversation(
        name = "周科",
        preview = "[视频通话 56:02]",
        time = "17:11",
        unread = 0,
        avatar = AvatarData.Initial("周", Primary),
    ),
    Conversation(
        name = "系统消息",
        preview = "专家会审-CLCY2025052089757 已完成会审",
        time = "16:34",
        unread = 0,
        avatar = AvatarData.System("broadcast"),
    ),
    Conversation(
        name = "吴风",
        preview = "[视频消息]",
        time = "16:20",
        unread = 0,
        avatar = AvatarData.Initial("吴", StateSuccess),
    ),
    Conversation(
        name = "刘沿",
        preview = "[图片] 第 3 工位 VIN 拓印",
        time = "16:11",
        unread = 1,
        avatar = AvatarData.Initial("刘", Accent),
    ),
    Conversation(
        name = "江庆幸",
        preview = "这是一条文字消息",
        time = "16:08",
        unread = 0,
        avatar = AvatarData.Initial("江", StateDanger),
    ),
    Conversation(
        name = "张老师",
        preview = "今晚培训记得参加",
        time = "16:20",
        unread = 0,
        avatar = AvatarData.Initial("张", Primary),
    ),
)

@Composable
fun MessageRoute() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDeep),
        contentPadding = PaddingValues(top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Header() }
        item { TopFilters() }
        items(CONVERSATIONS) { ConversationRow(it) }
    }
}

@Composable
private fun Header() {
    Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
        Text(text = "消息中心", style = MaterialTheme.typography.headlineLarge)
        Text(
            text = "实时协同 · 监管督查",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun TopFilters() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterPill("消息列表", selected = true, modifier = Modifier.weight(1f))
        FilterPill("在线求助", selected = false, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) Primary.copy(alpha = 0.2f) else SurfaceCard)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) Primary else TextSecondary,
        )
    }
}

@Composable
private fun ConversationRow(c: Conversation) {
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        background = Brush.verticalGradient(listOf(SurfaceCard, SurfaceCard)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(c.avatar)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = c.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = c.time,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextTertiary,
                    )
                }
                Spacer(Modifier.size(2.dp))
                Text(
                    text = c.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                )
            }
            if (c.unread > 0) {
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(StateDanger),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = c.unread.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun Avatar(data: AvatarData) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                when (data) {
                    is AvatarData.Initial -> Brush.linearGradient(
                        listOf(data.color.copy(alpha = 0.6f), data.color),
                    )
                    is AvatarData.System -> when (data.type) {
                        "supervise" -> Brush.linearGradient(listOf(StateSuccess, AccentDim))
                        else -> Brush.linearGradient(listOf(Primary, Accent))
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        when (data) {
            is AvatarData.Initial -> Text(
                text = data.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            is AvatarData.System -> Icon(
                imageVector = if (data.type == "supervise") Icons.Filled.SupportAgent else Icons.Filled.Campaign,
                contentDescription = null,
            )
        }
    }
}
