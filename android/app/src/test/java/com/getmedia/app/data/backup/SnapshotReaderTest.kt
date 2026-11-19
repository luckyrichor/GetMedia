package com.getmedia.app.data.backup

import com.getmedia.app.data.local.CategoryEntity
import com.getmedia.app.data.local.PlaylistEntity
import com.getmedia.app.data.local.PlaylistItemEntity
import com.getmedia.app.data.local.TrackEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/**
 * 云端快照的读取：正常情况下要能完整还原，四类坏数据必须被拦在库外面。
 * 这里用 [MetadataSnapshot.build] 生成真快照再读回来，避免手写一份"看起来像"的 JSON。
 */
class SnapshotReaderTest {

    private fun track(id: Long, title: String, page: Int = 1, url: String = "https://www.bilibili.com/video/BV1?p=$page") =
        TrackEntity(
            id = id, platform = "bilibili", videoId = if (page > 1) "BV1-p$page" else "BV1",
            title = title, uploader = "作者", durationSec = 12.5, audioBytes = 2048,
            audioFormat = "m4a", sourceUrl = url, localPath = null, favorite = true,
            localCategoryId = 2, addedAtMs = 1_700_000_000_000, sortIndex = 3,
            localTitleOverride = null, mediaType = "AUDIO",
        )

    private fun snapshotJson(): ByteArray = MetadataSnapshot.build(
        tracks = listOf(track(-1, "第一条"), track(-2, "第二条")),
        categories = listOf(CategoryEntity(2, "工作", null, "LEAF", 1)),
        playlists = listOf(PlaylistEntity(5, "默认", 1)),
        items = listOf(PlaylistItemEntity(5, -1, 1, 0), PlaylistItemEntity(5, -2, 1, 1)),
        appVersion = "0.1.0",
        nowSec = 1_800_000_000,
    ).gzip

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun gzip(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(raw) }
        return out.toByteArray()
    }

    @Test
    fun roundTripsARealSnapshot() {
        val gz = snapshotJson()
        val parsed = SnapshotReader.read(gz, sha(gz))

        assertEquals(2, parsed.counts.tracks)
        assertEquals(2, parsed.counts.playlistItems)
        assertEquals(1, parsed.categories.size)
        assertEquals(1, parsed.playlists.size)

        val first = parsed.tracks.first { it.title == "第一条" }
        assertEquals("bilibili", first.platform)
        assertEquals("BV1", first.videoId)
        assertEquals("第一条", first.title)
        assertEquals(1_700_000_000_000, first.addedAtMs)
        assertEquals(true, first.favorite)
        assertEquals(2L, first.categoryId)
        assertEquals(1, first.playlists.size)
        assertEquals(5L, first.playlists.first().playlistId)
    }

    @Test
    fun rejectsWhenShaDoesNotMatch() {
        val gz = snapshotJson()
        val error = assertThrows(SnapshotError::class.java) { SnapshotReader.read(gz, "00".repeat(32)) }
        assertEquals("备份文件校验失败", error.message)
    }

    @Test
    fun acceptsWhenServerOmitsTheShaHeader() {
        // 老版本反代可能吞掉自定义响应头：没有 sha 就只靠 gzip 完整性把关
        assertNotNull(SnapshotReader.read(snapshotJson(), null))
    }

    @Test
    fun rejectsTruncatedGzip() {
        val gz = snapshotJson()
        val cut = gz.copyOfRange(0, gz.size - 8)   // 去掉 gzip 尾部的 CRC 与长度
        assertThrows(SnapshotError::class.java) { SnapshotReader.read(cut, null) }
    }

    @Test
    fun rejectsUnsupportedVersion() {
        val json = JSONObject(String(java.util.zip.GZIPInputStream(snapshotJson().inputStream()).readBytes()))
            .put("v", 2).toString().toByteArray()
        val error = assertThrows(SnapshotError::class.java) { SnapshotReader.read(gzip(json), null) }
        assertEquals("备份版本不支持（v2）", error.message)
    }

    @Test
    fun rejectsWhenCountsDoNotMatchPayload() {
        val json = JSONObject(String(java.util.zip.GZIPInputStream(snapshotJson().inputStream()).readBytes()))
        json.getJSONObject("counts").put("tracks", 5)
        val error = assertThrows(SnapshotError::class.java) {
            SnapshotReader.read(gzip(json.toString().toByteArray()), null)
        }
        assertEquals("备份文件损坏", error.message)
    }

    @Test
    fun rejectsTrackWithoutDedupKey() {
        val json = JSONObject(String(java.util.zip.GZIPInputStream(snapshotJson().inputStream()).readBytes()))
        json.getJSONArray("tracks").getJSONObject(0).put("video_id", "")
        val error = assertThrows(SnapshotError::class.java) {
            SnapshotReader.read(gzip(json.toString().toByteArray()), null)
        }
        assertEquals("条目缺少 platform 或 video_id", error.message)
    }
}
