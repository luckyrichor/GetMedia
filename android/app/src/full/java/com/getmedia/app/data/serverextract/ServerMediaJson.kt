package com.getmedia.app.data.serverextract

import org.json.JSONObject

/**
 * 服务端 JSON → 模型，以及 HTTP 状态 / 业务码 → 中文文案。
 *
 * 单独成对象，纯字符串进出：单测不需要网络、不需要 Android，把"哪个码给哪句话"
 * 这类容易写错的映射钉在固定用例上。
 */
object ServerMediaJson {

    fun resolve(text: String): ResolveInfo {
        val root = object0(text)
        val platform = root.optString("platform")
        val videoId = root.optString("video_id")
        if (platform.isBlank() || videoId.isBlank()) {
            throw ServerMediaException(ServerProblem.SERVER, "服务器返回的解析结果不完整")
        }
        return ResolveInfo(
            platform = platform,
            videoId = videoId,
            title = root.optString("title").ifBlank { "未命名作品" },
            durationSec = root.number("duration_sec"),
            uploader = root.optString("uploader").ifBlank { null },
            coverUrl = root.optString("cover_url").ifBlank { null },
            sourceUrl = root.optString("source_url").ifBlank { null },
            estimatedBytes = root.optLong("estimated_bytes", 0),
        )
    }

    /** 202 的响应只有 `{id, status}`，其余字段全空，直接复用 [job]。 */
    fun extract(text: String): ExtractJob = job(text)

    fun job(text: String): ExtractJob {
        val root = object0(text)
        val id = root.optLong("id", 0)
        if (id <= 0) throw ServerMediaException(ServerProblem.SERVER, "服务器没有返回任务编号")
        return ExtractJob(
            id = id,
            status = JobStatus.of(root.optString("status")),
            mediaType = root.optString("media_type"),
            storage = root.optString("storage"),
            platform = root.optString("platform").ifBlank { null },
            videoId = root.optString("video_id").ifBlank { null },
            trackId = root.optLong("track_id", 0).takeIf { it > 0 },
            errorCode = root.optString("error_code").ifBlank { null },
            errorDetail = root.optString("error_detail").ifBlank { null },
        )
    }

    fun track(text: String): ServerTrack {
        val root = object0(text)
        val id = root.optLong("id", 0)
        if (id <= 0) throw ServerMediaException(ServerProblem.SERVER, "服务器没有返回媒体编号")
        return ServerTrack(
            id = id,
            platform = root.optString("platform"),
            videoId = root.optString("video_id"),
            page = root.optInt("page", 1).coerceAtLeast(1),
            title = root.optString("title").ifBlank { "未命名作品" },
            uploader = root.optString("uploader").ifBlank { null },
            durationSec = root.number("duration_sec"),
            coverUrl = root.optString("cover_url").ifBlank { null },
            sourceUrl = root.optString("source_url").orEmpty(),
            storage = root.optString("storage"),
            bytes = root.optLong("bytes", 0).takeIf { it > 0 },
            updatedSeq = root.optLong("updated_seq", 0),
            updatedAtSec = root.optLong("updated_at", 0).takeIf { it > 0 },
            hasFile = root.optBoolean("has_file"),
            mediaType = root.optString("media_type"),
            deleted = root.optBoolean("deleted"),
            sha256 = root.optString("sha256").takeIf { !root.isNull("sha256") && it.isNotBlank() },
        )
    }

    /**
     * HTTP 失败 → 异常。
     *
     * owner 通道在边缘把 401 / 403 / 非 owner 一律压成**无 body 的裸 404**，
     * 所以"404 且没有 code"只能提示"无权使用或登录已过期"，不能断言是哪种。
     */
    fun error(status: Int, body: String, retryAfterSeconds: Long? = null): ServerMediaException {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val code = json?.optString("code").orEmpty()
        val detail = json?.optString("detail").orEmpty()
        return when {
            status == 401 -> ServerMediaException(
                ServerProblem.LOGIN_REQUIRED, "登录已失效，请重新登录", code, detail)
            status == 404 && code.isBlank() -> ServerMediaException(
                ServerProblem.NOT_OWNER, "当前账号无权使用，或登录已过期，请重新登录")
            status == 404 -> failure(code, detail.ifBlank { "服务器上找不到这个对象" })
            status == 429 -> ServerMediaException(
                ServerProblem.RATE_LIMITED,
                "请求过于频繁，稍后自动重试",
                code,
                if (retryAfterSeconds != null) "retry_after=${retryAfterSeconds}s" else detail,
            )
            status == 416 -> ServerMediaException(
                ServerProblem.UNAVAILABLE, "断点位置已失效，将重新下载", code, detail)
            status in 400..499 -> ServerMediaException(
                ServerProblem.INPUT, detail.ifBlank { "请求被服务器拒绝" }, code, detail)
            else -> ServerMediaException(
                ServerProblem.SERVER, detail.ifBlank { "服务器暂时不可用，请稍后重试" }, code, detail)
        }
    }

    /**
     * 任务 `failed` 或 404 的业务码 → 异常。
     *
     * 文案直接对着 P0-1 设计 §5 的表写；`quota_exceeded` / `storage_disabled`
     * 单独归到 [ServerProblem.STORAGE_FULL]，编排层据此换 `metadata` 再试一次。
     */
    fun failure(code: String, detail: String?): ServerMediaException {
        val extra = detail.orEmpty().trim()
        val (problem, message) = when (code) {
            "unsupported_link" -> ServerProblem.INPUT to "识别不出这个链接，请确认是作品页"
            "resolve_failed", "playurl_failed", "playurl_empty" ->
                ServerProblem.UNAVAILABLE to "平台拒绝或没有可用分片，可稍后重试"
            "download_failed", "media_upstream_failed" ->
                ServerProblem.UNAVAILABLE to "服务器下载失败，可重试"
            "quota_exceeded", "storage_disabled" ->
                ServerProblem.STORAGE_FULL to "服务器存储不足，改用仅元数据重试"
            "job_not_found" -> ServerProblem.JOB_GONE to "任务已不存在（服务端重启或已清理）"
            "track_not_found" -> ServerProblem.JOB_GONE to "服务器上没有这条媒体"
            else -> ServerProblem.SERVER to (extra.ifBlank { "服务器处理失败，可重试" })
        }
        return ServerMediaException(problem, message, code, extra)
    }

    /** 429 的 `Retry-After` 只按秒解析；HTTP 日期形式不支持，缺省交给调用方退避。 */
    fun retryAfterSeconds(raw: String?): Long? = raw?.trim()?.toLongOrNull()?.takeIf { it in 1..60 }

    private fun object0(text: String): JSONObject =
        runCatching { JSONObject(text) }.getOrElse {
            throw ServerMediaException(ServerProblem.SERVER, "服务器返回的内容无法识别")
        }

    private fun JSONObject.number(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeUnless { it.isNaN() } else null
}
