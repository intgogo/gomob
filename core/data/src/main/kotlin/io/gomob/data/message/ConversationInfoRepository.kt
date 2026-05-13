package io.gomob.data.message

import io.gomob.data.auth.TokenStore
import io.gomob.database.message.ConversationMemberStateDao
import io.gomob.database.message.ConversationMemberStateEntity
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private const val LOCAL_SETTINGS_USER_ID = 0L

data class ConversationInfoSettings(
    val muted: Boolean = false,
    val folded: Boolean = false,
    val displayName: String = "",
    val remark: String = "",
    val announcement: String = "",
    val addedMembers: List<ConversationInfoStoredMember> = emptyList(),
    val removedMemberIds: Set<Long> = emptySet(),
)

data class ConversationInfoStoredMember(
    val userId: Long,
    val name: String,
)

@Singleton
class ConversationInfoRepository @Inject constructor(
    private val stateDao: ConversationMemberStateDao,
    private val tokenStore: TokenStore,
    private val json: Json,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeSettings(conversationId: Long): Flow<ConversationInfoSettings> =
        tokenStore.currentUserIdFlow.flatMapLatest { userId ->
            stateDao.observeState(conversationId, userId ?: LOCAL_SETTINGS_USER_ID)
                .map { it.toSettings(json) }
        }

    suspend fun setMuted(conversationId: Long, muted: Boolean) {
        updateState(conversationId) { copy(muted = muted) }
    }

    suspend fun setFolded(conversationId: Long, folded: Boolean) {
        updateState(conversationId) { copy(folded = folded) }
    }

    suspend fun setDisplayName(conversationId: Long, name: String) {
        updateState(conversationId) { copy(displayName = name.trim()) }
    }

    suspend fun setRemark(conversationId: Long, remark: String) {
        updateState(conversationId) { copy(remark = remark.trim()) }
    }

    suspend fun setAnnouncement(conversationId: Long, announcement: String) {
        updateState(conversationId) { copy(announcement = announcement.trim()) }
    }

    suspend fun addMember(conversationId: Long, member: ConversationInfoStoredMember) {
        updateState(conversationId) {
            val settings = toSettings(json)
            val nextMembers = (settings.addedMembers + member)
                .distinctBy { it.userId }
                .sortedBy { it.userId }
            copy(
                addedMembersJson = json.encodeToString(
                    ListSerializer(StoredMemberDto.serializer()),
                    nextMembers.map { StoredMemberDto(it.userId, it.name) },
                ),
                removedMemberIdsJson = json.encodeToString(
                    ListSerializer(Long.serializer()),
                    settings.removedMemberIds.filterNot { it == member.userId }.sorted(),
                ),
            )
        }
    }

    suspend fun removeMember(conversationId: Long, member: ConversationInfoStoredMember) {
        updateState(conversationId) {
            val settings = toSettings(json)
            val memberFromAddedList = settings.addedMembers.any { it.userId == member.userId }
            val nextMembers = settings.addedMembers.filterNot { it.userId == member.userId }
            val nextRemovedIds = if (memberFromAddedList) {
                settings.removedMemberIds
            } else {
                settings.removedMemberIds + member.userId
            }
            copy(
                addedMembersJson = json.encodeToString(
                    ListSerializer(StoredMemberDto.serializer()),
                    nextMembers.map { StoredMemberDto(it.userId, it.name) },
                ),
                removedMemberIdsJson = json.encodeToString(
                    ListSerializer(Long.serializer()),
                    nextRemovedIds.sorted(),
                ),
            )
        }
    }

    private suspend fun updateState(
        conversationId: Long,
        transform: ConversationMemberStateEntity.() -> ConversationMemberStateEntity,
    ) {
        val userId = tokenStore.currentUserId() ?: LOCAL_SETTINGS_USER_ID
        val current = stateDao.findState(conversationId, userId) ?: ConversationMemberStateEntity(
            conversationId = conversationId,
            userId = userId,
            lastReadSeq = 0,
            muted = false,
            pinned = false,
            updatedAt = Instant.now().toString(),
        )
        stateDao.upsertState(
            current
                .transform()
                .copy(updatedAt = Instant.now().toString()),
        )
    }
}

private fun ConversationMemberStateEntity?.toSettings(json: Json): ConversationInfoSettings {
    val state = this ?: return ConversationInfoSettings()
    return ConversationInfoSettings(
        muted = state.muted,
        folded = state.folded,
        displayName = state.displayName,
        remark = state.remark,
        announcement = state.announcement,
        addedMembers = runCatching {
            json.decodeFromString(ListSerializer(StoredMemberDto.serializer()), state.addedMembersJson)
                .map { ConversationInfoStoredMember(it.userId, it.name) }
        }.getOrDefault(emptyList()),
        removedMemberIds = runCatching {
            json.decodeFromString(ListSerializer(Long.serializer()), state.removedMemberIdsJson).toSet()
        }.getOrDefault(emptySet()),
    )
}

@Serializable
private data class StoredMemberDto(
    val userId: Long,
    val name: String,
)
