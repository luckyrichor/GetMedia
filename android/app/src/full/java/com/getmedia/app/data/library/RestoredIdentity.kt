package com.getmedia.app.data.library

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class RestoredIdentity(val platform: String, val videoId: String, val page: Int, val type: String)

private val biliId = Regex("(BV[0-9A-Za-z]+|av[0-9]+)(?:-p([0-9]+))?")
private val biliPath = Regex("/video/(BV[0-9A-Za-z]+|av[0-9]+)(?:/)?")
private val douyinPath = Regex("/(?:video|note)/([0-9]+)(?:/)?")

internal fun restoredIdentity(platform: String, videoId: String, page: Int, source: String?, type: String): RestoredIdentity? {
    val kind = type.lowercase().takeIf { it == "audio" || it == "video" } ?: return null
    if (page !in 1..9999 || videoId.isBlank()) return null
    val url = source?.toHttpUrlOrNull()
    return when (platform.lowercase()) {
        "bilibili" -> {
            val id = biliId.matchEntire(videoId) ?: return null
            val (base, suffix) = id.destructured
            val encoded = if (suffix.isEmpty()) null else suffix.toIntOrNull()?.takeIf { it in 1..9999 } ?: return null
            val biliUrl = url?.takeIf { it.host == "bilibili.com" || it.host.endsWith(".bilibili.com") }
            val fromUrl = biliUrl?.let { biliPath.matchEntire(it.encodedPath)?.groupValues?.get(1) }
            if (fromUrl != null && fromUrl != base) return null
            val queryPages = biliUrl?.queryParameterValues("p").orEmpty()
            val explicit = if (queryPages.isEmpty()) null else {
                val parsed = queryPages.map { it?.toIntOrNull()?.takeIf { p -> p in 1..9999 } }.distinct()
                parsed.singleOrNull() ?: return null
            }
            // Page 1 is the legacy default when the actual page lives in the suffix or URL.
            val evidence = listOfNotNull(encoded, explicit, page.takeIf { it > 1 })
            if (evidence.distinct().size > 1) return null
            RestoredIdentity("bilibili", base, evidence.firstOrNull() ?: 1, kind)
        }
        "douyin" -> {
            val fromUrl = url?.takeIf { it.host == "douyin.com" || it.host.endsWith(".douyin.com") }
                ?.let { douyinPath.matchEntire(it.encodedPath)?.groupValues?.get(1) }
            if (page != 1 || (fromUrl != null && fromUrl != videoId)) return null
            RestoredIdentity("douyin", videoId, 1, kind)
        }
        else -> null
    }
}
