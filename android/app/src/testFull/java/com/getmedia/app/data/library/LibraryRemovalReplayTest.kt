package com.getmedia.app.data.library

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.LocalIds
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity
import com.getmedia.app.data.local.TrackRemovalEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 阶段 5 删除重放：条件 DELETE、失败退避、重复幂等、先删后加、迟到旧响应、归档保护。
 *
 * 每条都以"文件字节数与调用次数"收尾，钉住一条硬约束：**任何自动路径都不删本机文件**。
 */
@RunWith(AndroidJUnit4::class)
class LibraryRemovalReplayTest {

    private lateinit var db: AppDatabase
    private val account = "boss"
    private val files = mutableListOf<File>()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        db.close()
        files.forEach { it.delete() }
    }

    private fun file(bytes: ByteArray = ByteArray(32) { 7 }): File =
        File.createTempFile("gm-removal-", ".audio").apply { writeBytes(bytes) }.also { files += it }

    private fun bound(
        id: Long = -101,
        videoId: String = "BV1",
        localPath: String? = null,
        serverSeq: Long = 9,
        addedAt: Long = 1_000,
        state: String = SyncState.SYNCED.name,
        storage: String? = "metadata",
        accountKey: String? = account,
    ) = TrackEntity(
        id = id, platform = "bilibili", videoId = videoId, title = "这一条",
        uploader = "UP", durationSec = 10.0, audioBytes = 32, audioFormat = "m4a",
        sourceUrl = "https://www.bilibili.com/video/$videoId", localPath = localPath,
        addedAtMs = addedAt, syncState = state, serverId = 501, serverSeq = serverSeq,
        accountKey = accountKey, serverStorage = storage,
    )

    private suspend fun enqueue(track: TrackEntity, at: Long = 2_000): TrackRemovalEntity =
        db.withTransaction {
            LibraryLocalEdits.queue(db, account, listOf(track), setOf(track.id), at)
            db.libraryAuxDao().removals(account).single()
        }

    private class FakeWrite(
        var deleteError: LibrarySyncException? = null,
        var detail: CloudTrack? = null,
        var patchError: LibrarySyncException? = null,
        var patched: CloudTrack? = null,
    ) : LibraryWriteTransport {
        val deletes = mutableListOf<Pair<Long, Long>>()
        var onDelete: suspend () -> Unit = {}
        override suspend fun delete(trackId: Long, expectedSeq: Long): DeleteResult {
            deletes += trackId to expectedSeq
            onDelete()
            deleteError?.let { throw it }
            return DeleteResult(trackId, true, expectedSeq + 1)
        }

        override suspend fun patchTitle(trackId: Long, title: String, expectedSeq: Long): CloudTrack {
            patchError?.let { throw it }
            return patched ?: error("测试没给 PATCH 返回")
        }

        override suspend fun detail(trackId: Long): CloudTrack =
            detail ?: error("测试没给 detail 返回")
    }

    private fun detail(track: TrackEntity, seq: Long = track.serverSeq ?: 9, deleted: Boolean = false) =
        CloudTrack(
            id = track.serverId ?: 501, platform = "bilibili", videoId = "BV1", page = 1,
            title = track.title, uploader = "UP", durationSec = 10.0,
            sourceUrl = track.sourceUrl, mediaType = "audio", deleted = deleted,
            updatedSeq = seq, updatedAtSec = 1_700_000_000, storage = "metadata", hasFile = false,
        )

    @Test fun successDropsQueueAndKeepsFileBytes() = runBlocking {
        val f = file()
        val before = f.readBytes()
        val t = bound(localPath = f.absolutePath)
        db.trackDao().upsertAll(listOf(t))
        enqueue(t)

        val write = FakeWrite()
        val summary = LibraryRemovalReplay(db, write, account).replay()

        assertEquals(1, summary.replayed)
        assertEquals(listOf(501L to 9L), write.deletes)
        assertEquals(0, db.libraryAuxDao().removalCount(account))
        assertEquals("文件必须原样保留", before.toList(), f.readBytes().toList())
        assertNotNull(db.trackDao().byId(t.id))
    }

    @Test fun repeatedReplayIsIdempotent() = runBlocking {
        val t = bound()
        db.trackDao().upsertAll(listOf(t))
        enqueue(t)
        val write = FakeWrite()
        LibraryRemovalReplay(db, write, account).replay()
        LibraryRemovalReplay(db, write, account).replay()
        assertEquals("成功一次后不能再次发 DELETE", 1, write.deletes.size)
    }

    @Test fun trackNotFoundStillEndsTheQueue() = runBlocking {
        val t = bound()
        db.trackDao().upsertAll(listOf(t))
        enqueue(t)
        val write = FakeWrite(deleteError = LibrarySyncException(
            LibrarySyncProblem.TRACK_NOT_FOUND, "不存在", "track_not_found"))
        val summary = LibraryRemovalReplay(db, write, account).replay()
        assertEquals(1, summary.replayed)
        assertEquals(0, db.libraryAuxDao().removalCount(account))
    }

    @Test fun archiveProtectedKeepsQueueAsWaiting() = runBlocking {
        val f = file()
        val before = f.readBytes()
        val t = bound(localPath = f.absolutePath, storage = "archive")
        db.trackDao().upsertAll(listOf(t))
        // UI 不会给 archive 行这个选项；这里直接入队，验证服务端拒绝时客户端不误清队列
        db.libraryAuxDao().addRemoval(
            TrackRemovalEntity(platform = "bilibili", videoKey = "BV1", account = account,
                remoteId = t.serverId, requestedAt = 2_000, expectedSeq = t.serverSeq, generation = 2_000),
        )
        val write = FakeWrite(deleteError = LibrarySyncException(
            LibrarySyncProblem.ARCHIVE_PROTECTED, "归档保留", "archive_protected"))
        val summary = LibraryRemovalReplay(db, write, account).replay()

        assertEquals(1, summary.waiting)
        assertEquals("归档拒绝要单独计数，用于给用户明确提示", 1, summary.archiveProtected)
        val op = db.libraryAuxDao().removals(account).single()
        assertEquals(LibraryMark.REMOVAL_WAITING, op.state)
        assertEquals(before.toList(), f.readBytes().toList())
        // WAITING 不再自动重试，避免每轮都打一次必然被拒的请求
        LibraryRemovalReplay(db, write, account).replay()
        assertEquals(1, write.deletes.size)
    }

    @Test fun archiveProtectedMarksRowArchiveWhenStorageWasUnknown() = runBlocking {
        // 本机不知道它是归档（storage 空）：照样给勾选项、照样入队；
        // 服务端拒绝后本机不用再猜——把这一行标成 archive，下次删除确认就不会再给错选项。
        val t = bound(localPath = file().absolutePath, storage = null)
        db.trackDao().upsertAll(listOf(t))
        db.libraryAuxDao().addRemoval(
            TrackRemovalEntity(platform = "bilibili", videoKey = "BV1", account = account,
                remoteId = t.serverId, requestedAt = 2_000, expectedSeq = t.serverSeq, generation = 2_000),
        )
        val write = FakeWrite(deleteError = LibrarySyncException(
            LibrarySyncProblem.ARCHIVE_PROTECTED, "归档保留", "archive_protected"))
        val summary = LibraryRemovalReplay(db, write, account).replay()

        assertEquals(1, summary.archiveProtected)
        assertEquals("archive", db.trackDao().byId(t.id)?.serverStorage)
        assertTrue("本机文件一律不动", File(t.localPath!!).exists())
    }

    @Test fun versionConflictPausesButDoesNotUseNewVersion() = runBlocking {
        val t = bound()
        db.trackDao().upsertAll(listOf(t))
        enqueue(t)
        val write = FakeWrite(
            deleteError = LibrarySyncException(
                LibrarySyncProblem.VERSION_CONFLICT, "变了", "track_version_conflict"),
            detail = detail(t, seq = 12),
        )
        val summary = LibraryRemovalReplay(db, write, account).replay()
        assertEquals(1, summary.waiting)
        assertEquals(LibraryMark.REMOVAL_WAITING, db.libraryAuxDao().removals(account).single().state)
        assertEquals("不能用新版本偷偷重试", listOf(501L to 9L), write.deletes)
    }

    @Test fun versionConflictWithTombstoneEndsTheQueue() = runBlocking {
        val t = bound()
        db.trackDao().upsertAll(listOf(t))
        enqueue(t)
        val write = FakeWrite(
            deleteError = LibrarySyncException(
                LibrarySyncProblem.VERSION_CONFLICT, "变了", "track_version_conflict"),
            detail = detail(t, seq = 12, deleted = true),
        )
        LibraryRemovalReplay(db, write, account).replay()
        assertEquals(0, db.libraryAuxDao().removalCount(account))
    }

    @Test fun networkFailureKeepsQueueForNextRound() = runBlocking {
        val t = bound()
        db.trackDao().upsertAll(listOf(t))
        enqueue(t)
        val write = FakeWrite(deleteError = LibrarySyncException(
            LibrarySyncProblem.NETWORK, "断网了"))
        val summary = LibraryRemovalReplay(db, write, account).replay()
        assertEquals(LibrarySyncProblem.NETWORK, summary.stopped)
        assertEquals(1, db.libraryAuxDao().removalCount(account))
    }

    @Test fun readdedBeforeSendCancelsTheOldDelete() = runBlocking {
        val old = bound(addedAt = 1_000)
        db.trackDao().upsertAll(listOf(old))
        val op = enqueue(old, at = 2_000)
        // 用户在删除请求发出前又提取了一条同键内容（新行、有文件）
        val f = file()
        db.trackDao().delete(old.id)
        db.trackDao().upsertAll(listOf(
            bound(id = LocalIds.localId("bilibili", "BV1", 1), localPath = f.absolutePath,
                addedAt = 3_000, state = SyncState.LOCAL_ONLY.name).copy(serverId = null, serverSeq = null),
        ))

        val write = FakeWrite()
        val summary = LibraryRemovalReplay(db, write, account).replay()

        assertEquals(1, summary.cancelled)
        assertTrue("请求发出前就取消，不该有 DELETE", write.deletes.isEmpty())
        assertEquals(0, db.libraryAuxDao().removalCount(account))
        assertTrue(f.isFile)
        assertNull(db.libraryAuxDao().removal(account, "bilibili", op.videoKey))
    }

    @Test fun readdedAfterServerDeleteIsDetachedAndKeepsFile() = runBlocking {
        val old = bound(addedAt = 1_000)
        db.trackDao().upsertAll(listOf(old))
        val op = enqueue(old, at = 2_000)
        // 请求已经发出（结果未知）之后，本机又加回同键内容
        db.libraryAuxDao().markRemoval(op.platform, op.videoKey, op.account, op.generation,
            LibraryMark.REMOVAL_SENDING, 0, null)
        val f = file()
        db.trackDao().delete(old.id)
        db.trackDao().upsertAll(listOf(
            bound(id = LocalIds.localId("bilibili", "BV1", 1), localPath = f.absolutePath,
                addedAt = 3_000, state = SyncState.LOCAL_ONLY.name).copy(serverId = null, serverSeq = null),
        ))

        val write = FakeWrite()
        write.onDelete = {
            // 服务器确认删除；本机新行仍在
            check(f.isFile)
        }
        LibraryRemovalReplay(db, write, account).replay()

        val row = db.trackDao().allNow().single()
        assertEquals(LibraryMark.DETACHED, row.syncState)
        assertNull("脱钩后不能再有服务器身份", row.serverId)
        assertTrue("文件必须保留", f.isFile)
        assertEquals(0, db.libraryAuxDao().removalCount(account))
        // 脱钩行不满足上传准入
        assertFalse(LibraryLocalEdits.canQueueCloudDelete(row, account))
    }

    @Test fun lateResponseOnlyClearsTheSameGeneration() = runBlocking {
        val t = bound()
        db.trackDao().upsertAll(listOf(t))
        val first = enqueue(t, at = 2_000)
        val write = FakeWrite()
        write.onDelete = {
            // 请求在途时用户又删了一次：新世代覆盖旧记录
            db.withTransaction {
                LibraryLocalEdits.queue(db, account, listOf(t), setOf(t.id), 3_000)
            }
        }
        LibraryRemovalReplay(db, write, account).replay()

        val current = db.libraryAuxDao().removals(account).single()
        assertTrue("新操作不能被迟到的旧响应清掉", current.generation > first.generation)
        assertEquals(1, db.libraryAuxDao().removalCount(account))
    }

    @Test fun olderRemovalOfAnotherAccountIsIgnored() = runBlocking {
        val t = bound()
        db.trackDao().upsertAll(listOf(t))
        db.libraryAuxDao().addRemoval(
            TrackRemovalEntity(platform = "bilibili", videoKey = "BV1", account = "other",
                remoteId = 501, requestedAt = 1, expectedSeq = 9, generation = 1),
        )
        val write = FakeWrite()
        LibraryRemovalReplay(db, write, account).replay()
        assertTrue(write.deletes.isEmpty())
        assertEquals(1, db.libraryAuxDao().removalCount("other"))
    }

    /**
     * 本机导入的条目：删它就要真的把云端那份删掉。
     *
     * 队列键用 `videoId`（`loc_<uuid>`，不随改名变），重放时发的是 `remoteId` + `expectedSeq`。
     * 以前这里一条都进不来——`canQueueCloudDelete` 最后一句是 `restoredKey() != null`，而
     * `restoredIdentity` 只认 bilibili / douyin，`local` 恒为 null；结果手机上删了，云端行
     * 与归档文件原地不动，另一台手机还能看到、还能下回来，还白占配额。
     */
    @Test fun localImportedRowQueuesARealCloudDelete() = runBlocking {
        val row = imported(id = -201, videoId = "loc_6f1c", serverId = 82, serverSeq = 23)
        db.trackDao().upsertAll(listOf(row))

        val op = enqueue(row)
        assertEquals("local", op.platform)
        assertEquals("队列键就是 video_id，不靠链接解析", "loc_6f1c", op.videoKey)
        assertEquals(82L, op.remoteId)
        assertEquals(23L, op.expectedSeq)

        val write = FakeWrite()
        LibraryRemovalReplay(db, write, account).replay()
        assertEquals("要真的发出 DELETE（带 If-Match 的版本）", listOf(82L to 23L), write.deletes)
        assertEquals("成功之后队列清空", 0, db.libraryAuxDao().removalCount(account))
    }

    /** 本机导入的那份是用户自己的备份，服务端也不会拿 `archive_protected` 拦它。 */
    @Test fun localArchiveIsNotTreatedAsProtected() = runBlocking {
        val row = imported(id = -202, videoId = "loc_7a2d", serverId = 83, serverSeq = 24)
        db.trackDao().upsertAll(listOf(row))

        assertFalse(LibraryLocalEdits.isKnownArchive(row))
        assertTrue(LibraryLocalEdits.canQueueCloudDelete(row, account))
        assertEquals("archive", db.trackDao().byId(row.id)!!.serverStorage)
    }

    private fun imported(
        id: Long,
        videoId: String,
        serverId: Long,
        serverSeq: Long,
    ) = TrackEntity(
        id = id, platform = "local", videoId = videoId, title = "自己录的一段",
        uploader = null, durationSec = 12.0, audioBytes = 4096, audioFormat = "mp3",
        sourceUrl = "", localPath = null, mediaType = "audio",
        syncState = SyncState.SYNCED.name, serverId = serverId, serverSeq = serverSeq,
        accountKey = account, serverStorage = "archive",
    )
}
