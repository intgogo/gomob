package io.gomob.feature.message

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.VideoCall
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob

const val MESSAGE_ROUTE = "message"

enum class MessageEntryTab { List, Help }

private enum class MsgTab { List, Help }

enum class AvatarKind { System, Call, Video, Image, Voice, Neutral }
enum class WatchTone { Accent, Warn, Danger, Ok, Neutral }

private data class ContactSectionUi(
    val id: String,
    val title: String,
    val countLabel: String,
    val tone: WatchTone,
    val contacts: List<ContactRowUi>,
)

private data class ContactRowUi(
    val id: String,
    val name: String,
    val initials: String,
    val role: String,
    val employeeId: String,
    val online: Boolean,
    val peerUserId: Long?,
    val detailId: String = id,
)

@Composable
fun MessageRoute(
    onOpenConversation: (String) -> Unit = {},
    onOpenConversationTarget: (String, String) -> Unit = { id, _ -> onOpenConversation(id) },
    onOpenHelpSearch: (String) -> Unit = {},
    onOpenLocalVideo: (String) -> Unit = {},
    onOpenExpertDetail: (String) -> Unit = {},
    onOpenContactDetail: (String) -> Unit = {},
    onOpenVideoCall: (roomId: String, title: String, mode: VideoCallMode) -> Unit = { _, _, _ -> },
    requestedTab: MessageEntryTab? = null,
    onRequestedTabConsumed: () -> Unit = {},
    viewModel: MessageListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val searchState by viewModel.searchUiState.collectAsStateWithLifecycle()
    val multiLineRoomsState by viewModel.multiLineRoomsUiState.collectAsStateWithLifecycle()
    val helpState by viewModel.helpUiState.collectAsStateWithLifecycle()
    val contactActionError by viewModel.contactActionError.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var tab by rememberSaveable { mutableStateOf((requestedTab ?: MessageEntryTab.List).toMsgTab()) }
    var contactsOpen by rememberSaveable { mutableStateOf(false) }
    var adHocPickerOpen by rememberSaveable { mutableStateOf(false) }
    val hasMessageUnread = (state as? MessageListUiState.Content)
        ?.conversations
        ?.any { it.unreadCount > 0 } == true
    val hasHelpUnread = (multiLineRoomsState as? MultiLineRoomsUiState.Content)
        ?.rooms
        ?.any { it.unreadCount > 0 } == true
    val pageNotice = messageRouteNotice(
        tab = tab,
        messageState = state,
        multiLineRoomsState = multiLineRoomsState,
        helpState = helpState,
        contactActionError = contactActionError.takeUnless { contactsOpen },
    )

    LaunchedEffect(requestedTab) {
        requestedTab?.let {
            tab = it.toMsgTab()
            onRequestedTabConsumed()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.openConversationEvents.collect { conversationId ->
            contactsOpen = false
            onOpenConversation(conversationId.toString())
        }
    }

    LaunchedEffect(Unit) {
        viewModel.openSearchMessageEvents.collect { event ->
            contactsOpen = false
            onOpenConversationTarget(event.conversationId.toString(), event.localKey)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.adHocVideoCallEvents.collect { event ->
            adHocPickerOpen = false
            onOpenVideoCall(event.roomId, event.title, event.mode)
        }
    }

    Box(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "消息中心",
                eyebrow = "实时协同 · 监管督查 · 多人会审",
                modifier = Modifier.clearInputFocusOnPointerDown(focusManager),
                trailing = {
                    ContactsIconButton(
                        onClick = {
                            focusManager.clearFocus()
                            viewModel.clearContactActionError()
                            contactsOpen = true
                        },
                    )
                },
            )
            SegmentedTabs(
                tab = tab,
                hasMessageUnread = hasMessageUnread,
                hasHelpUnread = hasHelpUnread,
                onChange = {
                    focusManager.clearFocus()
                    tab = it
                },
            )
            when (tab) {
                MsgTab.List -> ListPane(
                    state = state,
                    searchState = searchState,
                    helpState = helpState,
                    onOpenConversation = { viewModel.openConversation(it.id) },
                    onOpenMessage = viewModel::openSearchMessage,
                    onOpenContactDetail = { onOpenContactDetail(it.detailId) },
                    modifier = Modifier.weight(1f),
                )
                MsgTab.Help -> MultiLinePane(
                    roomsState = multiLineRoomsState,
                    onRefresh = {
                        viewModel.refreshHelpExperts()
                        viewModel.refreshHelpRoom()
                    },
                    onOpenRoom = { room -> viewModel.openConversation(room.id) },
                    onStartAdHoc = {
                        viewModel.clearContactActionError()
                        adHocPickerOpen = true
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        ContactsDrawer(
            visible = contactsOpen,
            state = helpState,
            errorText = contactActionError,
            onClose = {
                contactsOpen = false
                viewModel.clearContactActionError()
            },
            onOpenContactDetail = { contact ->
                contactsOpen = false
                onOpenContactDetail(contact.detailId)
            },
        )
        AdHocCallPicker(
            visible = adHocPickerOpen,
            state = helpState,
            errorText = contactActionError,
            onClose = {
                adHocPickerOpen = false
                viewModel.clearContactActionError()
            },
            onConfirm = { ids -> viewModel.startAdHocCall(ids) },
        )
        FloatingMessageError(
            text = pageNotice?.text,
            tone = pageNotice?.tone ?: FloatingMessageTone.Danger,
            onClick = {
                when (tab) {
                    MsgTab.List -> viewModel.refresh()
                    MsgTab.Help -> {
                        viewModel.refreshHelpExperts()
                        viewModel.refreshHelpRoom()
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp),
        )
    }
}

private fun MessageEntryTab.toMsgTab(): MsgTab = when (this) {
    MessageEntryTab.List -> MsgTab.List
    MessageEntryTab.Help -> MsgTab.Help
}

private data class MessageRouteNotice(
    val text: String,
    val tone: FloatingMessageTone,
)

private fun messageRouteNotice(
    tab: MsgTab,
    messageState: MessageListUiState,
    multiLineRoomsState: MultiLineRoomsUiState,
    helpState: HelpExpertsUiState,
    contactActionError: String?,
): MessageRouteNotice? = contactActionError?.let {
    MessageRouteNotice(it, FloatingMessageTone.Danger)
} ?: when (tab) {
    MsgTab.List -> messageState.floatingNotice()
    MsgTab.Help -> multiLineRoomsState.floatingNotice()
        ?: helpState.floatingNotice()
}

private fun MessageListUiState.floatingNotice(): MessageRouteNotice? = when (this) {
    is MessageListUiState.Error -> MessageRouteNotice(message, FloatingMessageTone.Danger)
    is MessageListUiState.Content -> if (offlineCached) {
        MessageRouteNotice("消息服务异常，当前显示本地缓存", FloatingMessageTone.Info)
    } else {
        null
    }
    else -> null
}

private fun HelpExpertsUiState.floatingNotice(): MessageRouteNotice? = when (this) {
    is HelpExpertsUiState.Error -> MessageRouteNotice(message, FloatingMessageTone.Danger)
    is HelpExpertsUiState.Content -> if (offlineCached) {
        MessageRouteNotice("消息服务异常，当前显示本地缓存", FloatingMessageTone.Info)
    } else {
        null
    }
    else -> null
}

private fun MultiLineRoomsUiState.floatingNotice(): MessageRouteNotice? = when (this) {
    is MultiLineRoomsUiState.Error -> MessageRouteNotice(message, FloatingMessageTone.Danger)
    is MultiLineRoomsUiState.Content -> if (offlineCached) {
        MessageRouteNotice("消息服务异常，当前显示本地缓存", FloatingMessageTone.Info)
    } else {
        null
    }
    else -> null
}

@Composable
private fun ContactsIconButton(onClick: () -> Unit) {
    Box(
        Modifier.size(Gomob.spacing.touchMin).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            GomobIcons.Contacts,
            contentDescription = "联系人",
            tint = Gomob.colors.fg2,
            modifier = Modifier.size(Gomob.spacing.icon20),
        )
    }
}

@Composable
private fun ContactsDrawer(
    visible: Boolean,
    state: HelpExpertsUiState,
    errorText: String?,
    onClose: () -> Unit,
    onOpenContactDetail: (ContactRowUi) -> Unit,
) {
    BackHandler(enabled = visible, onBack = onClose)
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(onClick = onClose),
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            ContactsDrawerContent(
                state = state,
                errorText = errorText,
                onOpenContactDetail = onOpenContactDetail,
            )
        }
    }
}

@Composable
private fun ContactsDrawerContent(
    state: HelpExpertsUiState,
    errorText: String?,
    onOpenContactDetail: (ContactRowUi) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val sections = remember(state) { buildContactSections(state) }
    var expandedSectionIds by rememberSaveable { mutableStateOf(listOf("recent")) }
    val visibleSections = remember(sections, query) { sections.filterContacts(query) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val drawerClickSource = remember { MutableInteractionSource() }
    fun dismissSearchInput() {
        focusManager.clearFocus()
        keyboardController?.hide()
    }

    Box(
        Modifier
            .fillMaxWidth(0.82f)
            .fillMaxHeight(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(Gomob.colors.bg1)
                .clickable(
                    interactionSource = drawerClickSource,
                    indication = null,
                    onClick = { dismissSearchInput() },
                ),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 14.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    Text(
                        "CONTACTS",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 0.14.em,
                        color = Gomob.colors.fg3,
                    )
                    Spacer(Modifier.height(Gomob.spacing.s2))
                    Text("选择联系人", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
                }
            }
            ContactSearchBar(query = query, onQueryChange = { query = it })
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                visibleSections.forEach { section ->
                    val expanded = query.isNotBlank() || section.id in expandedSectionIds
                    item(key = "section-${section.id}") {
                        ContactSectionHeader(
                            section = section,
                            expanded = expanded,
                            onClick = {
                                expandedSectionIds = if (section.id in expandedSectionIds) {
                                    expandedSectionIds - section.id
                                } else {
                                    expandedSectionIds + section.id
                                }
                            },
                        )
                    }
                    if (expanded) {
                        itemsIndexed(section.contacts, key = { _, contact -> contact.id }) { index, contact ->
                            Column {
                                ContactRow(
                                    contact = contact,
                                    onClick = { onOpenContactDetail(contact) },
                                )
                                if (index != section.contacts.lastIndex) {
                                    ContactListDivider()
                                }
                            }
                        }
                    }
                }
            }
        }
        FloatingMessageError(
            text = errorText,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 22.dp),
        )
    }
}

@Composable
private fun ContactSearchBar(query: String, onQueryChange: (String) -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 18.dp)
            .fillMaxWidth()
            .height(38.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg2)
            .padding(horizontal = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Icon(
            GomobIcons.Search,
            contentDescription = null,
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(14.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, color = Gomob.colors.fg0),
            cursorBrush = SolidColor(Gomob.colors.accent),
            decorationBox = { innerTextField ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("搜索 姓名 / 工号 / 职责", fontSize = 12.sp, color = Gomob.colors.fg3)
                    }
                    innerTextField()
                }
            },
        )
    }
}

@Composable
private fun ContactSectionHeader(
    section: ContactSectionUi,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val tone = section.tone.toContactTone()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r1)
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s6, vertical = Gomob.spacing.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
    ) {
        Text(if (expanded) "⌄" else "›", fontSize = 11.sp, color = Gomob.colors.fg3)
        Icon(
            GomobIcons.Folder,
            contentDescription = null,
            tint = tone,
            modifier = Modifier.size(13.dp),
        )
        Text(
            section.title,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Gomob.colors.fg1,
            modifier = Modifier.weight(1f),
        )
        Text(section.countLabel, style = Gomob.type.numInline, color = Gomob.colors.fg3)
    }
}

@Composable
private fun ContactRow(
    contact: ContactRowUi,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(Gomob.shapes.r2)
            .background(Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ContactAvatar(contact)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                contact.name,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = Gomob.colors.fg0,
                maxLines = 1,
            )
            Text(
                "${contact.role} · ${contact.employeeId}",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Gomob.colors.fg3,
                maxLines = 1,
            )
        }
        Icon(
            GomobIcons.ChevronRight,
            contentDescription = null,
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(13.dp),
        )
    }
}

@Composable
private fun ContactListDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 52.dp, end = 10.dp)
            .height(Gomob.spacing.hairline)
            .background(Gomob.colors.line1.copy(alpha = 0.03f)),
    )
}

@Composable
private fun ContactAvatar(contact: ContactRowUi) {
    MessageAvatarImage(
        seed = "contact-${contact.id}-${contact.name}",
        size = 32.dp,
        shape = Gomob.shapes.r1,
        online = contact.online,
    )
}

private fun buildContactSections(state: HelpExpertsUiState): List<ContactSectionUi> {
    // 外部专家来自 /v1/conversations/help-experts；本站联系人来自 /v1/contacts。
    // 监管中心 / 跨站协作等更精细分组留待后续按 role 维度拆。
    val experts: List<ContactRowUi>
    val stationRows: List<ContactRowUi>
    when (state) {
        is HelpExpertsUiState.Content -> {
            experts = state.experts.map { it.toContactProfileUi().toContactRowUi() }
            stationRows = state.stationContacts.map { it.toContactProfileUi().toContactRowUi() }
        }
        else -> {
            experts = emptyList()
            stationRows = emptyList()
        }
    }
    return listOf(
        ContactSectionUi("station", "本站联系人", stationRows.size.toString(), WatchTone.Accent, stationRows),
        ContactSectionUi("experts", "外部专家 · 协作池", experts.size.toString(), WatchTone.Danger, experts),
    ).filter { it.contacts.isNotEmpty() }
}

private fun ContactProfileUi.toContactRowUi(
    rowId: String = id,
    roleOverride: String? = null,
): ContactRowUi = ContactRowUi(
    id = rowId,
    name = name,
    initials = initials,
    role = roleOverride ?: roleTitle,
    employeeId = employeeId,
    online = online,
    peerUserId = peerUserId,
    detailId = id,
)

private fun List<ContactSectionUi>.filterContacts(query: String): List<ContactSectionUi> {
    val keyword = query.trim()
    if (keyword.isEmpty()) return this
    val normalizedKeyword = keyword.normalizedContactSearchToken()
    return mapNotNull { section ->
        val filtered = section.contacts.filter { contact -> contact.matchesContactKeyword(keyword, normalizedKeyword) }
        if (filtered.isEmpty()) null else section.copy(countLabel = filtered.size.toString(), contacts = filtered)
    }
}

private fun ContactRowUi.matchesContactKeyword(keyword: String, normalizedKeyword: String): Boolean {
    if (name.contains(keyword, ignoreCase = true) ||
        role.contains(keyword, ignoreCase = true) ||
        employeeId.contains(keyword, ignoreCase = true)
    ) {
        return true
    }
    if (normalizedKeyword.isEmpty()) return false
    return listOf(name, role, employeeId, initials)
        .flatMap { it.contactSearchTokens() }
        .any { it.contains(normalizedKeyword) }
}

private fun String.contactSearchTokens(): List<String> {
    val normalized = normalizedContactSearchToken()
    val syllables = mapNotNull { char ->
        when {
            char.isAsciiLetterOrDigit() -> char.lowercaseChar().toString()
            else -> contactPinyinMap[char]
        }
    }
    val fullPinyin = syllables.joinToString("")
    val initials = syllables.mapNotNull { it.firstOrNull()?.toString() }.joinToString("")
    return listOf(normalized, fullPinyin, initials).filter { it.isNotEmpty() }.distinct()
}

private fun String.normalizedContactSearchToken(): String =
    lowercase().filter { it.isAsciiLetterOrDigit() }

private fun Char.isAsciiLetterOrDigit(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

internal val contactPinyinMap = mapOf(
    '周' to "zhou",
    '科' to "ke",
    '吴' to "wu",
    '风' to "feng",
    '刘' to "liu",
    '冶' to "ye",
    '江' to "jiang",
    '庆' to "qing",
    '宇' to "yu",
    '沈' to "shen",
    '海' to "hai",
    '明' to "ming",
    '省' to "sheng",
    '所' to "suo",
    '复' to "fu",
    '核' to "he",
    '值' to "zhi",
    '班' to "ban",
    '督' to "du",
    '导' to "dao",
    '陈' to "chen",
    '若' to "ruo",
    '愚' to "yu",
    '林' to "lin",
    '知' to "zhi",
    '远' to "yuan",
    '一' to "yi",
    '苇' to "wei",
    '许' to "xu",
    '庭' to "ting",
    '主' to "zhu",
    '审' to "shen",
    '外' to "wai",
    '观' to "guan",
    '件' to "jian",
    '专' to "zhuan",
    '家' to "jia",
    '拓' to "ta",
    '印' to "yin",
    '查' to "cha",
    '验' to "yan",
    '员' to "yuan",
    '监' to "jian",
    '管' to "guan",
    '异' to "yi",
    '常' to "chang",
    '办' to "ban",
    '车' to "che",
    '身' to "shen",
    '维' to "wei",
    '修' to "xiu",
    '扫' to "sao",
    '描' to "miao",
    '重' to "zhong",
    '建' to "jian",
    '法' to "fa",
    '规' to "gui",
    '登' to "deng",
    '记' to "ji",
)

@Composable
private fun WatchTone.toContactTone(): Color = when (this) {
    WatchTone.Accent -> Gomob.colors.accent
    WatchTone.Warn -> Gomob.colors.warn
    WatchTone.Danger -> Gomob.colors.danger
    WatchTone.Ok -> Gomob.colors.ok
    WatchTone.Neutral -> Gomob.colors.fg3
}

@Composable
private fun SegmentedTabs(
    tab: MsgTab,
    hasMessageUnread: Boolean,
    hasHelpUnread: Boolean,
    onChange: (MsgTab) -> Unit,
) {
    Row(
        Modifier
            .padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, bottom = 8.dp)
            .fillMaxWidth()
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1),
    ) {
        SegItem(
            modifier = Modifier.weight(1f),
            active = tab == MsgTab.List,
            onClick = { onChange(MsgTab.List) },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
            ) {
                Text(
                    "消息列表",
                    fontSize = 12.sp,
                    color = if (tab == MsgTab.List) Gomob.colors.accent else Gomob.colors.fg2,
                )
                if (hasMessageUnread) UnreadStatusDot(WatchTone.Accent)
            }
        }
        SegItem(
            modifier = Modifier.weight(1f),
            active = tab == MsgTab.Help,
            onClick = { onChange(MsgTab.Help) },
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s6),
            ) {
                Text(
                    "多人连线",
                    fontSize = 12.sp,
                    color = if (tab == MsgTab.Help) Gomob.colors.accent else Gomob.colors.fg2,
                )
                if (hasHelpUnread) UnreadStatusDot(WatchTone.Accent)
            }
        }
    }
}

@Composable
private fun SegItem(
    modifier: Modifier = Modifier,
    active: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Row(
        modifier
            .height(36.dp)
            .background(if (active) Gomob.colors.accentSoft else Color.Transparent)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun ListPane(
    state: MessageListUiState,
    searchState: MessageListSearchUiState,
    helpState: HelpExpertsUiState,
    onOpenConversation: (ConversationRowUi) -> Unit,
    onOpenMessage: (MessageListSearchMessageUi) -> Unit,
    onOpenContactDetail: (ContactRowUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchActive by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val exitSearch = remember(focusManager, keyboardController) {
        {
            searchActive = false
            query = ""
            focusManager.clearFocus()
            keyboardController?.hide()
            Unit
        }
    }
    val dismissSearchFocus = remember(focusManager, keyboardController) {
        {
            searchActive = false
            focusManager.clearFocus()
            keyboardController?.hide()
            Unit
        }
    }
    BackHandler(enabled = searchActive || query.isNotBlank(), onBack = exitSearch)
    val normalizedQuery = query.trim()
    val searching = normalizedQuery.isNotBlank()

    Column(modifier.fillMaxSize()) {
        SearchContainer(
            query = query,
            onQueryChange = { query = it },
            onActiveChange = { searchActive = it },
        )
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clearInputFocusOnPointerDown(focusManager) {
                    keyboardController?.hide()
                },
            contentPadding = PaddingValues(bottom = Gomob.spacing.s24),
        ) {
            if (searching) {
                item {
                    MessageListSearchPanel(
                        query = normalizedQuery,
                        messageState = state,
                        searchState = searchState,
                        helpState = helpState,
                        onOpenConversation = {
                            dismissSearchFocus()
                            onOpenConversation(it)
                        },
                        onOpenMessage = {
                            dismissSearchFocus()
                            onOpenMessage(it)
                        },
                        onOpenContactDetail = {
                            dismissSearchFocus()
                            onOpenContactDetail(it)
                        },
                    )
                }
            } else {
                when (state) {
                    MessageListUiState.Loading -> item {
                        StateBlock(text = "正在加载会话", tone = StatusTone.Neutral)
                    }
                    MessageListUiState.Empty -> Unit
                    is MessageListUiState.Error -> Unit
                    is MessageListUiState.Content -> {
                        items(state.conversations, key = { item -> item.id }) { item ->
                            MsgRow(
                                item,
                                onClick = {
                                    dismissSearchFocus()
                                    onOpenConversation(item)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchContainer(
    query: String,
    onQueryChange: (String) -> Unit,
    onActiveChange: (Boolean) -> Unit,
) {
    Box(
        Modifier
            .padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, bottom = 8.dp)
            .fillMaxWidth(),
    ) {
        SearchBar(query = query, onQueryChange = onQueryChange, onActiveChange = onActiveChange)
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onActiveChange: (Boolean) -> Unit,
    placeholder: String = "搜索消息 / 联系人",
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg1)
            .padding(horizontal = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Icon(
            GomobIcons.Search,
            contentDescription = null,
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(14.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { onActiveChange(it.isFocused) },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, lineHeight = 18.sp, color = Gomob.colors.fg0),
                cursorBrush = SolidColor(Gomob.colors.accent),
                decorationBox = { innerTextField ->
                    Box(
                        Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (query.isEmpty()) {
                            Text(
                                placeholder,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = Gomob.colors.fg3,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }
    }
}

@Composable
private fun MessageListSearchPanel(
    query: String,
    messageState: MessageListUiState,
    searchState: MessageListSearchUiState,
    helpState: HelpExpertsUiState,
    onOpenConversation: (ConversationRowUi) -> Unit,
    onOpenMessage: (MessageListSearchMessageUi) -> Unit,
    onOpenContactDetail: (ContactRowUi) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val results = remember(query, messageState, searchState, helpState) {
        buildMessageListSearchResults(
            query = query,
            messageState = messageState,
            searchState = searchState,
            helpState = helpState,
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clearInputFocusOnPointerDown(focusManager)
            .padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s8),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        when {
            query.isBlank() -> SearchHintBlock("输入关键词搜索消息、联系人或会话")
            results.empty -> SearchHintBlock("没有找到相关消息或联系人")
            else -> {
                SearchResultSection(title = "联系人", count = results.contacts.size) {
                    results.contacts.forEach { contact ->
                        SearchContactRow(contact = contact, onClick = { onOpenContactDetail(contact) })
                    }
                }
                SearchResultSection(title = "聊天记录", count = results.messages.size) {
                    results.messages.forEach { message ->
                        SearchMessageRow(message = message, onClick = { onOpenMessage(message) })
                    }
                }
                SearchResultSection(title = "会话", count = results.conversations.size) {
                    results.conversations.forEach { conversation ->
                        SearchConversationRow(conversation = conversation, onClick = { onOpenConversation(conversation) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHintBlock(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .padding(Gomob.spacing.s16),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = Gomob.type.bodySm, color = Gomob.colors.fg2)
    }
}

@Composable
private fun SearchResultSection(
    title: String,
    count: Int,
    content: @Composable () -> Unit,
) {
    if (count <= 0) return
    Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s6)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Gomob.spacing.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = Gomob.type.caption, color = Gomob.colors.fg2, modifier = Modifier.weight(1f))
            Text(count.toString(), style = Gomob.type.numInline, color = Gomob.colors.fg3)
        }
        content()
    }
}

@Composable
private fun SearchContactRow(contact: ContactRowUi, onClick: () -> Unit) {
    SearchResultRow(
        icon = GomobIcons.Contacts,
        title = contact.name,
        subtitle = "${contact.role} · ${contact.employeeId}",
        meta = if (contact.online) "在线" else "离线",
        avatarSeed = "contact-${contact.detailId}-${contact.name}",
        onClick = onClick,
    )
}

@Composable
private fun SearchMessageRow(message: MessageListSearchMessageUi, onClick: () -> Unit) {
    SearchResultRow(
        icon = message.kind.searchResultIcon(),
        title = message.conversationTitle,
        subtitle = "${message.senderLabel} · ${message.preview}",
        meta = message.time,
        avatarSeed = "message-${message.conversationId}-${message.localKey}",
        onClick = onClick,
    )
}

@Composable
private fun SearchConversationRow(conversation: ConversationRowUi, onClick: () -> Unit) {
    SearchResultRow(
        icon = GomobIcons.Search,
        title = conversation.title,
        subtitle = conversation.preview.ifBlank { "会话 #${conversation.id}" },
        meta = conversation.time,
        avatarSeed = "conversation-${conversation.id}-${conversation.title}",
        onClick = onClick,
    )
}

@Composable
private fun SearchResultRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    meta: String,
    avatarSeed: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s12, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
            MessageAvatarImage(
                seed = avatarSeed,
                size = 34.dp,
                shape = Gomob.shapes.r2,
            )
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Gomob.colors.fg3,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(13.dp)
                    .clip(CircleShape)
                    .background(Gomob.colors.bg2)
                    .padding(2.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Gomob.colors.fg0,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (meta.isNotBlank()) {
                    Text(
                        meta,
                        style = Gomob.type.numInline.copy(fontSize = 10.sp),
                        color = Gomob.colors.fg3,
                        maxLines = 1,
                        modifier = Modifier.padding(start = Gomob.spacing.s8),
                    )
                }
            }
            Text(
                subtitle,
                fontSize = 11.sp,
                color = Gomob.colors.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            GomobIcons.ChevronRight,
            contentDescription = null,
            tint = Gomob.colors.fg3,
            modifier = Modifier.size(13.dp),
        )
    }
}

private data class MessageListSearchResults(
    val contacts: List<ContactRowUi>,
    val messages: List<MessageListSearchMessageUi>,
    val conversations: List<ConversationRowUi>,
) {
    val empty: Boolean get() = contacts.isEmpty() && messages.isEmpty() && conversations.isEmpty()
}

private fun buildMessageListSearchResults(
    query: String,
    messageState: MessageListUiState,
    searchState: MessageListSearchUiState,
    helpState: HelpExpertsUiState,
): MessageListSearchResults {
    val keyword = query.trim()
    if (keyword.isBlank()) {
        return MessageListSearchResults(emptyList(), emptyList(), emptyList())
    }
    val normalizedKeyword = keyword.normalizedContactSearchToken()
    val conversations = (messageState as? MessageListUiState.Content)
        ?.conversations
        .orEmpty()
        .filter { it.matchesMessageListKeyword(keyword, normalizedKeyword) }
        .take(6)
    val contacts = buildSearchContactRows(helpState)
        .filter { it.matchesContactKeyword(keyword, normalizedKeyword) }
        .take(8)
    val messages = searchState.messages
        .filter { it.matchesMessageKeyword(keyword, normalizedKeyword) }
        .take(10)
    return MessageListSearchResults(
        contacts = contacts,
        messages = messages,
        conversations = conversations,
    )
}

private fun buildSearchContactRows(state: HelpExpertsUiState): List<ContactRowUi> {
    return when (state) {
        is HelpExpertsUiState.Content -> (
            state.stationContacts.map { it.toContactProfileUi().toContactRowUi() } +
                state.experts.map { it.toContactProfileUi().toContactRowUi() }
            ).distinctBy { it.detailId }
        else -> emptyList()
    }
}

private fun ConversationRowUi.matchesMessageListKeyword(keyword: String, normalizedKeyword: String): Boolean =
    listOf(title, preview, initials).any { it.matchesSearchKeyword(keyword, normalizedKeyword) }

private fun MessageListSearchMessageUi.matchesMessageKeyword(keyword: String, normalizedKeyword: String): Boolean =
    listOf(conversationTitle, senderLabel, preview, searchableText).any {
        it.matchesSearchKeyword(keyword, normalizedKeyword)
    }

private fun String.matchesSearchKeyword(keyword: String, normalizedKeyword: String): Boolean {
    if (contains(keyword, ignoreCase = true)) return true
    if (normalizedKeyword.isEmpty()) return false
    return contactSearchTokens().any { it.contains(normalizedKeyword) }
}

private fun String.searchResultIcon(): ImageVector = when (this) {
    "image" -> GomobIcons.Eyeball
    "voice" -> GomobIcons.VoiceCircle
    "video_clip" -> GomobIcons.Video
    "inspection_card" -> GomobIcons.LinkShare
    "call_invite", "video_call", "audio_call" -> GomobIcons.Phone
    "file", "document" -> GomobIcons.Folder
    else -> GomobIcons.Search
}

@Composable
private fun MsgRow(item: ConversationRowUi, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Gomob.colors.bg0)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            MsgAvatar(seed = "conversation-${item.id}-${item.title}", kind = item.avatarKind)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Gomob.colors.fg0,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        item.time,
                        style = Gomob.type.numInline.copy(fontSize = 10.sp),
                        color = Gomob.colors.fg3,
                        modifier = Modifier.padding(start = Gomob.spacing.s8),
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.preview.ifBlank { " " },
                        fontSize = 12.sp,
                        color = Gomob.colors.fg2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (item.unreadCount > 0) {
                        MultiLineUnreadBadge(item.unreadCount)
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 76.dp, end = Gomob.spacing.s20)
                .height(Gomob.spacing.hairline)
                .background(Gomob.colors.line1.copy(alpha = 0.05f)),
        )
    }
}

@Composable
private fun MsgAvatar(seed: String, kind: AvatarKind) {
    MessageAvatarImage(
        seed = "$kind-$seed",
        size = 44.dp,
        shape = Gomob.shapes.r2,
    )
}

@Composable
private fun MultiLinePane(
    roomsState: MultiLineRoomsUiState,
    onRefresh: () -> Unit,
    onOpenRoom: (MultiLineRoomRowUi) -> Unit,
    onStartAdHoc: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        StartAdHocCallEntry(onClick = onStartAdHoc)
        MultiLineRoomList(
            state = roomsState,
            onRefresh = onRefresh,
            onOpenRoom = onOpenRoom,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StartAdHocCallEntry(onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s8)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.accentSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(Gomob.colors.accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.VideoCall,
                contentDescription = null,
                tint = Color.Black,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("发起多人连线", style = Gomob.type.bodySm, color = Gomob.colors.fg0, fontWeight = FontWeight.SemiBold)
            Text("从联系人中多选成员，立即开启视频会议", style = Gomob.type.caption, color = Gomob.colors.fg2)
        }
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = Gomob.colors.fg2,
        )
    }
}

@Composable
private fun MultiLineRoomList(
    state: MultiLineRoomsUiState,
    onRefresh: () -> Unit,
    onOpenRoom: (MultiLineRoomRowUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    fun dismissSearchInput() {
        focusManager.clearFocus()
        keyboardController?.hide()
    }
    val keyword = query.trim()
    val normalizedKeyword = keyword.normalizedContactSearchToken()
    val rooms = (state as? MultiLineRoomsUiState.Content)?.rooms.orEmpty()
    val visibleRooms = remember(rooms, keyword, normalizedKeyword) {
        if (keyword.isBlank()) {
            rooms
        } else {
            rooms.filter { it.matchesMultiLineRoomKeyword(keyword, normalizedKeyword) }
        }
    }
    Column(modifier.fillMaxSize()) {
        MultiLineRoomSearchBar(
            query = query,
            onQueryChange = { query = it },
        )
        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clearInputFocusOnPointerDown(focusManager) {
                    keyboardController?.hide()
                },
            contentPadding = PaddingValues(bottom = Gomob.spacing.s24),
        ) {
            when (state) {
                MultiLineRoomsUiState.Loading -> item {
                    StateBlock(text = "正在加载多人连线", tone = StatusTone.Neutral)
                }
                is MultiLineRoomsUiState.Error -> item {
                    StateBlock(text = state.message, tone = StatusTone.Danger, onClick = onRefresh)
                }
                MultiLineRoomsUiState.Empty -> item {
                    StateBlock(text = "暂无多人群聊", tone = StatusTone.Neutral, onClick = onRefresh)
                }
                is MultiLineRoomsUiState.Content -> {
                    if (visibleRooms.isEmpty()) {
                        item { StateBlock(text = "未找到相关群聊", tone = StatusTone.Neutral) }
                    } else {
                        items(visibleRooms, key = { it.id }) { room ->
                            MultiLineRoomRow(
                                room = room,
                                onClick = {
                                    dismissSearchInput()
                                    onOpenRoom(room)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiLineRoomSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Box(
        Modifier
            .padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, bottom = 8.dp)
            .fillMaxWidth(),
    ) {
        SearchBar(
            query = query,
            onQueryChange = onQueryChange,
            onActiveChange = {},
            placeholder = "搜索群聊",
        )
    }
}

@Composable
private fun MultiLineRoomRow(
    room: MultiLineRoomRowUi,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Gomob.colors.bg0)
            .clickable(onClick = onClick),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
        ) {
            MultiLineRoomAvatarMosaic(room.avatarSeeds)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        room.title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Gomob.colors.fg0,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        room.time,
                        style = Gomob.type.numInline.copy(fontSize = 10.sp),
                        color = Gomob.colors.fg3,
                        modifier = Modifier.padding(start = Gomob.spacing.s8),
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        room.preview.ifBlank { room.subtitle },
                        fontSize = 12.sp,
                        color = Gomob.colors.fg2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (room.unreadCount > 0) {
                        MultiLineUnreadBadge(room.unreadCount)
                    } else {
                        Text(
                            room.memberCountLabel,
                            style = Gomob.type.numInline.copy(fontSize = 10.sp),
                            color = Gomob.colors.fg3,
                            modifier = Modifier.padding(start = Gomob.spacing.s8),
                        )
                    }
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 76.dp, end = Gomob.spacing.s20)
                .height(Gomob.spacing.hairline)
                .background(Gomob.colors.line1.copy(alpha = 0.05f)),
        )
    }
}

@Composable
private fun MultiLineRoomAvatarMosaic(seeds: List<String>) {
    val normalizedSeeds = seeds.filter { it.isNotBlank() }.take(4).ifEmpty {
        listOf("group-default-a", "group-default-b", "group-default-c", "group-default-d")
    }
    Column(
        Modifier
            .size(44.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg2)
            .padding(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        normalizedSeeds.chunked(2).forEach { row ->
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                row.forEach { seed ->
                    MessageAvatarImage(
                        seed = seed,
                        size = 19.dp,
                        shape = Gomob.shapes.r1,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MultiLineUnreadBadge(count: Long) {
    val text = if (count > 99) "99+" else count.toString()
    Box(
        Modifier
            .padding(start = Gomob.spacing.s8)
            .height(18.dp)
            .width(if (text.length > 1) 26.dp else 18.dp)
            .clip(CircleShape)
            .background(Gomob.colors.danger),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Gomob.type.numInline.copy(fontSize = 10.sp), color = Color.White)
    }
}

private fun MultiLineRoomRowUi.matchesMultiLineRoomKeyword(keyword: String, normalizedKeyword: String): Boolean =
    listOf(title, subtitle, preview, memberCountLabel).any { it.matchesSearchKeyword(keyword, normalizedKeyword) }

@Composable
private fun UnreadStatusDot(
    tone: WatchTone,
    modifier: Modifier = Modifier,
) {
    val color = when (tone) {
        WatchTone.Danger -> Gomob.colors.danger
        WatchTone.Warn -> Gomob.colors.warn
        WatchTone.Accent -> Gomob.colors.accent
        WatchTone.Ok -> Gomob.colors.ok
        WatchTone.Neutral -> Gomob.colors.fg2
    }
    Box(
        modifier
            .size(7.dp)
            .clip(CircleShape)
            .background(color)
    )
}

@Composable
private fun AdHocCallPicker(
    visible: Boolean,
    state: HelpExpertsUiState,
    errorText: String?,
    onClose: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    BackHandler(enabled = visible, onBack = onClose)
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .clickable(onClick = onClose),
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { it }),
        exit = slideOutHorizontally(targetOffsetX = { it }),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            AdHocCallPickerContent(
                state = state,
                errorText = errorText,
                onClose = onClose,
                onConfirm = onConfirm,
            )
        }
    }
}

@Composable
private fun AdHocCallPickerContent(
    state: HelpExpertsUiState,
    errorText: String?,
    onClose: () -> Unit,
    onConfirm: (List<Long>) -> Unit,
) {
    val sections = remember(state) { buildContactSections(state) }
    val candidates = remember(sections) {
        // 仅取真实有 peerUserId 的联系人，过滤掉占位 / mock
        sections.flatMap { sec -> sec.contacts.mapNotNull { it.peerUserId?.let { uid -> uid to it } } }
            .distinctBy { it.first }
    }
    var selectedIds by rememberSaveable { mutableStateOf<List<Long>>(emptyList()) }
    val selectedCount = selectedIds.size

    Column(
        Modifier
            .fillMaxWidth(0.82f)
            .fillMaxHeight()
            .background(Gomob.colors.bg1),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s12),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            Column(Modifier.weight(1f)) {
                Text("AD-HOC CALL", fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 0.14.em, color = Gomob.colors.fg3)
                Spacer(Modifier.height(Gomob.spacing.s2))
                Text("发起多人连线", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
            }
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Close, contentDescription = "关闭", tint = Gomob.colors.fg2, modifier = Modifier.size(20.dp))
            }
        }
        if (!errorText.isNullOrBlank()) {
            Text(
                errorText,
                style = Gomob.type.caption,
                color = Gomob.colors.danger,
                modifier = Modifier.padding(horizontal = Gomob.spacing.s16, vertical = Gomob.spacing.s4),
            )
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Gomob.spacing.s12, vertical = Gomob.spacing.s8),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (candidates.isEmpty()) {
                item {
                    StateBlock(text = "暂无可呼叫的联系人", tone = StatusTone.Neutral)
                }
            } else {
                items(candidates, key = { it.first }) { (userId, contact) ->
                    AdHocCandidateRow(
                        contact = contact,
                        checked = userId in selectedIds,
                        onToggle = {
                            selectedIds = if (userId in selectedIds) selectedIds - userId
                            else selectedIds + userId
                        },
                    )
                }
            }
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(Gomob.spacing.s16),
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(Gomob.shapes.r2)
                    .background(if (selectedCount > 0) Gomob.colors.accent else Gomob.colors.bg2)
                    .clickable(enabled = selectedCount > 0) {
                        onConfirm(selectedIds)
                    }
                    .padding(vertical = Gomob.spacing.s12),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (selectedCount > 0) "发起视频连线 ($selectedCount)" else "请至少选 1 位",
                    style = Gomob.type.bodySm,
                    color = if (selectedCount > 0) Color.Black else Gomob.colors.fg3,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun AdHocCandidateRow(
    contact: ContactRowUi,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r1)
            .clickable(onClick = onToggle)
            .padding(horizontal = Gomob.spacing.s8, vertical = Gomob.spacing.s8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) Gomob.colors.accent else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(14.dp))
            } else {
                Box(Modifier.size(22.dp).clip(CircleShape).background(Color.Transparent))
            }
        }
        ContactRow(contact = contact, onClick = onToggle)
    }
}

@Composable
private fun StateBlock(
    text: String,
    tone: StatusTone,
    onClick: (() -> Unit)? = null,
) {
    Box(
        Modifier
            .padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s12)
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(Gomob.spacing.s16),
    ) {
        StatusTag(text = text, tone = tone, showDot = tone != StatusTone.Neutral)
    }
}
