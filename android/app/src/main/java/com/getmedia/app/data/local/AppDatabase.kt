package com.getmedia.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * GetMedia 本地库。schema 从 1 起，不迁移其他应用的旧数据。
 * 之后的结构变更必须新增 Migration，不得使用破坏性迁移。
 *
 * v1 → v2（P0-1）：tracks 加 6 列服务器来源字段，全部可空或带默认值，
 * 只 `ALTER TABLE ADD COLUMN`，不重建表、不动已有数据。
 */
@Database(
    entities = [TrackEntity::class, CategoryEntity::class, PlaylistEntity::class, PlaylistItemEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun categoryDao(): CategoryDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        /**
         * v1 → v2 的 6 条 `ALTER`。写成列表是为了能被单测逐条核对：
         * 列名与 [TrackEntity] 的声明一一对应，默认值必须与实体注解逐字相同，
         * 否则 Room 打开库时的结构校验会报"迁移后 schema 不匹配"。
         */
        val TRACK_MIGRATION_1_2_SQL: List<String> = listOf(
            "ALTER TABLE tracks ADD COLUMN serverId INTEGER",
            "ALTER TABLE tracks ADD COLUMN serverSeq INTEGER",
            "ALTER TABLE tracks ADD COLUMN serverPage INTEGER NOT NULL DEFAULT 1",
            "ALTER TABLE tracks ADD COLUMN serverUpdatedAtMs INTEGER",
            "ALTER TABLE tracks ADD COLUMN syncState TEXT NOT NULL DEFAULT 'LOCAL_ONLY'",
            "ALTER TABLE tracks ADD COLUMN accountKey TEXT",
        )

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                TRACK_MIGRATION_1_2_SQL.forEach(db::execSQL)
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "getmedia.db",
            )
                .addMigrations(MIGRATION_1_2)
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        db.execSQL("INSERT OR IGNORE INTO categories (id,name,parentId,kind,createdAt,accessCount,lastAccessedAt) VALUES (1,'未分类',NULL,'LEAF',0,0,0)")
                    }
                })
                .build()
                .also { instance = it }
        }
    }
}
