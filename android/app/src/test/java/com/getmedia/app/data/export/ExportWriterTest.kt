package com.getmedia.app.data.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * 写入器的边界：临时名 → 改名；改名不被支持时按最终名重写；重写也失败就两个半成品都删掉。
 *
 * 用假目标复刻 provider 的三种脾气（不支持改名、写一半失败、写着写着没空间），
 * 真机上的 SAF 只在节点 B 的链路验证里跑一次。
 */
class ExportWriterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun track(id: Long = 1L, title: String = "歌", format: String = "m4a") = ExportTrack(
        id = id,
        title = title,
        audioFormat = format,
        audioBytes = 10,
        localPath = "unused",
    )

    private fun item(title: String = "歌", dirs: List<String> = emptyList(), format: String = "m4a") =
        ExportPlan.Item(track = track(title = title, format = format), dirs = dirs, fileName = "$title.$format")

    private fun source(bytes: Int = 10): File =
        folder.newFile("src-${System.nanoTime()}.m4a").apply { writeBytes(ByteArray(bytes) { it.toByte() }) }

    @Test
    fun writesTempThenRenames() {
        val target = FakeTarget()
        val outcome = ExportWriter(target) { "~getmedia-deadbeef.part" }.write(item(), source(10))

        assertTrue(outcome.ok)
        assertEquals("歌.m4a", outcome.fileName)
        assertEquals(10L, outcome.bytes)
        assertFalse(outcome.rewritten)
        assertEquals(setOf("歌.m4a"), target.present())
        // 临时文件先建出来，改名后不再存在
        assertEquals(listOf("~getmedia-deadbeef.part"), target.created)
        assertEquals(listOf("~getmedia-deadbeef.part" to "歌.m4a"), target.renamed)
        assertTrue(target.deleted.isEmpty())
    }

    @Test
    fun fallsBackToRewriteWhenRenameUnsupported() {
        val target = FakeTarget(renameSupported = false)
        val outcome = ExportWriter(target) { "~getmedia-deadbeef.part" }.write(item(), source(10))

        assertTrue(outcome.ok)
        assertEquals("歌.m4a", outcome.fileName)
        assertTrue(outcome.rewritten)
        assertEquals(setOf("歌.m4a"), target.present())
        // 临时文件在重写成功后删掉，只留最终文件
        assertEquals(listOf("~getmedia-deadbeef.part"), target.deleted)
    }

    @Test
    fun deletesBothHalvesWhenRewriteFails() {
        // 第 1 次写成功、provider 不支持改名、第 2 次（按最终名重写）失败
        val target = FakeTarget(renameSupported = false, failOnWrite = 2)
        val outcome = ExportWriter(target) { "~getmedia-deadbeef.part" }.write(item(), source(10))

        assertFalse(outcome.ok)
        assertEquals(ExportWriter.REASON_RENAME_FALLBACK, outcome.reason)
        assertTrue("两个半成品都要删掉：${target.deleted}", target.deleted.containsAll(listOf("~getmedia-deadbeef.part", "歌.m4a")))
        assertTrue(target.present().isEmpty())
    }

    @Test
    fun byteMismatchDeletesTemp() {
        val target = FakeTarget(shortWrite = true)
        val outcome = ExportWriter(target) { "~getmedia-deadbeef.part" }.write(item(), source(10))

        assertFalse(outcome.ok)
        assertEquals(ExportWriter.REASON_INCOMPLETE, outcome.reason)
        assertTrue(target.present().isEmpty())
        assertEquals(listOf("~getmedia-deadbeef.part"), target.deleted)
    }

    @Test
    fun addsIndexWhenNameTaken() {
        val target = FakeTarget().apply { put("歌.m4a", 3) }
        val outcome = ExportWriter(target).write(item(), source(10))

        assertTrue(outcome.ok)
        assertEquals("歌 (2).m4a", outcome.fileName)
        assertEquals(setOf("歌.m4a", "歌 (2).m4a"), target.present())
    }

    @Test
    fun caseInsensitiveConflict() {
        val target = FakeTarget().apply { put("歌.M4A", 3) }
        val outcome = ExportWriter(target).write(item(), source(10))

        assertEquals("歌 (2).m4a", outcome.fileName)
    }

    @Test
    fun givesUpWhenIndexesExhausted() {
        val target = FakeTarget().apply {
            put("歌.m4a", 3)
            for (index in 2..ExportNaming.MAX_INDEX) put("歌 ($index).m4a", 3)
        }
        val outcome = ExportWriter(target).write(item(), source(10))

        assertFalse(outcome.ok)
        assertEquals(ExportWriter.REASON_TOO_MANY, outcome.reason)
        assertTrue("没有建过任何文件：${target.created}", target.created.isEmpty())
    }

    @Test
    fun missingSourceIsReportedWithoutTouchingTarget() {
        val target = FakeTarget()
        val missing = File(folder.root, "gone.m4a")
        val outcome = ExportWriter(target).write(item(), missing)

        assertFalse(outcome.ok)
        assertEquals(ExportPlan.REASON_MISSING_LOCAL, outcome.reason)
        assertTrue(target.created.isEmpty())
    }

    @Test
    fun createsNestedDirsFromOptions() {
        val target = FakeTarget()
        val dirs = listOf("2026-11 导出", "音频")
        val outcome = ExportWriter(target).write(item(dirs = dirs), source(4))

        assertTrue(outcome.ok)
        assertEquals(listOf(dirs), target.dirsRequested.distinct())
    }

    @Test
    fun securityExceptionMarksBatchAbort() {
        val target = FakeTarget(securityOnCreate = true)
        val outcome = ExportWriter(target).write(item(), source(4))

        assertFalse(outcome.ok)
        assertTrue(outcome.abort)
        assertEquals("目录授权已失效，请重新选择", outcome.reason)
    }

    @Test
    fun noSpaceIsFlaggedForAbort() {
        val target = FakeTarget(noSpaceOnWrite = true)
        val outcome = ExportWriter(target).write(item(), source(4))

        assertFalse(outcome.ok)
        assertTrue(outcome.noSpace)
        assertEquals("目标空间不足", outcome.reason)
        assertTrue(target.present().isEmpty())
    }

    @Test
    fun mimeComesFromExtension() {
        val target = FakeTarget()
        ExportWriter(target).write(item(title = "片", format = "webm"), source(4))
        assertEquals("audio/webm", target.lastMime)
    }

    /** 内存里的假目标：只记名字与字节数，够验证"有没有半成品剩下"。 */
    private class FakeTarget(
        private val renameSupported: Boolean = true,
        /** 第几次 write 抛 IOException（1 起算，0 表示都不抛）。 */
        private val failOnWrite: Int = 0,
        private val shortWrite: Boolean = false,
        private val securityOnCreate: Boolean = false,
        private val noSpaceOnWrite: Boolean = false,
    ) : ExportTarget {

        val created = mutableListOf<String>()
        val deleted = mutableListOf<String>()
        val renamed = mutableListOf<Pair<String, String>>()
        val dirsRequested = mutableListOf<List<String>>()
        var lastMime: String? = null

        private val files = LinkedHashMap<String, Long>()
        private var writes = 0

        fun put(name: String, bytes: Long) {
            files[name] = bytes
        }

        fun present(): Set<String> = files.keys.toSet()

        override fun ensureDir(dirs: List<String>): ExportTarget.Dir {
            dirsRequested += dirs
            return FakeDir(dirs)
        }

        override fun childNames(dir: ExportTarget.Dir): Set<String> = files.keys.toSet()

        override fun createDocument(dir: ExportTarget.Dir, mimeType: String, name: String): ExportTarget.Doc {
            lastMime = mimeType
            if (securityOnCreate) throw SecurityException("no permission")
            created += name
            files[name] = 0L
            return FakeDoc(name)
        }

        override fun write(doc: ExportTarget.Doc, source: File): Long {
            writes += 1
            val handle = doc as FakeDoc
            if (failOnWrite > 0 && writes == failOnWrite) {
                files.remove(handle.name)
                throw IOException("write failed")
            }
            if (noSpaceOnWrite) {
                files.remove(handle.name)
                throw IOException("ENOSPC (No space left on device)")
            }
            val bytes = if (shortWrite) source.length() - 1 else source.length()
            files[handle.name] = bytes
            return bytes
        }

        override fun rename(doc: ExportTarget.Doc, newName: String): Boolean {
            if (!renameSupported) return false
            val name = (doc as FakeDoc).name
            val bytes = files.remove(name) ?: 0L
            files[newName] = bytes
            renamed += name to newName
            return true
        }

        override fun delete(doc: ExportTarget.Doc) {
            val name = (doc as FakeDoc).name
            files.remove(name)
            deleted += name
        }

        override fun availableBytes(): Long = -1L
    }

    private class FakeDir(val path: List<String>) : ExportTarget.Dir
    private class FakeDoc(val name: String) : ExportTarget.Doc
}
