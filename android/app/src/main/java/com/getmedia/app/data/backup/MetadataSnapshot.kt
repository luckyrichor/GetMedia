package com.getmedia.app.data.backup

import com.getmedia.app.data.local.CategoryEntity
import com.getmedia.app.data.local.PlaylistEntity
import com.getmedia.app.data.local.PlaylistItemEntity
import com.getmedia.app.data.local.TrackEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPOutputStream

/** 一份待上传的快照：gzip 请求体 + 内容指纹 + 条数。 */
class Snapshot(val gzip: ByteArray, val fingerprint: String, val counts: SnapshotCounts)

data class SnapshotCounts(val tracks: Int, val categories: Int, val playlists: Int, val playlistItems: Int) {
    fun json(): JSONObject = JSONObject()
        .put("tracks", tracks).put("categories", categories)
        .put("playlists", playlists).put("playlist_items", playlistItems)
}

/**
 * 曲库清单快照的组装（设计 §2）。
 *
 * 两条必须守住的规则：
 * 1. **只带清单，不带文件**：路径、封面、sha256、账号、设备标识都不进快照；
 * 2. **先截断再序列化**：服务端对超长字段是 422 拒收，一条超长标题会把整份快照打回去
 *    （设计 §5.0 ①）。截断只作用在快照上，Room 里的原值不动。
 */
object MetadataSnapshot {
    const val VERSION = 1
    const val TITLE_MAX = 300
    const val UPLOADER_MAX = 120
    const val URL_MAX = 2048

    private val PAGE = Regex("[?&]p=(\\d+)")

    /** 从来源链接取分 P；取不到按 1（设计 §2.3）。 */
    fun pageOf(sourceUrl: String): Int =
        PAGE.find(sourceUrl)?.groupValues?.get(1)?.toIntOrNull()?.coerceAtLeast(1) ?: 1

    fun build(
        tracks: List<TrackEntity>,
        categories: List<CategoryEntity>,
        playlists: List<PlaylistEntity>,
        items: List<PlaylistItemEntity>,
        appVersion: String,
        nowSec: Long,
    ): Snapshot {
        val sortedTracks = tracks.sortedBy { it.id }
        val sortedCategories = categories.sortedBy { it.id }
        val sortedPlaylists = playlists.sortedBy { it.id }
        val sortedItems = items.sortedWith(compareBy({ it.playlistId }, { it.trackId }))
        val byTrack = sortedItems.groupBy { it.trackId }

        val counts = SnapshotCounts(
            tracks = sortedTracks.size,
            categories = sortedCategories.size,
            playlists = sortedPlaylists.size,
            playlistItems = sortedItems.size,
        )

        val json = JSONObject()
            .put("v", VERSION)
            .put("generated_at", nowSec)
            .put("app_version", appVersion)
            .put("counts", counts.json())
            .put("categories", JSONArray().apply { sortedCategories.forEach { put(categoryJson(it)) } })
            .put("playlists", JSONArray().apply { sortedPlaylists.forEach { put(playlistJson(it)) } })
            .put("tracks", JSONArray().apply {
                sortedTracks.forEach { put(trackJson(it, byTrack[it.id].orEmpty())) }
            })

        val raw = json.toString().toByteArray(Charsets.UTF_8)
        return Snapshot(gzip(raw), fingerprint(sortedTracks, sortedCategories, sortedPlaylists, sortedItems), counts)
    }

    // ---------- JSON ----------

    private fun categoryJson(c: CategoryEntity) = JSONObject()
        .put("id", c.id).put("name", c.name)
        .put("parent_id", c.parentId ?: JSONObject.NULL)
        .put("kind", c.kind ?: JSONObject.NULL)
        .put("created_at", c.createdAt)

    private fun playlistJson(p: PlaylistEntity) = JSONObject()
        .put("id", p.id).put("name", p.name)
        .put("is_default", p.isDefault).put("created_at", p.createdAt)

    private fun trackJson(t: TrackEntity, items: List<PlaylistItemEntity>) = JSONObject()
        .put("platform", t.platform)
        .put("video_id", t.videoId)
        .put("page", pageOf(t.sourceUrl))
        .put("title", titleOf(t))
        .put("uploader", t.uploader?.let(::clipUploader) ?: JSONObject.NULL)
        .put("media_type", t.mediaType)
        .put("format", t.audioFormat)
        .put("duration_sec", t.durationSec ?: JSONObject.NULL)
        .put("bytes", t.audioBytes ?: JSONObject.NULL)
        .put("source_url", clipUrl(t.sourceUrl))
        .put("added_at", t.addedAtMs)
        .put("favorite", t.favorite)
        .put("category_id", t.localCategoryId)
        .put("sort_index", t.sortIndex)
        .put("title_override", t.localTitleOverride?.let(::clipTitle) ?: JSONObject.NULL)
        .put("playlists", JSONArray().apply {
            items.forEach { put(JSONObject().put("playlist_id", it.playlistId).put("order_index", it.orderIndex)) }
        })

    fun titleOf(t: TrackEntity): String = clipTitle(t.localTitleOverride ?: t.title)

    fun clipTitle(value: String): String = if (value.length > TITLE_MAX) value.substring(0, TITLE_MAX) else value

    fun clipUploader(value: String): String = if (value.length > UPLOADER_MAX) value.substring(0, UPLOADER_MAX) else value

    /** 超长链接整条丢掉：截断后的 URL 一定打不开，留着只会让"重新下载"必然失败。 */
    fun clipUrl(value: String): String = if (value.length > URL_MAX) "" else value

    // ---------- 指纹 ----------

    /**
     * 内容指纹：与 `generated_at` / `app_version` 无关，只反映"清单本身变没变"。
     *
     * 用长度前缀拼接而不是分隔符：标题里带 `|` 或换行也不会让两份不同的清单算出同一个指纹。
     * 这里用的也是截断后的值——第 301 个字符变化本来就不会改变上传内容，不该触发一次上传。
     */
    fun fingerprint(
        tracks: List<TrackEntity>,
        categories: List<CategoryEntity>,
        playlists: List<PlaylistEntity>,
        items: List<PlaylistItemEntity>,
    ): String {
        val text = buildString {
            categories.sortedBy { it.id }.forEach { c ->
                field("c").field(c.id.toString()).field(c.name).field(c.parentId?.toString())
                    .field(c.kind).field(c.createdAt.toString()).end()
            }
            playlists.sortedBy { it.id }.forEach { p ->
                field("p").field(p.id.toString()).field(p.name).field(if (p.isDefault) "1" else "0")
                    .field(p.createdAt.toString()).end()
            }
            tracks.sortedBy { it.id }.forEach { t ->
                field("t").field(t.platform).field(t.videoId).field(pageOf(t.sourceUrl).toString())
                    .field(titleOf(t)).field(t.uploader?.let(::clipUploader)).field(t.mediaType)
                    .field(t.audioFormat).field(t.durationSec?.let { String.format(Locale.US, "%.3f", it) })
                    .field(t.audioBytes?.toString()).field(clipUrl(t.sourceUrl))
                    .field(t.addedAtMs.toString()).field(if (t.favorite) "1" else "0")
                    .field(t.localCategoryId.toString()).field(t.sortIndex.toString())
                    .field(t.localTitleOverride?.let(::clipTitle)).end()
            }
            items.sortedWith(compareBy({ it.playlistId }, { it.trackId })).forEach { i ->
                field("i").field(i.playlistId.toString()).field(i.trackId.toString())
                    .field(i.orderIndex.toString()).end()
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** `长度:内容`，null 写 `~`。 */
    private fun StringBuilder.field(value: String?): StringBuilder {
        if (value == null) append('~') else append(value.length).append(':').append(value)
        return append('|')
    }

    private fun StringBuilder.end(): StringBuilder = append('\n')

    private fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size / 2 + 64)
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }
}
