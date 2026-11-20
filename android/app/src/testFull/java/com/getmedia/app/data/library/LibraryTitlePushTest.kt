package com.getmedia.app.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.backup.MetadataSnapshot
import com.getmedia.app.data.backup.RestoreService
import com.getmedia.app.data.backup.SnapshotReader
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阶段 5 标题推送：条件 PATCH、服务器为准、响应丢失不重发、墓碑清草稿、世代防覆盖。
 * 另外钉住备份口径：**待推送草稿不覆盖已确认标题，恢复不会生成 PATCH**。
 */
@RunWith(AndroidJUnit4::class)
class LibraryTitlePushTest {

    private lateinit var db: AppDatabase
    private val account = "boss"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun bound(
        title: String = "服务器标题",
        seq: Long = 9,
        state: String = SyncState.SYNCED.name,
        accountKey: String? = account,
    ) = TrackEntity(
        id = -201, platform = "bilibili", videoId = "BV1", title = title,
        uploader = "UP", durationSec = 10.0, audioBytes = 32, audioFormat = "m4a",
        sourceUrl = "https://www.bilibili.com/video/BV1", localPath = "/tmp/x.m4a",
        syncState = state, serverId = 501, serverSeq = seq, accountKey = accountKey,
        serverStorage = "metadata", confirmedTitle = title,
    )

    private suspend fun rename(track: TrackEntity, title: String, at: Long = 2_000): TrackEntity {
        assertTrue(LibraryLocalEdits.markTitleEdited(db, track, title, at))
        return db.trackDao().byId(track.id)!!
    }

    private fun cloud(seq: Long, title: String, deleted: Boolean = false) = CloudTrack(
        id = 501, platform = "bilibili", videoId = "BV1", page = 1, title = title,
        uploader = "UP", durationSec = 10.0, sourceUrl = "https://www.bilibili.com/video/BV1",
        mediaType = "audio", deleted = deleted, updatedSeq = seq, updatedAtSec = 1_700_000_000,
        storage = "metadata", hasFile = false,
    )

    private class FakeWrite(
        var detail: CloudTrack? = null,
        var patched: CloudTrack? = null,
        var patchError: LibrarySyncException? = null,
    ) : LibraryWriteTransport {
        val patches = mutableListOf<Triple<Long, String, Long>>()
        var onPatch: suspend () -> Unit = {}
        override suspend fun delete(trackId: Long, expectedSeq: Long): DeleteResult =
            error("标题推送不该发 DELETE")
        override suspend fun patchTitle(trackId: Long, title: String, expectedSeq: Long): CloudTrack {
            patches += Triple(trackId, title, expectedSeq)
            onPatch()
            patchError?.let { throw it }
            return patched ?: error("测试没给 PATCH 返回")
        }
        override suspend fun detail(trackId: Long): CloudTrack = detail ?: error("测试没给 detail 返回")
    }

    @Test fun renameStoresDraftSeparatelyFromConfirmedTitle() = runBlocking {
        val t = bound(title = "确认过的")
        db.trackDao().upsertAll(listOf(t))
        val changed = rename(t, "草稿")

        assertEquals("草稿", changed.title)
        assertEquals("草稿", changed.localTitleOverride)
        assertEquals("确认过的", changed.confirmedTitle)
        assertEquals("草稿", changed.pendingTitle)
        assertEquals(9L, changed.pendingTitleBaseSeq)
        assertEquals(LibraryMark.PENDING_UP, changed.syncState)
    }

    @Test fun secondRenameKeepsOriginalBaseAndLatestDraft() = runBlocking {
        val t = bound()
        db.trackDao().upsertAll(listOf(t))
        rename(t, "第一次", at = 2_000)
        val second = rename(db.trackDao().byId(t.id)!!, "第二次", at = 3_000)

        assertEquals("第二次", second.pendingTitle)
        assertEquals("服务器标题", second.confirmedTitle)
        assertEquals(9L, second.pendingTitleBaseSeq)
        assertEquals(3_000L, second.pendingTitleGen)
    }

    @Test fun successfulPatchAdoptsServerTitleAndClearsDraft() = runBlocking {
        val t = bound()
        db.trackDao().upsertAll(listOf(t))
        rename(t, "新标题")
        val write = FakeWrite(
            detail = cloud(9, "服务器标题"),
            patched = cloud(10, "新标题"),
        )
        val summary = LibraryTitlePush(db, write, account).push()

        assertEquals(1, summary.pushed)
        assertEquals(listOf(Triple(501L, "新标题", 9L)), write.patches)
        val row = db.trackDao().byId(t.id)!!
        assertEquals("新标题", row.title)
        assertEquals("新标题", row.confirmedTitle)
        assertNull(row.pendingTitle)
        assertNull(row.localTitleOverride)
        assertEquals(10L, row.serverSeq)
        assertEquals(SyncState.SYNCED.name, row.syncState)
    }

    @Test fun serverWinsWhenItChangedAfterTheDraftBase() = runBlocking {
        val t = bound()
        db.trackDao().upsertAll(listOf(t))
        rename(t, "我的草稿")
        val write = FakeWrite(detail = cloud(12, "网页后来改的"))
        val summary = LibraryTitlePush(db, write, account).push()

        assertEquals(1, summary.conflicts)
        assertTrue("服务器版本更新时不能发 PATCH", write.patches.isEmpty())
        val row = db.trackDao().byId(t.id)!!
        assertEquals("网页后来改的", row.title)
        assertEquals("网页后来改的", row.confirmedTitle)
        assertNull(row.pendingTitle)
        assertEquals(12L, row.serverSeq)
    }

    @Test fun lostResponseWithMatchingTitleIsAdoptedWithoutSecondPatch() = runBlocking {
        val t = bound()
        db.trackDao().upsertAll(listOf(t))
        rename(t, "已经上去了")
        val write = FakeWrite(detail = cloud(10, "已经上去了"))
        val summary = LibraryTitlePush(db, write, account).push()

        assertEquals(1, summary.adopted)
        assertTrue("标题已是目标值，不重发", write.patches.isEmpty())
        val row = db.trackDao().byId(t.id)!!
        assertNull(row.pendingTitle)
        assertEquals("已经上去了", row.confirmedTitle)
    }

    @Test fun tombstoneClearsDraftAndAddsRemoteMark() = runBlocking {
        val t = bound()
        db.trackDao().upsertAll(listOf(t))
        rename(t, "别推了")
        val write = FakeWrite(detail = cloud(10, "别推了", deleted = true))
        LibraryTitlePush(db, write, account).push()

        val row = db.trackDao().byId(t.id)!!
        assertNull(row.pendingTitle)
        assertTrue(write.patches.isEmpty())
        assertEquals(1, db.libraryAuxDao().markCount(account))
    }

    @Test fun offlineKeepsDraftForNextRound() = runBlocking {
        val t = bound()
        db.trackDao().upsertAll(listOf(t))
        rename(t, "等联网")
        val write = FakeWrite(
            detail = cloud(9, "服务器标题"),
            patchError = LibrarySyncException(LibrarySyncProblem.NETWORK, "断网"),
        )
        val summary = LibraryTitlePush(db, write, account).push()
        assertEquals(LibrarySyncProblem.NETWORK, summary.stopped)
        val row = db.trackDao().byId(t.id)!!
        assertEquals("等联网", row.pendingTitle)
        assertEquals(LibraryMark.PENDING_UP, row.syncState)
    }

    @Test fun lateResponseDoesNotOverwriteANewerDraft() = runBlocking {
        val t = bound()
        db.trackDao().upsertAll(listOf(t))
        rename(t, "第一版")
        val write = FakeWrite(detail = cloud(9, "服务器标题"), patched = cloud(10, "第一版"))
        write.onPatch = {
            // PATCH 在途时用户又改了一次：旧响应不能把新草稿清掉
            LibraryLocalEdits.markTitleEdited(db, db.trackDao().byId(t.id)!!, "第二版", 5_000)
        }
        LibraryTitlePush(db, write, account).push()

        val row = db.trackDao().byId(t.id)!!
        assertEquals("第二版", row.pendingTitle)
        assertEquals(5_000L, row.pendingTitleGen)
    }

    @Test fun localOnlyRowsAreNotHandledBySyncTitle() = runBlocking {
        // 纯本机行：确认返回 false，走原来的本机改名
        val pure = TrackEntity(
            id = -202, platform = "bilibili", videoId = "BV2", title = "本机",
            uploader = null, durationSec = 10.0,
            audioBytes = 1, audioFormat = "m4a", sourceUrl = "https://b/BV2",
            syncState = SyncState.LOCAL_ONLY.name,
        )
        assertEquals(false, LibraryLocalEdits.markTitleEdited(db, pure, "新名", 1))
        assertEquals(false, LibraryLocalEdits.canQueueCloudDelete(pure, account))
    }

    @Test fun backupRestoreNeverCreatesAPendingPatch() = runBlocking {
        val t = bound(title = "确认过的")
        db.trackDao().upsertAll(listOf(t))
        val changed = rename(t, "草稿")
        val snapshot = MetadataSnapshot.build(
            tracks = listOf(changed), categories = emptyList(), playlists = emptyList(),
            items = emptyList(), appVersion = "0.1.11", nowSec = 1,
        )
        val parsed = SnapshotReader.read(snapshot.gzip, null)
        // 模拟另一台设备：本机没有这一行，恢复才会插入
        db.trackDao().delete(changed.id)
        RestoreService(db).restore(parsed, nowMs = 10, account = account)

        val restored = db.trackDao().allNow().single()
        assertNull("恢复行不能带待推送草稿", restored.pendingTitle)
        assertEquals("恢复的是已确认标题", "确认过的", restored.title)
        // 备份恢复后即使跑标题推送，也不该产生 PATCH
        val write = FakeWrite()
        LibraryTitlePush(db, write, account).push()
        assertTrue(write.patches.isEmpty())
    }

    @Test fun snapshotUsesConfirmedTitleAndDropsDraftOverride() = runBlocking {
        val t = bound(title = "确认过的")
        db.trackDao().upsertAll(listOf(t))
        val changed = rename(t, "草稿")
        val snapshot = MetadataSnapshot.build(
            tracks = listOf(changed), categories = emptyList(), playlists = emptyList(),
            items = emptyList(), appVersion = "0.1.11", nowSec = 1,
        )
        val text = java.util.zip.GZIPInputStream(snapshot.gzip.inputStream())
            .bufferedReader().readText()
        assertTrue("备份用已确认标题：$text", text.contains("\"title\":\"确认过的\""))
        assertTrue("待推送草稿不能进备份：$text", !text.contains("\"title\":\"草稿\""))
        assertTrue("title_override 必须是 null：$text", text.contains("\"title_override\":null"))
        assertEquals("确认过的", MetadataSnapshot.titleOf(changed))
        assertNull(MetadataSnapshot.titleOverrideOf(changed))
        assertNotNull(changed.pendingTitle)
    }
}
