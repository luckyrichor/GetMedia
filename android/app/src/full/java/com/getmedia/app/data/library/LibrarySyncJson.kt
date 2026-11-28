package com.getmedia.app.data.library

import org.json.JSONObject

/**
 * 同步接口的 JSON ⇄ 模型，以及 HTTP 失败 → [LibrarySyncException]。
 *
 * 纯字符串进出、不碰网络与 Android：两个 409 的区分、"裸 404 算什么"这类最容易写反的映射
 * 都在这里用固定用例钉住（`LibrarySyncJsonTest`）。
 */
object LibrarySyncJson {

    /** 单页条数上限，与服务端 `limit` 的 `le=500` 对齐。 */
    const val MAX_PAGE = 500

    fun page(text: String, strictCatalog: Boolean = false): PullPage {
        val root = runCatching { JSONObject(text) }.getOrElse {
            throw LibrarySyncException(LibrarySyncProblem.SERVER, "服务器返回的内容无法识别")
        }
        val arr = root.optJSONArray("items")
            ?: throw LibrarySyncException(LibrarySyncProblem.SERVER, "服务器返回的同步页缺少条目列表")
        if (strictCatalog && (root.opt("has_more") !is Boolean || root.opt("next_seq") !is Number)) {
            throw LibrarySyncException(LibrarySyncProblem.SERVER, "云端清单缺少分页信息")
        }
        val items = ArrayList<CloudTrack>(arr.length())
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i)?.let { track(it, strictCatalog) }
            if (strictCatalog && (item == null || item.updatedSeq <= 0)) {
                throw LibrarySyncException(LibrarySyncProblem.SERVER, "云端清单有无法核对的条目")
            }
            if (item != null) items += item
        }
        return PullPage(
            items = items,
            nextSeq = root.optLong("next_seq", 0),
            // 服务端就是 `len(items) >= limit`：恰好一页满时会多打一次空请求，可接受
            hasMore = root.optBoolean("has_more"),
        )
    }

    /**
     * `POST /v2/tracks` 的 200 响应：`{"created": true|false, "track": {...}}`。
     *
     * `created=false` 不是失败：服务器上已经有这个键，客户端按服务器那一行回填。
     * 缺 `track` 直接当服务器错误——没有服务器那一行就没法回填 `serverId`，留着也是脏数据。
     */
    fun created(text: String): CreatedTrack {
        val root = runCatching { JSONObject(text) }.getOrElse {
            throw LibrarySyncException(LibrarySyncProblem.SERVER, "服务器返回的内容无法识别")
        }
        val obj = root.optJSONObject("track")
            ?: throw LibrarySyncException(LibrarySyncProblem.SERVER, "服务器没有返回上传后的条目")
        val parsed = track(obj)
            ?: throw LibrarySyncException(LibrarySyncProblem.SERVER, "服务器返回的条目缺少平台或作品号")
        return CreatedTrack(created = root.optBoolean("created", true), track = parsed)
    }

    /** `DELETE /v2/sync/tracks/{id}` 的成功体：`{ok,id,deleted,updated_seq}`。 */
    fun deleteResult(text: String): DeleteResult {
        val root = runCatching { JSONObject(text) }.getOrElse {
            throw LibrarySyncException(LibrarySyncProblem.SERVER, "服务器返回的内容无法识别")
        }
        val id = root.optLong("id", 0)
        val seq = root.optLong("updated_seq", 0)
        if (id <= 0 || seq <= 0 || !root.optBoolean("deleted", false)) {
            throw LibrarySyncException(LibrarySyncProblem.SERVER, "服务器没有确认删除结果")
        }
        return DeleteResult(id, deleted = true, updatedSeq = seq)
    }

    /** 解析一条；**键不全的行直接丢掉**（没有 platform/video_id 就没法算本地 id）。 */
    private fun track(o: JSONObject, strictCatalog: Boolean = false): CloudTrack? {
        val platform = o.text("platform").orEmpty().trim()
        val videoId = o.text("video_id").orEmpty().trim()
        if (platform.isEmpty() || videoId.isEmpty()) return null
        val id = o.optLong("id", 0)
        if (id <= 0) return null
        val page = if (strictCatalog) o.optInt("page", 0) else o.optInt("page", 1).coerceIn(1, 9999)
        return CloudTrack(
            id = id,
            platform = platform,
            // 服务器把分 P 放在 page 列，video_id 不带后缀；万一带上就先剥掉，统一成规范形态
            videoId = if (page > 1 && !strictCatalog) videoId.removeSuffix("-p$page") else videoId,
            page = page,
            title = o.text("title") ?: "未命名作品",
            uploader = o.text("uploader"),
            durationSec = if (o.has("duration_sec") && !o.isNull("duration_sec")) {
                o.optDouble("duration_sec").takeUnless { it.isNaN() }
            } else null,
            sourceUrl = o.text("source_url").orEmpty(),
            mediaType = o.text("media_type") ?: if (strictCatalog) "" else "audio",
            deleted = o.optBoolean("deleted", false),
            updatedSeq = o.optLong("updated_seq", 0),
            updatedAtSec = o.optLong("updated_at", 0),
            storage = o.text("storage").orEmpty(),
            hasFile = o.optBoolean("has_file", false),
            favorite = o.optBoolean("favorite", false),
            category = o.text("category").orEmpty().trim(),
        )
    }

    /**
     * 取文本字段：键不存在、值为 JSON `null`、值为空串，一律当"没有"。
     *
     * 不能用裸 `optString`——`org.json` 在值为 `null` 时返回字符串 `"null"`，
     * 于是"没有作者"会变成名字叫 `null` 的作者，界面上直接显示 `null`。
     */
    private fun JSONObject.text(name: String): String? =
        if (isNull(name)) null else optString(name).ifBlank { null }

    /** `GET /v2/tracks/{id}` / `PATCH /v2/sync/tracks/{id}` 返回的单条。 */
    fun singleTrack(text: String): CloudTrack {
        val root = runCatching { JSONObject(text) }.getOrElse {
            throw LibrarySyncException(LibrarySyncProblem.SERVER, "服务器返回的内容无法识别")
        }
        val parsed = track(root)
            ?: throw LibrarySyncException(LibrarySyncProblem.SERVER, "服务器返回的条目缺少平台或作品号")
        return parsed
    }

    /**
     * HTTP 失败 → 异常。**409 必须按 `code` 分**：两个 409 的处理完全不同——
     * `track_deleted` 是"这一条在云端被删了"（带那一行，弹确认），
     * `library_full` 是"整个账号满了"（停手、提示清理）。
     */
    fun error(status: Int, body: String, retryAfterSeconds: Long? = null): LibrarySyncException {
        val json = runCatching { JSONObject(body) }.getOrNull()
        val code = json?.optString("code").orEmpty()
        val detail = json?.optString("detail").orEmpty()
        return when {
            status == 401 -> LibrarySyncException(
                LibrarySyncProblem.LOGIN_REQUIRED, "登录已失效，请重新登录", code,
            )
            // owner 通道把"未登录 / 非 owner / 路由不存在"压成无 body 的裸 404
            status == 404 && code.isBlank() -> LibrarySyncException(
                LibrarySyncProblem.NOT_AVAILABLE, "当前账号无法同步，或服务器还没开这个功能",
            )
            // 有业务码的 404 是"这条确实不存在"，与裸 404 的"接口不可用"必须分开：
            // 前者可以结项，后者要保留队列等部署/权限恢复。
            status == 404 && code == "track_not_found" -> LibrarySyncException(
                LibrarySyncProblem.TRACK_NOT_FOUND, detail.ifBlank { "服务器上找不到这条" }, code,
            )
            status == 404 -> LibrarySyncException(
                LibrarySyncProblem.INPUT, detail.ifBlank { "服务器上找不到这条" }, code,
            )
            status == 409 && code == "archive_protected" -> LibrarySyncException(
                LibrarySyncProblem.ARCHIVE_PROTECTED,
                detail.ifBlank { "云端归档会保留，手机端不能移除" }, code,
                conflict = json?.optJSONObject("track"),
            )
            status == 412 -> LibrarySyncException(
                LibrarySyncProblem.VERSION_CONFLICT,
                detail.ifBlank { "云端条目已变化，请刷新后重试" }, code,
                conflict = json?.optJSONObject("track"),
            )
            status == 409 && code == "track_deleted" -> LibrarySyncException(
                LibrarySyncProblem.TRACK_DELETED, "这条在云端已被删除", code,
                conflict = json?.optJSONObject("track"),
            )
            status == 409 && code == "library_full" -> LibrarySyncException(
                LibrarySyncProblem.LIBRARY_FULL,
                detail.ifBlank { "云端曲库已满，请先在网页端清理" }, code,
            )
            // 认不出的 409 不猜：按服务器错误处理，宁可重试也不误删
            status == 409 -> LibrarySyncException(
                LibrarySyncProblem.SERVER, detail.ifBlank { "服务器拒绝了这次同步" }, code,
            )
            status == 429 -> LibrarySyncException(
                LibrarySyncProblem.RATE_LIMITED, "同步太频繁，稍后自动重试", code, retryAfterSeconds,
            )
            // 裸 400（没有业务码）不是"你的请求写错了"。应用自己的每个错误都带 `code`
            // （errors.ApiError 构造时必填），能回出裸 400 的只有边缘：手机传到一半连接断了，
            // nginx 就在访问日志里记一行 `400 0`，响应体是空的。这类失败重试就能好，
            // 一旦按 INPUT 处理就会被 [LocalArchiveBackup] 判成"永久失败"，本机原件悄悄不再备份。
            status == 400 && code.isBlank() -> LibrarySyncException(
                LibrarySyncProblem.SERVER, "传输中断，稍后自动重试", code,
            )
            status in 400..499 -> LibrarySyncException(
                LibrarySyncProblem.INPUT, detail.ifBlank { "请求被服务器拒绝" }, code,
            )
            else -> LibrarySyncException(
                LibrarySyncProblem.SERVER, detail.ifBlank { "服务器暂时不可用，请稍后重试" }, code,
            )
        }
    }

    /** `Retry-After` 只认秒数；HTTP 日期形式不支持，缺省交给调用方按默认间隔退避。 */
    fun retryAfterSeconds(raw: String?): Long? = raw?.trim()?.toLongOrNull()?.takeIf { it in 1..3600 }
}
