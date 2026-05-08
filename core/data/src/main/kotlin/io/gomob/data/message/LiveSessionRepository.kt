package io.gomob.data.message

import io.gomob.database.message.LiveSessionDao
import io.gomob.model.message.LiveSessionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LiveSessionRepository @Inject constructor(
    private val liveSessionDao: LiveSessionDao,
) {
    fun observeLiveSessions(): Flow<List<LiveSessionSummary>> =
        liveSessionDao.observeByStatus("live").map { items -> items.map { it.toDomain() } }
}
