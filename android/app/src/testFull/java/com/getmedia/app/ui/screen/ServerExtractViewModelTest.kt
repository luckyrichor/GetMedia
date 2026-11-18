package com.getmedia.app.ui.screen

import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.TrackEntity
import com.getmedia.app.data.serverextract.ServerExtractClient
import com.getmedia.app.data.serverextract.ServerMediaException
import com.getmedia.app.data.serverextract.ServerProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 服务器提取的队列编排：**一条一条串行**，单条失败不影响后面的，重试只重跑那一条。
 * 不连服务器、不碰 Room、不碰 Android API（诊断走 [ServerDiagnostics.None]）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerExtractViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private val threeLinks =
        "https://v.douyin.com/a1/ https://v.douyin.com/a2/ https://v.douyin.com/a3/"

    /** [failAt] 是第几次调用（0 基）抛业务异常；其余返回成功。重试用例会把它清空。 */
    private class FakeExtractor(var failAt: Set<Int> = emptySet()) : ServerExtractor {
        val calls = mutableListOf<String>()
        private var concurrent = 0
        var maxConcurrent = 0
            private set

        override suspend fun extract(
            input: String,
            kind: MediaKind,
            onProgress: (ServerExtractClient.Progress) -> Unit,
        ): TrackEntity {
            val index = calls.size
            calls += input
            concurrent++
            maxConcurrent = maxOf(maxConcurrent, concurrent)
            onProgress(ServerExtractClient.Progress(ServerExtractClient.Stage.DOWNLOADING, 5, 10))
            // 真让出一次：如果实现改成了并发，这里就会看到 concurrent == 2
            yield()
            concurrent--
            if (index in failAt) {
                throw ServerMediaException(ServerProblem.UNAVAILABLE, "平台拒绝，稍后重试", detail = "playurl_empty")
            }
            return TrackEntity(
                id = index.toLong(), platform = "douyin", videoId = "v$index", title = "第 ${index + 1} 条",
                uploader = null, durationSec = 10.0, audioBytes = 10, audioFormat = "m4a",
                sourceUrl = "https://s/$index",
            )
        }
    }

    @Test fun linksAreExtractedOneByOneInOrder() = runTest(dispatcher.scheduler) {
        val extractor = FakeExtractor()
        val vm = ServerExtractViewModel(extractor)
        vm.input = threeLinks

        vm.submit()
        advanceUntilIdle()

        assertEquals("三条都要跑，且顺序与粘贴顺序一致", 3, extractor.calls.size)
        assertEquals("同一时刻只能有一条在跑", 1, extractor.maxConcurrent)
        assertTrue("全部成功", vm.tasks.all { it.status == ServerTaskStatus.SUCCESS })
        assertFalse("跑完必须解除忙碌", vm.busy)
        assertEquals("四段进度停在已入库", ServerExtractClient.Stage.SAVED, vm.progress.value?.stage)
        assertTrue("摘要要说清结果", vm.summary!!.contains("全部完成"))
    }

    @Test fun oneFailureDoesNotStopTheRest() = runTest(dispatcher.scheduler) {
        val extractor = FakeExtractor(failAt = setOf(1))
        val vm = ServerExtractViewModel(extractor)
        vm.input = threeLinks

        vm.submit()
        advanceUntilIdle()

        assertEquals("失败那条之后的仍要跑", 3, extractor.calls.size)
        assertEquals(listOf(
            ServerTaskStatus.SUCCESS,
            ServerTaskStatus.FAILED,
            ServerTaskStatus.SUCCESS,
        ), vm.tasks.map { it.status })
        val failed = vm.tasks[1]
        assertEquals("失败文案要能直接展示", "平台拒绝，稍后重试", failed.message)
        assertEquals("服务器原文留给卡片小字", "playurl_empty", failed.detail)
        assertTrue("摘要要带上失败条数", vm.summary!!.contains("失败 1 条"))
        assertFalse(vm.busy)
    }

    @Test fun retryRerunsOnlyTheFailedLink() = runTest(dispatcher.scheduler) {
        val extractor = FakeExtractor(failAt = setOf(1))
        val vm = ServerExtractViewModel(extractor)
        vm.input = threeLinks
        vm.submit()
        advanceUntilIdle()
        val failedUrl = vm.tasks[1].url
        assertEquals("先确认那次真的失败了", ServerTaskStatus.FAILED, vm.tasks[1].status)

        // 让第 4 次调用（也就是重试的那一次）成功
        extractor.failAt = emptySet()
        vm.retry(1)
        advanceUntilIdle()

        assertEquals("重试只重跑失败的那一条，不重跑整批", 4, extractor.calls.size)
        assertEquals(failedUrl, extractor.calls.last())
        assertEquals(ServerTaskStatus.SUCCESS, vm.tasks[1].status)
        assertTrue("其它两条不受影响", vm.tasks[0].status == ServerTaskStatus.SUCCESS)
        assertFalse(vm.busy)
    }

    @Test fun emptyInputShowsHintInsteadOfExtracting() = runTest(dispatcher.scheduler) {
        val extractor = FakeExtractor()
        val vm = ServerExtractViewModel(extractor)
        vm.input = "这段话里没有链接"
        vm.submit()
        advanceUntilIdle()

        assertTrue(extractor.calls.isEmpty())
        assertTrue("要提示用户没识别到链接", vm.hint!!.contains("没有识别到链接"))
    }
}
