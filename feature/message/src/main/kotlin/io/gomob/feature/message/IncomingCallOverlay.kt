package io.gomob.feature.message

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.gomob.data.auth.TokenStore
import io.gomob.data.message.IncomingCallInvite
import io.gomob.data.message.MessageRepository
import io.gomob.designsystem.glass.glassChrome
import io.gomob.designsystem.theme.Gomob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 全局来电邀请管理：订阅 [MessageRepository.incomingCallInvites]，过滤掉本端发起的邀请，
 * 暴露当前 pending 浮窗状态给 [IncomingCallOverlay] 渲染。
 */
@HiltViewModel
class IncomingCallOverlayViewModel @Inject constructor(
    repository: MessageRepository,
    private val tokenStore: TokenStore,
) : ViewModel() {

    private val _pending = MutableStateFlow<IncomingCallInvite?>(null)
    val pending: StateFlow<IncomingCallInvite?> = _pending.asStateFlow()

    // 已被用户处理（接听/拒绝）的邀请标识，避免 combine 因 currentUserId 等上游重新发射时
    // 把同一条已处置的来电再次弹出。以 (conversationId, serverSeq) 作为来电唯一标识。
    private var handledInviteKey: Pair<Long, Long>? = null

    init {
        viewModelScope.launch {
            combine(
                repository.incomingCallInvites,
                tokenStore.currentUserIdFlow,
            ) { invite, currentUserId -> invite to currentUserId }
                .collectLatest { (invite, currentUserId) ->
                    // 自己主动 createCallInvite 时 server 也会 fanout 给 sender，过滤掉。
                    if (invite.senderId != null && invite.senderId == currentUserId) return@collectLatest
                    // 已处置过的同一条来电不再重弹（上游可能因 currentUserId 变化重新发射）。
                    if (invite.identityKey() == handledInviteKey) return@collectLatest
                    _pending.value = invite
                }
        }
    }

    private fun IncomingCallInvite.identityKey(): Pair<Long, Long> = conversationId to serverSeq

    /** 用户点拒绝：标记已处置、关闭浮窗，并通知服务端取消主叫振铃。 */
    fun decline() {
        val invite = _pending.value
        _pending.value = null
        if (invite == null) return
        handledInviteKey = invite.identityKey()
        // TODO(structural call-decline): 当前 data/network/server 层尚无“拒接通知”端到端通道，
        // 拒接只在本端关闭浮窗 + 去重防重弹，主叫会持续振铃直至超时。终态需新增
        // MessageRepository.declineCall(conversationId, reason="rejected") → MessageApi
        // → 服务端 call_decline 事件 fanout 给主叫；本组无法跨改 data/network 模块，留待结构化整改。
        // 一旦 repository 暴露 declineCall，在此 viewModelScope.launch { repository.declineCall(...) } 调用。
    }

    /** 用户点接听：标记已处置并关闭浮窗（实际接通走外部 onAccept → LiveKit）。 */
    fun accept() {
        val invite = _pending.value
        handledInviteKey = invite?.identityKey()
        _pending.value = null
    }

    fun dismiss() {
        _pending.value = null
    }
}

/**
 * 全局来电浮窗：登录后挂在 AppRoot 顶层 (Box overlay)，无论用户在哪个 tab / 页面，
 * 收到 ws `call_invite` 都能看到接听 / 拒绝按钮。
 *
 * MVP：点接听暂时只 dismiss + 触发外部 `onAccept` 回调；接通 LiveKit 走 conv 内的
 * VideoCallInviteCard.onAcceptCall 路径，后续可以把这俩合一。
 */
@Composable
fun IncomingCallOverlay(
    onAccept: (IncomingCallInvite) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: IncomingCallOverlayViewModel = hiltViewModel(),
) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val invite = pending ?: return
    Box(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Shell 层已下发 LocalHazeState → 卡片走真模糊玻璃; 文案换语义色适配双主题
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .glassChrome()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Gomob.colors.accent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Phone, contentDescription = null, tint = Color.Black, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.weight(1f)) {
                val tag = if (invite.conversationKind == "group") "群通话来电" else "视频来电"
                Text(tag, color = Gomob.colors.fg1, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                val mainLabel = if (invite.conversationKind == "group") {
                    invite.conversationTitle?.takeIf { it.isNotBlank() } ?: invite.title
                } else {
                    invite.title
                }
                Text(mainLabel, color = Gomob.colors.fg0, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
            // 拒绝
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Gomob.colors.danger)
                    .clickable { viewModel.decline() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.CallEnd, contentDescription = "拒绝", tint = Color.White, modifier = Modifier.size(18.dp))
            }
            // 接听
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Gomob.colors.ok)
                    .clickable {
                        onAccept(invite)
                        viewModel.accept()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Phone, contentDescription = "接听", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

