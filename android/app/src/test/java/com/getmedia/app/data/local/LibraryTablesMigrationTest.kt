package com.getmedia.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 守住 v2 → v3 → v4 的曲库辅助结构（P0-2）。
 *
 * 三条硬要求：
 * 1. v3 只新建表；v4 只 `ALTER TABLE ADD COLUMN` 与新建 `remote_marks`，都不重建、不搬数据；
 * 2. 表名/列名中性——不含 sync / server / cloud 一类字样，free 的解包扫描才干净；
 * 3. 迁移 SQL 的列名与实体属性逐字一致，否则真机上 Room 的结构校验会直接崩。
 *
 * 最后一条用 Robolectric 真跑一遍：建一个带旧行的库、退回 v2/v3 再让 Room 迁移，
 * 升级后逐字段比对。
 */
@RunWith(AndroidJUnit4::class)
class LibraryTablesMigrationTest {

    private val sql = AppDatabase.LIBRARY_TABLES_2_3_SQL
    private val trackV4 = AppDatabase.TRACK_MIGRATION_3_4_SQL
    private val removalV4 = AppDatabase.REMOVAL_MIGRATION_3_4_SQL

    @Test fun createsExactlyTwoTables() {
        assertEquals(2, sql.size)
        sql.forEach {
            assertTrue("只允许建表：$it", it.startsWith("CREATE TABLE IF NOT EXISTS"))
        }
        assertTrue(sql[0].contains("`track_removals`"))
        assertTrue(sql[1].contains("`library_cursor`"))
    }

    @Test fun namesStayNeutral() {
        val banned = listOf("sync", "server", "cloud")
        val names = sql + listOf(
            TrackRemovalEntity::class.java.simpleName,
            LibraryCursorEntity::class.java.simpleName,
            LibraryAuxDao::class.java.simpleName,
            "track_removals", "library_cursor",
        )
        names.forEach { text ->
            banned.forEach { word ->
                assertTrue("名字里不能出现 $word：$text", !text.lowercase().contains(word))
            }
        }
    }

    @Test fun columnsMatchEntities() {
        val removalColumns = fieldsOf(TrackRemovalEntity::class.java)
        val cursorColumns = fieldsOf(LibraryCursorEntity::class.java)
        val markColumns = fieldsOf(RemoteMarkEntity::class.java)
        assertEquals(
            listOf("platform", "videoKey", "account", "remoteId", "requestedAt",
                "expectedSeq", "generation", "state", "attempts", "lastError"),
            removalColumns,
        )
        assertEquals(listOf("account", "lastPos", "lastAt", "note"), cursorColumns)
        assertEquals(listOf("account", "remoteId", "platform", "videoKey", "title", "seq", "seenAt"), markColumns)
        removalColumns.take(5).forEach { assertTrue("迁移缺列：$it", sql[0].contains("`$it`")) }
        removalColumns.drop(5).forEach { assertTrue("迁移缺列：$it", removalV4.any { s -> s.contains("`$it`") }) }
        cursorColumns.forEach { assertTrue("迁移缺列：$it", sql[1].contains("`$it`")) }
        markColumns.forEach { assertTrue("迁移缺列：$it", AppDatabase.REMOTE_MARKS_3_4_SQL.contains("`$it`")) }
        // tracks 的新列必须逐条出现在 v4 迁移里；这里只核对新增的 5 个，旧列由 1→2 负责
        listOf("serverStorage", "confirmedTitle", "pendingTitle", "pendingTitleBaseSeq", "pendingTitleGen")
            .forEach { assertTrue("tracks 迁移缺列：$it", trackV4.any { s -> s.contains("`$it`") }) }
    }

    /** 只要真实列名：Compose 编译器会给类插 `$stable` 静态字段，不算列。 */
    private fun fieldsOf(cls: Class<*>) = cls.declaredFields
        .filterNot { it.isSynthetic || java.lang.reflect.Modifier.isStatic(it.modifiers) || it.name.startsWith('$') }
        .map { it.name }

    @Test fun migrationAddsTablesAndKeepsExistingRows() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("lib-migration-test.db")
        file.parentFile?.mkdirs()
        file.delete()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("CREATE TABLE tracks (id INTEGER PRIMARY KEY, title TEXT NOT NULL)")
            db.execSQL("INSERT INTO tracks(id, title) VALUES(1, '迁移前的一行')")
            db.version = 2

            sql.forEach(db::execSQL)

            val tables = tableNames(db)
            assertTrue("缺 track_removals：$tables", tables.contains("track_removals"))
            assertTrue("缺 library_cursor：$tables", tables.contains("library_cursor"))
            db.rawQuery("SELECT title FROM tracks WHERE id=1", null).use { c ->
                assertTrue("迁移不能动已有行", c.moveToFirst())
                assertEquals("迁移前的一行", c.getString(0))
            }
        } finally {
            db.close()
            file.delete()
        }
    }

    @Test fun roomOpensTheNewVersionAndDaoWorks() {        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(4, sqlite.version)
            val tables = tableNames(sqlite)
            assertTrue("缺 track_removals：$tables", tables.contains("track_removals"))
            assertTrue("缺 library_cursor：$tables", tables.contains("library_cursor"))
            assertTrue("缺 remote_marks：$tables", tables.contains("remote_marks"))

            // 两列非空、主键组合正确：DAO 插一条再读回来就能证明结构对得上
            runBlocking {
                db.libraryAuxDao().addRemoval(
                    TrackRemovalEntity(platform = "bilibili", videoKey = "BV1xx-p2", account = "boss", requestedAt = 7),
                )
                db.libraryAuxDao().putCursor(LibraryCursorEntity(account = "boss", lastPos = 42, lastAt = 9))
                assertEquals(1, db.libraryAuxDao().removalCount("boss"))
                assertEquals(42L, db.libraryAuxDao().cursor("boss")?.lastPos)
                assertTrue(db.libraryAuxDao().removals("other").isEmpty())
            }
        } finally {
            db.close()
        }
    }

    /**
     * **带真实数据的库**：这条是装机前必须过的那道关——手机上的数据不能因为升级丢。
     *
     * 做法（离线无法凭空造一个"当年 v2 建的"库：`exportSchema=false`，v2 实体也早没了）：
     * 1. 用 Room 在磁盘上建库（当前 v3），通过 DAO 写进有代表性的行：多 P、收藏、改名、
     *    非默认类别、服务器身份、播放列表归属、以及两张新表里的一行；
     * 2. 把这个库**退回 v2**：删掉 v3 新增的两张表，`user_version` 设回 2；
     * 3. 再让 Room 自己打开——它会走 [AppDatabase.MIGRATION_2_3]、跑完结构校验
     *    （identity hash 对不上会直接抛），然后逐字段比对升级前后。
     *
     * 换句话说：迁移是被 Room 的真实打开路径验证的，不是"我调一下 SQL 看它没报错"。
     */
    @Test fun upgradingARealDatabaseKeepsEveryRow() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "lib-upgrade-real.db"
        ctx.deleteDatabase(name)

        val t1 = TrackEntity(
            id = -101, platform = "bilibili", videoId = "BVreal-p2", title = "真实数据 第一行",
            uploader = "某UP", durationSec = 245.5, coverUrl = "https://c/1.jpg",
            audioBytes = 4_096_000, audioFormat = "m4a", sourceUrl = "https://b/BVreal?p=2",
            audioSha256 = "deadbeef", localPath = "/files/-101.m4a", locallyEdited = true,
            favorite = true, localCategoryId = 2, updatedAtMs = 1_700_000_000_123,
            localTitleOverride = "我改的名字", addedAtMs = 1_699_000_000_000, sortIndex = 5,
            mediaType = MediaKind.AUDIO.name, serverId = 42, serverSeq = 17, serverPage = 2,
            serverUpdatedAtMs = 1_699_500_000_000, syncState = SyncState.SYNCED.name, accountKey = "boss",
        )
        val t2 = TrackEntity(
            id = -102, platform = "douyin", videoId = "7300000000000000000", title = "真实数据 第二行",
            uploader = null, durationSec = 61.0, audioBytes = 900_000, audioFormat = "m4a",
            sourceUrl = "https://v.douyin.com/x/", localPath = null,
            syncState = SyncState.RESTORED.name,
        )

        var db = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val beforeTracks: List<TrackEntity>
        val beforeItems: List<PlaylistItemEntity>
        try {
            db.trackDao().upsertAll(listOf(t1, t2))
            db.categoryDao().insert(CategoryEntity(id = 2, name = "通勤", kind = "LEAF", createdAt = 1))
            val pid = db.playlistDao().insert(PlaylistEntity(name = "通勤歌单", createdAt = 2))
            db.playlistDao().add(listOf(PlaylistItemEntity(playlistId = pid, trackId = t1.id, orderIndex = 3)))
            db.libraryAuxDao().putCursor(LibraryCursorEntity(account = "boss", lastPos = 99, lastAt = 3))
            beforeTracks = db.trackDao().allNow()
            beforeItems = db.playlistDao().itemsAll()
        } finally {
            db.close()
        }

        // 退回 v3：重建一份没有 v4 新列的 tracks，并只留 v3 的两张辅助表。
        // 不用 ALTER TABLE DROP COLUMN：Android 的 SQLite 在目标版本上不支持。
        val newTrackColumns = listOf("serverStorage", "confirmedTitle", "pendingTitle",
            "pendingTitleBaseSeq", "pendingTitleGen")
        SQLiteDatabase.openDatabase(ctx.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            downgradeTracks(raw, newTrackColumns, "tracks_v3")
            raw.execSQL("DROP TABLE IF EXISTS track_removals")
            raw.execSQL("DROP TABLE IF EXISTS library_cursor")
            raw.execSQL("DROP TABLE IF EXISTS remote_marks")
            AppDatabase.LIBRARY_TABLES_2_3_SQL.forEach(raw::execSQL)
            raw.version = 3
        }

        db = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries().build()
        try {
            assertEquals("升级后必须是 v4", 4, db.openHelper.writableDatabase.version)
            assertEquals("tracks 一行都不能少、一个字段都不能变", beforeTracks, db.trackDao().allNow())
            assertEquals("播放列表归属与顺序不变", beforeItems, db.playlistDao().itemsAll())
            assertEquals("类别不变", listOf("通勤"), db.categoryDao().allNow().map { it.name })
            // 新表要能用（旧库里本来没有它们）
            db.libraryAuxDao().putCursor(LibraryCursorEntity(account = "boss", lastPos = 1, lastAt = 4))
            assertEquals(1L, db.libraryAuxDao().cursor("boss")?.lastPos)
        } finally {
            db.close()
            ctx.deleteDatabase(name)
        }
    }

    /**
     * 手机 1 的真实升级路径：0.1.1 的 Room v2 → 当前 v4，中间必须连续走 v2→v3→v4。
     *
     * 造一个 v2 库（v4 表结构去掉 v4 新列，删掉 v3/v4 才有的三张表），写入带代表性的真实数据，
     * 然后只交给 Room 的打开路径升级。断言：
     * 1. 版本到 4；
     * 2. `tracks` / `categories` / `playlists` / `playlist_items` 逐字段不变；
     * 3. `track_removals` / `library_cursor` / `remote_marks` 建出来且为空；
     * 4. 三张辅助表在升级后都能按 v4 列写入、读回。
     *
     * 这条用例不碰手机、不依赖旧 APK：v2 的起始结构由当前 DDL 反推，数据用 v2 就有的字段。
     */
    @Test fun upgradingFromV2WithRealDataRunsFullChainToV4() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "lib-upgrade-from-v2-real.db"
        ctx.deleteDatabase(name)

        val t1 = TrackEntity(
            id = -201, platform = "bilibili", videoId = "BVv2-p3", title = "v2 第一行",
            uploader = "某UP", durationSec = 245.5, coverUrl = "https://c/v2.jpg",
            audioBytes = 4_096_000, audioFormat = "m4a", sourceUrl = "https://b/BVv2?p=3",
            audioSha256 = "deadbeef", localPath = "/files/-201.m4a", locallyEdited = true,
            sourceTrackId = -200, favorite = true, localCategoryId = 2,
            updatedAtMs = 1_700_000_000_123, localTitleOverride = "v2 改过的名字",
            addedAtMs = 1_699_000_000_000, sortIndex = 5, mediaType = MediaKind.AUDIO.name,
            serverId = 42, serverSeq = 7, serverPage = 3,
            serverUpdatedAtMs = 1_699_500_000_000, syncState = SyncState.SYNCED.name, accountKey = "boss",
        )
        val t2 = TrackEntity(
            id = -202, platform = "douyin", videoId = "7300000000000000000", title = "v2 第二行",
            uploader = null, durationSec = 61.0, audioBytes = 900_000, audioFormat = "m4a",
            sourceUrl = "https://v.douyin.com/x/", localPath = null,
            favorite = false, localCategoryId = 1, addedAtMs = 1_699_100_000_000,
            sortIndex = 0, mediaType = MediaKind.VIDEO.name,
            syncState = SyncState.RESTORED.name,
        )

        var db = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val beforeTracks: List<TrackEntity>
        val beforeCategories: List<CategoryEntity>
        val beforePlaylists: List<PlaylistEntity>
        val beforeItems: List<PlaylistItemEntity>
        try {
            db.trackDao().upsertAll(listOf(t1, t2))
            db.categoryDao().insert(CategoryEntity(id = 2, name = "通勤", kind = "LEAF", createdAt = 2))
            db.categoryDao().insert(CategoryEntity(id = 3, name = "子类", parentId = 2, kind = "LEAF", createdAt = 3))
            val pid = db.playlistDao().insert(PlaylistEntity(name = "v2 歌单", createdAt = 4))
            db.playlistDao().add(listOf(PlaylistItemEntity(playlistId = pid, trackId = t1.id, orderIndex = 3)))
            // 先写进 v4 的辅助表，稍后随 v2 降级一起丢弃，证明它们不会被误当成 v2 数据
            db.libraryAuxDao().putCursor(LibraryCursorEntity(account = "boss", lastPos = 11, lastAt = 12, note = "n"))
            db.libraryAuxDao().addRemoval(
                TrackRemovalEntity(platform = "bilibili", videoKey = "BVv2-p3", account = "boss",
                    remoteId = 42, requestedAt = 5, expectedSeq = 7, generation = 6,
                    state = "WAITING", attempts = 2, lastError = "x"),
            )
            db.libraryAuxDao().addMark(
                RemoteMarkEntity(account = "boss", remoteId = 42, platform = "bilibili",
                    videoKey = "BVv2-p3", title = "t", seq = 8, seenAt = 9),
            )
            beforeTracks = db.trackDao().allNow()
            beforeCategories = db.categoryDao().allNow()
            beforePlaylists = db.playlistDao().allNow()
            beforeItems = db.playlistDao().itemsAll()
        } finally {
            db.close()
        }

        // 退回 v2：去掉 v4 新列，删掉 v3/v4 才有的三张表
        SQLiteDatabase.openDatabase(ctx.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            downgradeTracks(raw, listOf("serverStorage", "confirmedTitle", "pendingTitle",
                "pendingTitleBaseSeq", "pendingTitleGen"), "tracks_v2")
            raw.execSQL("DROP TABLE IF EXISTS track_removals")
            raw.execSQL("DROP TABLE IF EXISTS library_cursor")
            raw.execSQL("DROP TABLE IF EXISTS remote_marks")
            raw.version = 2
        }

        db = Room.databaseBuilder(ctx, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, AppDatabase.MIGRATION_2_3, AppDatabase.MIGRATION_3_4)
            .allowMainThreadQueries().build()
        try {
            assertEquals("必须连续升到 v4", 4, db.openHelper.writableDatabase.version)
            val tables = tableNames(db.openHelper.writableDatabase)
            listOf("tracks", "categories", "playlists", "playlist_items",
                "track_removals", "library_cursor", "remote_marks").forEach {
                assertTrue("缺表 $it：$tables", tables.contains(it))
            }
            assertEquals("tracks 逐字段不变", beforeTracks, db.trackDao().allNow())
            assertEquals("categories 不变", beforeCategories, db.categoryDao().allNow())
            assertEquals("playlists 不变", beforePlaylists, db.playlistDao().allNow())
            assertEquals("playlist_items 不变", beforeItems, db.playlistDao().itemsAll())
            assertEquals("v2 没有删除队列，升级后应为空", 0, db.libraryAuxDao().removalCount("boss"))
            assertNull("v2 没有水位，升级后应为空", db.libraryAuxDao().cursor("boss"))
            assertEquals("v2 没有墓碑表，升级后应为空", 0, db.libraryAuxDao().markCount("boss"))

            // v4 三张辅助表都要能按新列写入、读回
            db.libraryAuxDao().addRemoval(
                TrackRemovalEntity(platform = "bilibili", videoKey = "BVv2-p3", account = "boss",
                    remoteId = 42, requestedAt = 20, expectedSeq = 21, generation = 22,
                    state = "QUEUED", attempts = 0, lastError = null),
            )
            val removal = db.libraryAuxDao().removals("boss").single()
            assertEquals(21L, removal.expectedSeq)
            assertEquals(22L, removal.generation)
            assertEquals("QUEUED", removal.state)

            db.libraryAuxDao().putCursor(LibraryCursorEntity(account = "boss", lastPos = 30, lastAt = 31, note = "ok"))
            assertEquals(30L, db.libraryAuxDao().cursor("boss")?.lastPos)
            assertEquals("ok", db.libraryAuxDao().cursor("boss")?.note)

            db.libraryAuxDao().addMark(
                RemoteMarkEntity(account = "boss", remoteId = 42, platform = "bilibili",
                    videoKey = "BVv2-p3", title = "墓碑标题", seq = 40, seenAt = 41),
            )
            val mark = db.libraryAuxDao().marks("boss").single()
            assertEquals(42L, mark.remoteId)
            assertEquals(40L, mark.seq)
            assertEquals("墓碑标题", mark.title)
        } finally {
            db.close()
            ctx.deleteDatabase(name)
        }
    }

    /**
     * 把当前 Room 建的 v4 `tracks` 重建为缺少 [removedColumns] 的旧结构，用于模拟旧版本库。
     *
     * 不依赖 `ALTER TABLE DROP COLUMN`（Android 目标版本的 SQLite 不支持）：
     * 从真实 DDL 里按顶层逗号切掉新列，建临时表、复制旧列、改名。
     */
    private fun downgradeTracks(raw: SQLiteDatabase, removedColumns: List<String>, tempName: String) {
        val v4Ddl = raw.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='tracks'", null)
            .use { c -> if (c.moveToFirst()) c.getString(0) else error("找不到 tracks 表") }
        val inner = v4Ddl.substringAfter("(").substringBeforeLast(")")
        val defs = inner.split(",").filterNot { def ->
            val head = def.trimStart()
            removedColumns.any { head.startsWith("`$it`") || head.startsWith("$it ") }
        }
        val oldDdl = "CREATE TABLE `$tempName` (" + defs.joinToString(",") + ")"
        val oldColumns = fieldsOf(TrackEntity::class.java).filterNot { it in removedColumns }
        raw.execSQL("ALTER TABLE `tracks` RENAME TO `tracks_v4`")
        raw.execSQL(oldDdl)
        val cols = oldColumns.joinToString(", ") { "`$it`" }
        raw.execSQL("INSERT INTO `$tempName` ($cols) SELECT $cols FROM `tracks_v4`")
        raw.execSQL("DROP TABLE `tracks_v4`")
        raw.execSQL("ALTER TABLE `$tempName` RENAME TO `tracks`")
    }

    private fun tableNames(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun tableNames(db: SupportSQLiteDatabase): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
}
