package io.gomob.feature.message

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Videocam
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.gomob.designsystem.component.ScreenHeader
import io.gomob.designsystem.component.StatusTag
import io.gomob.designsystem.component.StatusTone
import io.gomob.designsystem.icons.GomobIcons
import io.gomob.designsystem.theme.Gomob
import io.gomob.model.message.MessageQuote

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
)

@Composable
fun MessageRoute(
    onOpenConversation: (String) -> Unit = {},
    onOpenLocalVideo: (String) -> Unit = {},
    onOpenExpertDetail: (String) -> Unit = {},
    requestedTab: MessageEntryTab? = null,
    onRequestedTabConsumed: () -> Unit = {},
    viewModel: MessageListViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val helpState by viewModel.helpUiState.collectAsStateWithLifecycle()
    val helpRoomState by viewModel.helpRoomUiState.collectAsStateWithLifecycle()
    val forwardTargets by viewModel.forwardTargets.collectAsStateWithLifecycle()
    val contactActionError by viewModel.contactActionError.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let(viewModel::sendHelpRoomImage) },
    )
    val photoCapture = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicturePreview(),
        onResult = { bitmap ->
            bitmap?.let { viewModel.sendHelpRoomImage(it.writeMessageCapture(context)) }
        },
    )
    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let(viewModel::sendHelpRoomVideoClip) },
    )
    val focusManager = LocalFocusManager.current
    var tab by rememberSaveable { mutableStateOf((requestedTab ?: MessageEntryTab.List).toMsgTab()) }
    var contactsOpen by rememberSaveable { mutableStateOf(false) }
    var helpInspectionPickerOpen by rememberSaveable { mutableStateOf(false) }
    val hasMessageUnread = (state as? MessageListUiState.Content)
        ?.conversations
        ?.any { it.unreadCount > 0 } == true
    val hasHelpUnread = (helpRoomState as? HelpRoomUiState.Content)?.unreadCount?.let { it > 0 } == true
    val pageErrorText = messageRouteErrorText(
        tab = tab,
        messageState = state,
        helpState = helpState,
        helpRoomState = helpRoomState,
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
        viewModel.forwardResultEvents.collect { message ->
            context.showMessageActionToast(message)
        }
    }

    Box(Modifier.fillMaxSize().background(Gomob.colors.bg0)) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = "消息中心",
                eyebrow = "实时协同 · 监管督查 · 专家会审",
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
                    onRefresh = viewModel::refresh,
                    onOpenConversation = { viewModel.openConversation(it.id) },
                    modifier = Modifier.weight(1f),
                )
                MsgTab.Help -> HelpPane(
                    state = helpState,
                    roomState = helpRoomState,
                    onRefresh = {
                        viewModel.refreshHelpExperts()
                        viewModel.refreshHelpRoom()
                    },
                    onOpenExpertDetail = { expert -> onOpenExpertDetail(expert.userId.toString()) },
                    onSendHelpMessage = viewModel::sendHelpRoomMessage,
                    onPickHelpImage = {
                        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onTakeHelpPhoto = { photoCapture.launch(null) },
                    onSendHelpVoice = viewModel::sendHelpRoomVoice,
                    onTranscribeHelpVoice = viewModel::transcribeHelpRoomVoice,
                    onSendHelpVideoClip = {
                        videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                    },
                    onShareHelpInspection = {
                        focusManager.clearFocus()
                        helpInspectionPickerOpen = true
                    },
                    onRetry = viewModel::retryHelpRoomMessage,
                    onRetryTranscript = viewModel::retryHelpRoomVoiceTranscript,
                    onError = viewModel::showHelpRoomError,
                    onOpenLocalVideo = { onOpenLocalVideo("专家连线 · 第一视角") },
                    forwardTargets = forwardTargets,
                    onForwardHelpMessages = viewModel::forwardHelpRoomMessages,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        InspectionSharePicker(
            visible = helpInspectionPickerOpen,
            onDismiss = { helpInspectionPickerOpen = false },
            onSelect = { card ->
                helpInspectionPickerOpen = false
                viewModel.sendHelpRoomInspectionCard(card)
            },
        )
        ContactsDrawer(
            visible = contactsOpen,
            state = helpState,
            errorText = contactActionError,
            onClose = {
                contactsOpen = false
                viewModel.clearContactActionError()
            },
            onSendMessage = { contact -> viewModel.openDirectConversation(contact.peerUserId) },
            onOpenAudioVideo = { contact -> onOpenLocalVideo("${contact.name} · 音视频通话") },
        )
        FloatingMessageError(
            text = pageErrorText,
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
                .padding(bottom = if (tab == MsgTab.Help) 92.dp else 22.dp),
        )
    }
}

private fun MessageEntryTab.toMsgTab(): MsgTab = when (this) {
    MessageEntryTab.List -> MsgTab.List
    MessageEntryTab.Help -> MsgTab.Help
}

private fun messageRouteErrorText(
    tab: MsgTab,
    messageState: MessageListUiState,
    helpState: HelpExpertsUiState,
    helpRoomState: HelpRoomUiState,
    contactActionError: String?,
): String? = contactActionError ?: when (tab) {
    MsgTab.List -> messageState.floatingErrorText()
    MsgTab.Help -> helpRoomState.floatingErrorText() ?: helpState.floatingErrorText()
}

private fun MessageListUiState.floatingErrorText(): String? = when (this) {
    is MessageListUiState.Error -> message
    is MessageListUiState.Content -> if (offlineCached) errorMessage ?: "未连接实时通道" else null
    else -> null
}

private fun HelpExpertsUiState.floatingErrorText(): String? = when (this) {
    is HelpExpertsUiState.Error -> message
    is HelpExpertsUiState.Content -> if (offlineCached) errorMessage ?: "专家列表使用本地缓存" else null
    else -> null
}

private fun HelpRoomUiState.floatingErrorText(): String? = when (this) {
    is HelpRoomUiState.Error -> message
    is HelpRoomUiState.Content -> errorMessage
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
    onSendMessage: (ContactRowUi) -> Unit,
    onOpenAudioVideo: (ContactRowUi) -> Unit,
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
                onSendMessage = onSendMessage,
                onOpenAudioVideo = onOpenAudioVideo,
            )
        }
    }
}

@Composable
private fun ContactsDrawerContent(
    state: HelpExpertsUiState,
    errorText: String?,
    onSendMessage: (ContactRowUi) -> Unit,
    onOpenAudioVideo: (ContactRowUi) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val sections = remember(state) { buildContactSections(state) }
    var expandedSectionIds by rememberSaveable { mutableStateOf(listOf("recent")) }
    var selectedId by rememberSaveable { mutableStateOf(sections.firstContact()?.id.orEmpty()) }
    val visibleSections = remember(sections, query) { sections.filterContacts(query) }
    val selected = sections.contacts().firstOrNull { it.id == selectedId } ?: sections.firstContact()
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
                                    selected = contact.id == selected?.id,
                                    onClick = { selectedId = contact.id },
                                )
                                if (index != section.contacts.lastIndex) {
                                    ContactListDivider()
                                }
                            }
                        }
                    }
                }
            }
            ContactDrawerBottomBar(
                selected = selected,
                onSendMessage = onSendMessage,
                onOpenAudioVideo = onOpenAudioVideo,
            )
        }
        FloatingMessageError(
            text = errorText,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 94.dp),
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
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(Gomob.shapes.r2)
            .background(if (selected) Gomob.colors.accentSoft else Color.Transparent)
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
            tint = if (selected) Gomob.colors.accent else Gomob.colors.fg3,
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

@Composable
private fun ContactDrawerBottomBar(
    selected: ContactRowUi?,
    onSendMessage: (ContactRowUi) -> Unit,
    onOpenAudioVideo: (ContactRowUi) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Gomob.colors.bg1)
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
    ) {
        selected?.let { contact ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                ContactAvatar(contact)
                Text(contact.name, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.fg0)
                Text(
                    if (contact.online) "在线" else "忙碌",
                    fontSize = 10.sp,
                    color = if (contact.online) Gomob.colors.ok else Gomob.colors.warn,
                    modifier = Modifier
                        .clip(Gomob.shapes.r1)
                        .background(if (contact.online) Gomob.colors.ok.copy(alpha = 0.12f) else Gomob.colors.warn.copy(alpha = 0.12f))
                        .padding(horizontal = Gomob.spacing.s6, vertical = 2.dp),
                )
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(Gomob.shapes.r2)
                    .background(Gomob.colors.accentSoft)
                    .clickable(enabled = selected != null) { selected?.let(onSendMessage) },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    GomobIcons.Send,
                    contentDescription = null,
                    tint = Gomob.colors.accent,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(Gomob.spacing.s8))
                Text("发消息", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Gomob.colors.accent)
            }
            ContactActionIcon(GomobIcons.Phone, "语音通话") {
                selected?.let(onOpenAudioVideo)
            }
            ContactActionIcon(Icons.Filled.Videocam, "视频通话", iconSize = 18.dp) {
                selected?.let(onOpenAudioVideo)
            }
        }
    }
}

@Composable
private fun ContactActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    iconSize: Dp = 15.dp,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(42.dp)
            .clip(Gomob.shapes.r2)
            .background(Gomob.colors.bg0)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = Gomob.colors.fg2, modifier = Modifier.size(iconSize))
    }
}

private fun buildContactSections(state: HelpExpertsUiState): List<ContactSectionUi> {
    val station = listOf(
        ContactRowUi("station-zhou", "周科", "周", "OBD 主审", "ZAA01", online = true, peerUserId = null),
        ContactRowUi("station-wu", "吴风", "吴", "外观件专家", "ZAA02", online = true, peerUserId = null),
        ContactRowUi("station-liu", "刘冶", "刘", "VIN 拓印", "ZAA03", online = false, peerUserId = null),
        ContactRowUi("station-jiang", "江庆宇", "江", "查验员", "ZAA04", online = false, peerUserId = null),
        ContactRowUi("station-shen", "沈海明", "沈", "查验员", "ZAA0120230001", online = true, peerUserId = null),
    )
    val supervision = listOf(
        ContactRowUi("supervision-review", "省所复核", "省", "监管复核", "REG01", online = true, peerUserId = null),
        ContactRowUi("supervision-duty", "值班督导", "督", "异常督办", "REG02", online = false, peerUserId = null),
    )
    val experts = when (state) {
        is HelpExpertsUiState.Content -> state.experts.map { expert ->
            ContactRowUi(
                id = "expert-${expert.userId}",
                name = expert.name,
                initials = expert.initials,
                role = expert.roleTitle,
                employeeId = expert.employeeId,
                online = expert.availabilityText == "可发消息",
                peerUserId = expert.userId,
            )
        }
        else -> emptyList()
    }
    val recent = experts.take(3).mapIndexed { index, contact ->
        contact.copy(
            id = "recent-${contact.id}",
            role = when (index) {
                0 -> "今日 12:31"
                1 -> "今日 12:37"
                else -> "昨日 18:02"
            },
        )
    }.ifEmpty {
        station.take(3).mapIndexed { index, contact ->
            contact.copy(
                id = "recent-${contact.id}",
                role = when (index) {
                    0 -> "今日 12:31"
                    1 -> "今日 12:37"
                    else -> "昨日 18:02"
                },
            )
        }
    }

    return listOf(
        ContactSectionUi("recent", "近期联系人", recent.size.toString(), WatchTone.Neutral, recent),
        ContactSectionUi("station", "本站 · 杭州西湖检测站", station.size.toString(), WatchTone.Accent, station),
        ContactSectionUi("supervision", "监管中心 · 浙江省车管所", supervision.size.toString(), WatchTone.Warn, supervision),
        ContactSectionUi("experts", "外部专家 · 协作池", experts.size.toString(), WatchTone.Danger, experts),
    ).filter { it.contacts.isNotEmpty() }
}

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

private val contactPinyinMap = mapOf(
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

private fun List<ContactSectionUi>.contacts(): List<ContactRowUi> = flatMap { it.contacts }

private fun List<ContactSectionUi>.firstContact(): ContactRowUi? = contacts().firstOrNull()

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
                    "专家连线",
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
    onRefresh: () -> Unit,
    onOpenConversation: (ConversationRowUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    var searchActive by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val exitSearch = remember(focusManager, keyboardController) {
        {
            searchActive = false
            focusManager.clearFocus()
            keyboardController?.hide()
            Unit
        }
    }
    BackHandler(enabled = searchActive, onBack = exitSearch)

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = Gomob.spacing.s24),
        ) {
            item { SearchContainer(onActiveChange = { searchActive = it }) }
            when (state) {
                MessageListUiState.Loading -> item {
                    StateBlock(text = "正在加载会话", tone = StatusTone.Neutral)
                }
                MessageListUiState.Empty -> Unit
                is MessageListUiState.Error -> Unit
                is MessageListUiState.Content -> {
                    items(state.conversations, key = { item -> item.id }) { item ->
                        Box(
                            Modifier
                                .padding(horizontal = Gomob.spacing.s20)
                                .padding(bottom = Gomob.spacing.s8),
                        ) {
                            MsgRow(item, onClick = { onOpenConversation(item) })
                        }
                    }
                }
            }
        }
        SearchInputScrim(
            visible = searchActive,
            onDismiss = exitSearch,
            modifier = Modifier.padding(top = 50.dp),
        )
    }
}

@Composable
private fun SearchContainer(onActiveChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .padding(start = Gomob.spacing.s20, end = Gomob.spacing.s20, bottom = 8.dp)
            .fillMaxWidth(),
    ) {
        SearchBar(onActiveChange = onActiveChange)
    }
}

@Composable
private fun SearchBar(onActiveChange: (Boolean) -> Unit) {
    var draft by remember { mutableStateOf("") }
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
                value = draft,
                onValueChange = { draft = it },
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
                        if (draft.isEmpty()) {
                            Text(
                                "搜索消息 / 联系人",
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
private fun SearchInputScrim(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return
    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.1f))
            .clickable(onClick = onDismiss),
    )
}

@Composable
private fun MsgRow(item: ConversationRowUi, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .clickable(onClick = onClick)
            .padding(horizontal = Gomob.spacing.s14, vertical = Gomob.spacing.s12),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Gomob.spacing.s12),
    ) {
        MsgAvatar(seed = "conversation-${item.id}-${item.title}", kind = item.avatarKind)
        Column(Modifier.weight(1f)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    item.title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = Gomob.colors.fg0,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    item.time,
                    style = Gomob.type.numInline.copy(fontSize = 10.sp),
                    color = Gomob.colors.fg3,
                    modifier = Modifier.padding(start = Gomob.spacing.s8),
                )
            }
            Spacer(Modifier.height(Gomob.spacing.s4))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.preview.isNotBlank()) {
                    Text(
                        item.preview,
                        fontSize = 12.sp,
                        color = Gomob.colors.fg2,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(
                        Modifier
                            .weight(1f)
                            .height(18.dp),
                    )
                }
                if (item.unreadCount > 0) {
                    UnreadStatusDot(item.unreadTone, modifier = Modifier.padding(start = Gomob.spacing.s8))
                }
            }
        }
    }
}

@Composable
private fun MsgAvatar(seed: String, kind: AvatarKind) {
    MessageAvatarImage(
        seed = "$kind-$seed",
        size = 38.dp,
        shape = Gomob.shapes.r2,
    )
}

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
private fun HelpPane(
    state: HelpExpertsUiState,
    roomState: HelpRoomUiState,
    onRefresh: () -> Unit,
    onOpenExpertDetail: (HelpExpertRowUi) -> Unit,
    onSendHelpMessage: (String, MessageQuote?) -> Unit,
    onPickHelpImage: () -> Unit,
    onTakeHelpPhoto: () -> Unit,
    onSendHelpVoice: (android.net.Uri, Int) -> Unit,
    onTranscribeHelpVoice: (android.net.Uri, Int) -> Unit,
    onSendHelpVideoClip: () -> Unit,
    onShareHelpInspection: () -> Unit,
    onRetry: (String?) -> Unit,
    onRetryTranscript: (Long?) -> Unit,
    onError: (String) -> Unit,
    onOpenLocalVideo: () -> Unit,
    forwardTargets: List<MessageForwardTargetUi>,
    onForwardHelpMessages: (MessageForwardTargetUi, List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf("") }
    var inputFocused by remember { mutableStateOf(false) }
    var voiceRecording by remember { mutableStateOf(false) }
    var quoteDraft by remember { mutableStateOf<QuoteDraftUi?>(null) }
    var favoriteMessageKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var multiSelectMode by rememberSaveable { mutableStateOf(false) }
    var selectedMessageKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var forwardingMessages by remember { mutableStateOf<List<MessageBubbleUi>>(emptyList()) }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val focusManager = LocalFocusManager.current
    val voiceRecorder = rememberVoiceRecorder()
    val voicePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            runCatching {
                voiceRecorder.start()
                voiceRecording = true
            }.onFailure { onError(it.message ?: "录音启动失败") }
        } else {
            onError("未授予录音权限")
        }
    }
    val startVoiceRecording: () -> Unit = startVoice@{
        if (voiceRecording) return@startVoice
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                voiceRecorder.start()
                voiceRecording = true
            }.onFailure { onError(it.message ?: "录音启动失败") }
        } else {
            voicePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val sendVoiceRecording: () -> Unit = sendVoice@{
        if (!voiceRecording) return@sendVoice
        runCatching { voiceRecorder.stop() }
            .onSuccess { result -> onSendHelpVoice(result.uri, result.durationSec) }
            .onFailure { onError(it.message ?: "录音失败") }
        voiceRecording = false
    }
    val cancelVoiceRecording: () -> Unit = cancelVoice@{
        if (!voiceRecording) return@cancelVoice
        voiceRecorder.cancel()
        voiceRecording = false
    }
    val transcribeVoiceRecording: () -> Unit = transcribeVoice@{
        if (!voiceRecording) return@transcribeVoice
        runCatching { voiceRecorder.stop() }
            .onSuccess { result -> onTranscribeHelpVoice(result.uri, result.durationSec) }
            .onFailure { onError(it.message ?: "录音失败") }
        voiceRecording = false
    }
    fun toggleMessageSelection(localKey: String) {
        selectedMessageKeys = if (localKey in selectedMessageKeys) {
            selectedMessageKeys - localKey
        } else {
            selectedMessageKeys + localKey
        }
        if (selectedMessageKeys.isEmpty()) multiSelectMode = false
    }
    fun selectedMessages(): List<MessageBubbleUi> =
        (roomState as? HelpRoomUiState.Content)?.messages.orEmpty().filter { it.localKey in selectedMessageKeys }

    fun exitMultiSelect() {
        multiSelectMode = false
        selectedMessageKeys = emptyList()
    }
    fun copyMessages(messages: List<MessageBubbleUi>) {
        val text = messageShareText(messages)
        if (text.isBlank()) return
        clipboard.setText(AnnotatedString(text))
        context.showMessageActionToast("已复制")
    }
    fun handleMessageAction(action: MessageQuickAction, bubble: MessageBubbleUi) {
        when (action) {
            MessageQuickAction.Copy -> copyMessages(listOf(bubble))
            MessageQuickAction.Forward -> forwardingMessages = listOf(bubble)
            MessageQuickAction.Favorite -> {
                val added = bubble.localKey !in favoriteMessageKeys
                favoriteMessageKeys = if (added) favoriteMessageKeys + bubble.localKey else favoriteMessageKeys - bubble.localKey
                context.showMessageActionToast(if (added) "已收藏" else "已取消收藏")
            }
            MessageQuickAction.MultiSelect -> {
                multiSelectMode = true
                selectedMessageKeys = listOf(bubble.localKey)
            }
            MessageQuickAction.Quote -> {
                quoteDraft = QuoteDraftUi(bubble.toMessageQuote())
                context.showMessageActionToast("已引用")
            }
            MessageQuickAction.TranscribeVoice -> onRetryTranscript(bubble.serverId)
        }
    }

    MessageForwardTargetDialog(
        visible = forwardingMessages.isNotEmpty(),
        targets = forwardTargets,
        messageCount = forwardingMessages.size,
        onDismiss = { forwardingMessages = emptyList() },
        onSelectTarget = { target ->
            val sourceLocalKeys = forwardingMessages.map { it.localKey }
            forwardingMessages = emptyList()
            exitMultiSelect()
            onForwardHelpMessages(target, sourceLocalKeys)
        },
    )

    Column(modifier.fillMaxSize().imePadding()) {
        when (roomState) {
            HelpRoomUiState.Loading -> LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clearInputFocusOnPointerDown(focusManager),
                contentPadding = PaddingValues(
                    horizontal = Gomob.spacing.s20,
                    vertical = Gomob.spacing.s8,
                ),
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                item {
                    HelpParticipantCompactHeader(
                        state = state,
                        onOpenExpertDetail = onOpenExpertDetail,
                        onRefresh = onRefresh,
                    )
                }
            }
            is HelpRoomUiState.Error -> LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clearInputFocusOnPointerDown(focusManager),
                contentPadding = PaddingValues(
                    horizontal = Gomob.spacing.s20,
                    vertical = Gomob.spacing.s8,
                ),
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
            ) {
                item {
                    HelpParticipantCompactHeader(
                        state = state,
                        onOpenExpertDetail = onOpenExpertDetail,
                        onRefresh = onRefresh,
                    )
                }
            }
            is HelpRoomUiState.Content -> HelpRoomMessageList(
                state = roomState,
                expertState = state,
                onRefresh = onRefresh,
                onOpenExpertDetail = onOpenExpertDetail,
                onRetry = onRetry,
                onRetryTranscript = onRetryTranscript,
                inputFocused = inputFocused,
                favoriteMessageKeys = favoriteMessageKeys,
                selectedMessageKeys = selectedMessageKeys,
                multiSelectMode = multiSelectMode,
                onToggleSelected = ::toggleMessageSelection,
                onQuickAction = ::handleMessageAction,
                modifier = Modifier.weight(1f),
            )
        }
        if (multiSelectMode) {
            MessageMultiSelectBar(
                selectedCount = selectedMessageKeys.size,
                onCancel = ::exitMultiSelect,
                onCopy = {
                    copyMessages(selectedMessages())
                    exitMultiSelect()
                },
                onForward = {
                    forwardingMessages = selectedMessages()
                },
            )
        } else {
            MessageComposerBar(
                draft = draft,
                enabled = roomState is HelpRoomUiState.Content,
                onDraftChange = { draft = it },
                onShareInspection = onShareHelpInspection,
                onPickImage = onPickHelpImage,
                onTakePhoto = onTakeHelpPhoto,
                onStartVoice = startVoiceRecording,
                onSendVoice = sendVoiceRecording,
                onCancelVoice = cancelVoiceRecording,
                onTranscribeVoice = transcribeVoiceRecording,
                onSendVideoClip = onSendHelpVideoClip,
                onOpenLocalVideo = onOpenLocalVideo,
                voiceRecording = voiceRecording,
                quoteDraft = quoteDraft,
                onClearQuote = { quoteDraft = null },
                onInputFocusChanged = { inputFocused = it },
                onSendText = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        onSendHelpMessage(text, quoteDraft?.quote)
                        draft = ""
                        quoteDraft = null
                    }
                },
            )
        }
    }
}

@Composable
private fun HelpParticipantCompactHeader(
    state: HelpExpertsUiState,
    onOpenExpertDetail: (HelpExpertRowUi) -> Unit,
    onRefresh: () -> Unit,
) {
    when (state) {
        HelpExpertsUiState.Loading -> HelpInlineStatus(
            text = "正在加载固定专家",
            tone = StatusTone.Neutral,
            onClick = onRefresh,
        )
        HelpExpertsUiState.Empty -> HelpInlineStatus(
            text = "服务端未配置固定专家",
            tone = StatusTone.Warn,
            onClick = onRefresh,
        )
        is HelpExpertsUiState.Error -> HelpInlineStatus(
            text = "固定专家待刷新",
            tone = StatusTone.Neutral,
            onClick = onRefresh,
        )
        is HelpExpertsUiState.Content -> {
            Column(verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8)) {
                ExpertParticipantCompactBar(
                    experts = state.experts,
                    onOpenExpertDetail = onOpenExpertDetail,
                )
            }
        }
    }
}

@Composable
private fun HelpRoomMessageList(
    state: HelpRoomUiState.Content,
    expertState: HelpExpertsUiState,
    onRefresh: () -> Unit,
    onOpenExpertDetail: (HelpExpertRowUi) -> Unit,
    onRetry: (String?) -> Unit,
    onRetryTranscript: (Long?) -> Unit,
    inputFocused: Boolean,
    favoriteMessageKeys: List<String>,
    selectedMessageKeys: List<String>,
    multiSelectMode: Boolean,
    onToggleSelected: (String) -> Unit,
    onQuickAction: (MessageQuickAction, MessageBubbleUi) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val latestMessage = state.messages.lastOrNull()
    val timelineItems = remember(state.messages) { buildChatTimeline(state.messages) }
    val displayItems = remember(timelineItems) { timelineItems.asReversed() }
    val targetItemCount = helpRoomListItemCount(state, displayItems)
    var lastObservedLatestMessageKey by remember { mutableStateOf<String?>(null) }
    var hasObservedInitialLatestMessage by remember { mutableStateOf(false) }
    val imeBottom = WindowInsets.ime.getBottom(density)
    LaunchedEffect(
        inputFocused,
        imeBottom,
        latestMessage?.localKey,
        latestMessage?.mine,
        state.loading,
        state.errorMessage,
        state.offlineCached,
    ) {
        val latestKey = latestMessage?.localKey
        val latestChanged = latestKey != null && latestKey != lastObservedLatestMessageKey
        val newOwnMessage = latestChanged && hasObservedInitialLatestMessage && latestMessage?.mine == true
        if (targetItemCount > 0) {
            val alreadyNearLatest = listState.layoutInfo.visibleItemsInfo.any { it.index <= 1 }
            if (inputFocused || newOwnMessage || alreadyNearLatest) {
                listState.animateScrollToItem(0)
            }
        }
        if (latestKey != null) {
            lastObservedLatestMessageKey = latestKey
            hasObservedInitialLatestMessage = true
        }
    }

    Box(
        modifier
            .fillMaxWidth()
            .clipToBounds()
            .clearInputFocusOnPointerDown(focusManager),
    ) {
        StarfieldBackground(Modifier.matchParentSize())
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Gomob.spacing.s20, vertical = Gomob.spacing.s8),
            ) {
                HelpParticipantCompactHeader(
                    state = expertState,
                    onOpenExpertDetail = onOpenExpertDetail,
                    onRefresh = onRefresh,
                )
            }
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(
                    horizontal = Gomob.spacing.s20,
                    vertical = Gomob.spacing.s8,
                ),
                verticalArrangement = Arrangement.spacedBy(Gomob.spacing.s8),
                reverseLayout = true,
            ) {
                when {
                    state.errorMessage != null && state.messages.isEmpty() -> Unit
                    state.empty -> Unit
                    else -> items(displayItems, key = { it.key }) { item ->
                        when (item) {
                            is ChatTimelineItem.TimeDivider -> ChatTimeDivider(item.label)
                            is ChatTimelineItem.Message -> {
                                val bubble = item.bubble
                                ChatMessageRow(
                                    bubble = bubble,
                                    favorite = bubble.localKey in favoriteMessageKeys,
                                    selected = bubble.localKey in selectedMessageKeys,
                                    multiSelectMode = multiSelectMode,
                                    onToggleSelected = { onToggleSelected(bubble.localKey) },
                                    onQuickAction = onQuickAction,
                                    onRetry = { onRetry(bubble.clientMsgId) },
                                    onRetryTranscript = { onRetryTranscript(bubble.serverId) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun helpRoomListItemCount(
    state: HelpRoomUiState.Content,
    displayItems: List<ChatTimelineItem>,
): Int {
    return when {
        state.errorMessage != null && state.messages.isEmpty() -> 0
        state.empty -> 0
        else -> displayItems.size
    }
}

@Composable
private fun ExpertParticipantCompactBar(
    experts: List<HelpExpertRowUi>,
    onOpenExpertDetail: (HelpExpertRowUi) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        experts.take(6).forEach { expert ->
            ExpertCompactAvatar(
                expert = expert,
                onAvatarClick = { onOpenExpertDetail(expert) },
            )
        }
        if (experts.size > 6) {
            ExpertOverflowAvatar(count = experts.size - 6)
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun ExpertCompactAvatar(
    expert: HelpExpertRowUi,
    onAvatarClick: () -> Unit,
) {
    val available = expert.availabilityText == "可发消息"
    Box(
        Modifier
            .size(40.dp)
            .clickable(onClick = onAvatarClick),
        contentAlignment = Alignment.BottomEnd,
    ) {
        MessageAvatarImage(
            seed = "expert-${expert.userId}-${expert.name}",
            size = 36.dp,
            shape = CircleShape,
            online = available,
            modifier = Modifier.align(Alignment.Center),
        )
    }
}

@Composable
private fun ExpertOverflowAvatar(count: Int) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Gomob.colors.bg2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "+$count",
            style = Gomob.type.numInline,
            color = Gomob.colors.fg3,
        )
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

@Composable
private fun HelpInlineStatus(
    text: String,
    tone: StatusTone,
    onClick: (() -> Unit)?,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(Gomob.shapes.r3)
            .background(Gomob.colors.bg1)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(Gomob.spacing.s16),
    ) {
        StatusTag(text = text, tone = tone, showDot = tone != StatusTone.Neutral)
    }
}
