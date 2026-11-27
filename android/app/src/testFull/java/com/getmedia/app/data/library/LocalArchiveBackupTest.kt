package com.getmedia.app.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.ArchiveState
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 本机导入文件 → 服务器归档的备份队列（v7）。
 *
 * 这几条是这次改动的边界，钉死它们比"能传上去"更重要：
 *
 * - 只碰 `platform=local`、且归属本账号、且本地文件真在的行；
 * - 传完**不删**本机副本（播放永远读本地）；
 * - 网络类失败按 2/5/15/60/300 秒退避、一轮 5 次后冷却；空间不足这类直接标"无法备份"；
 * - 云端那份被淘汰后要变成 `EVICTED`，不能静静地假装还备份着。
 */
@RunWith(AndroidJUnit4::class)
class LocalArchiveBackupTest {

    private lateinit var db: AppDatabase
    private val account = "boss"
    private val files = mutableListOf<File>()
    private var nextId = -1L
    private var now = 1_700_000_000_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() {
        db.close()
        files.forEach { it.delete() }
    }

    private fun audioFile(): File =
        File.createTempFile("gm-archive-", ".audio").apply { writeBytes(ByteArray(64) { 7 }) }
            .also { files += it }

    private fun local(
        path: String? = null,
        state: ArchiveState = ArchiveState.PENDING,
        serverId: Long? = 42,
        accountKey: String? = account,
        platform: String = "local",
        serverStorage: String? = "metadata",
        nextAt: Long = 0,
        tries: Int = 0,
    ) = TrackEntity(
        id = nextId--,
        platform = platform,
        videoId = "loc_test${nextId}",
        title = "本机导入的一条",
        uploader = null,
        durationSec = 3.0,
        audioBytes = 64,
        audioFormat = "mp3",
        sourceUrl = "",
        localPath = path,
        serverId = serverId,
        serverSeq = 9,
        accountKey = accountKey,
        serverStorage = serverStorage,
        archiveState = state.name,
        archiveTries = tries,
        archiveNextAt = nextAt,
    )

    private class Fake(val answer: (Long) -> CloudTrack) : LocalFileUploadTransport {
        val calls = mutableListOf<Long>()
        override suspend fun uploadFile(trackId: Long, file: File): CloudTrack {
            calls += trackId
            return answer(trackId)
        }
    }

    private fun cloud(id: Long) = CloudTrack(
        id = id, platform = "local", videoId = "loc_x", page = 1, title = "本机导入的一条",
        uploader = null, durationSec = 3.0, sourceUrl = "", mediaType = "audio", deleted = false,
        updatedSeq = 77, updatedAtSec = now / 1000, storage = "archive", hasFile = true,
    )

    private fun backup(transport: LocalFileUploadTransport) =
        LocalArchiveBackup(db, transport, account, nowMs = { now })

    @Test fun uploadMarksDoneAndKeepsLocalCopy() = runBlocking {
        val file = audioFile()
        val row = local(path = file.absolutePath)
        db.trackDao().upsertAll(listOf(row))
        val transport = Fake { cloud(it) }

        val summary = backup(transport).run()

        assertEquals(1, summary.uploaded)
        assertEquals("传的是服务器条目编号", listOf(42L), transport.calls)
        val after = db.trackDao().byId(row.id)!!
        assertEquals(ArchiveState.DONE, ArchiveState.of(after.archiveState))
        assertEquals("archive", after.serverStorage)
        assertEquals(77L, after.serverSeq)
        assertEquals(0, after.archiveTries)
        assertTrue("传完必须保留本机副本（播放读的就是它）", file.isFile)
    }

    @Test fun networkFailureBacksOffThenCoolsDown() = runBlocking {
        val row = local(path = audioFile().absolutePath)
        db.trackDao().upsertAll(listOf(row))
        val transport = object : LocalFileUploadTransport {
            override suspend fun uploadFile(trackId: Long, file: File): CloudTrack =
                throw LibrarySyncException(LibrarySyncProblem.NETWORK, "网络不可用")
        }

        // 第 1 次失败：等 2 秒
        backup(transport).run()
        var after = db.trackDao().byId(row.id)!!
        assertEquals(ArchiveState.PENDING, ArchiveState.of(after.archiveState))
        assertEquals(1, after.archiveTries)
        assertEquals(now + 2_000, after.archiveNextAt)

        // 第 5 次失败：进入冷却（10 分钟），而且不再立刻重试
        now += 3_000
        after = after.copy(archiveTries = 4, archiveNextAt = 0)
        db.trackDao().updateExisting(after)
        backup(transport).run()
        after = db.trackDao().byId(row.id)!!
        assertEquals(5, after.archiveTries)
        assertEquals(now + 10 * 60 * 1000L, after.archiveNextAt)

        // 冷却没到：一个请求都不发
        val quiet = Fake { cloud(it) }
        backup(quiet).run()
        assertTrue("冷却期内不该重试", quiet.calls.isEmpty())
    }

    @Test fun quotaExceededStopsRetrying() = runBlocking {
        val row = local(path = audioFile().absolutePath)
        db.trackDao().upsertAll(listOf(row))
        val transport = object : LocalFileUploadTransport {
            override suspend fun uploadFile(trackId: Long, file: File): CloudTrack =
                throw LibrarySyncException(
                    LibrarySyncProblem.SERVER, "空间不足", code = "quota_exceeded",
                )
        }

        val summary = backup(transport).run()

        assertEquals(1, summary.unable)
        val after = db.trackDao().byId(row.id)!!
        assertEquals(ArchiveState.UNABLE, ArchiveState.of(after.archiveState))
        assertEquals(0, after.archiveNextAt)
        assertTrue(after.archiveNote!!.contains("空间"))
    }

    @Test fun evictedBackupIsSurfaced() = runBlocking {
        val row = local(path = audioFile().absolutePath, state = ArchiveState.DONE, serverStorage = "metadata")
        db.trackDao().upsertAll(listOf(row))

        val summary = backup(Fake { cloud(it) }).run()

        assertEquals(1, summary.evicted)
        assertEquals(ArchiveState.EVICTED, ArchiveState.of(db.trackDao().byId(row.id)!!.archiveState))
    }

    @Test fun platformEntriesAndOtherAccountsAreIgnored() = runBlocking {
        val bilibili = local(path = audioFile().absolutePath, platform = "bilibili", serverId = 7)
        val foreign = local(path = audioFile().absolutePath, accountKey = "someone-else")
        val noServerId = local(path = audioFile().absolutePath, serverId = null)
        val missingFile = local(path = "/definitely/not/here.mp3")
        db.trackDao().upsertAll(listOf(bilibili, foreign, noServerId, missingFile))
        val transport = Fake { cloud(it) }

        val summary = backup(transport).run()

        assertEquals(0, summary.uploaded)
        assertTrue(transport.calls.isEmpty())
    }

    @Test fun waitScheduleIsTheAgreedCurve() {
        assertEquals(2_000L, LocalArchiveBackup.waitAfter(1))
        assertEquals(5_000L, LocalArchiveBackup.waitAfter(2))
        assertEquals(15_000L, LocalArchiveBackup.waitAfter(3))
        assertEquals(60_000L, LocalArchiveBackup.waitAfter(4))
        assertEquals(10 * 60 * 1000L, LocalArchiveBackup.waitAfter(5))
        assertEquals(20 * 60 * 1000L, LocalArchiveBackup.waitAfter(10))
        assertEquals(LocalArchiveBackup.COOLDOWN_MAX_MS, LocalArchiveBackup.waitAfter(60))
        // 服务器给了 Retry-After 就取更大的那个，别比它更早重试
        assertEquals(90_000L, LocalArchiveBackup.waitAfter(1, retryAfterSeconds = 90))
    }
}
