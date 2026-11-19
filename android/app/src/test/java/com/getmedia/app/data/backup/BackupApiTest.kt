package com.getmedia.app.data.backup

import com.getmedia.app.account.AccountApi
import com.getmedia.app.account.ApiException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackupApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: BackupApi
    private var token: String? = "tok-123"

    @Before fun setUp() {
        AccountApi.ALLOW_TEST_HTTP = true
        server = MockWebServer().also { it.start() }
        api = BackupApi(server.url("/getmedia/api").toString().trimEnd('/'), token = { token })
    }

    @After fun tearDown() { AccountApi.ALLOW_TEST_HTTP = false; server.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    @Test fun uploadPostsGzipWithSessionAndMetadataHeaders() = runBlocking {
        server.enqueue(json("""{"id":"bkp_abc","created_at":1731912345,"pruned":[]}""", 201))
        val body = byteArrayOf(1, 2, 3, 4)

        val result = api.upload(body, "0.1.0", "Pixel 8")

        assertEquals("bkp_abc", result.id)
        assertTrue(result.pruned.isEmpty())

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/getmedia/api/account/metadata-backups", req.path)
        assertEquals("application/gzip", req.getHeader("Content-Type"))
        assertEquals("tok-123", req.getHeader("X-GetMedia-Session"))
        assertEquals("0.1.0", req.getHeader("X-GetMedia-App"))
        assertEquals("Pixel 8", req.getHeader("X-GetMedia-Device"))
        assertEquals(4, req.bodySize)
    }

    @Test fun uploadReturnsPrunedIds() = runBlocking {
        server.enqueue(json("""{"id":"bkp_new","created_at":1,"pruned":["bkp_old"]}""", 201))
        assertEquals(listOf("bkp_old"), api.upload(byteArrayOf(1), "0.1.0", "Pixel").pruned)
    }

    @Test fun unprocessableCarriesServerDetail() {
        server.enqueue(json("""{"detail":"title 超过 300 个字符","code":"backup_invalid"}""", 422))
        val error = assertThrows(ApiException::class.java) {
            runBlocking { api.upload(byteArrayOf(1), "0.1.0", "Pixel") }
        }
        assertEquals(422, error.status)
        assertEquals("backup_invalid", error.code)
        // 这句要原样进诊断记录：没有它只能靠抓包才知道哪条数据不合规
        assertEquals("title 超过 300 个字符", error.message)
    }

    @Test fun nginxHtmlBodyStillMapsToTheSameStatus() {
        // nginx 自己回的 413 是 HTML 错误页，不是 JSON：仍要能按 413 处理
        server.enqueue(MockResponse().setResponseCode(413).setBody("<html><body>413 Request Entity Too Large</body></html>"))
        val error = assertThrows(ApiException::class.java) {
            runBlocking { api.upload(ByteArray(600_000), "0.1.0", "Pixel") }
        }
        assertEquals(413, error.status)
        assertEquals(BackupOutcome.TooLarge, BackupOutcome.of(error))
    }

    @Test fun rateLimitKeepsRetryAfter() {
        server.enqueue(MockResponse().setResponseCode(429)
            .setHeader("Retry-After", "412")
            .setBody("""{"detail":"备份太频繁，请稍后再试","code":"rate_limited"}"""))
        val error = assertThrows(ApiException::class.java) {
            runBlocking { api.upload(byteArrayOf(1), "0.1.0", "Pixel") }
        }
        assertEquals(429, error.status)
        assertEquals(412, error.retryAfterSec)
        assertEquals(BackupOutcome.RateLimited, BackupOutcome.of(error))
    }

    @Test fun missingEndpointIs404() {
        server.enqueue(json("""{"detail":"未找到","code":"http_404"}""", 404))
        val error = assertThrows(ApiException::class.java) {
            runBlocking { api.upload(byteArrayOf(1), "0.1.0", "Pixel") }
        }
        assertEquals(BackupOutcome.Unsupported, BackupOutcome.of(error))
    }

    @Test fun uploadWithoutSessionFailsBeforeSending() {
        token = null
        val error = assertThrows(ApiException::class.java) {
            runBlocking { api.upload(byteArrayOf(1), "0.1.0", "Pixel") }
        }
        assertEquals(401, error.status)
        assertEquals("没有令牌不该发请求", 0, server.requestCount)
    }

    @Test fun deleteAllReturnsCount() = runBlocking {
        server.enqueue(json("""{"deleted":2}"""))
        assertEquals(2, api.deleteAll())
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/getmedia/api/account/metadata-backups", req.path)
    }

    @Test fun nonAsciiDeviceNameIsStripped() = runBlocking {
        server.enqueue(json("""{"id":"bkp_x","created_at":1,"pruned":[]}""", 201))
        // 中文机型名直接塞进 header 会被 OkHttp 拒绝，必须先滤成 ASCII
        api.upload(byteArrayOf(1), "0.1.0", "小米 14")
        val req = server.takeRequest()
        assertEquals("14", req.getHeader("X-GetMedia-Device"))
    }

    @Test fun listParsesBackupsAndIgnoresBrokenEntries() = runBlocking {
        server.enqueue(json("""{"backups":[
            {"id":"bkp_a","bytes":1234,"counts":{"tracks":12},"app_version":"0.1.0","device":"Pixel 8","created_at":1731912345},
            {"bytes":1,"created_at":1},
            null
        ],"total_bytes":1235,"keep":3}"""))

        val items = api.list()

        assertEquals("缺 id 的条目直接丢掉", 1, items.size)
        assertEquals("bkp_a", items.first().id)
        assertEquals(12, items.first().tracks)
        assertEquals(1731912345L, items.first().createdAt)
        assertEquals("/getmedia/api/account/metadata-backups", server.takeRequest().path)
    }

    @Test fun downloadReturnsGzipAndServerSha() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200)
            .setHeader("Content-Type", "application/gzip")
            .setHeader("X-Backup-Sha256", "abc123")
            .setBody(okio.Buffer().write("gz-bytes".toByteArray())))

        val payload = api.download("bkp_a")

        assertEquals("gz-bytes".length, payload.gzip.size)
        assertEquals("abc123", payload.sha256)
        assertEquals("/getmedia/api/account/metadata-backups/bkp_a", server.takeRequest().path)
    }

    @Test fun downloadOfMissingBackupIs404() {
        server.enqueue(json("""{"detail":"备份不存在","code":"backup_not_found"}""", 404))
        val error = assertThrows(ApiException::class.java) { runBlocking { api.download("bkp_x") } }
        assertEquals(404, error.status)
    }

    /** 单份删除走 `DELETE /account/metadata-backups/{id}`，不能把全部删掉。 */
    @Test fun deleteOneHitsTheIdPath() = runBlocking {
        server.enqueue(json("""{"deleted":1}"""))

        assertTrue(api.delete("bkp_a"))

        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/getmedia/api/account/metadata-backups/bkp_a", req.path)
        assertEquals("tok-123", req.getHeader("X-GetMedia-Session"))
    }

    @Test fun deleteOneMissingIs404() {
        server.enqueue(json("""{"detail":"备份不存在","code":"backup_not_found"}""", 404))
        val error = assertThrows(ApiException::class.java) { runBlocking { api.delete("bkp_gone") } }
        assertEquals(404, error.status)
    }
}
