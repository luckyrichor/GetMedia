package com.getmedia.app.data.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * 云端快照的读取与校验（设计 §5.5 ②）。
 *
 * 三道关都要过，任何一道不过就中止、不动库：
 * 1. gzip 字节的 sha256 与响应头 `X-Backup-Sha256` 一致（传输损坏、被截改）；
 * 2. gzip 能完整解开（截断的流解到一半就报错）；
 * 3. `v == 1` 且条数与 `counts` 完全一致（缺条目的快照宁可不用，也不要恢复出半个库）。
 */
class SnapshotError(message: String) : Exception(message)

data class BackupCategory(val id: Long, val name: String, val parentId: Long?, val kind: String?, val createdAt: Long)

data class BackupPlaylist(val id: Long, val name: String, val isDefault: Boolean, val createdAt: Long)

data class BackupTrack(
    val platform: String,
    val videoId: String,
    val page: Int,
    val title: String,
    val uploader: String?,
    val mediaType: String,
    val format: String,
    val durationSec: Double?,
    val bytes: Long?,
    val sourceUrl: String,
    val addedAtMs: Long,
    val favorite: Boolean,
    val categoryId: Long?,
    val sortIndex: Long,
    val titleOverride: String?,
    val playlists: List<BackupPlaylistRef>,
)

data class BackupPlaylistRef(val playlistId: Long, val orderIndex: Int)

class ParsedSnapshot(
    val generatedAt: Long,
    val appVersion: String,
    val counts: SnapshotCounts,
    val categories: List<BackupCategory>,
    val playlists: List<BackupPlaylist>,
    val tracks: List<BackupTrack>,
)

object SnapshotReader {
    const val MAX_RAW = 16 * 1024 * 1024

    fun read(payload: ByteArray, expectedSha256: String?): ParsedSnapshot {
        if (expectedSha256 != null && !expectedSha256.equals(sha256(payload), ignoreCase = true)) {
            throw SnapshotError("备份文件校验失败")
        }
        val raw = gunzip(payload)
        val json = try {
            JSONObject(String(raw, Charsets.UTF_8))
        } catch (e: Exception) {
            throw SnapshotError("备份文件损坏")
        }
        val version = json.optInt("v", 0)
        if (version != MetadataSnapshot.VERSION) throw SnapshotError("备份版本不支持（v$version）")

        val categories = json.optJSONArray("categories").objects().map(::category)
        val playlists = json.optJSONArray("playlists").objects().map(::playlist)
        val tracks = json.optJSONArray("tracks").objects().map(::track)

        // 条数必须与 counts 逐项相等：少条目说明快照被截断，恢复出来是个残缺的库
        val counts = SnapshotCounts(
            tracks = tracks.size,
            categories = categories.size,
            playlists = playlists.size,
            playlistItems = tracks.sumOf { it.playlists.size },
        )
        val declared = json.optJSONObject("counts")
        if (declared != null && (
                declared.optInt("tracks", -1) != counts.tracks ||
                    declared.optInt("categories", -1) != counts.categories ||
                    declared.optInt("playlists", -1) != counts.playlists ||
                    declared.optInt("playlist_items", -1) != counts.playlistItems
                )
        ) {
            throw SnapshotError("备份文件损坏")
        }

        return ParsedSnapshot(
            generatedAt = json.optLong("generated_at", 0),
            appVersion = json.optString("app_version"),
            counts = counts,
            categories = categories,
            playlists = playlists,
            tracks = tracks,
        )
    }

    // ---------- 单条 ----------

    private fun category(o: JSONObject) = BackupCategory(
        id = o.optLong("id"),
        name = o.optString("name"),
        parentId = o.optLongOrNull("parent_id"),
        kind = o.optStringOrNull("kind"),
        createdAt = o.optLong("created_at", 0),
    ).also { if (it.name.isBlank()) throw SnapshotError("分类名为空") }

    private fun playlist(o: JSONObject) = BackupPlaylist(
        id = o.optLong("id"),
        name = o.optString("name"),
        isDefault = o.optBoolean("is_default", false),
        createdAt = o.optLong("created_at", 0),
    ).also { if (it.name.isBlank()) throw SnapshotError("列表名为空") }

    private fun track(o: JSONObject): BackupTrack {
        val platform = o.optString("platform").trim()
        val videoId = o.optString("video_id").trim()
        // 去重键缺了就没法派生 id，恢复出来的行会互相覆盖
        if (platform.isEmpty() || videoId.isEmpty()) throw SnapshotError("条目缺少 platform 或 video_id")
        return BackupTrack(
            platform = platform,
            videoId = videoId,
            page = o.optInt("page", 1).coerceAtLeast(1),
            title = o.optString("title"),
            uploader = o.optStringOrNull("uploader"),
            mediaType = o.optString("media_type", "AUDIO"),
            format = o.optString("format", "m4a"),
            durationSec = o.optDoubleOrNull("duration_sec"),
            bytes = o.optLongOrNull("bytes"),
            sourceUrl = o.optString("source_url"),
            addedAtMs = o.optLong("added_at", 0),
            favorite = o.optBoolean("favorite", false),
            categoryId = o.optLongOrNull("category_id"),
            sortIndex = o.optLong("sort_index", 0),
            titleOverride = o.optStringOrNull("title_override"),
            playlists = o.optJSONArray("playlists").objects().map {
                BackupPlaylistRef(it.optLong("playlist_id"), it.optInt("order_index", 0))
            },
        )
    }

    // ---------- 工具 ----------

    private fun gunzip(payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(payload.size.coerceAtLeast(64) * 2)
        try {
            GZIPInputStream(ByteArrayInputStream(payload)).use { gz ->
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = gz.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_RAW) throw SnapshotError("备份文件过大")
                    out.write(buf, 0, n)
                }
            }
        } catch (e: SnapshotError) {
            throw e
        } catch (e: Exception) {
            // 截断的 gzip 在这里表现为 EOFException / ZipException
            throw SnapshotError("备份文件损坏")
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun JSONArray?.objects(): List<JSONObject> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { optJSONObject(it) }
    }

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (isNull(name)) null else optLong(name)

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (isNull(name)) null else optDouble(name)

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).ifBlank { null }
}
