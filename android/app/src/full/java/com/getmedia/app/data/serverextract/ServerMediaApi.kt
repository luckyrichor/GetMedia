package com.getmedia.app.data.serverextract

import com.getmedia.app.BuildConfig
import com.getmedia.app.account.AccountApi
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.local.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 服务器提取接口（P0-1）。只走 **owner 通道** `/getmedia/owner/api`。
 *
 * 与 [com.getmedia.app.data.creator.CreatorApi] 同一套约定：编译期地址、`X-GetMedia-Session`、
 * 不使用 Cookie（边缘会改写 cookie path）。区别只是这里的媒体流要支持 `Range`，
 * 所以读超时按 nginx 的 `proxy_read_timeout 120s` 放宽——那是**读间隔**上限，不是总时长。
 */
class ServerMediaApi(
    baseUrl: String = BuildConfig.OWNER_SERVER_BASE,
    private val client: OkHttpClient = AccountApi.defaultClient(),
    private val token: () -> String? = SessionStore::token,
) : MediaGateway {
    private val base = baseUrl.trimEnd('/')
    private val json = "application/json; charset=utf-8".toMediaType()
    private val streamClient = client.newBuilder().readTimeout(120, TimeUnit.SECONDS).build()

    init {
        require(base.startsWith("https://") || AccountApi.ALLOW_TEST_HTTP) { "只允许 HTTPS" }
    }

    suspend fun resolve(text: String, kind: MediaKind): ResolveInfo {
        val body = JSONObject().put("text", text.trim()).put("media_type", kind.wire)
        return ServerMediaJson.resolve(call(post("/v2/resolve", body.toString())))
    }

    /** 建任务。**202 只代表已排队**，成功与否要看 [job] 轮询。 */
    override suspend fun startExtract(input: String, kind: MediaKind, storage: String): ExtractJob =
        startExtract(input, kind, storage, target = null)

    override suspend fun startTargetedExtract(input: String, kind: MediaKind, target: ExtractTarget): ExtractJob =
        startExtract(input, kind, Storage.CACHE, target)

    private suspend fun startExtract(input: String, kind: MediaKind, storage: String, target: ExtractTarget?): ExtractJob {
        val body = JSONObject()
            .put("input", input.trim())
            .put("media_type", kind.wire)
            .put("storage", storage)
        if (target != null) {
            body.put("target", JSONObject().put("track_id", target.trackId).put("platform", target.platform)
                .put("video_id", target.videoId).put("page", target.page))
        }
        return ServerMediaJson.extract(call(post("/v2/extract", body.toString())))
    }

    override suspend fun job(id: Long): ExtractJob = ServerMediaJson.job(call(get("/v2/extraction-jobs/$id")))

    override suspend fun track(id: Long): ServerTrack = ServerMediaJson.track(call(get("/v2/tracks/$id")))

    /**
     * 打开媒体流。**调用方负责 `use {}` 关闭**——这是唯一不在这里读完响应的接口。
     *
     * `offset > 0` 时带 `Range: bytes=offset-`；服务器若忽略 Range 直接回 200，
     * 由 [ServerDownload] 把本地 `.part` 截断重写，不在这里猜。
     */
    override fun openMedia(trackId: Long, offset: Long): Response = openMedia(trackId, offset, storedOnly = false)

    override fun openStoredMedia(trackId: Long, offset: Long): Response = openMedia(trackId, offset, storedOnly = true)

    private fun openMedia(trackId: Long, offset: Long, storedOnly: Boolean): Response {
        val url = (base + "/v2/media/$trackId").toHttpUrl().newBuilder()
        if (storedOnly) url.addQueryParameter("stored_only", "1")
        val builder = Request.Builder()
            .url(url.build())
            .header("X-GetMedia-Session", session())
            .header("Accept-Encoding", "identity")
        if (offset > 0) builder.header("Range", "bytes=$offset-")
        val request = builder.get().build()
        return try {
            streamClient.newCall(request).execute()
        } catch (e: IOException) {
            throw network(e)
        }
    }

    fun mediaUrl(trackId: Long): String = base + "/v2/media/$trackId"

    // ---------- 内部 ----------

    private fun session(): String =
        token() ?: throw ServerMediaException(ServerProblem.LOGIN_REQUIRED, "请先登录再使用服务器提取")

    private fun post(path: String, payload: String): Request = Request.Builder()
        .url((base + path).toHttpUrl())
        .header("Accept", "application/json")
        .header("X-GetMedia-Session", session())
        .post(payload.toRequestBody(json))
        .build()

    private fun get(path: String): Request = Request.Builder()
        .url((base + path).toHttpUrl())
        .header("Accept", "application/json")
        .header("X-GetMedia-Session", session())
        .get()
        .build()

    private suspend fun call(request: Request): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ServerMediaJson.error(
                        response.code,
                        text,
                        ServerMediaJson.retryAfterSeconds(response.header("Retry-After")),
                    )
                }
                text
            }
        } catch (e: IOException) {
            throw network(e)
        }
    }

    private fun network(e: IOException) =
        ServerMediaException(ServerProblem.NETWORK, "网络不可用，请检查连接后重试", detail = e.message.orEmpty())

    companion object {
        /** 服务器落一份临时文件，手机用 Range 续传；到期由 `prune_cache` 自动清（设计 §3）。 */
        const val STORAGE_CACHE = Storage.CACHE
        /** 配额不足时的退化选项：不落盘，当场解析后中转，无法续传。 */
        const val STORAGE_METADATA = Storage.METADATA

        private val MediaKind.wire: String
            get() = if (this == MediaKind.VIDEO) "video" else "audio"
    }
}
