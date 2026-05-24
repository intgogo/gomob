package io.gomob.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN preview TEXT")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversations ADD COLUMN clearedBeforeSeq INTEGER NOT NULL DEFAULT 0")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_conversations_pinned_updatedAt " +
                "ON conversations(pinned, updatedAt)",
        )
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE conversation_member_states ADD COLUMN folded INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE conversation_member_states ADD COLUMN displayName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE conversation_member_states ADD COLUMN remark TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE conversation_member_states ADD COLUMN announcement TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE conversation_member_states ADD COLUMN addedMembersJson TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE conversation_member_states ADD COLUMN removedMemberIdsJson TEXT NOT NULL DEFAULT '[]'")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN recalledAt TEXT")
    }
}
