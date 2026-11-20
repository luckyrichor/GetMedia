package com.getmedia.app.data.library

import com.getmedia.app.BuildConfig
import com.getmedia.app.account.AccountApi
import com.getmedia.app.account.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 拉取通道。抽成接口只有一个目的：单测要能断言"同步只发 GET"，
 * 以及在不碰网络的前提下喂进指定的分页/错误序列。
 */
interface LibrarySyncTransport {
    suspend fun pull(sinceSeq: Long, limit: Int): PullPage
}

/**
 * `GET /v2/sync?since_seq=&limit=`（P2a 就有，含墓碑与分页，服务端不用改）。
 *
 * 走 **owner 通道**，与服务器提取同一个 base；认证头同样是 `X-GetMedia-Session`，
 * 不用 Cookie（边缘会改写 cookie path）。
 */
class LibrarySyncApi(
    baseUrl: String = BuildConfig.OWNER_SERVER_BASE,
    private val client: OkHttpClient = AccountApi.defaultClient(),
    private val token: () -> String? = SessionStore::token,
) : LibrarySyncTransport {

    private val base = baseUrl.trimEnd('/')

    init { require(base.startsWith("https://") || AccountApi.ALLOW_TEST_HTTP) { "只允许 HTTPS" } }

    override suspend fun pull(sinceSeq: Long, limit: Int): PullPage = withContext(Dispatchers.IO) {
        val url = (base + PATH).toHttpUrl().newBuilder()
            .addQueryParameter("since_seq", sinceSeq.coerceAtLeast(0).toString())
            .addQueryParameter("limit", limit.coerceIn(1, LibrarySyncJson.MAX_PAGE).toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-GetMedia-Session", session())
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw LibrarySyncJson.error(
                        response.code, text,
                        LibrarySyncJson.retryAfterSeconds(response.header("Retry-After")),
                    )
                }
                LibrarySyncJson.page(text)
            }
        } catch (e: IOException) {
            // 只捕 IO：上面主动抛的 LibrarySyncException 必须原样穿出去，不能被当成网络问题重试
            throw LibrarySyncException(
                LibrarySyncProblem.NETWORK, "网络不可用，请检查连接后重试", detail = e.message.orEmpty(),
            )
        }
    }

    private fun session(): String =
        token() ?: throw LibrarySyncException(LibrarySyncProblem.LOGIN_REQUIRED, "请先登录")

    companion object {
        const val PATH = "/v2/sync"
    }
}
