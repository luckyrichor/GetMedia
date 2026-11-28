package com.getmedia.app.data.library

import com.getmedia.app.account.AccountApi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * `POST /v2/tracks` 的客户端（P0-2 阶段 3）。
 *
 * 钉住四件事，都是踩过或容易写反的：
 * 1. 请求体**只有那 8 个字段**——服务端 `extra="forbid"`，多带一个 `localPath` 就 422；
 * 2. `created=false` 不是失败（幂等重放/网页先建过），要如实带回来；
 * 3. 409 按 `code` 分：`track_deleted` 带冲突行（阶段 5 的确认要用），`library_full` 只是停手；
 * 4. 429 的 `Retry-After` 要解析成秒，交给上传队列退避。
 */
class LibrarySyncClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: LibrarySyncClient
    private var token: String? = "tok-1"

    @Before fun setUp() {
        AccountApi.ALLOW_TEST_HTTP = true
        server = MockWebServer().also { it.start() }
        client = LibrarySyncClient(server.url("/getmedia/owner/api").toString().trimEnd('/'), token = { token })
    }

    @After fun tearDown() {
        AccountApi.ALLOW_TEST_HTTP = false
        server.shutdown()
    }

    private val entry = UploadEntry(
        platform = "bilibili", videoId = "BV1xx411c7mD", page = 2, mediaType = "audio",
        title = "手机上的标题", uploader = "某UP", durationSec = 12.5,
        sourceUrl = "https://www.bilibili.com/video/BV1xx411c7mD?p=2",
    )

    private fun ok(created: Boolean = true, title: String = "手机上的标题") = MockResponse().setBody(
        """{"created":$created,"track":{"id":41,"media_type":"audio","platform":"bilibili",""" +
            """"video_id":"BV1xx411c7mD","page":2,"title":"$title","uploader":"某UP",""" +
            """"duration_sec":12.5,"cover_url":null,"source_url":"https://x","storage":"metadata",""" +
            """"bytes":0,"pinned":false,"deleted":false,"updated_seq":88,"created_at":1,""" +
            """"updated_at":1700000000,"cache_expires_at":null,"has_file":false}}""",
    )

    @Test fun postCarriesExactlyTheEightFields() = runBlocking {
        server.enqueue(ok())
        val result = client.create(entry)
        assertTrue(result.created)
        assertEquals(41L, result.track.id)
        assertEquals(88L, result.track.updatedSeq)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/getmedia/owner/api/v2/tracks", request.path)
        assertEquals("tok-1", request.getHeader("X-GetMedia-Session"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals(
            setOf("platform", "video_id", "page", "media_type", "title", "uploader", "duration_sec", "source_url"),
            body.keys().asSequence().toSet(),
        )
        assertEquals(2, body.getInt("page"))
        assertEquals("audio", body.getString("media_type"))
    }

    /** `created=false`：服务器上已经有这个键，不算失败，也不改它的字段。 */
    @Test fun existingRowComesBackAsAdopted() = runBlocking {
        server.enqueue(ok(created = false, title = "网页上的标题"))
        val result = client.create(entry)
        assertFalse(result.created)
        assertEquals("网页上的标题", result.track.title)
    }

    @Test fun trackDeletedCarriesConflictRow() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(409).setBody(
                """{"code":"track_deleted","detail":"这条已在云端删除","track":{"id":41,"platform":"bilibili",""" +
                    """"video_id":"BV1xx411c7mD","page":2,"title":"删掉的","media_type":"audio",""" +
                    """"deleted":true,"updated_seq":90,"updated_at":1700000000}}""",
            ),
        )
        try {
            client.create(entry)
            fail("应当抛 TRACK_DELETED")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncProblem.TRACK_DELETED, e.problem)
            assertEquals("删掉的", e.conflict?.optString("title"))
        }
    }

    @Test fun libraryFullStopsWithoutConflictRow() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"code":"library_full","detail":"曲库已满"}"""))
        try {
            client.create(entry)
            fail("应当抛 LIBRARY_FULL")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncProblem.LIBRARY_FULL, e.problem)
            assertNull(e.conflict)
        }
    }

    @Test fun rateLimitKeepsRetryAfter() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(429).setHeader("Retry-After", "7")
                .setBody("""{"code":"rate_limited","detail":"太快了"}"""),
        )
        try {
            client.create(entry)
            fail("应当抛 RATE_LIMITED")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncProblem.RATE_LIMITED, e.problem)
            assertEquals(7L, e.retryAfterSeconds)
        }
    }

    /** 400 是"这一条本身有问题"（坏键、坏页码、空标题），跳过它，别停整批。 */
    @Test fun badInputIsSkippable() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"code":"bad_title","detail":"标题不能为空"}"""))
        try {
            client.create(entry)
            fail("应当抛 INPUT")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncProblem.INPUT, e.problem)
            assertEquals("bad_title", e.code)
        }
    }

    /** 没有会话就不该发请求：闸门之外的第二道保险（例如退出登录的瞬间）。 */
    @Test fun noSessionThrowsBeforeNetwork() = runBlocking {
        token = null
        try {
            client.create(entry)
            fail("应当抛 LOGIN_REQUIRED")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncProblem.LOGIN_REQUIRED, e.problem)
        }
        assertEquals(0, server.requestCount)
    }

    /**
     * 传文件要真的按"写出去多少"报进度。
     *
     * 这条盯的是 OkHttp 那一层：`progressBody` 用 `ForwardingSink` 包住文件那一段，
     * 只有字节真交给 socket 才回调。写错成"从内存读出来就报"的话，进度会先冲到 100%
     * 再卡住等网络——用户看到的就是"卡在 100% 不动"。
     *
     * 另外钉住分母：必须是**文件长度**，不是 multipart 的总长度（含边界与表单头，
     * 那样百分比会从 0.1% 起步、结尾差几个字节）。
     */
    @Test fun uploadFileReportsBytesAsTheyReachTheSocket() = runBlocking {
        server.enqueue(MockResponse().setBody(trackJson()))
        val file = File.createTempFile("gm-upload-", ".mp3").apply {
            writeBytes(ByteArray(256 * 1024) { 7 })
        }
        try {
            val seen = mutableListOf<Pair<Long, Long>>()
            val uploaded = client.uploadFile(41, file) { sent, total -> seen += sent to total }

            assertEquals(41L, uploaded.id)
            assertTrue("必须报过多次进度，不是只报一次", seen.size >= 2)
            assertEquals("分母是文件长度", file.length(), seen.last().second)
            assertEquals("最后一定报到满", file.length(), seen.last().first)
            assertEquals("只增不减", seen.map { it.first }.sorted(), seen.map { it.first })

            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertTrue(
                "打到这一条的文件接口：" + request.path,
                request.path!!.endsWith("/v2/tracks/41/file"),
            )
            assertTrue(
                "文件要以 multipart 的表单字段发出去",
                request.getHeader("Content-Type")!!.startsWith("multipart/form-data"),
            )
            // 请求体 = 文件本体 + 一小段 multipart 头；被包两遍的话会接近两倍
            val overhead = request.bodySize - file.length()
            assertTrue("请求体不该把文件包两遍（多出 $overhead 字节）", overhead in 1..4096)
        } finally {
            file.delete()
        }
    }

    /** 传完文件服务器回的那一行（`singleTrack` 直接解析根对象）。 */
    private fun trackJson(): String = """{"id":41,"platform":"local","video_id":"loc_x","page":1,"title":"自己录的一段","uploader":null,"duration_sec":12.5,"source_url":"","media_type":"audio","deleted":false,"updated_seq":91,"updated_at":1700000000,"storage":"archive","bytes":262144,"has_file":true}"""

    /**
     * 413 是边缘（nginx `client_max_body_size`）拦下来的"文件太大"，不是"你这条请求写错了"。
     *
     * 以前它落到 `400..499 → INPUT` 的兜底里，条目上显示「未备份 · 请求被服务器拒绝」——
     * 用户会以为服务器不让传这一条，实际是文件超过了单文件上限。
     */
    @Test fun tooLargeUploadSaysTheFileIsTooBig() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(413)
                .setBody("<html><body><h1>413 Request Entity Too Large</h1></body></html>"),
        )
        val file = File.createTempFile("gm-huge-", ".mp4").apply { writeBytes(ByteArray(1024) { 1 }) }
        try {
            client.uploadFile(41, file) { _, _ -> }
            fail("应当抛 INPUT")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncProblem.INPUT, e.problem)
            assertTrue("要说清楚是文件太大：" + e.message, e.message.contains("太大"))
            assertEquals("too_large", e.code)
        } finally {
            file.delete()
        }
    }
}
