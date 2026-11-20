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

    private fun newSession(
        items: List<ExportPlan.Item>,
        target: FakeTarget,
        clock: () -> Long = { 0L },
    ): ExportSession =
        ExportSession(items, ExportWriter(target), io = Dispatchers.Unconfined, clock = clock)

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
        // 中断发生在第一项写到一半：这一项不算完成，进"未执行"等重试
        assertEquals(0, state.results.count { it.status == ExportSession.Status.DONE })
        assertEquals(3, state.notRun)
        assertEquals(setOf(1L, 2L, 3L), session.retryIds)
        assertTrue("被中断的项不能留下半成品", target.present().isEmpty())
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
        assertEquals(setOf(1L, 2L), session.retryIds)
        assertTrue("取消时当前项的半成品必须删掉", target.present().isEmpty())
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

    /** 云端行（有 serverId、没 localPath）要说"尚未下载"，不能让人去重提取一遍（阶段 3 验收 ④）。 */
    @Test
    fun cloudRowSaysNotDownloadedInsteadOfMissing() = runBlocking {
        val cloud = ExportTrack(
            id = 9L, title = "云端的一条", audioFormat = "", audioBytes = null,
            localPath = null, serverId = 41L,
        )
        val session = newSession(
            listOf(ExportPlan.Item(cloud, emptyList(), "云端的一条.m4a"), item(2L, source("b.m4a"))),
            FakeTarget(),
        )

        session.run()

        val state = session.state.value as ExportSession.State.Finished
        assertEquals(ExportPlan.REASON_NOT_DOWNLOADED, state.results.first { it.id == 9L }.reason)
        assertEquals("尚未下载到本机，请先下载", state.results.first { it.id == 9L }.reason)
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

    @Test
    fun timeLimitStopsWithItsOwnWording() = runBlocking {
        var now = 0L
        lateinit var session: ExportSession
        val target = FakeTarget(onWrite = { nth -> if (nth == 2) now = 10_000L })
        session = newSession(
            listOf(item(1L, source("a.m4a")), item(2L, source("b.m4a")), item(3L, source("c.m4a"))),
            target,
            clock = { now },
        )

        session.run(deadlineMs = 5_000L)

        val state = session.state.value as ExportSession.State.Interrupted
        assertEquals(ExportSession.Reason.TIME_LIMIT, state.reason)
        assertEquals("导出时间过长，已主动收尾，可重试失败项", state.reason.text)
        assertEquals(1, state.results.count { it.status == ExportSession.Status.DONE })
        assertEquals(setOf(2L, 3L), session.retryIds)
        assertEquals("第 2 项的临时文件要删掉，只留第 1 项的成品", setOf("曲目 1.m4a"), target.present())
    }

    /**
     * 三条收尾路径（通知取消 / Android 15 onTimeout / 5 小时自保护）留下的东西必须一模一样：
     * 已完成项保留、当前项不记结果、剩余项进重试、目标目录里不留半成品。
     * 区别只允许在 [ExportSession.Reason] 的文案上。
     */
    @Test
    fun allStopPathsLeaveTheSameShape() = runBlocking {
        suspend fun shape(reason: ExportSession.Reason): Triple<List<Pair<Long, ExportSession.Status>>, Int, Set<String>> {
            var now = 0L
            lateinit var session: ExportSession
            val target = FakeTarget(
                onWrite = { nth ->
                    if (nth == 2) {
                        when (reason) {
                            ExportSession.Reason.USER_CANCEL -> session.cancel()
                            ExportSession.Reason.SYSTEM_TIMEOUT -> session.interrupt(reason)
                            ExportSession.Reason.TIME_LIMIT -> now = 9_999L
                            ExportSession.Reason.ABORTED -> Unit
                        }
                    }
                },
            )
            val tag = reason.name.lowercase()
            session = newSession(
                listOf(item(1L, source("a-$tag.m4a")), item(2L, source("b-$tag.m4a")), item(3L, source("c-$tag.m4a"))),
                target,
                clock = { now },
            )

            session.run(deadlineMs = if (reason == ExportSession.Reason.TIME_LIMIT) 5_000L else null)

            val state = session.state.value as ExportSession.State.Interrupted
            assertEquals(reason, state.reason)
            assertTrue("停止路径不该产生失败项：${state.results}", state.results.none { it.status == ExportSession.Status.FAILED })
            return Triple(state.results.map { it.id to it.status }, state.notRun, target.present())
        }

        val cancelled = shape(ExportSession.Reason.USER_CANCEL)
        val timedOut = shape(ExportSession.Reason.SYSTEM_TIMEOUT)
        val selfLimited = shape(ExportSession.Reason.TIME_LIMIT)

        assertEquals(cancelled, timedOut)
        assertEquals(cancelled, selfLimited)
        // 具体长什么样也要钉住，否则三条一起错也会"一致地通过"
        assertEquals(listOf(1L to ExportSession.Status.DONE), cancelled.first)
        assertEquals(2, cancelled.second)
        assertEquals(setOf("曲目 1.m4a"), cancelled.third)
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

        override fun write(doc: ExportTarget.Doc, source: File, stop: () -> Boolean): Long {
            writes += 1
            onWrite(writes)
            val handle = doc as FakeDoc
            // 检查点放在"已经写了一点"之后：模拟真实写循环里每 64 KiB 问一次，
            // 这样取消发生在这一项中途，而不是"刚好写完"
            files[handle.name] = 0L
            if (stop()) {
                files.remove(handle.name)
                throw WriteCancelled()
            }
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

        /** 目标里剩下的文件：用来断言"半成品没有落下"。 */
        fun present(): Set<String> = files.keys.toSet()
    }

    private object FakeDir : ExportTarget.Dir
    private class FakeDoc(val name: String) : ExportTarget.Doc
}
