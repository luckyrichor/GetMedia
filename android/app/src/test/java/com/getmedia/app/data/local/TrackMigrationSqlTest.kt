package com.getmedia.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 守住 v1 → v2 的迁移 SQL。
 *
 * 真机上的 Room 结构校验只有装机才会跑；这里先挡最容易犯的错：漏列、列名打错、
 * 给可空列写了 `NOT NULL` 却没有默认值（`ALTER TABLE` 会直接失败）。
 */
class TrackMigrationSqlTest {

    private val sql = AppDatabase.TRACK_MIGRATION_1_2_SQL

    @Test fun coversExactlyTheSixNewColumns() {
        assertEquals(6, sql.size)
        listOf("serverId", "serverSeq", "serverPage", "serverUpdatedAtMs", "syncState", "accountKey")
            .forEach { column ->
                assertEquals("缺列：$column", 1, sql.count { it.contains("COLUMN $column ") })
            }
    }

    @Test fun everyStatementOnlyAddsColumns() {
        sql.forEach {
            assertTrue("迁移只允许 ADD COLUMN：$it", it.startsWith("ALTER TABLE tracks ADD COLUMN "))
        }
    }

    @Test fun nonNullColumnsCarryDefaults() {
        val notNull = sql.filter { it.contains("NOT NULL") }
        assertEquals(2, notNull.size)
        notNull.forEach {
            assertTrue("NOT NULL 的列必须带 DEFAULT，否则已有行无法升级：$it", it.contains("DEFAULT "))
        }
        assertTrue(sql.any { it.endsWith("DEFAULT 1") })
        assertTrue(sql.any { it.endsWith("DEFAULT 'LOCAL_ONLY'") })
    }

    @Test fun entityHasTheSameColumns() {
        // 反射取实体属性名，确保"加了列没写迁移"或"写了迁移没加属性"都会红
        val names = TrackEntity::class.java.declaredFields.map { it.name }.toSet()
        listOf("serverId", "serverSeq", "serverPage", "serverUpdatedAtMs", "syncState", "accountKey")
            .forEach { assertNotNull("实体缺属性：$it", names.firstOrNull { n -> n == it }) }
        assertEquals(SyncState.LOCAL_ONLY.name, SyncState.of(null).name)
        assertEquals(SyncState.LOCAL_ONLY.name, SyncState.of("nonsense").name)
        assertEquals(SyncState.SYNCED.name, SyncState.of("SYNCED").name)
    }
}
