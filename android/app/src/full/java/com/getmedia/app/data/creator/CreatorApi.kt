package com.getmedia.app.data.creator

import com.getmedia.app.account.AccountApi
import com.getmedia.app.account.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

/**
 * 博主批量接口。只调用 GetMedia 自己的服务器（编译期地址 + 现有会话令牌），
 * 不直连任何平台——列表抓取需要服务端出口和服务端 cookie，手机端做不稳定。
 */
class CreatorApi(
    baseUrl: String = com.getmedia.app.BuildConfig.SERVER_BASE,
    private val client: OkHttpClient = AccountApi.defaultClient(),
    private val token: () -> String? = SessionStore::token,
) {
    private val base = baseUrl.trimEnd('/')
    private val json = "application/json; charset=utf-8".toMediaType()

    init {
        require(base.startsWith("https://") || AccountApi.ALLOW_TEST_HTTP) { "只允许 HTTPS" }
    }

    suspend fun preview(input: String, platform: CreatorPlatform?, start: Int, count: Int): CreatorPreview {
        val body = JSONObject().put("url", input.trim()).put("start", start).put("count", count)
            .put("include_stats", true).put("include_comments", false)
        if (platform != null) body.put("platform", platform.wire)
        val text = post("/v2/creators/preview", body.toString())
        return CreatorJson.preview(text)
    }

    private suspend fun post(path: String, payload: String): String = withContext(Dispatchers.IO) {
        val session = token() ?: throw CreatorException(CreatorProblem.LOGIN_REQUIRED, "请先登录再使用博主批量提取")
        val request = Request.Builder()
            .url((base + path).toHttpUrl())
            .header("Accept", "application/json")
            .header("X-GetMedia-Session", session)
            .post(payload.toRequestBody(json))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) text
                else throw CreatorJson.error(response.code, text)
            }
        } catch (e: IOException) {
            throw CreatorException(CreatorProblem.NETWORK, "网络不可用，请检查连接后重试")
        }
    }

    companion object {
        /** 单页上限由服务端决定，这里只是防呆默认值。 */
        const val DEFAULT_COUNT = 10
        const val MAX_COUNT = 50
        const val MAX_START = 500
    }
}
