package io.gomob.database

import androidx.room.Database
import androidx.room.RoomDatabase
import io.gomob.database.message.ConversationDao
import io.gomob.database.message.ConversationEntity
import io.gomob.database.message.ConversationMemberStateDao
import io.gomob.database.message.ConversationMemberStateEntity
import io.gomob.database.message.LiveSessionDao
import io.gomob.database.message.LiveSessionEntity
import io.gomob.database.message.MessageDao
import io.gomob.database.message.MessageEntity

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ConversationMemberStateEntity::class,
        LiveSessionEntity::class,
    ],
    version = 4,
    exportSchema = false,
)
abstract class GomobDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun conversationMemberStateDao(): ConversationMemberStateDao
    abstract fun messageDao(): MessageDao
    abstract fun liveSessionDao(): LiveSessionDao
}
