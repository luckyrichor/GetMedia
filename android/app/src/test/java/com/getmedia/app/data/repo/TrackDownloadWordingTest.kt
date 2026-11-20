package com.getmedia.app.data.repo

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.PlaylistEntity
import com.getmedia.app.data.local.PlaylistItemEntity
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * `TrackRepository.download()` 的**说法分流**（P0-2 阶段 3 硬性验收 ③）。
 *
 * 三种"拿不到文件"必须说三种话，说错会让人白做一遍：
 *
 * - 云端行还没下载过（`serverId` 有值、`localPath` 为 null）→ "尚未下载到本机，请先下载"；
 * - 本机产物真的丢了（有 `localPath` 但文件不在）→ "本机音频文件已丢失，请重新提取"；
 * - 剪辑副本丢了 → "本地剪辑文件已丢失，请重新剪辑"（既有规则，不能被上面两条挤掉）。
 */
@RunWith(AndroidJUnit4::class)
class TrackDownloadWordingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val repo = TrackRepository(context)
    private val db = AppDatabase.get(context)

    private fun row(
        id: Long,
        serverId: Long? = null,
        localPath: String? = null,
        locallyEdited: Boolean = false,
    ) = TrackEntity(
        id = id, platform = "bilibili", videoId = "BVdl$id", title = "第 $id 条",
        uploader = null, durationSec = 10.0, audioBytes = null, audioFormat = "m4a",
        sourceUrl = "https://x", localPath = localPath, locallyEdited = locallyEdited,
        serverId = serverId,
    )

    private fun seed(track: TrackEntity) = runBlocking { db.trackDao().upsertAll(listOf(track)) }

    @Test
    fun cloudRowSaysNotDownloadedYet() = runBlocking {
        val track = row(id = -9001L, serverId = 41L)
        seed(track)
        val error = repo.download(track).exceptionOrNull()
        assertEquals("尚未下载到本机，请先下载", error?.message)
    }

    @Test
    fun lostLocalFileKeepsOldWording() = runBlocking {
        val track = row(id = -9002L, localPath = File(context.cacheDir, "gone-9002.audio").absolutePath)
        seed(track)
        val error = repo.download(track).exceptionOrNull()
        assertEquals("本机音频文件已丢失，请重新提取", error?.message)
    }

    @Test
    fun lostEditCopyKeepsItsOwnWording() = runBlocking {
        val track = row(id = -9003L, localPath = File(context.cacheDir, "gone-edit.audio").absolutePath, locallyEdited = true)
        seed(track)
        val error = repo.download(track).exceptionOrNull()
        assertEquals("本地剪辑文件已丢失，请重新剪辑", error?.message)
    }

    @Test
    fun realFileIsReturnedNoMatterWhatState() = runBlocking {
        val file = File(context.cacheDir, "present-9004.audio").apply { writeBytes(ByteArray(16) { 2 }) }
        val track = row(id = -9004L, serverId = 41L, localPath = file.absolutePath)
        seed(track)
        assertNull(repo.download(track).exceptionOrNull())
        assertEquals(file.absolutePath, repo.download(track).getOrThrow().absolutePath)
    }

    @Test fun refillUpdatesOriginalAndRetainsLatestLocalOrganization() = runBlocking {
        val original = row(id = -9005L, serverId = 6L).copy(
            favorite = true, localCategoryId = 8, accountKey = "owner",
        )
        seed(original)
        val playlist = db.playlistDao().insert(PlaylistEntity(name = "refill test"))
        val listItem = PlaylistItemEntity(playlistId = playlist, trackId = original.id, orderIndex = 7)
        db.playlistDao().add(listOf(listItem))
        db.trackDao().rename(original.id, "renamed during download", 123)
        val bytes = "P3 audio".toByteArray()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val staged = File.createTempFile("refill-9005-", ".part", context.cacheDir).apply { writeBytes(bytes) }
        val saved = repo.fillExistingFile(original, staged, bytes.size.toLong(), hash, 10, 11) { true }
        assertEquals(original.id, saved.id)
        assertEquals(original.serverId, saved.serverId)
        assertEquals("renamed during download", saved.title)
        assertEquals(saved.title, saved.localTitleOverride)
        assertTrue(saved.favorite)
        assertEquals(8L, saved.localCategoryId)
        assertEquals(listOf(listItem), db.playlistDao().itemsNow(playlist))
        assertEquals(saved, db.trackDao().byId(original.id))
        assertTrue(File(requireNotNull(saved.localPath)).readBytes().contentEquals(bytes))
    }

    @Test fun refillAfterAccountChangeLeavesOriginalUntouched() = runBlocking {
        val original = row(id = -9006L, serverId = 6L).copy(accountKey = "owner")
        seed(original)
        val staged = File.createTempFile("refill-9006-", ".part", context.cacheDir).apply { writeText("P3") }
        val failure = runCatching {
            repo.fillExistingFile(original, staged, 2, "unused", 10, 11) { false }
        }
        assertTrue(failure.isFailure)
        assertEquals(original, db.trackDao().byId(original.id))
        assertTrue(staged.isFile)
    }
}
