package io.gomob.feature.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.auth.TokenStore
import io.gomob.data.message.MessageRepository
import io.gomob.designsystem.component.BackHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.glass.GlassHeaderScaffold
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.model.message.ConversationSummary
import io.gomob.model.message.MessageRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject

private const val CHAT_SEARCH_HISTORY_LIMIT = 300

@Composable
fun ChatSearchRoute(
    onBack: () -> Unit,
    onOpenMessage: (String) -> Unit,
    viewModel: ChatSearchViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filterValue by rememberSaveable { mutableStateOf(ChatSearchFilter.All.name) }
    var selectedDateKey by rememberSaveable { mutableStateOf<String?>(null) }
    val filter = remember(filterValue) {
        runCatching { ChatSearchFilter.valueOf(filterValue) }.getOrDefault(ChatSearchFilter.All)
    }
    val normalizedQuery = query.trim()
    val baseItems = remember(state.items, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            state.items
        } else {
            state.items.filter { it.matchesQuery(normalizedQuery) }
        }
    }
    val resultItems = remember(baseItems, filter, selectedDateKey) {
        baseItems.filter { item ->
            when (filter) {
                ChatSearchFilter.All -> true
                ChatSearchFilter.Date -> selectedDateKey?.let { item.dateKey == it } ?: false
                ChatSearchFilter.File -> item.isFile
                ChatSearchFilter.Photo -> item.kind == "image"
                ChatSearchFilter.Inspection -> item.kind == "inspection_card"
                ChatSearchFilter.Plate -> item.plates.isNotEmpty()
                ChatSearchFilter.Vin -> item.vins.isNotEmpty()
            }
        }
    }
    val dateGroups = remember(baseItems) {
        baseItems
            .groupBy { it.dateKey }
            .map { (dateKey, items) ->
                ChatSearchDateGroupUi(
                    dateKey = dateKey,
                    label = items.firstOrNull()?.dateLabel ?: dateKey,
                    count = items.size,
                    firstLocalKey = items.firstOrNull()?.localKey.orEmpty(),
                )
            }
            .sortedByDescending { it.dateKey }
    }

    LaunchedEffect(filter) {
        if (filter != ChatSearchFilter.Date) {
            selectedDateKey = null
        }
    }

    val dateListState = rememberLazyListState()
    val resultListState = rememberLazyListState()
    val showDateList = filter == ChatSearchFilter.Date && selectedDateKey == null
    GlassHeaderScaffold(
        listState = if (showDateList) dateListState else resultListState,
        // 搜索框 + 筛选条一并入玻璃 header, 结果列表从其下穿过
        header = {
            Column {
                BackHeader(
                    title = "查找聊天记录",
                    eyebrow = state.title,
                    onBack = onBack,
                )
                ChatSearchInput(
                    query = query,
                    onQueryChange = { query = it },
                )
                ChatSearchFilterRow(
                    selected = filter,
                    onSelect = {
                        filterValue = it.name
                        if (it == ChatSearchFilter.Date) {
                            selectedDateKey = null
                        }
                    },
                )
                if (filter == ChatSearchFilter.Date && selectedDateKey != null) {
                    ChatSearchDateScopeBar(
                        label = dateGroups.firstOrNull { it.dateKey == selectedDateKey }?.label.orEmpty(),
                        onClear = { selectedDateKey = null },
                    )
                }
            }
        },
    ) { padding ->
        ChatSearchContent(
            state = state,
            filter = filter,
            dateGroups = dateGroups,
            resultItems = resultItems,
            selectedDateKey = selectedDateKey,
            onSelectDate = { selectedDateKey = it.dateKey },
            onOpenMessage = onOpenMessage,
            dateListState = dateListState,
            resultListState = resultListState,
            contentPadding = padding,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@HiltViewModel
class ChatSearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: MessageRepository,
    private val tokenStore: TokenStore,
    private val json: Json,
) : ViewModel() {
    private val conversationId = savedStateHandle.get<String>("id")?.toLongOrNull() ?: 0L
    private val refreshState = MutableStateFlow<ChatSearchRefreshState>(ChatSearchRefreshState.Loading)

    val uiState: StateFlow<ChatSearchUiState> =
        combine(
            repository.observeConversation(conversationId),
            repository.observeMessages(conversationId),
            refreshState,
            tokenStore.currentUserIdFlow,
        ) { conversation, messages, refresh, currentUserId ->
            ChatSearchUiState(
                title = conversation.chatSearchTitle(conversationId),
                items = messages.map { it.toChatSearchItem(json, currentUserId) },
                loading = refresh is ChatSearchRefreshState.Loading,
                errorMessage = (refresh as? ChatSearchRefreshState.Error)?.message,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ChatSearchUiState(
                title = repository.cachedConversation(conversationId).chatSearchTitle(conversationId),
                items = repository.cachedMessages(conversationId).map {
                    it.toChatSearchItem(json, tokenStore.currentUserId())
                },
                loading = true,
                errorMessage = null,
            ),
        )

    init {
        refresh()
    }

    fun refresh() {
        if (conversationId <= 0) {
            refreshState.value = ChatSearchRefreshState.Error("会话参数无效")
            return
        }
        viewModelScope.launch {
            refreshState.value = ChatSearchRefreshState.Loading
            refreshState.value = runCatching {
                repository.refreshMessages(
                    conversationId = conversationId,
                    limit = CHAT_SEARCH_HISTORY_LIMIT,
                    fullSync = true,
                )
                ChatSearchRefreshState.Ready
            }.getOrElse { ChatSearchRefreshState.Error(it.readableSearchMessage()) }
        }
    }
}

data class ChatSearchUiState(
    val title: String,
    val items: List<ChatSearchItemUi>,
    val loading: Boolean,
    val errorMessage: String?,
)

data class ChatSearchItemUi(
    val localKey: String,
    val kind: String,
    val title: String,
    val preview: String,
    val senderLabel: String,
    val timeLabel: String,
    val dateKey: String,
    val dateLabel: String,
    val searchableText: String,
    val plates: List<String>,
    val vins: List<String>,
    val isFile: Boolean,
)

private data class ChatSearchDateGroupUi(
    val dateKey: String,
    val label: String,
    val count: Int,
    val firstLocalKey: String,
)

private sealed interface ChatSearchRefreshState {
    data object Loading : ChatSearchRefreshState
    data object Ready : ChatSearchRefreshState
    data class Error(val message: String) : ChatSearchRefreshState
}

private enum class ChatSearchFilter(val label: String, val icon: ImageVector) {
    All("全部", GomobIcons.Search),
    Date("日期", GomobIcons.History),
    File("文件", GomobIcons.Folder),
    Photo("照片", GomobIcons.Eyeball),
    Inspection("流水", GomobIcons.LinkShare),
    Plate("号牌", GomobIcons.ID),
    Vin("车架号", GomobIcons.Filter),
}

@Composable
private fun ChatSearchInput(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s8)
            .height(40.dp)
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .padding(horizontal = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Icon(
            imageVector = GomobIcons.Search,
            contentDescription = "搜索",
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(Gomob.spacing.icon16),
        )
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = Gomob.type.bodySm.copy(color = Gomob.colors.fg0),
                cursorBrush = SolidColor(Gomob.colors.accent),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            if (query.isEmpty()) {
                Text("搜索文字、号牌、车架号", style = Gomob.type.bodySm, color = Gomob.colors.fg3)
            }
        }
    }
}

@Composable
private fun ChatSearchFilterRow(
    selected: ChatSearchFilter,
    onSelect: (ChatSearchFilter) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s6),
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        ChatSearchFilter.entries.forEach { filter ->
            ChatSearchFilterChip(
                filter = filter,
                selected = filter == selected,
                onClick = { onSelect(filter) },
            )
        }
    }
}

@Composable
private fun ChatSearchFilterChip(
    filter: ChatSearchFilter,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) Gomob.colors.accentSoft else Gomob.colors.bg1
    val fg = if (selected) Gomob.colors.accent else Gomob.colors.fg2
    Row(
        Modifier
            .height(34.dp)
            .clip(Gomob.shapes.r2)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        Icon(filter.icon, contentDescription = filter.label, tint = fg, modifier = Modifier.size(15.dp))
        Text(filter.label, style = Gomob.type.caption, color = fg, maxLines = 1)
    }
}

@Composable
private fun ChatSearchDateScopeBar(
    label: String,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        StatusTag(text = label.ifBlank { "日期" }, tone = StatusTone.Neutral, showDot = false)
        Text(
            "查看全部日期",
            style = Gomob.type.caption,
            color = Gomob.colors.accent,
            modifier = Modifier.clickable(onClick = onClear).padding(Gomob.spacing.s4),
        )
    }
}

@Composable
private fun ChatSearchContent(
    state: ChatSearchUiState,
    filter: ChatSearchFilter,
    dateGroups: List<ChatSearchDateGroupUi>,
    resultItems: List<ChatSearchItemUi>,
    selectedDateKey: String?,
    onSelectDate: (ChatSearchDateGroupUi) -> Unit,
    onOpenMessage: (String) -> Unit,
    dateListState: LazyListState,
    resultListState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading && state.items.isEmpty() -> {
            Box(modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
                Text("正在同步聊天记录", style = Gomob.type.bodySm, color = Gomob.colors.fg3)
            }
        }
        state.errorMessage != null && state.items.isEmpty() -> {
            Box(modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
                Text(state.errorMessage, style = Gomob.type.bodySm, color = Gomob.colors.danger)
            }
        }
        filter == ChatSearchFilter.Date && selectedDateKey == null -> {
            ChatSearchDateList(
                groups = dateGroups,
                onSelectDate = onSelectDate,
                listState = dateListState,
                contentPadding = contentPadding,
                modifier = modifier,
            )
        }
        else -> {
            ChatSearchResultList(
                items = resultItems,
                onOpenMessage = onOpenMessage,
                listState = resultListState,
                contentPadding = contentPadding,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ChatSearchDateList(
    groups: List<ChatSearchDateGroupUi>,
    onSelectDate: (ChatSearchDateGroupUi) -> Unit,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (groups.isEmpty()) {
        ChatSearchEmpty(modifier.padding(contentPadding), "没有可筛选的日期")
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Gomob.spacing.s16,
            end = Gomob.spacing.s16,
            top = contentPadding.calculateTopPadding() + Gomob.spacing.s8,
            bottom = contentPadding.calculateBottomPadding() + Gomob.spacing.s8,
        ),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        items(groups, key = { it.dateKey }) { group ->
            ChatSearchDateRow(group = group, onClick = { onSelectDate(group) })
        }
    }
}

@Composable
private fun ChatSearchResultList(
    items: List<ChatSearchItemUi>,
    onOpenMessage: (String) -> Unit,
    listState: LazyListState,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) {
        ChatSearchEmpty(modifier.padding(contentPadding), "没有找到相关聊天记录")
        return
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Gomob.spacing.s16,
            end = Gomob.spacing.s16,
            top = contentPadding.calculateTopPadding() + Gomob.spacing.s8,
            bottom = contentPadding.calculateBottomPadding() + Gomob.spacing.s8,
        ),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        items(items, key = { it.localKey }) { item ->
            ChatSearchResultRow(item = item, onClick = { onOpenMessage(item.localKey) })
        }
    }
}

@Composable
private fun ChatSearchDateRow(
    group: ChatSearchDateGroupUi,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1)
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Icon(GomobIcons.History, contentDescription = null, tint = Gomob.colors.accent, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s2)) {
            Text(group.label, style = Gomob.type.bodySm, color = Gomob.colors.fg0, fontWeight = FontWeight.Medium)
            Text("${group.count} 条聊天记录", style = Gomob.type.caption, color = Gomob.colors.fg3)
        }
        Icon(GomobIcons.ChevronRight, contentDescription = "查看", tint = Gomob.colors.fg3, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ChatSearchResultRow(
    item: ChatSearchItemUi,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1)
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(Gomob.shapes.r2)
                .background(item.resultToneColor().copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = item.resultIcon(),
                contentDescription = null,
                tint = item.resultToneColor(),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
                Text(
                    item.title,
                    style = Gomob.type.bodySm,
                    color = Gomob.colors.fg0,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Medium,
                )
                if (item.plates.isNotEmpty()) {
                    StatusTag(text = item.plates.first(), tone = StatusTone.Neutral, showDot = false)
                } else if (item.vins.isNotEmpty()) {
                    StatusTag(text = "VIN", tone = StatusTone.Neutral, showDot = false)
                }
            }
            Text(
                item.preview,
                style = Gomob.type.caption,
                color = Gomob.colors.fg2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${item.senderLabel} · ${item.dateLabel} ${item.timeLabel}",
                style = Gomob.type.numInline,
                color = Gomob.colors.fg3,
                maxLines = 1,
            )
        }
        Icon(GomobIcons.ChevronRight, contentDescription = "跳转到聊天", tint = Gomob.colors.fg3, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun ChatSearchEmpty(modifier: Modifier, text: String) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = Gomob.type.bodySm, color = Gomob.colors.fg3)
    }
}

private fun ChatSearchItemUi.matchesQuery(query: String): Boolean =
    searchableText.contains(query, ignoreCase = true)

private fun MessageRecord.toChatSearchItem(json: Json, currentUserId: Long?): ChatSearchItemUi {
    val payload = runCatching { json.parseToJsonElement(payloadJson) }.getOrNull()
    val payloadText = payload.collectSearchText()
    val previewText = previewText(json)
    val searchText = listOf(kind.searchKindLabel(), previewText, payloadText)
        .joinToString(" ")
    val createdAtInstant = runCatching { Instant.parse(createdAt) }.getOrDefault(Instant.EPOCH)
    val zoned = createdAtInstant.atZone(ZoneId.systemDefault())
    return ChatSearchItemUi(
        localKey = localKey,
        kind = kind,
        title = kind.searchKindLabel(),
        preview = previewText.ifBlank { payloadText.ifBlank { kind.searchKindLabel() } },
        senderLabel = if (senderId != null && senderId == currentUserId) "我" else senderId?.let { "成员 #$it" } ?: "系统",
        timeLabel = zoned.format(DateTimeFormatter.ofPattern("HH:mm")),
        dateKey = zoned.toLocalDate().toString(),
        dateLabel = zoned.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
        searchableText = searchText,
        plates = PlateRegex.findAll(searchText).map { it.value }.distinct().toList(),
        vins = VinRegex.findAll(searchText.uppercase()).map { it.value }.distinct().toList(),
        isFile = kind in setOf("file", "document", "voice", "video_clip"),
    )
}

private fun String.searchKindLabel(): String = when (this) {
    "text" -> "文字"
    "image" -> "照片"
    "voice" -> "语音"
    "video_clip" -> "视频文件"
    "inspection_card" -> "业务流水"
    "call_invite" -> "视频通话邀请"
    "video_call" -> "视频通话"
    "audio_call" -> "语音通话"
    "system" -> "系统消息"
    "file", "document" -> "文件"
    else -> this
}

private fun JsonElement?.collectSearchText(): String {
    val element = this ?: return ""
    return when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonArray -> element.joinToString(" ") { it.collectSearchText() }
        is JsonObject -> element.values.joinToString(" ") { it.collectSearchText() }
    }
}

private fun ChatSearchItemUi.resultIcon(): ImageVector = when (kind) {
    "image" -> GomobIcons.Eyeball
    "voice" -> GomobIcons.VoiceCircle
    "video_clip" -> GomobIcons.Video
    "inspection_card" -> GomobIcons.LinkShare
    "call_invite", "video_call", "audio_call" -> GomobIcons.Phone
    "file", "document" -> GomobIcons.Folder
    else -> GomobIcons.Search
}

@Composable
private fun ChatSearchItemUi.resultToneColor() = when (kind) {
    "inspection_card" -> Gomob.colors.warn
    "image", "video_clip" -> Gomob.colors.accent
    "voice", "audio_call" -> Gomob.colors.ok
    "call_invite", "video_call" -> Gomob.colors.danger
    else -> Gomob.colors.fg2
}

private fun ConversationSummary?.chatSearchTitle(conversationId: Long): String =
    this?.title?.takeIf { it.isNotBlank() }
        ?: this?.peer?.name?.takeIf { it.isNotBlank() }
        ?: if (conversationId > 0) "会话 #$conversationId" else "会话"

private fun Throwable.readableSearchMessage(): String =
    message?.takeIf { it.isNotBlank() } ?: "聊天记录暂不可用"

private val PlateRegex = Regex("""[\u4e00-\u9fa5][A-Z][A-Z0-9]{5,6}""")
private val VinRegex = Regex("""\b[A-HJ-NPR-Z0-9]{17}\b""")
