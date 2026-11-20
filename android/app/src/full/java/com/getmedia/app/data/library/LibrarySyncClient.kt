package com.getmedia.app.data.library

import com.getmedia.app.BuildConfig
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
 * 上传通道：`POST /v2/tracks`（P0-2 阶段 1 的服务端接口）。
 *
 * 与拉取通道分成两个接口，不是一个带两个方法的接口：阶段 2 的用例断言过"拉取只发 GET"，
 * 那种"只读"的保证靠类型隔离最省事——[LibrarySyncEngine] 手上根本没有 [LibraryUploadTransport]。
 */
interface LibraryUploadTransport {
    suspend fun create(entry: UploadEntry): CreatedTrack
}

/**
 * 真实实现。`owner` 通道、`X-GetMedia-Session` 认证，与服务器提取同一套约定。
 *
 * 服务端限流 60/分：撞上就按 `Retry-After` 退避一次，再撞就整批停（由 [LibraryUploader] 决定），
 * 这里只负责把 [LibrarySyncException] 分类抛出去。
 */
class LibrarySyncClient(
    baseUrl: String = BuildConfig.OWNER_SERVER_BASE,
    private val client: OkHttpClient = AccountApi.defaultClient(),
    private val token: () -> String? = SessionStore::token,
) : LibraryUploadTransport {

    private val base = baseUrl.trimEnd('/')
    private val json = "application/json; charset=utf-8".toMediaType()

    init { require(base.startsWith("https://") || AccountApi.ALLOW_TEST_HTTP) { "只允许 HTTPS" } }

    override suspend fun create(entry: UploadEntry): CreatedTrack = withContext(Dispatchers.IO) {
        // 八个字段，多一个都不带：服务端开了 extra="forbid"，多字段直接 422
        val body = JSONObject()
            .put("platform", entry.platform)
            .put("video_id", entry.videoId)
            .put("page", entry.page)
            .put("media_type", entry.mediaType)
            .put("title", entry.title)
            .put("uploader", entry.uploader ?: JSONObject.NULL)
            .put("duration_sec", entry.durationSec ?: JSONObject.NULL)
            .put("source_url", entry.sourceUrl)
            .toString()
        val request = Request.Builder()
            .url((base + PATH).toHttpUrl())
            .header("Accept", "application/json")
            .header("X-GetMedia-Session", session())
            .post(body.toRequestBody(json))
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
                LibrarySyncJson.created(text)
            }
        } catch (e: IOException) {
            // 只捕 IO：上面主动抛的 LibrarySyncException 要原样穿出去，不能被当成网络问题重试
            throw LibrarySyncException(
                LibrarySyncProblem.NETWORK, "网络不可用，请检查连接后重试", detail = e.message.orEmpty(),
            )
        }
    }

    private fun session(): String =
        token() ?: throw LibrarySyncException(LibrarySyncProblem.LOGIN_REQUIRED, "请先登录")

    companion object {
        const val PATH = "/v2/tracks"
    }
}
