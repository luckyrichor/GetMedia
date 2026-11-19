package com.getmedia.app.data.export

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * 会话状态机：串行、取消、**系统超时中断**、空间不足中止、只重试未完成项。
 *
 * 系统超时那条是本节点必须有的：Android 15 上 `dataSync` 前台服务到点会回调
 * `Service.onTimeout(startId, fgsType)`，若不当场把状态改成"被中断、可重试"，
 * 界面会永远卡在 7/12。节点 C 的 `onTimeout` 只负责调 [ExportSession.interrupt]。
 */
class ExportSessionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun source(name: String, bytes: Int = 8): File =
        folder.newFile(name).apply { writeBytes(ByteArray(bytes) { it.toByte() }) }

    private fun track(id: Long, file: File?) = ExportTrack(
        id = id,
        title = "曲目 $id",
        audioFormat = "m4a",
        audioBytes = file?.length(),
        localPath = file?.absolutePath,
    )

    private fun item(id: Long, file: File?): ExportPlan.Item =
        ExportPlan.Item(track = track(id, file), dirs = emptyList(), fileName = "曲目 $id.m4a")

    private fun newSession(items: List<ExportPlan.Item>, target: FakeTarget): ExportSession =
        ExportSession(items, ExportWriter(target), io = Dispatchers.Unconfined)

    @Test
    fun systemTimeoutLeavesInterruptedStateWithRetry() = runBlocking {
        val items = listOf(item(1L, source("a.m4a")), item(2L, source("b.m4a")), item(3L, source("c.m4a")))
        lateinit var session: ExportSession
        val target = FakeTarget(onWrite = { nth -> if (nth == 1) session.interrupt(ExportSession.Reason.SYSTEM_TIMEOUT) })
        session = newSession(items, target)

        session.run()

        val state = session.state.value
        assertTrue("状态应为 Interrupted，实际 $state", state is ExportSession.State.Interrupted)
        state as ExportSession.State.Interrupted
        assertEquals(ExportSession.Reason.SYSTEM_TIMEOUT, state.reason)
        assertEquals("导出被系统中断，可重试失败项", state.reason.text)
        assertEquals(1, state.results.count { it.status == ExportSession.Status.DONE })
        assertEquals(2, state.notRun)
        assertEquals(setOf(2L, 3L), session.retryIds)
        // 被中断的项不能记成失败，否则摘要会骗人
        assertTrue(state.results.none { it.status == ExportSession.Status.FAILED })
    }

    @Test
    fun cancelUsesItsOwnWording() = runBlocking {
        lateinit var session: ExportSession
        val target = FakeTarget(onWrite = { nth -> if (nth == 1) session.cancel() })
        session = newSession(listOf(item(1L, source("a.m4a")), item(2L, source("b.m4a"))), target)

        session.run()

        val state = session.state.value as ExportSession.State.Interrupted
        assertEquals(ExportSession.Reason.USER_CANCEL, state.reason)
        assertEquals("已取消导出", state.reason.text)
        assertEquals(setOf(2L), session.retryIds)
    }

    @Test
    fun noSpaceAbortsBatchAndSkipsRest() = runBlocking {
        val target = FakeTarget(noSpaceOnWrite = 1)
        val session = newSession(
            listOf(item(1L, source("a.m4a")), item(2L, source("b.m4a")), item(3L, source("c.m4a"))),
            target,
        )

        session.run()

        val state = session.state.value as ExportSession.State.Interrupted
        assertEquals(ExportSession.Reason.ABORTED, state.reason)
        assertEquals(1, state.results.count { it.status == ExportSession.Status.FAILED })
        assertEquals(2, state.results.count { it.status == ExportSession.Status.SKIPPED })
        assertEquals("目标空间不足", state.results.first { it.status == ExportSession.Status.FAILED }.reason)
        // 跳过项也要能重试：腾出空间后接着导
        assertEquals(setOf(1L, 2L, 3L), session.retryIds)
    }

    @Test
    fun missingLocalFileFailsWithoutTouchingTarget() = runBlocking {
        val target = FakeTarget()
        val session = newSession(listOf(item(1L, null), item(2L, source("b.m4a"))), target)

        session.run()

        val state = session.state.value as ExportSession.State.Finished
        assertEquals(ExportSession.Status.FAILED, state.results.first { it.id == 1L }.status)
        assertEquals(ExportPlan.REASON_MISSING_LOCAL, state.results.first { it.id == 1L }.reason)
        assertEquals(ExportSession.Status.DONE, state.results.first { it.id == 2L }.status)
    }

    @Test
    fun retryOnlyRunsUnfinishedItems() = runBlocking {
        val late = File(folder.root, "late.m4a") // 先不存在，重试前才建
        val target = FakeTarget()
        val session = newSession(listOf(item(1L, late), item(2L, source("b.m4a"))), target)

        session.run()
        assertEquals(1, (session.state.value as ExportSession.State.Finished).results.count { it.status == ExportSession.Status.FAILED })

        late.writeBytes(ByteArray(5))
        session.run(only = session.retryIds)

        val state = session.state.value as ExportSession.State.Finished
        assertEquals(0, state.results.count { it.status == ExportSession.Status.FAILED })
        assertEquals(2, state.results.count { it.status == ExportSession.Status.DONE })
        // 重试只跑了 late 这一项：b 没有被重复写
        assertEquals(2, target.writes)
    }

    @Test
    fun rewriteFallbackIsReportedPerItem() = runBlocking {
        val target = FakeTarget(renameSupported = false)
        val session = newSession(listOf(item(1L, source("a.m4a"))), target)

        session.run()

        val state = session.state.value as ExportSession.State.Finished
        assertTrue(state.results.single().rewritten)
    }

    /** 只记调用次数与成败的假目标。 */
    private class FakeTarget(
        private val renameSupported: Boolean = true,
        private val noSpaceOnWrite: Int = 0,
        private val onWrite: (Int) -> Unit = {},
    ) : ExportTarget {

        var writes = 0
        private val files = HashMap<String, Long>()

        override fun ensureDir(dirs: List<String>): ExportTarget.Dir = FakeDir

        override fun childNames(dir: ExportTarget.Dir): Set<String> = files.keys.toSet()

        override fun createDocument(dir: ExportTarget.Dir, mimeType: String, name: String): ExportTarget.Doc {
            files[name] = 0L
            return FakeDoc(name)
        }

        override fun write(doc: ExportTarget.Doc, source: File): Long {
            writes += 1
            onWrite(writes)
            val handle = doc as FakeDoc
            if (writes == noSpaceOnWrite) {
                files.remove(handle.name)
                throw IOException("ENOSPC (No space left on device)")
            }
            files[handle.name] = source.length()
            return source.length()
        }

        override fun rename(doc: ExportTarget.Doc, newName: String): Boolean {
            if (!renameSupported) return false
            val name = (doc as FakeDoc).name
            files[newName] = files.remove(name) ?: 0L
            return true
        }

        override fun delete(doc: ExportTarget.Doc) {
            files.remove((doc as FakeDoc).name)
        }

        override fun availableBytes(): Long = -1L
    }

    private object FakeDir : ExportTarget.Dir
    private class FakeDoc(val name: String) : ExportTarget.Doc
}
