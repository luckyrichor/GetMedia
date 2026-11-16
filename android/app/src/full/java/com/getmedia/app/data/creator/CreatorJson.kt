package com.getmedia.app.data.creator

import org.json.JSONObject

/**
 * 服务端 JSON → 模型；错误码 → 可展示的中文提示。
 *
 * 单独放一个对象，方便用固定字符串做单测，不需要真实网络或 MockWebServer。
 */
object CreatorJson {

    fun preview(text: String): CreatorPreview {
        val root = runCatching { JSONObject(text) }.getOrElse {
            throw CreatorException(CreatorProblem.SERVER, "服务器返回的内容无法识别")
        }
        val profile = root.optJSONObject("profile")
            ?: throw CreatorException(CreatorProblem.SERVER, "服务器没有返回博主信息")
        val platform = CreatorPlatform.of(profile.optString("platform"))
            ?: throw CreatorException(CreatorProblem.SERVER, "服务器返回了不支持的平台")

        val items = buildList {
            val array = root.optJSONArray("items") ?: return@buildList
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val url = row.optString("source_url")
                if (url.isBlank()) continue
                add(
                    CreatorItem(
                        position = row.optInt("position", i + 1),
                        videoId = row.optString("video_id"),
                        title = row.optString("title").ifBlank { "未命名作品" },
                        sourceUrl = url,
                        coverUrl = row.optString("cover_url").ifBlank { null },
                        durationSec = if (row.isNull("duration_sec")) null else row.optDouble("duration_sec"),
                        publishedAt = row.optString("published_at").ifBlank { null },
                    )
                )
            }
        }

        return CreatorPreview(
            profile = CreatorProfile(
                platform = platform,
                creatorId = profile.optString("creator_id"),
                name = profile.optString("name").ifBlank { "未命名博主" },
                homeUrl = profile.optString("url"),
                avatarUrl = profile.optString("avatar_url").ifBlank { null },
                signature = profile.optString("signature").ifBlank { null },
                followerCount = profile.optLong("follower_count").takeIf { profile.has("follower_count") && !profile.isNull("follower_count") },
                totalVideos = profile.optLong("total_videos").takeIf { profile.has("total_videos") && !profile.isNull("total_videos") },
            ),
            items = items,
            total = root.optLong("total").takeIf { root.has("total") && !root.isNull("total") },
            hasMore = root.optBoolean("has_more"),
            start = root.optJSONObject("range")?.optInt("start") ?: 1,
            count = root.optJSONObject("range")?.optInt("count") ?: items.size,
        )
    }

    /** 服务端错误体：{"detail": "...", "code": "..."}；code 稳定，detail 可直接展示。 */
    fun error(status: Int, text: String): CreatorException {
        val body = runCatching { JSONObject(text) }.getOrNull()
        val code = body?.optString("code").orEmpty()
        val detail = body?.optString("detail").orEmpty().ifBlank { fallback(status, code) }
        val problem = when {
            status == 401 -> CreatorProblem.LOGIN_REQUIRED
            status == 404 -> CreatorProblem.NOT_FOUND
            status == 428 -> CreatorProblem.PLATFORM_AUTH
            status == 429 -> CreatorProblem.UNAVAILABLE
            status == 503 -> CreatorProblem.UNAVAILABLE
            status in 400..499 -> CreatorProblem.INPUT
            status >= 500 -> CreatorProblem.SERVER
            else -> CreatorProblem.SERVER
        }
        return CreatorException(problem, detail)
    }

    private fun fallback(status: Int, code: String): String = when {
        status == 401 -> "登录已失效，请重新登录"
        // 服务端还是旧版本时，路由不存在会返回不带 code 的 404
        status == 404 && code.isBlank() -> "服务器还没有这个功能，请更新服务端后再试"
        code == "creator_not_found" -> "没有找到这个博主，请检查链接或 ID"
        code == "creator_auth" -> "平台需要重新授权，请稍后再试"
        code == "creator_unavailable" -> "平台暂时不可用，请稍后重试"
        code == "rate_limited" -> "查询太频繁，请稍后再试"
        status == 428 -> "平台需要重新授权"
        status in 500..599 -> "服务器暂时不可用，请稍后重试"
        else -> "请求未被接受（HTTP $status）"
    }
}
