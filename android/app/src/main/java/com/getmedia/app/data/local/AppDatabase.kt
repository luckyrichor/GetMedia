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
        TrackRemovalEntity::class, LibraryCursorEntity::class, RemoteMarkEntity::class,
        PlaylistRemovalEntity::class,
    ],
    version = 7,
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

        /**
         * v3 → v4（P0-2 阶段 5）：曲库补云端存储类型与标题草稿，删除队列补版本/世代/状态，
         * 另建墓碑待确认表。
         *
         * 全部是 `ALTER TABLE ADD COLUMN` 与一张新表，不重建、不搬数据：
         * 升级后旧行一个字段都不变，新列按 null / 默认值读。
         */
        val TRACK_MIGRATION_3_4_SQL: List<String> = listOf(
            "ALTER TABLE tracks ADD COLUMN `serverStorage` TEXT",
            "ALTER TABLE tracks ADD COLUMN `confirmedTitle` TEXT",
            "ALTER TABLE tracks ADD COLUMN `pendingTitle` TEXT",
            "ALTER TABLE tracks ADD COLUMN `pendingTitleBaseSeq` INTEGER",
            "ALTER TABLE tracks ADD COLUMN `pendingTitleGen` INTEGER",
        )

        val REMOVAL_MIGRATION_3_4_SQL: List<String> = listOf(
            "ALTER TABLE track_removals ADD COLUMN `expectedSeq` INTEGER",
            "ALTER TABLE track_removals ADD COLUMN `generation` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE track_removals ADD COLUMN `state` TEXT NOT NULL DEFAULT 'QUEUED'",
            "ALTER TABLE track_removals ADD COLUMN `attempts` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE track_removals ADD COLUMN `lastError` TEXT",
        )

        val REMOTE_MARKS_3_4_SQL: String =
            "CREATE TABLE IF NOT EXISTS `remote_marks` (`account` TEXT NOT NULL, " +
                "`remoteId` INTEGER NOT NULL, `platform` TEXT NOT NULL, `videoKey` TEXT NOT NULL, " +
                "`title` TEXT NOT NULL, `seq` INTEGER NOT NULL, `seenAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`account`, `remoteId`))"

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                TRACK_MIGRATION_3_4_SQL.forEach(db::execSQL)
                REMOVAL_MIGRATION_3_4_SQL.forEach(db::execSQL)
                db.execSQL(REMOTE_MARKS_3_4_SQL)
            }
        }

        /**
         * v4 → v5（整理信息跨设备同步）：曲库补"收藏/类别待推送"标记，列表补脏位，
         * 另建一张"待重放的列表删除"。
         *
         * 全部是 `ALTER TABLE ADD COLUMN` 加一张新表：升级后旧行一个字段都不变，
         * 已有的收藏、类别、列表内容原样保留——**本机整理结果不能因为一次升级被重置**。
         */
        val MIGRATION_4_5_SQL: List<String> = listOf(
            "ALTER TABLE tracks ADD COLUMN `metaDirty` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE playlists ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE playlists ADD COLUMN `syncedAt` INTEGER NOT NULL DEFAULT 0",
            // 升级前就有内容的列表要传一次：不置脏位它们永远不会进云端（本地列表本来 dirty=0）。
            // 第一轮会先拉云端——云端已有同名列表时以云端为准，只有云端没有才把本地这份传上去，
            // 所以两台设备先后升级不会互相覆盖。
            "UPDATE playlists SET dirty = 1",
        )

        val PLAYLIST_REMOVALS_4_5_SQL: String =
            "CREATE TABLE IF NOT EXISTS `playlist_removals` (`account` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `requestedAt` INTEGER NOT NULL, " +
                "PRIMARY KEY(`account`, `name`))"

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_4_5_SQL.forEach(db::execSQL)
                db.execSQL(PLAYLIST_REMOVALS_4_5_SQL)
            }
        }

        /**
         * v5 → v6：清掉早期拉取写进库里的字符串 `"null"`。
         *
         * 旧解析用裸 `optString`：服务器把"没有作者"存成 NULL，JSON 里是 `null`，
         * `org.json` 却给出字符串 `"null"`，于是界面上显示成名字叫 `null` 的作者。
         * 解析已经改掉，但**已经落库的行不会自己变**——同步是增量的，那些行不会再被拉一次，
         * 所以在这里一次性纠正。只改 `uploader` 一列，其它字段一概不动。
         *
         * （类别不是存路径的列：本机按 id 挂在 `localCategoryId` 上，路径是同步时算出来的，
         * 所以这里没有需要清理的类别列。）
         */
        val MIGRATION_5_6_SQL: List<String> = listOf(
            "UPDATE tracks SET uploader = NULL WHERE uploader = 'null'",
        )

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                MIGRATION_5_6_SQL.forEach(db::execSQL)
            }
        }

        /**
         * v6 → v7：本机导入的文件要备份到服务器归档，曲库补四个状态列。
         *
         * 全部是 `ALTER TABLE ADD COLUMN`，旧行按默认值读成 `NONE`（不涉及备份）。
         * 历史 `local` 行不会因此被批量上传：它们的 `videoId` 还是标题算出来的旧身份，
         * 用户已明确会自己清空重建，这里不猜、不迁移。
         */
        val TRACK_MIGRATION_6_7_SQL: List<String> = listOf(
            "ALTER TABLE tracks ADD COLUMN `archiveState` TEXT NOT NULL DEFAULT 'NONE'",
            "ALTER TABLE tracks ADD COLUMN `archiveTries` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE tracks ADD COLUMN `archiveNextAt` INTEGER NOT NULL DEFAULT 0",
            "ALTER TABLE tracks ADD COLUMN `archiveNote` TEXT",
        )

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                TRACK_MIGRATION_6_7_SQL.forEach(db::execSQL)
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
                    MIGRATION_6_7)
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
