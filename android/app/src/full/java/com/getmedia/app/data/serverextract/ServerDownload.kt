package com.getmedia.app.data.serverextract

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException

/** 下载完成的结果：文件已落到暂存路径、长度校验通过。 */
data class DownloadedFile(val file: File, val bytes: Long, val sha256: String)

/**
 * 服务器媒体流的下载与续传（P0-1 设计 §5「下载中断」）。
 *
 * 只做一件事：把 `GET /v2/media/{id}` 的字节稳定地落进一个 `.part` 文件。
 * 断流就用 `Range: bytes=<已有长度>-` 接着要；服务器不认 Range（回 200）就把本地截断重写；
 * 长度不符重试到上限后报错——**绝不把没校验完的文件交出去**，落库由调用方在校验通过后做。
 *
 * 失败按"`.part` 还能不能复用"分两类，这是本类的核心约定：
 * - **网络类**（读流时断流、读超时）：已落盘的字节是真实前缀，**保留** `.part` 供下一轮续传；
 *   用尽次数后抛 [ServerProblem.NETWORK]，原始 `IOException` 不外泄。
 * - **内容已确认不可用**（416 断点失效、长度不符到上限、空文件）：续传只会把错误拼进文件，
 *   一律删 `.part`（416 当场删，其余在重试用尽时删）。
 */
object ServerDownload {

    /** 一条媒体最多试 3 次：首次 + 两次续传。 */
    const val MAX_ATTEMPTS = 3

    /**
     * 有 `.part` 时从哪个字节续。
     *
     * `expected != null && existing >= expected` 说明本地这份比服务器声明的还长或刚好等长，
     * 那它不可能是"下载到一半"，一律丢重下（返回 0），避免在错误数据后面继续追加。
     */
    fun resumeOffset(existing: Long, expected: Long?): Long = when {
        existing <= 0 -> 0
        expected != null && existing >= expected -> 0
        else -> existing
    }

    /**
     * `Content-Range: bytes 100-999/1000` → 1000；不带 total（`bytes 100-*\/1000` 之外的形式）返回 null。
     * 服务器若没给 Content-Range，就退回 `offset + Content-Length`。
     */
    fun totalBytes(contentRange: String?, contentLength: Long, offset: Long): Long? {
        val total = contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
        if (total != null && total > 0) return total
        if (contentLength > 0) return offset + contentLength
        return null
    }

    /** 单次尝试的结果，只决定"下一轮要不要续、`.part` 留不留"。 */
    private sealed interface Attempt {
        class Done(val file: DownloadedFile) : Attempt

        /** 可再试：`part` 里是有效前缀（`contentSuspect = false`），或内容可疑（`true`，用尽时删）。 */
        class Retry(
            val reason: String,
            val network: Boolean,
            val contentSuspect: Boolean = false,
        ) : Attempt

        /** 内容已确认不可用：`.part` 必须删。 */
        class Invalid(val reason: String) : Attempt
    }

    suspend fun fetch(
        api: MediaGateway,
        trackId: Long,
        part: File,
        expected: Long?,
        storedOnly: Boolean = false,
        onProgress: (downloaded: Long, total: Long?) -> Unit = { _, _ -> },
        cancelled: () -> Boolean = { false },
    ): DownloadedFile = withContext(Dispatchers.IO) {
        var lastReason = ""
        var network = false
        var contentSuspect = false
        repeat(MAX_ATTEMPTS) { attempt ->
            val offset = resumeOffset(part.length(), expected)
            if (offset == 0L && part.exists() && part.length() > 0) part.delete()

            val result = try {
                downloadOnce(api, trackId, part, offset, expected, storedOnly, onProgress, cancelled)
            } catch (e: IOException) {
                // 断流、读超时：已落盘的字节是真实前缀，保留给下一轮用 Range 续；
                // 不把原始 IOException 抛给上层（上层只认 ServerMediaException 与中文文案）。
                Attempt.Retry(networkReason(e), network = true)
            }
            when (result) {
                is Attempt.Done -> return@withContext result.file
                is Attempt.Invalid -> {
                    lastReason = result.reason
                    network = false
                    contentSuspect = true
                    part.delete()
                }
                is Attempt.Retry -> {
                    lastReason = result.reason
                    network = result.network
                    contentSuspect = result.contentSuspect
                }
            }
            if (attempt < MAX_ATTEMPTS - 1) delay(500L * (attempt + 1))
        }
        if (contentSuspect) part.delete()
        throw ServerMediaException(
            if (network) ServerProblem.NETWORK else ServerProblem.UNAVAILABLE,
            if (network) "网络中断，已重试 $MAX_ATTEMPTS 次仍失败；已下载的部分已保留，可再次提取续传"
            else "下载失败，已重试 $MAX_ATTEMPTS 次仍失败",
            detail = lastReason,
        )
    }

    /**
     * 一次完整的请求 + 写盘。IOException 一律向上抛（由 [fetch] 归类为网络失败并保留 `.part`），
     * 其余终态错误（登录、越权、媒体不存在）直接抛 [ServerMediaException]，不重试。
     */
    private fun downloadOnce(
        api: MediaGateway,
        trackId: Long,
        part: File,
        offset: Long,
        expected: Long?,
        storedOnly: Boolean,
        onProgress: (downloaded: Long, total: Long?) -> Unit,
        cancelled: () -> Boolean,
    ): Attempt {
        val response: Response = if (storedOnly) api.openStoredMedia(trackId, offset) else api.openMedia(trackId, offset)
        response.use { r ->
            if (r.code == 416) {
                // 本地断点越界：这份 `.part` 不能用，删掉从 0 重来
                part.delete()
                return Attempt.Retry("断点位置失效", network = false)
            }
            if (!r.isSuccessful) {
                throw ServerMediaJson.error(
                    r.code,
                    r.body?.string().orEmpty(),
                    ServerMediaJson.retryAfterSeconds(r.header("Retry-After")),
                )
            }
            if (storedOnly && r.header("X-GetMedia-Media-Source") != "stored") {
                throw ServerMediaException(ServerProblem.SERVER, "服务器尚未启用已存文件下载",
                    code = "stored_mode_unsupported")
            }
            val body = r.body ?: throw ServerMediaException(
                ServerProblem.SERVER, "服务器没有返回媒体内容")
            var total = totalBytes(r.header("Content-Range"), body.contentLength(), offset)
            var start = offset
            if (storedOnly && r.code == 206 &&
                r.header("Content-Range")?.startsWith("bytes $offset-") != true) {
                return Attempt.Invalid("服务器返回的断点位置不符")
            }
            if (r.code != 206 && start > 0) {
                // 服务器忽略了 Range：从头写，否则文件会变成"半段 + 全段"
                RandomAccessFile(part, "rw").use { it.setLength(0) }
                start = 0
                total = body.contentLength().takeIf { it > 0 }
            }
            var written = start
            RandomAccessFile(part, "rw").use { out ->
                out.seek(start)
                val buffer = ByteArray(64 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        // 取消只能在这里检查：读流是阻塞 IO，协程取消不会打断它，
                        // 光 cancel Job 要等到这一整段（甚至整条）读完才生效。
                        // 抛 CancellationException 而不是自定义异常：语义就是"这个协程不要了"，
                        // 上层 `catch (IOException)` 不会误吞，`response.use` 也照常关连接。
                        if (cancelled()) throw CancellationException("下载已取消")
                        val count = input.read(buffer)
                        if (count < 0) break
                        out.write(buffer, 0, count)
                        written += count
                        onProgress(written, total ?: expected)
                    }
                }
            }
            val size = part.length()
            if (size <= 0) return Attempt.Invalid("下载结果为空")
            val want = expected ?: total
            if (want != null && size != want) {
                // 保留（可能是被客户端读干净了的短包，续一次能补齐），但可确认这份内容不对
                return Attempt.Retry("长度不符：本地 $size / 预期 $want", network = false, contentSuspect = true)
            }
            return Attempt.Done(DownloadedFile(part, size, sha256(part)))
        }
    }

    private fun networkReason(e: IOException): String {
        val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
        return "网络中断：$detail"
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
