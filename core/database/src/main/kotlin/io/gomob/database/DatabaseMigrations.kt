package io.gomob.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// 注: 已删除 MIGRATION_1_2 死链(M11.8)。v1 schema 从未随正式包发布，线上不存在 v1 DB，
//   该 migration 永不触发；且仅 ADD preview 一列，相对真实 v2 schema 严重不完整，保留反而是
//   一旦真有 v1 DB 升级会建表缺失崩溃的隐患。当前迁移链从 2→3 起。

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
