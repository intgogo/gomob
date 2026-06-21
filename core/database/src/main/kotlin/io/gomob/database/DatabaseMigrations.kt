package io.gomob.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// TODO(db R6/correctness 终态): 此 1→2 migration 是死代码——v1 schema 从未随正式包发布，
//   线上不存在 v1 DB，故永不触发；且仅 ADD preview 一列，相对真实 v2 schema(含 conversations /
//   conversation_member_states / live_sessions 等表)严重不完整，一旦真有 v1 DB 升级会建表缺失崩溃。
//   正确做法是删除本 migration 并从 DatabaseModule.addMigrations 摘掉 MIGRATION_1_2(起始版本提到 2),
//   但 DatabaseModule.kt 不在本轮可改文件范围(并行 agent 持有),删除会断编译。
//   故本轮先标注不改结构;终态由开 exportSchema 后用 schemas/2.json 校验或直接删除该死链。
//   终态见 docs/architecture(database 专题) + MigrationTestHelper 接入。
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
