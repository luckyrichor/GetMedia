package com.getmedia.app.data.serverextract

import com.getmedia.app.account.AccountApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.cancellation.CancellationException

class ServerDownloadTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ServerMediaApi
    private lateinit var dir: File

    private val whole = "0123456789abcdef".toByteArray()

    @Before fun setUp() {
        AccountApi.ALLOW_TEST_HTTP = true
        server = MockWebServer().also { it.start() }
        api = ServerMediaApi(server.url("/getmedia/owner/api").toString().trimEnd('/'), token = { "tok" })
        dir = Files.createTempDirectory("gm-download").toFile()
    }

    @After fun tearDown() {
        AccountApi.ALLOW_TEST_HTTP = false
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun part(name: String = "t.part") = File(dir, name).apply { delete() }

    @Test fun resumeOffsetRules() {
        assertEquals(0L, ServerDownload.resumeOffset(0, 100))
        assertEquals(40L, ServerDownload.resumeOffset(40, 100))
        // 已有长度不小于服务器声明：这份文件不可信，重下
        assertEquals(0L, ServerDownload.resumeOffset(100, 100))
        assertEquals(0L, ServerDownload.resumeOffset(120, 100))
        // 服务器没给长度：能续就续
        assertEquals(40L, ServerDownload.resumeOffset(40, null))
    }

    @Test fun totalBytesPrefersContentRangeThenContentLength() {
        assertEquals(1000L, ServerDownload.totalBytes("bytes 0-999/1000", 1000, 0))
        // 没给 Content-Range：offset + Content-Length
        assertEquals(500L, ServerDownload.totalBytes(null, 200, 300))
        assertNull(ServerDownload.totalBytes(null, -1, 0))
    }

    @Test fun fullDownloadWritesWholeFile() = runBlocking {
        server.enqueue(MockResponse().setBody(okio.Buffer().write(whole)))
        val file = part()
        val result = ServerDownload.fetch(api, 31, file, whole.size.toLong())

        assertEquals(whole.size.toLong(), result.bytes)
        assertArrayEquals(whole, file.readBytes())
        assertEquals(ServerDownload.sha256(file), result.sha256)
        assertNull("首次下载不该带 Range", server.takeRequest().getHeader("Range"))
    }

    /**
     * 进度回调必须真的报上来：界面上那条进度条只认它。
     *
     * 钉三件事——字节数单调不减、最后一段等于文件长度、总长度取服务器声明的那一份。
     * 少任何一条，用户看到的都是"进度条不动"或者"百分比乱跳"。
     */
    @Test fun reportsProgressWhileWritingTheFile() = runBlocking {
        val body = ByteArray(256 * 1024) { (it % 251).toByte() }   // 4 个 64 KB 的读，够看出单调
        server.enqueue(MockResponse().setBody(okio.Buffer().write(body)))
        val seen = mutableListOf<Pair<Long, Long?>>()

        ServerDownload.fetch(api, 31, part(), body.size.toLong(), onProgress = { done, total -> seen += done to total })

        assertTrue("至少要报一次进度", seen.isNotEmpty())
        assertTrue("第一次回调就该带上已写入的字节：${seen.first()}", seen.first().first > 0)
        assertEquals("最后一段等于文件长度", body.size.toLong(), seen.last().first)
        assertEquals("总长度取服务器声明的那一份", body.size.toLong(), seen.last().second)
        assertTrue("字节数不能回退：$seen", seen.map { it.first }.zipWithNext().all { (a, b) -> b >= a })
    }

    /**
     * 取消要真的停下来。
     *
     * 读流是阻塞 IO，**协程取消打断不了它**——只 cancel Job 的话，用户点完「取消」得等
     * 整条 200 MB 传完才生效。所以 `fetch` 的写盘循环每读一块就问一次 [cancelled]。
     * 停下时已经落盘的字节要留着（和断流一样），下次点下载用 Range 接着要。
     */
    @Test fun cancelStopsMidwayAndKeepsThePartForResume() = runBlocking {
        val body = ByteArray(512 * 1024) { (it % 251).toByte() }   // 8 个 64 KB 的读
        server.enqueue(MockResponse().setBody(okio.Buffer().write(body)))
        val file = part()
        var checks = 0

        try {
            ServerDownload.fetch(api, 31, file, body.size.toLong(), cancelled = { ++checks > 1 })
            fail("取消必须抛出 CancellationException，不能假装下载完了")
        } catch (expected: CancellationException) {
            // 正是想要的：协程语义上的"不要了"，上层的 catch(IOException) 不会误吞
        }

        assertTrue("确实提前停了：$checks 次检查", checks in 2..body.size)
        assertTrue("已写下的字节要留着续传，实际 ${file.length()}", file.length() in 1 until body.size.toLong())
    }

    @Test fun resumesFromExistingBytes() = runBlocking {        val file = part()
        file.writeBytes(whole.copyOfRange(0, 6))
        val rest = whole.copyOfRange(6, whole.size)
        server.enqueue(MockResponse().setResponseCode(206)
            .setHeader("Content-Range", "bytes 6-15/16")
            .setBody(okio.Buffer().write(rest)))

        val result = ServerDownload.fetch(api, 31, file, whole.size.toLong())

        assertEquals("bytes=6-", server.takeRequest().getHeader("Range"))
        assertEquals(whole.size.toLong(), result.bytes)
        assertArrayEquals("续传后必须与整段一致", whole, file.readBytes())
    }

    @Test fun serverIgnoringRangeRewritesFromZero() = runBlocking {
        val file = part()
        file.writeBytes(whole.copyOfRange(0, 6))
        // 回 200 且给全量：说明它没认 Range，本地必须截断重写，否则会变成"半段+全段"
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(whole)))

        val result = ServerDownload.fetch(api, 31, file, whole.size.toLong())

        assertEquals(whole.size.toLong(), result.bytes)
        assertArrayEquals(whole, file.readBytes())
    }

    @Test fun rangeNotSatisfiableRestartsOnce() = runBlocking {
        val file = part()
        file.writeBytes(whole.copyOfRange(0, 6))
        server.enqueue(MockResponse().setResponseCode(416))
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(whole)))

        val result = ServerDownload.fetch(api, 31, file, whole.size.toLong())

        assertEquals(whole.size.toLong(), result.bytes)
        assertArrayEquals(whole, file.readBytes())
        server.takeRequest()
        assertNull("416 之后应当从 0 重来", server.takeRequest().getHeader("Range"))
    }

    @Test fun lengthMismatchNeverHandsBackAFile() = runBlocking {
        val file = part()
        repeat(ServerDownload.MAX_ATTEMPTS) {
            server.enqueue(MockResponse().setBody(okio.Buffer().write(whole.copyOfRange(0, 8))))
        }
        try {
            ServerDownload.fetch(api, 31, file, whole.size.toLong())
            fail("长度不符必须报错，不能把半截文件交给调用方")
        } catch (e: ServerMediaException) {
            assertEquals(ServerProblem.UNAVAILABLE, e.problem)
            assertTrue(e.message.contains("重试"))
        }
    }

    @Test fun emptyBodyIsRejected() {
        repeat(ServerDownload.MAX_ATTEMPTS) { server.enqueue(MockResponse().setBody("")) }
        try {
            runBlocking { ServerDownload.fetch(api, 31, part(), null) }
            fail("空文件不能落库")
        } catch (e: ServerMediaException) {
            assertEquals(ServerProblem.UNAVAILABLE, e.problem)
        }
    }

    // ---------- 断流续传：网络类失败保留 .part，下一轮带 Range 接着要 ----------

    /** 按 Range 切片；第一份响应只发一半就断（mockwebserver 的 body 截断策略发一半后关连接）。 */
    private inner class HalfThenResume(private val body: ByteArray) : Dispatcher() {
        val ranges = mutableListOf<String?>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            val range = request.getHeader("Range")
            ranges += range
            val start = range?.substringAfter("bytes=")?.substringBefore('-')?.toIntOrNull() ?: 0
            val response = if (start > 0) {
                MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
            } else {
                MockResponse()
            }
            response.setBody(Buffer().write(body, start, body.size - start))
            if (ranges.size == 1) response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            return response
        }
    }

    @Test fun disconnectMidBodyResumesFromReceivedBytes() = runBlocking {
        val body = ByteArray(64 * 1024) { ('a'.code + it % 26).toByte() }
        val dispatcher = HalfThenResume(body)
        server.dispatcher = dispatcher
        val file = part()

        val result = ServerDownload.fetch(api, 31, file, body.size.toLong())

        assertEquals(body.size.toLong(), result.bytes)
        assertArrayEquals("续传后必须与整段一致", body, file.readBytes())
        assertNull("首次请求不带 Range", dispatcher.ranges[0])
        assertEquals(
            "断流后必须从已收到的字节接着要",
            "bytes=${body.size / 2}-",
            dispatcher.ranges[1],
        )
    }

    @Test fun repeatedDisconnectsFailWithNetworkErrorAndKeepPart() {
        val body = ByteArray(64 * 1024) { 'x'.code.toByte() }
        repeat(ServerDownload.MAX_ATTEMPTS) {
            server.enqueue(
                MockResponse().setBody(Buffer().write(body))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            )
        }
        val file = part()
        try {
            runBlocking { ServerDownload.fetch(api, 31, file, body.size.toLong()) }
            fail("连续断流必须报错，不能返回半截文件")
        } catch (e: ServerMediaException) {
            assertEquals("网络类失败应归到 NETWORK", ServerProblem.NETWORK, e.problem)
            assertTrue("文案必须是中文，不能是原始 IOException", e.message.contains("网络中断"))
            assertTrue("要告诉用户还能续传", e.message.contains("续传"))
        }
        assertTrue("网络类失败必须保留 .part 供下次续传", file.exists())
        assertEquals(body.size / 2L, file.length())
    }
}
