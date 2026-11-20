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
    entities = [
        TrackEntity::class, CategoryEntity::class, PlaylistEntity::class, PlaylistItemEntity::class,
        TrackRemovalEntity::class, LibraryCursorEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun categoryDao(): CategoryDao
    abstract fun playlistDao(): PlaylistDao

    /** 两张辅助表（待重放的删除、每账号的处理位置）。P0-2 骨架建表，阶段 2 起才有人读写。 */
    abstract fun libraryAuxDao(): LibraryAuxDao

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

        /**
         * v2 → v3（P0-2 骨架）：两张辅助表，只新建、不动 `tracks` 与其它任何表。
         *
         * 表名与列名可以中性：这张表跟曲库同库，free 也带着这个库文件，名字里没有
         * sync / server / cloud，解包扫描才干净（`LibraryTablesMigrationTest` 会检查）。
         * 列名与实体属性逐字对应，否则 Room 打开库时的结构校验会报"迁移后 schema 不匹配"。
         */
        val LIBRARY_TABLES_2_3_SQL: List<String> = listOf(
            "CREATE TABLE IF NOT EXISTS `track_removals` (`platform` TEXT NOT NULL, " +
                "`videoKey` TEXT NOT NULL, `account` TEXT NOT NULL, `remoteId` INTEGER, " +
                "`requestedAt` INTEGER NOT NULL, PRIMARY KEY(`platform`, `videoKey`, `account`))",
            "CREATE TABLE IF NOT EXISTS `library_cursor` (`account` TEXT NOT NULL, " +
                "`lastPos` INTEGER NOT NULL, `lastAt` INTEGER NOT NULL, `note` TEXT, " +
                "PRIMARY KEY(`account`))",
        )

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                LIBRARY_TABLES_2_3_SQL.forEach(db::execSQL)
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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
