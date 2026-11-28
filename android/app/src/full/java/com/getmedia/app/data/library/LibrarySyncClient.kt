package com.getmedia.app.data.library

import com.getmedia.app.BuildConfig
import com.getmedia.app.account.AccountApi
import com.getmedia.app.account.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.ForwardingSink
import okio.buffer
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

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
 * 本机文件上传通道：`POST /v2/tracks/{id}/file`（multipart）。
 *
 * 与 [LibraryUploadTransport] 分开：那条路只传 8 个字段的元数据，这条路要传几百 MB 的
 * 二进制，超时、断流、限流的表现完全不同，混在一个接口里会让两边的错误处理互相牵制。
 */
interface LocalFileUploadTransport {
    /**
     * 把 [file] 传到 `POST /v2/tracks/{id}/file`。
     *
     * [onProgress] 每写出一段就回调一次（已写字节、文件总字节）。**按文件本身算**，
     * 不含 multipart 的头与边界——界面要显示的是"这个文件传到哪了"。
     */
    suspend fun uploadFile(
        trackId: Long,
        file: File,
        onProgress: (sent: Long, total: Long) -> Unit = { _, _ -> },
    ): CloudTrack
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
) : LibraryUploadTransport, LibraryWriteTransport, MetaWriteTransport, LocalFileUploadTransport {

    private val base = baseUrl.trimEnd('/')
    private val json = "application/json; charset=utf-8".toMediaType()

    /** 上传文件用二进制流：服务端不解析内容，类型只影响它给的后缀兜底。 */
    private val octet = "application/octet-stream".toMediaType()

    /**
     * 传文件单独一个客户端：默认的 30 秒写超时是按"几百字节 JSON"定的，
     * 一个 300 MB 的视频在移动网络上要传好几分钟，用默认值必然半路失败。
     */
    private val uploadClient: OkHttpClient by lazy {
        client.newBuilder()
            .writeTimeout(15, TimeUnit.MINUTES)
            .readTimeout(2, TimeUnit.MINUTES)
            .build()
    }

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

    override suspend fun uploadFile(
        trackId: Long,
        file: File,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): CloudTrack = withContext(Dispatchers.IO) {
        // 文件名只用来给后缀：服务端会自己生成 uuid 前缀的落盘名，不看这里的名字
        val size = file.length()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, progressBody(file.asRequestBody(octet), size, onProgress))
            .build()
        val request = Request.Builder()
            .url((base + PATH + "/" + trackId + "/file").toHttpUrl())
            .header("Accept", "application/json")
            .header("X-GetMedia-Session", session())
            .post(body)
            .build()
        call(uploadClient, request) { text -> LibrarySyncJson.singleTrack(text) }
    }

    /**
     * 把请求体包一层，每写出一段就报一次进度。
     *
     * 为什么包在**文件那一部分**而不是整个 multipart：multipart 的 `contentLength` 含边界
     * 与表单头，用它当分母会让进度从 0.1% 起步、结尾差几个字节；包住文件本身，分母就是
     * 文件长度，界面上那个百分比才和"这个文件传到哪了"对得上。
     *
     * 只在 okio 真正把字节交给 socket 时回调（`ForwardingSink.write` 之后），
     * 不是"从内存读出来"就算——否则进度条会先跑到 100% 再卡住等网络。
     */
    private fun progressBody(
        delegate: RequestBody,
        total: Long,
        onProgress: (sent: Long, total: Long) -> Unit,
    ): RequestBody = object : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) {
            val forwarding = object : ForwardingSink(sink) {
                private var written = 0L
                override fun write(source: Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    written += byteCount
                    onProgress(written, total)
                }
            }
            val buffered = forwarding.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
        }
    }

    private fun <T> call(request: Request, parse: (String) -> T): T = call(client, request, parse)

    private fun <T> call(http: OkHttpClient, request: Request, parse: (String) -> T): T = try {
        http.newCall(request).execute().use { response ->
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
