package io.gomob.database

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.gomob.database.message.ConversationDao
import io.gomob.database.message.ConversationMemberStateDao
import io.gomob.database.message.LiveSessionDao
import io.gomob.database.message.MessageDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): GomobDatabase =
        Room.databaseBuilder(context, GomobDatabase::class.java, "gomob.db")
            .addMigrations(MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
            .build()

    @Provides
    fun provideConversationDao(db: GomobDatabase): ConversationDao = db.conversationDao()

    @Provides
    fun provideConversationMemberStateDao(db: GomobDatabase): ConversationMemberStateDao =
        db.conversationMemberStateDao()

    @Provides
    fun provideMessageDao(db: GomobDatabase): MessageDao = db.messageDao()

    @Provides
    fun provideLiveSessionDao(db: GomobDatabase): LiveSessionDao = db.liveSessionDao()
}
