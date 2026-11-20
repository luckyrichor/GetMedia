package com.getmedia.app.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.PlaylistEntity
import com.getmedia.app.data.local.PlaylistItemEntity
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 「移除这条」只对**没有本机文件的空壳**开放（阶段 6 硬条件 b）：
 *
 * - 删本机行 + 清播放列表引用；
 * - **不写** `track_removals`（云端已经是墓碑或根本没有这一条，再删一次是多余请求）；
 * - **不删任何文件**；
 * - 有文件的行一律拒绝（那里要走普通删除，才有"文件也删掉吗"的确认）。
 */
@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.Config(sdk = [33])
class RemoveShellTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = TrackRepository(context)
    private val db = AppDatabase.get(context)

    private fun shell(id: Long) = TrackEntity(
        id = id, platform = "bilibili", videoId = "BVshell$id", title = "空壳 $id",
        uploader = null, durationSec = 30.0, audioBytes = null, audioFormat = "m4a",
        sourceUrl = "https://www.bilibili.com/video/BVshell$id", localPath = null,
        syncState = SyncState.RESTORED.name, accountKey = "owner1", mediaType = "AUDIO",
    )

    @Test
    fun removingShellDropsRowAndPlaylistReferenceWithoutQueueingRemoval() = runBlocking {
        val id = -9201L
        db.trackDao().upsertAll(listOf(shell(id)))
        val listId = db.playlistDao().insert(PlaylistEntity(name = "空壳测试", isDefault = false))
        db.playlistDao().add(listOf(PlaylistItemEntity(playlistId = listId, trackId = id, orderIndex = 0)))
        assertEquals(0, db.libraryAuxDao().removalCount("owner1"))

        val removed = repo.removeShell(id)

        assertEquals(1, removed)
        assertNull(db.trackDao().byId(id))
        assertTrue(
            "播放列表引用要一起清掉",
            db.playlistDao().itemsNow(listId).none { it.trackId == id },
        )
        assertEquals("空壳不写云端删除队列", 0, db.libraryAuxDao().removalCount("owner1"))
        db.playlistDao().delete(listId)
    }

    @Test
    fun removingShellDoesNotTouchFiles() = runBlocking {
        val id = -9202L
        val watched = File(context.cacheDir, "keep-me-$id.audio").apply { writeBytes(ByteArray(8) { 3 }) }
        db.trackDao().upsertAll(listOf(shell(id)))
        repo.removeShell(id)
        assertNull(db.trackDao().byId(id))
        assertTrue("这里没有文件也不该动别的文件", watched.isFile)
        watched.delete()
        Unit
    }

    @Test
    fun rowWithLocalFileIsRefused() = runBlocking {
        val id = -9203L
        val file = File(context.cacheDir, "has-file-$id.audio").apply { writeBytes(ByteArray(8) { 4 }) }
        val row = shell(id).copy(localPath = file.absolutePath, syncState = SyncState.LOCAL_ONLY.name)
        db.trackDao().upsertAll(listOf(row))

        val error = runCatching { repo.removeShell(id) }.exceptionOrNull()

        assertNotNull("有文件的行不能走「移除这条」", error)
        assertTrue(error!!.message!!.contains("普通删除"))
        assertNotNull("行必须还在，等用户走普通删除", db.trackDao().byId(id))
        assertTrue("文件必须还在", file.isFile)
        db.trackDao().deleteByIds(listOf(id))
        file.delete()
        Unit
    }
}
