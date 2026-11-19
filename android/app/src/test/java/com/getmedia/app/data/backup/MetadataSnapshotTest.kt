package com.getmedia.app.data.backup

import com.getmedia.app.data.local.CategoryEntity
import com.getmedia.app.data.local.PlaylistEntity
import com.getmedia.app.data.local.PlaylistItemEntity
import com.getmedia.app.data.local.TrackEntity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

class MetadataSnapshotTest {

    private fun track(
        id: Long = -1,
        title: String = "标题",
        uploader: String? = "作者",
        sourceUrl: String = "https://www.bilibili.com/video/BV1?p=1",
        favorite: Boolean = false,
        categoryId: Long = 1,
        sortIndex: Long = 0,
        override: String? = null,
        format: String = "m4a",
        duration: Double? = 12.5,
        bytes: Long? = 1024,
    ) = TrackEntity(
        id = id, platform = "bilibili", videoId = "BV1", title = title, uploader = uploader,
        durationSec = duration, audioBytes = bytes, audioFormat = format, sourceUrl = sourceUrl,
        localPath = "/data/user/0/x/files/audio/-1.audio", favorite = favorite,
        localCategoryId = categoryId, sortIndex = sortIndex, localTitleOverride = override,
        addedAtMs = 1_700_000_000_000, audioSha256 = "deadbeef", coverUrl = "https://c/1.jpg",
    )

    private val categories = listOf(
        CategoryEntity(id = 1, name = "未分类", parentId = null, kind = "LEAF", createdAt = 0),
        CategoryEntity(id = 2, name = "通勤", parentId = null, kind = "LEAF", createdAt = 100),
    )
    private val playlists = listOf(PlaylistEntity(id = 1, name = "默认", createdAt = 50, isDefault = true))

    private fun build(
        tracks: List<TrackEntity> = listOf(track()),
        items: List<PlaylistItemEntity> = listOf(PlaylistItemEntity(1, -1, 0, orderIndex = 0)),
        nowSec: Long = 1_731_912_345,
        appVersion: String = "0.1.0",
    ) = MetadataSnapshot.build(tracks, categories, playlists, items, appVersion, nowSec)

    private fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
            val out = ByteArrayOutputStream()
            input.copyTo(out)
            out.toString(Charsets.UTF_8.name())
        }

    @Test
    fun snapshotCarriesCountsAndVersion() {
        val snap = build()
        assertEquals(1, snap.counts.tracks)
        assertEquals(2, snap.counts.categories)
        assertEquals(1, snap.counts.playlists)
        assertEquals(1, snap.counts.playlistItems)

        val json = JSONObject(gunzip(snap.gzip))
        assertEquals(1, json.getInt("v"))
        assertEquals(1_731_912_345L, json.getLong("generated_at"))
        assertEquals("0.1.0", json.getString("app_version"))
        assertEquals(1, json.getJSONObject("counts").getInt("tracks"))
        assertEquals(1, json.getJSONArray("tracks").length())
    }

    @Test
    fun snapshotExcludesFilesAndIdentifiers() {
        val json = JSONObject(gunzip(build().gzip))
        val t = json.getJSONArray("tracks").getJSONObject(0)
        // 只带清单：路径、封面、sha256、账号这些都不在快照里
        listOf("local_path", "localPath", "cover_url", "coverUrl", "audio_sha256", "audioSha256",
            "account_key", "accountKey", "server_id", "serverId").forEach {
            assertTrue("不该出现 $it", !t.has(it))
        }
        assertEquals("BV1", t.getString("video_id"))
    }

    @Test
    fun longFieldsAreTruncatedBeforeSerialization() {
        val snap = build(
            tracks = listOf(
                track(
                    title = "长".repeat(MetadataSnapshot.TITLE_MAX + 50),
                    uploader = "作".repeat(MetadataSnapshot.UPLOADER_MAX + 10),
                    sourceUrl = "https://v.douyin.com/" + "x".repeat(MetadataSnapshot.URL_MAX),
                ),
            ),
        )
        val t = JSONObject(gunzip(snap.gzip)).getJSONArray("tracks").getJSONObject(0)
        assertEquals(MetadataSnapshot.TITLE_MAX, t.getString("title").length)
        assertEquals(MetadataSnapshot.UPLOADER_MAX, t.getString("uploader").length)
        // 超长链接整条丢掉，而不是截断成一条打不开的地址
        assertEquals("", t.getString("source_url"))
    }

    @Test
    fun pageComesFromSourceUrl() {
        assertEquals(2, MetadataSnapshot.pageOf("https://www.bilibili.com/video/BV1?p=2"))
        assertEquals(3, MetadataSnapshot.pageOf("https://www.bilibili.com/video/BV1?x=1&p=3"))
        assertEquals(1, MetadataSnapshot.pageOf("https://www.bilibili.com/video/BV1"))
        assertEquals(1, MetadataSnapshot.pageOf("https://www.bilibili.com/video/BV1?p=0"))
        assertEquals(1, MetadataSnapshot.pageOf("https://www.bilibili.com/video/BV1?p=abc"))
        assertEquals(1, MetadataSnapshot.pageOf(""))
    }

    @Test
    fun fingerprintIgnoresGeneratedAtAndVersion() {
        val a = build(nowSec = 1_000)
        val b = build(nowSec = 2_000, appVersion = "9.9.9")
        assertEquals("时间与版本不参与指纹", a.fingerprint, b.fingerprint)
    }

    @Test
    fun fingerprintChangesWhenLibraryChanges() {
        val base = build()
        assertNotEquals(base.fingerprint, build(tracks = listOf(track(title = "改了标题"))).fingerprint)
        assertNotEquals(base.fingerprint, build(tracks = listOf(track(favorite = true))).fingerprint)
        assertNotEquals(base.fingerprint, build(tracks = listOf(track(categoryId = 2))).fingerprint)
        assertNotEquals(
            base.fingerprint,
            build(items = listOf(PlaylistItemEntity(1, -1, 0, orderIndex = 5))).fingerprint,
        )
    }

    @Test
    fun fingerprintUsesTruncatedValues() {
        // 第 301 个字符之后的变化不改变上传内容，就不该触发一次上传
        val short = build(tracks = listOf(track(title = "a".repeat(MetadataSnapshot.TITLE_MAX))))
        val long = build(tracks = listOf(track(title = "a".repeat(MetadataSnapshot.TITLE_MAX) + "后面还有")))
        assertEquals(short.fingerprint, long.fingerprint)
    }

    @Test
    fun titleOverrideWins() {
        val snap = build(tracks = listOf(track(title = "原标题", override = "我改的名字")))
        val t = JSONObject(gunzip(snap.gzip)).getJSONArray("tracks").getJSONObject(0)
        assertEquals("我改的名字", t.getString("title"))
        assertEquals("我改的名字", t.getString("title_override"))
    }

    @Test
    fun nullUploaderStaysNull() {
        val snap = build(tracks = listOf(track(uploader = null)))
        val t = JSONObject(gunzip(snap.gzip)).getJSONArray("tracks").getJSONObject(0)
        assertTrue(t.isNull("uploader"))
    }
}
