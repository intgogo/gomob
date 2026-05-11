package io.gomob.feature.message

import com.google.common.truth.Truth.assertThat
import io.gomob.model.message.ConversationSummary
import org.junit.Test

class MessageViewModelsTest {
    @Test
    fun visibleMessageConversationsFiltersOnlineHelpRoom() {
        val p2p = conversationSummary(id = 1, subjectKind = null, title = null)
        val help = conversationSummary(id = 2, subjectKind = "online_help", title = "在线求助")
        val review = conversationSummary(id = 3, subjectKind = "review", title = "复核会话")
        val legacyHelp = conversationSummary(id = 4, subjectKind = null, title = "在线求助")

        val visible = visibleMessageConversations(listOf(p2p, help, review, legacyHelp))

        assertThat(visible.map { it.id }).containsExactly(1L, 3L).inOrder()
    }

    @Test
    fun conversationStartsBlankInsteadOfLoading() {
        val state = ConversationUiState(conversationId = 9)

        assertThat(state.loading).isFalse()
        assertThat(state.empty).isTrue()
    }
}

private fun conversationSummary(
    id: Long,
    subjectKind: String?,
    title: String?,
): ConversationSummary = ConversationSummary(
    id = id,
    kind = "group",
    title = title,
    peer = null,
    subjectKind = subjectKind,
    subjectId = null,
    lastMessage = null,
    lastReadSeq = 0,
    unreadCount = 0,
    createdAt = "2026-05-08T11:00:00Z",
    updatedAt = "2026-05-08T12:00:00Z",
)
