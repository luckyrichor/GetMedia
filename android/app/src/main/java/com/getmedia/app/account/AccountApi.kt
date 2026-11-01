package com.getmedia.app.account

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * GetMedia 账号与反馈接口。基址是编译期常量（含 /getmedia/api 前缀）；
 * 只走 HTTPS，不跟随重定向，令牌只放请求头，不进 URL 和日志。
 */
class AccountApi(
    baseUrl: String,
    private val client: OkHttpClient = defaultClient(),
    private val token: () -> String? = { null },
) {
    private val base = baseUrl.trimEnd('/')

    private val json = "application/json; charset=utf-8".toMediaType()

    init { require(base.startsWith("https://") || ALLOW_TEST_HTTP) { "只允许 HTTPS" } }

    // ---------- 公开接口 ----------
    suspend fun config(): ServerConfig = AccountJson.config(get("/v2/config", auth = false))
    suspend fun legal(): LegalBundle = AccountJson.legal(get("/v2/legal", auth = false))

    suspend fun register(
        loginName: String, password: String, inviteCode: String?, consent: LegalBundle, info: ClientInfo,
    ): AuthResult = AccountJson.auth(post("/account/register", JSONObject()
        .put("login_name", loginName).put("password", password)
        .apply { if (!inviteCode.isNullOrBlank()) put("invite_code", inviteCode.trim()) }
        .put("age_confirmed", true)
        .put("consent", consentJson(consent)).put("client", clientJson(info)), auth = false))

    suspend fun login(loginName: String, password: String, info: ClientInfo, evictDeviceId: String? = null): AuthResult =
        AccountJson.auth(post("/account/login", JSONObject()
            .put("login_name", loginName).put("password", password).put("client", clientJson(info))
            .apply { if (evictDeviceId != null) put("evict_device_id", evictDeviceId) }, auth = false))

    suspend fun recover(loginName: String, recoveryCode: String, newPassword: String): String =
        post("/account/recover", JSONObject().put("login_name", loginName)
            .put("recovery_code", recoveryCode.trim()).put("new_password", newPassword), auth = false)
            .getString("recovery_code")

    // ---------- 需要登录 ----------
    suspend fun me(): MeInfo = AccountJson.me(get("/account/me"))
    suspend fun logout() { send("POST", "/account/logout", "{}".toRequestBody(json), auth = true) }
    suspend fun consent(consent: LegalBundle) { send("POST", "/account/consent", consentJson(consent).toString().toRequestBody(json), auth = true) }
    suspend fun recoveryAck() { send("POST", "/account/recovery/ack", "{}".toRequestBody(json), auth = true) }
    suspend fun devices(): List<DeviceInfo> = DeviceInfo.parseList(get("/account/devices").optJSONArray("devices"))
    suspend fun revokeDevice(id: String) { send("DELETE", "/account/devices/" + java.net.URLEncoder.encode(id, "UTF-8"), null, auth = true) }
    suspend fun revokeOthers(): Int = post("/account/devices/revoke-others", JSONObject()).optInt("revoked")
    suspend fun changePassword(old: String, new: String) {
        send("POST", "/account/password", JSONObject().put("old_password", old).put("new_password", new).toString().toRequestBody(json), auth = true)
    }
    /** 注销账号：返回物理删除时间（秒，冷静期结束）。 */
    suspend fun deleteAccount(password: String): Long =
        JSONObject(send("DELETE", "/account/me", JSONObject().put("password", password).toString().toRequestBody(json), auth = true)).optLong("delete_at")
    suspend fun heartbeat(info: ClientInfo) { send("POST", "/v2/heartbeat", JSONObject().put("client", clientJson(info)).toString().toRequestBody(json), auth = true) }

    /** 联系作者。`idempotencyKey` 同一条反馈重试时保持不变，服务端据此去重。 */
    suspend fun feedback(
        text: String, category: String, contact: String?, diagnosticsJson: String?,
        idempotencyKey: String, images: List<Pair<String, ByteArray>> = emptyList(),
    ): String {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("text", text).addFormDataPart("category", category)
            .addFormDataPart("idempotency_key", idempotencyKey)
            .apply {
                if (!contact.isNullOrBlank()) addFormDataPart("contact", contact)
                if (!diagnosticsJson.isNullOrBlank()) addFormDataPart("diagnostics", diagnosticsJson)
                images.forEachIndexed { i, (mime, bytes) ->
                    addFormDataPart("images", "image$i", bytes.toRequestBody(mime.toMediaType()))
                }
            }.build()
        return JSONObject(send("POST", "/v2/feedback", body, auth = true)).getString("id")
    }

    // ---------- 内部 ----------
    private fun consentJson(c: LegalBundle) = JSONObject()
        .put("user_agreement", JSONObject().put("version", c.userAgreement.version).put("sha256", c.userAgreement.sha256))
        .put("privacy_policy", JSONObject().put("version", c.privacyPolicy.version).put("sha256", c.privacyPolicy.sha256))

    private fun clientJson(i: ClientInfo) = JSONObject()
        .put("channel", "android").put("app_version", i.appVersion).put("os", i.os).put("device", i.device)

    private suspend fun get(path: String, auth: Boolean = true) = JSONObject(send("GET", path, null, auth))
    private suspend fun post(path: String, body: JSONObject, auth: Boolean = true) =
        JSONObject(send("POST", path, body.toString().toRequestBody(json), auth))

    /** 返回响应正文（204 为空串）；非 2xx 抛 [ApiException]，网络问题抛 [IOException]。 */
    private suspend fun send(method: String, path: String, body: RequestBody?, auth: Boolean): String = withContext(Dispatchers.IO) {
        val url = (base + path).toHttpUrl()
        val builder = Request.Builder().url(url).header("Accept", "application/json").method(method, body)
        if (auth) {
            val t = token() ?: throw ApiException(401, "unauthenticated", AccountJson.defaultMessage(401))
            builder.header("X-GetMedia-Session", t)
        }
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.isSuccessful) text
            else throw AccountJson.error(response.code, text, response.header("Retry-After"))
        }
    }

    companion object {
        /** 仅供单元测试用 MockWebServer（明文）；正式构建里永远是 false。 */
        @Volatile internal var ALLOW_TEST_HTTP = false

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false).followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
