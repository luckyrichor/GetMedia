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

/**
 * 备份快照的取数规则（P0-2 §18.4 ①）：**云端行 + 没有任何整理痕迹 → 不进快照**。
 *
 * 理由：这类行服务器上本来就有，重装后同步一遍就回来；备份它们只会把真正值钱的内容
 * （本机文件、收藏、类别、播放列表顺序、改名）挤到 8000 行 / 512 KiB 的上限外面。
 *
 * 每条痕迹单独一条用例——少一条，用户在最需要恢复的时候才会发现丢东西。
 */
class BackupSnapshotFilterTest {

    private fun cloudRow(
        id: Long,
        favorite: Boolean = false,
        categoryId: Long = MetadataSnapshot.DEFAULT_CATEGORY_ID,
        override: String? = null,
        localPath: String? = null,
    ) = TrackEntity(
        id = id, platform = "bilibili", videoId = "BV$id", title = "云端 $id", uploader = "UP",
        durationSec = 10.0, audioBytes = null, audioFormat = "", sourceUrl = "https://b/$id",
        localPath = localPath, favorite = favorite, localCategoryId = categoryId,
        localTitleOverride = override, addedAtMs = 1_700_000_000_000,
    )

    private val categories = listOf(
        CategoryEntity(id = 1, name = "未分类", parentId = null, kind = "LEAF", createdAt = 0),
        CategoryEntity(id = 2, name = "通勤", parentId = null, kind = "LEAF", createdAt = 1),
    )
    private val playlists = listOf(PlaylistEntity(id = 1, name = "默认", createdAt = 0, isDefault = true))

    private fun build(
        tracks: List<TrackEntity>,
        items: List<PlaylistItemEntity> = emptyList(),
    ) = MetadataSnapshot.build(
        tracks, categories, playlists, items, appVersion = "0.1.4", nowSec = 1_800_000_000,
    )

    private fun trackCount(snapshot: Snapshot) = snapshot.counts.tracks

    /** 从快照里读回条目，确认筛掉的不只是计数、JSON 里也确实没有。 */
    private fun snapshotTrackKeys(snapshot: Snapshot): List<Pair<String, Int>> {
        val text = GZIPInputStream(ByteArrayInputStream(snapshot.gzip)).use {
            val out = ByteArrayOutputStream(); it.copyTo(out); out.toString(Charsets.UTF_8.name())
        }
        val arr = JSONObject(text).getJSONArray("tracks")
        return (0 until arr.length()).map { i -> arr.getJSONObject(i).getString("video_id") to arr.getJSONObject(i).getInt("page") }
    }

    @Test fun cloudRowWithoutTracesIsExcluded() {
        val s = build(listOf(cloudRow(1)))
        assertEquals(0, trackCount(s))
        assertTrue(snapshotTrackKeys(s).isEmpty())
    }

    @Test fun localFileKeepsIt() {
        val s = build(listOf(cloudRow(1, localPath = "/files/-1.audio")))
        assertEquals(1, trackCount(s))
    }

    @Test fun favoriteKeepsIt() {
        val s = build(listOf(cloudRow(1, favorite = true)))
        assertEquals(1, trackCount(s))
    }

    @Test fun nonDefaultCategoryKeepsIt() {
        val s = build(listOf(cloudRow(1, categoryId = 2)))
        assertEquals(1, trackCount(s))
    }

    @Test fun playlistMembershipKeepsIt() {
        // 默认列表也算：顺序只在手机上，服务器没有这个概念
        val s = build(
            listOf(cloudRow(1)),
            items = listOf(PlaylistItemEntity(playlistId = 1, trackId = 1, orderIndex = 0)),
        )
        assertEquals(1, trackCount(s))
    }

    @Test fun localRenameKeepsIt() {
        // 改名要等阶段 5 才推给服务器；在那之前它只存在于本机，不备份就会丢
        val s = build(listOf(cloudRow(1, override = "我改的名字")))
        assertEquals(1, trackCount(s))
        assertEquals(listOf("BV1" to 1), snapshotTrackKeys(s))
    }

    @Test fun mixedLibraryKeepsOnlyWhatMatters() {
        val s = build(
            listOf(
                cloudRow(1),                                  // 无痕迹：丢
                cloudRow(2, favorite = true),                 // 收藏：留
                cloudRow(3, categoryId = 2),                  // 非默认类别：留
                cloudRow(4, localPath = "/files/-4.audio"),   // 有文件：留
                cloudRow(5),                                  // 在播放列表里：留
                cloudRow(6, override = "改过名"),              // 改名：留
            ),
            items = listOf(PlaylistItemEntity(playlistId = 1, trackId = 5, orderIndex = 0)),
        )
        assertEquals(5, trackCount(s))
        assertEquals(
            listOf("BV2" to 1, "BV3" to 1, "BV4" to 1, "BV5" to 1, "BV6" to 1),
            snapshotTrackKeys(s),
        )
    }

    @Test fun excludedRowsDoNotChangeTheFingerprint() {
        // 同步每拉来一批云端行都会加行；若这些行进了指纹，每次拉取都会触发一次上传
        val base = listOf(cloudRow(1, localPath = "/files/-1.audio"))
        val withCloudRows = base + listOf(cloudRow(9), cloudRow(10), cloudRow(11))
        assertEquals(
            "无痕迹的云端行不能改变指纹",
            build(base).fingerprint,
            build(withCloudRows).fingerprint,
        )
    }

    @Test fun addingATraceDoesChangeTheFingerprint() {
        val base = listOf(cloudRow(9))
        val favorited = listOf(cloudRow(9, favorite = true))
        assertNotEquals(build(base).fingerprint, build(favorited).fingerprint)
    }

    @Test fun libraryOfOnlyCloudRowsProducesAnEmptySnapshot() {
        // 空快照 + BackupManager 的"0 首不上传"守卫 = 同步不会把云端备份挤掉
        val s = build((1L..5L).map { cloudRow(it) })
        assertEquals(0, trackCount(s))
        assertEquals(0, s.counts.playlistItems)
    }

    @Test fun playlistItemsOfDroppedTracksAreDroppedToo() {
        // 引用一条不存在（或已被筛掉）的行：不能让它进快照，否则恢复时会指向空行
        val s = build(
            listOf(cloudRow(1, localPath = "/files/-1.audio")),
            items = listOf(
                PlaylistItemEntity(playlistId = 1, trackId = 1, orderIndex = 0),
                PlaylistItemEntity(playlistId = 1, trackId = 999, orderIndex = 1),
            ),
        )
        assertEquals(1, s.counts.playlistItems)
    }
}
