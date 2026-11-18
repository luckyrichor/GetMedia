package com.getmedia.app.data.serverextract

import com.getmedia.app.data.local.MediaKind
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ServerMediaApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: ServerMediaApi
    private var token: String? = "tok-1"

    @Before fun setUp() {
        com.getmedia.app.account.AccountApi.ALLOW_TEST_HTTP = true
        server = MockWebServer().also { it.start() }
        api = ServerMediaApi(server.url("/getmedia/owner/api").toString().trimEnd('/'), token = { token })
    }

    @After fun tearDown() {
        com.getmedia.app.account.AccountApi.ALLOW_TEST_HTTP = false
        server.shutdown()
    }

    @Test fun extractPostsOwnerPathWithSessionHeader() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"id":7,"status":"queued"}"""))
        val job = api.startExtract("https://v.douyin.com/x/", MediaKind.AUDIO)
        assertEquals(7L, job.id)

        val request = server.takeRequest()
        assertEquals("/getmedia/owner/api/v2/extract", request.path)
        assertEquals("tok-1", request.getHeader("X-GetMedia-Session"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"storage\":\"cache\""))
        assertTrue(body.contains("\"media_type\":\"audio\""))
        assertTrue(body.contains("v.douyin.com"))
    }

    @Test fun videoUsesVideoMediaType() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(202).setBody("""{"id":8,"status":"queued"}"""))
        api.startExtract("https://www.bilibili.com/video/BV1", MediaKind.VIDEO)
        assertTrue(server.takeRequest().body.readUtf8().contains("\"media_type\":\"video\""))
    }

    @Test fun missingTokenFailsWithoutHittingTheNetwork() {
        token = null
        try {
            runBlocking { api.startExtract("x", MediaKind.AUDIO) }
            fail("没有令牌应当直接报登录")
        } catch (e: ServerMediaException) {
            assertEquals(ServerProblem.LOGIN_REQUIRED, e.problem)
        }
    }

    @Test fun bare404BecomesPermissionMessage() {
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            runBlocking { api.job(7) }
            fail("裸 404 应当抛 NOT_OWNER")
        } catch (e: ServerMediaException) {
            assertEquals(ServerProblem.NOT_OWNER, e.problem)
            assertTrue(e.message.contains("请重新登录"))
        }
    }

    @Test fun rateLimitExposesRetryAfter() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "5")
            .setBody("""{"code":"rate_limited"}"""))
        try {
            runBlocking { api.track(31) }
            fail("429 应当抛限流")
        } catch (e: ServerMediaException) {
            assertEquals(ServerProblem.RATE_LIMITED, e.problem)
        }
    }

    @Test fun mediaRequestSendsRangeAndIdentityEncoding() {
        server.enqueue(MockResponse().setResponseCode(206).setHeader("Content-Range", "bytes 4-7/8")
            .setBody("abcd"))
        api.openMedia(31, offset = 4).use { response ->
            assertEquals(206, response.code)
            val request = server.takeRequest()
            assertEquals("bytes=4-", request.getHeader("Range"))
            assertEquals("identity", request.getHeader("Accept-Encoding"))
            assertEquals("tok-1", request.getHeader("X-GetMedia-Session"))
            assertNull(request.getHeader("Cookie"))
        }
    }

    @Test fun mediaUrlKeepsOwnerPrefix() {
        assertEquals(server.url("/getmedia/owner/api/v2/media/31").toString(), api.mediaUrl(31))
    }

    @Test fun truncatedBodySurfacesAsNetworkProblem() {
        server.enqueue(MockResponse().setBody("abcdefgh")
            .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try {
            runBlocking { api.job(7) }
            fail("断流应当抛网络错误")
        } catch (e: ServerMediaException) {
            assertEquals(ServerProblem.NETWORK, e.problem)
        }
    }
}
