package com.getmedia.app.account

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AccountApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: AccountApi
    private var token: String? = null
    private val info = ClientInfo("0.1.0", "Android 14", "Pixel")
    private val doc = LegalDocument(1, "2026-10-30", "a".repeat(64), "t")
    private val legal = LegalBundle(doc, doc.copy(sha256 = "b".repeat(64)))

    @Before fun setUp() {
        AccountApi.ALLOW_TEST_HTTP = true
        server = MockWebServer().also { it.start() }
        // 与正式地址一样带前缀，验证路径拼接
        api = AccountApi(server.url("/getmedia/api").toString().trimEnd('/'), token = { token })
    }

    @After fun tearDown() { AccountApi.ALLOW_TEST_HTTP = false; server.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setBody(body)

    @Test fun publicCallsHaveNoSessionHeaderAndKeepPrefix() = runBlocking {
        token = "secret-token"
        server.enqueue(json("""{"registration_open":true,"invite_required":false,"maintenance":null,"min_version":"0.1.0"}"""))
        assertTrue(api.config().registrationOpen)
        val req = server.takeRequest()
        assertEquals("/getmedia/api/v2/config", req.path)
        assertNull(req.getHeader("X-GetMedia-Session"))
    }

    @Test fun authedCallsSendSessionHeaderOnly() = runBlocking {
        token = "tok-123"
        server.enqueue(json("""{"id":"a","login_name":"abc","tier":"free","consent_required":false,"recovery_confirmed":true,"status":"active"}"""))
        assertEquals("abc", api.me().loginName)
        val req = server.takeRequest()
        assertEquals("tok-123", req.getHeader("X-GetMedia-Session"))
        assertFalse(req.path!!.contains("tok-123"))
    }

    @Test fun authedCallWithoutTokenFailsLocally() {
        token = null
        val e = assertThrows(ApiException::class.java) { runBlocking { api.me() } }
        assertEquals("unauthenticated", e.code)
        assertEquals(0, server.requestCount)
    }

    @Test fun registerBodyCarriesConsentAgeAndClient() = runBlocking {
        server.enqueue(json("""{"session":{"token":"t","expires_at":1},"account":{"id":"a","login_name":"abc","tier":"free"},"recovery_code":"ABCD2345EFGH6789"}""", 201))
        val r = api.register("abc", "password-1", "  INV  ", legal, info)
        assertEquals("ABCD2345EFGH6789", r.recoveryCode)
        val body = org.json.JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(body.getBoolean("age_confirmed"))
        assertEquals("INV", body.getString("invite_code"))
        assertEquals(1, body.getJSONObject("consent").getJSONObject("user_agreement").getInt("version"))
        assertEquals("a".repeat(64), body.getJSONObject("consent").getJSONObject("user_agreement").getString("sha256"))
        assertEquals("android", body.getJSONObject("client").getString("channel"))
    }

    @Test fun deviceLimitSurfacesDevicesAndRetryUsesEvictId() = runBlocking {
        server.enqueue(json("""{"detail":"设备已达上限","code":"device_limit","devices":[{"id":"dev_1","channel":"android","device":"A","app_version":"1","created_at":1,"last_used_at":2,"current":false}]}""", 409))
        val e = assertThrows(ApiException::class.java) { runBlocking { api.login("abc", "password-1", info) } }
        assertEquals("device_limit", e.code); assertEquals("dev_1", e.devices.single().id)
        server.takeRequest()
        server.enqueue(json("""{"session":{"token":"t","expires_at":1},"account":{"id":"a","login_name":"abc","tier":"free"},"consent_required":false,"recovery_confirmed":true}"""))
        api.login("abc", "password-1", info, evictDeviceId = "dev_1")
        assertEquals("dev_1", org.json.JSONObject(server.takeRequest().body.readUtf8()).getString("evict_device_id"))
    }

    @Test fun redirectsAreNotFollowed() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://evil.invalid/"))
        val e = assertThrows(ApiException::class.java) { runBlocking { api.config() } }
        assertEquals(302, e.status)
        assertEquals(1, server.requestCount)
    }

    @Test fun logoutAndAckAcceptEmpty204() = runBlocking {
        token = "t"
        server.enqueue(MockResponse().setResponseCode(204)); api.logout()
        server.enqueue(MockResponse().setResponseCode(204)); api.recoveryAck()
        assertEquals("/getmedia/api/account/logout", server.takeRequest().path)
        assertEquals("/getmedia/api/account/recovery/ack", server.takeRequest().path)
    }

    @Test fun deleteAccountSendsPasswordAndReadsDeadline() = runBlocking {
        token = "t"
        server.enqueue(json("""{"delete_at":12345}"""))
        assertEquals(12345L, api.deleteAccount("pw-pw-pw-1"))
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("pw-pw-pw-1", org.json.JSONObject(req.body.readUtf8()).getString("password"))
    }

    @Test fun feedbackIsMultipartWithIdempotencyKey() = runBlocking {
        token = "t"
        server.enqueue(json("""{"id":"3f2c"}""", 201))
        assertEquals("3f2c", api.feedback("内容", "bug", null, null, "key-0001"))
        val req = server.takeRequest()
        assertTrue(req.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val raw = req.body.readUtf8()
        assertTrue(raw.contains("key-0001")); assertTrue(raw.contains("内容"))
        assertFalse(raw.contains("name=\"contact\""))
    }

    @Test fun httpsOnlyWithoutTestFlag() {
        AccountApi.ALLOW_TEST_HTTP = false
        assertThrows(IllegalArgumentException::class.java) { AccountApi("http://example.invalid/getmedia/api") }
    }
}
