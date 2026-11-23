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
 * 阶段 5 的写通道：条件删除、条件改名、单条核对。
 *
 * 与上传分开是因为它们**必须带 If-Match**，而且失败语义不同（归档保护、版本冲突、
 * 明确的 404 各自有不同处理）。旧服务器没有这两条路径时返回裸 404，客户端保持队列、
 * 不降级到旧的无条件写接口。
 */
interface LibraryWriteTransport {
    suspend fun delete(trackId: Long, expectedSeq: Long): DeleteResult
    suspend fun patchTitle(trackId: Long, title: String, expectedSeq: Long): CloudTrack
    suspend fun detail(trackId: Long): CloudTrack
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
) : LibraryUploadTransport, LibraryWriteTransport, MetaWriteTransport {

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

    override suspend fun delete(trackId: Long, expectedSeq: Long): DeleteResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url((base + SYNC_PATH + trackId).toHttpUrl())
            .header("Accept", "application/json")
            .header("If-Match", "\"$expectedSeq\"")
            .header("X-GetMedia-Session", session())
            .delete()
            .build()
        call(request) { text -> LibrarySyncJson.deleteResult(text) }
    }

    override suspend fun patchTitle(trackId: Long, title: String, expectedSeq: Long): CloudTrack =
        withContext(Dispatchers.IO) {
            val body = JSONObject().put("title", title).toString()
            val request = Request.Builder()
                .url((base + SYNC_PATH + trackId).toHttpUrl())
                .header("Accept", "application/json")
                .header("If-Match", "\"$expectedSeq\"")
                .header("X-GetMedia-Session", session())
                .patch(body.toRequestBody(json))
                .build()
            call(request) { text -> LibrarySyncJson.singleTrack(text) }
        }

    override suspend fun detail(trackId: Long): CloudTrack = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url((base + PATH + "/" + trackId).toHttpUrl())
            .header("Accept", "application/json")
            .header("X-GetMedia-Session", session())
            .get()
            .build()
        call(request) { text -> LibrarySyncJson.singleTrack(text) }
    }

    /**
     * 收藏与类别：走网页端那条 `PATCH /v2/tracks/{id}`。
     *
     * 只带这两个字段，标题/置顶一概不碰——多带一个字段就可能在另一端覆盖别人刚改的东西。
     * 这条路没有 If-Match：收藏是"最后一次操作算数"的语义，冲突时不需要让用户裁决。
     */
    override suspend fun patchMeta(trackId: Long, favorite: Boolean, category: String): CloudTrack =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("favorite", favorite)
                .put("category", category)
                .toString()
            val request = Request.Builder()
                .url((base + PATH + "/" + trackId).toHttpUrl())
                .header("Accept", "application/json")
                .header("X-GetMedia-Session", session())
                .patch(body.toRequestBody(json))
                .build()
            call(request) { text -> LibrarySyncJson.singleTrack(text) }
        }

    private fun <T> call(request: Request, parse: (String) -> T): T = try {
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw LibrarySyncJson.error(
                    response.code, text,
                    LibrarySyncJson.retryAfterSeconds(response.header("Retry-After")),
                )
            }
            parse(text)
        }
    } catch (e: IOException) {
        throw LibrarySyncException(
            LibrarySyncProblem.NETWORK, "网络不可用，请检查连接后重试", detail = e.message.orEmpty(),
        )
    }

    private fun session(): String =
        token() ?: throw LibrarySyncException(LibrarySyncProblem.LOGIN_REQUIRED, "请先登录")

    companion object {
        const val PATH = "/v2/tracks"
        const val SYNC_PATH = "/v2/sync/tracks/"
    }
}
