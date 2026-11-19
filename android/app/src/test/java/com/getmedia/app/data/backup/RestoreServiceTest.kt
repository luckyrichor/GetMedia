package com.getmedia.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.CategoryEntity
import com.getmedia.app.data.local.LocalIds
import com.getmedia.app.data.local.PlaylistEntity
import com.getmedia.app.data.local.PlaylistItemEntity
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 合并恢复（设计 §5.5）：空库全量、非空只补缺失、已有条目的字段一个字节都不改。
 * 用内存库跑，直接看事务落库后的结果，不经过 UI。
 */
@RunWith(AndroidJUnit4::class)
class RestoreServiceTest {

    private lateinit var db: AppDatabase
    private lateinit var service: RestoreService

    private fun track(
        platform: String = "bilibili",
        videoId: String = "BV1",
        page: Int = 1,
        title: String = "标题",
        categoryId: Long? = 1,
        playlists: List<BackupPlaylistRef> = emptyList(),
    ) = BackupTrack(
        platform = platform, videoId = videoId, page = page, title = title, uploader = "作者",
        mediaType = "AUDIO", format = "m4a", durationSec = 12.5, bytes = 2048,
        sourceUrl = "https://www.bilibili.com/video/$videoId?p=$page", addedAtMs = 1_700_000_000_000,
        favorite = true, categoryId = categoryId, sortIndex = 3, titleOverride = null, playlists = playlists,
    )

    private fun snapshot(
        tracks: List<BackupTrack>,
        categories: List<BackupCategory> = listOf(BackupCategory(2, "工作", null, "LEAF", 1)),
        playlists: List<BackupPlaylist> = listOf(BackupPlaylist(5, "默认", true, 1)),
    ) = ParsedSnapshot(
        generatedAt = 1_800_000_000, appVersion = "0.1.0",
        counts = SnapshotCounts(tracks.size, categories.size, playlists.size, tracks.sumOf { it.playlists.size }),
        categories = categories, playlists = playlists, tracks = tracks,
    )

    private fun localTrack(id: Long, title: String, favorite: Boolean = true, categoryId: Long = 1) = TrackEntity(
        id = id, platform = "bilibili", videoId = "BV1", title = title, uploader = "本地作者",
        durationSec = 1.0, audioBytes = 1, audioFormat = "m4a", sourceUrl = "https://example.com",
        localPath = "/sdcard/a.m4a", favorite = favorite, localCategoryId = categoryId,
        addedAtMs = 1, sortIndex = 0, mediaType = "AUDIO",
    )

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        // 生产库由 onCreate 回调插入 id=1 的"未分类"，内存库没有回调，这里补上
        db.categoryDao().insert(CategoryEntity(1, "未分类", null, "LEAF", 0))
        service = RestoreService(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun emptyLibraryRestoresEverything() = runBlocking {
        val result = service.restore(
            snapshot(
                tracks = listOf(
                    track(categoryId = 2, playlists = listOf(BackupPlaylistRef(5, 0))),
                    track(videoId = "BV2", categoryId = 2, playlists = listOf(BackupPlaylistRef(5, 1))),
                ),
            )
        )

        assertEquals(2, result.addedTracks)
        assertEquals(0, result.skippedTracks)
        assertEquals(1, result.addedCategories)
        assertEquals(1, result.addedPlaylists)
        assertEquals(2, result.addedItems)

        val tracks = db.trackDao().allNow()
        assertEquals(2, tracks.size)
        tracks.forEach {
            assertNull("恢复回来的条目没有文件", it.localPath)
            assertEquals("工作", db.categoryDao().byId(it.localCategoryId)?.name)
        }
        assertEquals(2, db.playlistDao().itemsAll().size)
    }

    @Test
    fun existingTrackIsSkippedAndNotRewritten() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV1", 1)
        db.trackDao().upsertAll(listOf(localTrack(id, "我改过的标题", favorite = false, categoryId = 1)))

        val result = service.restore(snapshot(tracks = listOf(track(), track(videoId = "BV2"))))

        assertEquals(1, result.addedTracks)
        assertEquals(1, result.skippedTracks)
        val kept = db.trackDao().byId(id)
        assertNotNull(kept)
        assertEquals("我改过的标题", kept!!.title)
        assertEquals(false, kept.favorite)
        assertEquals(1L, kept.localCategoryId)
        assertEquals("/sdcard/a.m4a", kept.localPath)
    }

    @Test
    fun multiPageBecomesTwoRowsWithTheSameIdsAsBefore() = runBlocking {
        service.restore(snapshot(tracks = listOf(track(page = 1), track(page = 2))))

        val tracks = db.trackDao().allNow().sortedBy { it.serverPage }
        assertEquals(2, tracks.size)
        assertEquals("BV1", tracks[0].videoId)
        assertEquals("BV1-p2", tracks[1].videoId)
        assertEquals(LocalIds.localId("bilibili", "BV1", 1), tracks[0].id)
        assertEquals(LocalIds.localId("bilibili", "BV1", 2), tracks[1].id)
        assertEquals(2, tracks.map { it.id }.toSet().size)
    }

    @Test
    fun existingPlaylistAndCategoryAreReusedByName() = runBlocking {
        db.playlistDao().insert(PlaylistEntity(9, "默认", 1))
        db.categoryDao().insert(CategoryEntity(7, "工作", null, "LEAF", 1))

        val result = service.restore(snapshot(tracks = listOf(track(categoryId = 2, playlists = listOf(BackupPlaylistRef(5, 0))))))

        assertEquals("同名列表复用，不新建", 0, result.addedPlaylists)
        assertEquals("同名类别复用，不新建", 0, result.addedCategories)
        assertEquals(9L, db.playlistDao().itemsAll().first().playlistId)
        assertEquals(7L, db.trackDao().allNow().first().localCategoryId)
    }

    @Test
    fun restoringTwiceAddsNothingTheSecondTime() = runBlocking {
        val snap = snapshot(tracks = listOf(track(playlists = listOf(BackupPlaylistRef(5, 0)))))
        service.restore(snap)
        val second = service.restore(snap)

        assertEquals(0, second.addedTracks)
        assertEquals(1, second.skippedTracks)
        assertEquals(0, second.addedItems)
        assertEquals(1, db.trackDao().allNow().size)
        assertEquals(1, db.playlistDao().itemsAll().size)
    }

    @Test
    fun existingPlaylistOrderIsNotTouched() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV1", 1)
        val playlistId = db.playlistDao().insert(PlaylistEntity(0, "默认", 1))
        db.trackDao().upsertAll(listOf(localTrack(id, "标题")))
        db.playlistDao().add(listOf(PlaylistItemEntity(playlistId, id, 1, 7)))

        service.restore(snapshot(tracks = listOf(track(playlists = listOf(BackupPlaylistRef(5, 0))))))

        val items = db.playlistDao().itemsAll()
        assertEquals(1, items.size)
        assertEquals("本地已有的顺序不动", 7, items.first().orderIndex)
    }
}
