package com.getmedia.app.data.library

import com.getmedia.app.account.AccountApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * `GET /v2/sync` 的客户端。
 *
 * 两条硬要求：
 * 1. **只发 GET**——拉取阶段一个写请求都不许有（这里用 MockWebServer 逐条核对方法与路径）；
 * 2. 真正的 HTTP 409 也要按 `code` 落到不同结论（不是只在 JSON 层对着字符串测）。
 */
class LibrarySyncApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: LibrarySyncApi
    private var token: String? = "tok-1"

    @Before fun setUp() {
        AccountApi.ALLOW_TEST_HTTP = true
        server = MockWebServer().also { it.start() }
        api = LibrarySyncApi(server.url("/getmedia/owner/api").toString().trimEnd('/'), token = { token })
    }

    @After fun tearDown() {
        AccountApi.ALLOW_TEST_HTTP = false
        server.shutdown()
    }

    @Test fun pullIsAPlainGetWithSessionHeader() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"items":[],"next_seq":0,"has_more":false}"""))
        val page = api.pull(sinceSeq = 12, limit = 200)
        assertTrue(page.items.isEmpty())

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/getmedia/owner/api/v2/sync", request.path?.substringBefore('?'))
        assertEquals("12", request.requestUrl?.queryParameter("since_seq"))
        assertEquals("200", request.requestUrl?.queryParameter("limit"))
        assertEquals("tok-1", request.getHeader("X-GetMedia-Session"))
    }

    @Test fun pullNeverWritesWhenPaginating() = runBlocking {
        // 两页都拉一遍，逐条核对：一次写请求都没有
        repeat(2) { server.enqueue(MockResponse().setBody("""{"items":[],"next_seq":0,"has_more":false}""")) }
        api.pull(0, 200)
        api.pull(0, 200)
        repeat(2) {
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertNull("不许带请求体", request.body.size.takeIf { it > 0 })
        }
    }

    @Test fun missingTokenFailsWithoutHittingTheNetwork() {
        token = null
        try {
            runBlocking { api.pull(0, 200) }
            fail("没有令牌应当直接报登录")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncProblem.LOGIN_REQUIRED, e.problem)
        }
        assertEquals("没有令牌时不该出网", 0, server.requestCount)
    }

    @Test fun http409TombstoneBecomesTrackDeleted() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"detail":"该条目已被删除","code":"track_deleted","track":{"id":3}}"""),
        )
        try {
            api.pull(0, 200)
            fail("应当抛 409")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncProblem.TRACK_DELETED, e.problem)
            assertEquals(3L, e.conflict?.optLong("id") ?: 0)
        }
    }

    @Test fun http409LibraryFullStaysSeparate() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409)
                .setBody("""{"detail":"云端曲库已达上限（20000 条），请先在网页端清理","code":"library_full"}"""),
        )
        try {
            api.pull(0, 200)
            fail("应当抛 409")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncProblem.LIBRARY_FULL, e.problem)
            assertNull(e.conflict)
        }
    }

    @Test fun bare404IsNotAvailable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            api.pull(0, 200)
            fail("应当抛 404")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncProblem.NOT_AVAILABLE, e.problem)
        }
    }

    @Test fun networkFailureIsNotMistakenForServerError() = runBlocking {
        server.shutdown() // 直接把服务端关掉，制造 IOException
        try {
            api.pull(0, 200)
            fail("应当抛网络错误")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncProblem.NETWORK, e.problem)
        }
    }
}
