package com.getmedia.app.data.library

import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.TrackEntity
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

internal class RestoredRedownload(
    private val cacheDir: File,
    private val extract: suspend (String, MediaKind, File) -> TrackEntity,
    private val save: suspend (TrackEntity, File, Long, String, String) -> TrackEntity,
) {
    suspend fun download(original: TrackEntity, stillUnmatched: suspend () -> Boolean): TrackEntity {
        val expected = original.restoredKey()
        check(original.needsRestoreMatch() && expected != null) { "恢复条目身份不足或已变化，已保留原样" }
        val url = checkNotNull(original.sourceUrl.toHttpUrlOrNull()) { "缺少可用的来源链接，已保留原样" }
        val input = if (expected.platform == "bilibili" && expected.page > 1 &&
            (url.host == "bilibili.com" || url.host.endsWith(".bilibili.com"))
        ) {
            url.newBuilder().setQueryParameter("p", expected.page.toString()).build().toString()
        } else url.toString()
        val kind = MediaKind.valueOf(expected.type.uppercase())
        val staged = File.createTempFile("restored-", if (kind == MediaKind.VIDEO) ".mp4" else ".audio", cacheDir)
        try {
            val result = extract(input, kind, staged)
            check(result.restoredKey() == expected) { "重新下载所得作品、分集或媒体类型与恢复记录不符，已保留原样" }
            val bytes = result.audioBytes
            val hash = result.audioSha256
            check(result.localPath == staged.absolutePath && bytes != null && bytes > 0 &&
                staged.length() == bytes && !hash.isNullOrBlank() && result.audioFormat.isNotBlank()) {
                "重新下载的文件不完整，已保留原样"
            }
            check(stillUnmatched()) { "云端匹配结果已变化，请重新查看此项" }
            return save(original, staged, bytes, hash, result.audioFormat)
        } finally {
            staged.delete()
        }
    }
}
