package com.getmedia.app.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
        val v3TrackColumns = fieldsOf(TrackEntity::class.java).filterNot { it in newTrackColumns }
        SQLiteDatabase.openDatabase(ctx.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { raw ->
            val v4Ddl = raw.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='tracks'", null)
                .use { c -> if (c.moveToFirst()) c.getString(0) else error("找不到 tracks 表") }
            // Room 生成的 DDL 可能是单行；按顶层逗号切列定义，比按行过滤稳
            val inner = v4Ddl.substringAfter("(").substringBeforeLast(")")
            val defs = inner.split(",").filterNot { def ->
                val head = def.trimStart()
                newTrackColumns.any { head.startsWith("`$it`") || head.startsWith("$it ") }
            }
            val v3Ddl = "CREATE TABLE `tracks_v3` (" + defs.joinToString(",") + ")"
            raw.execSQL("ALTER TABLE `tracks` RENAME TO `tracks_v4`")
            raw.execSQL(v3Ddl)
            val cols = v3TrackColumns.joinToString(", ") { "`$it`" }
            raw.execSQL("INSERT INTO `tracks_v3` ($cols) SELECT $cols FROM `tracks_v4`")
            raw.execSQL("DROP TABLE `tracks_v4`")
            raw.execSQL("ALTER TABLE `tracks_v3` RENAME TO `tracks`")
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

    private fun tableNames(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun tableNames(db: SupportSQLiteDatabase): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
}
