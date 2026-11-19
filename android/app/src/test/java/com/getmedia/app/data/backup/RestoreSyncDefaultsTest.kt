package com.getmedia.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.CategoryEntity
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 恢复出来的行在六个同步列上取什么值——这是给 P0-2 的接口约定，不是实现细节。
 *
 * 恢复的行"只有元数据、没有文件"，如果被 P0-2 当成"用户在本机新增的条目"上传，
 * 服务器上就会出现和用户库里已有条目重复的新行。所以默认值必须钉住：
 *
 * | 列 | 值 | 含义 |
 * |---|---|---|
 * | `syncState` | `RESTORED` | 不是本机新增，别直接上传 |
 * | `serverId` | `null` | 服务器身份未知 |
 * | `serverSeq` | `null` | 同上，没得比版本 |
 * | `serverUpdatedAtMs` | `null` | 同上 |
 * | `accountKey` | `null` | 不属于任何已登录账号的同步范围 |
 * | `serverPage` | 快照里的 `page` | P2 必须留 2，否则和 P1 撞 id |
 *
 * 改这张表之前先想清楚 P0-2 会怎么读它。
 */
@RunWith(AndroidJUnit4::class)
class RestoreSyncDefaultsTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        db.categoryDao().insert(CategoryEntity(1, "未分类", null, "LEAF", 0))
        Unit
    }

    @After
    fun tearDown() = db.close()

    private fun backupTrack(page: Int) = BackupTrack(
        platform = "bilibili", videoId = "BV1", page = page, title = "第 $page P",
        uploader = "作者", mediaType = "AUDIO", format = "m4a", durationSec = 10.0, bytes = 1024,
        sourceUrl = "https://www.bilibili.com/video/BV1", addedAtMs = 1_700_000_000_000,
        favorite = false, categoryId = 1, sortIndex = 0, titleOverride = null, playlists = emptyList(),
    )

    private suspend fun restore(vararg tracks: BackupTrack) =
        RestoreService(db).restore(
            ParsedSnapshot(
                generatedAt = 1_800_000_000, appVersion = "0.1.0",
                counts = SnapshotCounts(tracks.size, 1, 0, 0),
                categories = listOf(BackupCategory(1, "未分类", null, "LEAF", 0)),
                playlists = emptyList(),
                tracks = tracks.toList(),
            )
        )

    @Test
    fun restoredRowsCarryNoServerIdentityAndAreNotPendingUpload() = runBlocking {
        restore(backupTrack(page = 1), backupTrack(page = 2))

        val rows = db.trackDao().allNow()
        assertEquals(2, rows.size)
        for (row in rows) {
            assertEquals("不是本机新增，不能当待上传", SyncState.RESTORED.name, row.syncState)
            assertNull("服务器身份未知", row.serverId)
            assertNull(row.serverSeq)
            assertNull(row.serverUpdatedAtMs)
            assertNull("恢复与本机账号无关", row.accountKey)
            assertNull("没有文件", row.localPath)
        }
        assertEquals(
            "P2 的页码必须留着，否则恢复成 P1",
            listOf(1, 2),
            rows.map { it.serverPage }.sorted(),
        )
    }

    @Test
    fun restoredRowIsNotEligibleForLibraryUploadEvenAfterRedownload() = runBlocking {
        restore(backupTrack(page = 2))
        val row = db.trackDao().allNow().single()

        assertFalse(
            "恢复的行即便补回了文件，也不该被 P0-2 当成新条目上传",
            SyncState.mayUploadToLibrary(SyncState.of(row.syncState), hasLocalFile = true),
        )
        // 反向对照：本机新提取的行（有文件）才在准入范围内，证明上面的 false 不是因为规则恒假
        assertTrue(SyncState.mayUploadToLibrary(SyncState.LOCAL_ONLY, hasLocalFile = true))
        assertFalse("没有文件的行传上去只是空壳", SyncState.mayUploadToLibrary(SyncState.LOCAL_ONLY, hasLocalFile = false))
        assertFalse(SyncState.mayUploadToLibrary(SyncState.SYNCED, hasLocalFile = true))
    }

    @Test
    fun redownloadKeepsTheRestoredMarker() = runBlocking {
        // 重新下载只是把文件补回来，这条从来不是"本机新增"：写回时不能把状态刷回 LOCAL_ONLY
        val id = -42L
        db.trackDao().upsertAll(
            listOf(
                TrackEntity(
                    id = id, platform = "bilibili", videoId = "BV1", title = "P2", uploader = null,
                    durationSec = null, audioBytes = null, audioFormat = "m4a",
                    sourceUrl = "https://www.bilibili.com/video/BV1", localPath = null,
                    favorite = false, localCategoryId = 1, addedAtMs = 1, sortIndex = 0,
                    mediaType = "AUDIO", serverPage = 2, syncState = SyncState.RESTORED.name,
                )
            )
        )
        val runner = RedownloadRunner(
            db = db,
            extract = { _, _ ->
                TrackEntity(
                    id = 0, platform = "bilibili", videoId = "BV1-p2", title = "P2", uploader = "作者",
                    durationSec = 1.0, audioBytes = 10, audioFormat = "m4a",
                    sourceUrl = "https://www.bilibili.com/video/BV1?p=2", localPath = "/sdcard/p2.m4a",
                    favorite = false, localCategoryId = 1, addedAtMs = 0, sortIndex = 0, mediaType = "AUDIO",
                )
            },
            sleep = { },
        )
        runner.start(listOf(id))
        val deadline = System.currentTimeMillis() + 5_000
        while (runner.state.value.running && System.currentTimeMillis() < deadline) Thread.sleep(20)

        val saved = db.trackDao().byId(id)!!
        assertEquals("/sdcard/p2.m4a", saved.localPath)
        assertEquals(SyncState.RESTORED.name, saved.syncState)
        assertEquals(2, saved.serverPage)
        assertNull(saved.serverId)
    }
}
