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
 * 守住 v2 → v3 的两张辅助表（P0-2 骨架）。
 *
 * 三条硬要求：
 * 1. 只新建表，不动 `tracks`（同步永远不改别人的表结构）；
 * 2. 表名/列名中性——不含 sync / server / cloud 一类字样，free 的解包扫描才干净；
 * 3. 迁移 SQL 的列名与实体属性逐字一致，否则真机上 Room 的结构校验会直接崩。
 *
 * 后两条用 Robolectric 真跑一遍：建一个带旧行的 v2 库、执行迁移、再用 Room 打开 v3 新建库。
 */
@RunWith(AndroidJUnit4::class)
class LibraryTablesMigrationTest {

    private val sql = AppDatabase.LIBRARY_TABLES_2_3_SQL

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
        assertEquals(listOf("platform", "videoKey", "account", "remoteId", "requestedAt"), removalColumns)
        assertEquals(listOf("account", "lastPos", "lastAt", "note"), cursorColumns)
        removalColumns.forEach { assertTrue("迁移缺列：$it", sql[0].contains("`$it`")) }
        cursorColumns.forEach { assertTrue("迁移缺列：$it", sql[1].contains("`$it`")) }
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

    @Test fun roomOpensTheNewVersionAndDaoWorks() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val sqlite = db.openHelper.writableDatabase
            assertEquals(3, sqlite.version)
            val tables = tableNames(sqlite)
            assertTrue("缺 track_removals：$tables", tables.contains("track_removals"))
            assertTrue("缺 library_cursor：$tables", tables.contains("library_cursor"))

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

    private fun tableNames(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun tableNames(db: SupportSQLiteDatabase): Set<String> =
        db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
}
