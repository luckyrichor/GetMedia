package com.getmedia.app.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.download.UploadProgress
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.ArchiveState
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private val context = ApplicationProvider.getApplicationContext<Context>()
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

        /** 进到"正在传"这一刻（一个字节都还没写）界面上读到的那一帧。 */
        var stateOnEntry: UploadProgress.Active? = null

        /** 每次上报进度之后界面上读到的那一帧；空表示一次都没报。 */
        val frames = mutableListOf<UploadProgress.Active?>()

        override suspend fun uploadFile(
            trackId: Long,
            file: File,
            onProgress: (sent: Long, total: Long) -> Unit,
        ): CloudTrack {
            calls += trackId
            stateOnEntry = UploadProgress.state.value
            for (sent in listOf(file.length() / 2, file.length())) {
                onProgress(sent, file.length())
                frames += UploadProgress.state.value
            }
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

    /**
     * 上传过程中界面要真的能看见进度——这是用户提的原话："看不到它是不是正在上传"。
     *
     * 钉三件事：进上传前就已经点亮（否则前几秒界面一片空白，看起来像没开始）、
     * 分母是文件长度而不是 multipart 的总长度（含边界与表单头，百分比会从个位数起步）、
     * 传完立刻收掉（不能留一条永远停在 100% 的进度条）。
     */
    @Test fun uploadProgressIsVisibleWhileUploadingAndGoneAfterwards() = runBlocking {
        val file = audioFile()
        val row = local(path = file.absolutePath)
        db.trackDao().upsertAll(listOf(row))
        val transport = Fake { cloud(it) }

        backup(transport).run()

        val onEntry = transport.stateOnEntry
        assertEquals("一开始就要知道是哪一条在传", row.id, onEntry?.trackId)
        assertEquals("还没写字节", 0L, onEntry?.done)
        assertEquals("分母是文件长度", file.length(), onEntry?.total)

        assertEquals(2, transport.frames.size)
        assertEquals(row.id, transport.frames[0]?.trackId)
        assertEquals(0.5f, transport.frames[0]?.fraction ?: -1f, 0.001f)
        assertEquals(1f, transport.frames[1]?.fraction ?: -1f, 0.001f)

        assertNull("传完必须收掉，否则界面永远停在「正在上传 100%」", UploadProgress.state.value)
    }

    @Test fun failedUploadAlsoClearsTheProgressLine() = runBlocking {
        val row = local(path = audioFile().absolutePath)
        db.trackDao().upsertAll(listOf(row))
        val transport = object : LocalFileUploadTransport {
            override suspend fun uploadFile(
                trackId: Long,
                file: File,
                onProgress: (sent: Long, total: Long) -> Unit,
            ): CloudTrack = throw LibrarySyncException(LibrarySyncProblem.NETWORK, "网络不可用")
        }

        backup(transport).run()

        assertNull("失败也要收掉，原因由条目上的红字去说", UploadProgress.state.value)
    }

    @Test fun eachTrackInTheBatchLightsItsOwnLine() = runBlocking {
        val first = local(path = audioFile().absolutePath)
        val second = local(path = audioFile().absolutePath)
        db.trackDao().upsertAll(listOf(first, second))
        val seen = mutableListOf<Long?>()
        val transport = object : LocalFileUploadTransport {
            override suspend fun uploadFile(
                trackId: Long,
                file: File,
                onProgress: (sent: Long, total: Long) -> Unit,
            ): CloudTrack {
                onProgress(file.length() / 2, file.length())
                seen += UploadProgress.state.value?.trackId
                return cloud(trackId)
            }
        }

        backup(transport).run()

        assertEquals("两条都要点亮过", 2, seen.size)
        assertEquals("各自点自己那一条，不能串台", setOf(first.id, second.id), seen.toSet())
    }

    @Test fun networkFailureBacksOffThenCoolsDown() = runBlocking {
        val row = local(path = audioFile().absolutePath)
        db.trackDao().upsertAll(listOf(row))
        val transport = object : LocalFileUploadTransport {
            override suspend fun uploadFile(
                trackId: Long,
                file: File,
                onProgress: (sent: Long, total: Long) -> Unit,
            ): CloudTrack = throw LibrarySyncException(LibrarySyncProblem.NETWORK, "网络不可用")
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
            override suspend fun uploadFile(
                trackId: Long,
                file: File,
                onProgress: (sent: Long, total: Long) -> Unit,
            ): CloudTrack = throw LibrarySyncException(
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

    /**
     * 升级后，还没备份成功的原件要立刻重试一次，且**只做一次**。
     *
     * 两种卡法都要放出来：`UNABLE`（0.1.41 及以前，边缘回的空 body 400 被当成 `INPUT`，
     * 一次断网就永久放弃），以及 `PENDING` 但 `archiveNextAt` 还在很远的未来
     * （0.1.41 及以前每次上传都必然失败，退避次数早就攒到冷却上限，最长 6 小时）。
     * 同时钉住不许动的两类：`DONE`（已经传上去了）与 `EVICTED`（云端那份被清理，是要
     * 告诉用户的事实，不能悄悄重置成"还没传"）。
     */
    @Test fun upgradeRetriesUnbackedFilesOncePerAccount() = runBlocking {
        val file = audioFile()
        val unable = local(path = file.absolutePath, state = ArchiveState.UNABLE, tries = 5, nextAt = now + 999_999)
            .copy(archiveNote = "请求被服务器拒绝")
        val cooling = local(path = file.absolutePath, state = ArchiveState.PENDING, tries = 40, nextAt = now + 6 * 3600_000)
        val done = local(path = file.absolutePath, state = ArchiveState.DONE, serverStorage = "archive")
        val evicted = local(path = file.absolutePath, state = ArchiveState.EVICTED)
        val platformRow = local(path = file.absolutePath, state = ArchiveState.PENDING, platform = "bilibili", tries = 40, nextAt = now + 999_999)
        val otherAccount = local(path = file.absolutePath, state = ArchiveState.PENDING, accountKey = "someone-else", tries = 40, nextAt = now + 999_999)
        db.trackDao().upsertAll(listOf(unable, cooling, done, evicted, platformRow, otherAccount))

        val prefs = LibrarySyncPrefs(context)
        LibrarySyncRunner.retryBackupsOnce(db, prefs, account)

        for (row in listOf(unable, cooling)) {
            val back = db.trackDao().byId(row.id)!!
            assertEquals("要放回待上传", ArchiveState.PENDING, ArchiveState.of(back.archiveState))
            assertEquals("次数与退避都要清零，否则放回去立刻又被冷却挡住", 0, back.archiveTries)
            assertEquals(0L, back.archiveNextAt)
            assertNull("旧的原因已经过时了，留着会误导", back.archiveNote)
        }
        assertEquals("已经传上去的不该再传一遍",
            ArchiveState.DONE, ArchiveState.of(db.trackDao().byId(done.id)!!.archiveState))
        assertEquals("淘汰是事实，不能悄悄重置",
            ArchiveState.EVICTED, ArchiveState.of(db.trackDao().byId(evicted.id)!!.archiveState))
        assertEquals("平台条目没有原件，不归这条路管",
            ArchiveState.PENDING, ArchiveState.of(db.trackDao().byId(platformRow.id)!!.archiveState))
        assertEquals(40, db.trackDao().byId(platformRow.id)!!.archiveTries)
        assertEquals("别的账号的行不许碰",
            40, db.trackDao().byId(otherAccount.id)!!.archiveTries)

        db.trackDao().updateExisting(db.trackDao().byId(cooling.id)!!.copy(archiveNextAt = now + 999_999))
        LibrarySyncRunner.retryBackupsOnce(db, prefs, account)
        assertEquals("每账号只放一次：再失败就按退避冷却走",
            now + 999_999, db.trackDao().byId(cooling.id)!!.archiveNextAt)
    }
}
