package com.getmedia.app.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.MediaKind
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
import java.io.File

/**
 * 上传队列（P0-2 阶段 3）。
 *
 * 重点不在"能传上去"，而在**不该传的一个都不传、传完不覆盖本机整理痕迹**：
 *
 * - 准入：`LOCAL_ONLY` + 本地文件在 + 不是别的账号的行；
 * - 写回：`existing.copy(...)`，收藏/类别/入库时间必须原样保留（`upsertAll` 是 REPLACE）；
 * - 失败：409 不删文件、400 跳过继续、429/5xx/网络整批停并保留 `LOCAL_ONLY`。
 */
@RunWith(AndroidJUnit4::class)
class LibraryUploaderTest {

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

    // ---------- 夹具 ----------

    private var nextId = -1L

    private fun file(name: String = "a"): File {
        val f = File.createTempFile("gm-upload-", ".audio").apply { writeBytes(ByteArray(64) { 1 }) }
        files += f
        return f
    }

    private fun track(
        title: String = "本机的一条",
        localPath: String? = null,
        state: String = SyncState.LOCAL_ONLY.name,
        serverId: Long? = null,
        accountKey: String? = null,
        videoId: String = "BVupload${nextId}",
        localTitleOverride: String? = null,
        favorite: Boolean = false,
        category: Long = 1,
    ) = TrackEntity(
        id = nextId--,
        platform = "bilibili",
        videoId = videoId,
        title = title,
        uploader = "某UP",
        durationSec = 12.5,
        audioBytes = 64,
        audioFormat = "m4a",
        sourceUrl = "https://www.bilibili.com/video/$videoId?p=1",
        localPath = localPath,
        favorite = favorite,
        localCategoryId = category,
        addedAtMs = 1_700_000_000_000,
        mediaType = MediaKind.AUDIO.name,
        serverId = serverId,
        syncState = state,
        accountKey = accountKey,
        localTitleOverride = localTitleOverride,
    )

    private class FakeUploader(private val replies: Map<String, Any>) : LibraryUploadTransport {
        val sent = mutableListOf<UploadEntry>()
        override suspend fun create(entry: UploadEntry): CreatedTrack {
            sent += entry
            return when (val r = replies[entry.title] ?: replies["*"]) {
                is LibrarySyncException -> throw r
                is CreatedTrack -> r
                else -> error("没有为 ${entry.title} 准备回复")
            }
        }
    }

    private fun created(title: String = "", created: Boolean = true, id: Long = 900) = CreatedTrack(
        created = created,
        track = CloudTrack(
            id = id, platform = "bilibili", videoId = "BVx", page = 1,
            title = title.ifBlank { "服务器上的标题" }, uploader = "某UP", durationSec = 12.5,
            sourceUrl = "https://x", mediaType = "audio", deleted = false,
            updatedSeq = 77, updatedAtSec = 1_700_000_500,
        ),
    )

    private suspend fun insert(vararg rows: TrackEntity) = db.trackDao().upsertAll(rows.toList())

    // ---------- 准入 ----------

    @Test fun onlyLocalRowsWithRealFileAreCandidates() = runBlocking {
        val good = track(localPath = file().absolutePath)
        insert(
            good,
            track(state = LibraryMark.CLOUD_ONLY),
            track(state = LibraryMark.DETACHED, localPath = file().absolutePath),
            track(state = LibraryMark.PENDING_UP, localPath = file().absolutePath),
            track(state = SyncState.SYNCED.name, localPath = file().absolutePath, serverId = 5),
            track(state = SyncState.RESTORED.name, localPath = file().absolutePath),
            track(localPath = File("/nonexistent/never.audio").absolutePath),
            track(localPath = file().absolutePath, accountKey = "someone-else"),
        )
        val candidates = LibraryUploader(db, FakeUploader(emptyMap()), account).candidates()
        assertEquals(listOf(good.id), candidates.map { it.id })
    }

    // ---------- 写回 ----------

    @Test fun uploadWritesBackWithoutLosingLocalEdits() = runBlocking {
        val row = track(localPath = file().absolutePath, favorite = true, category = 7)
        insert(row)
        val transport = FakeUploader(mapOf("本机的一条" to created()))
        val summary = LibraryUploader(db, transport, account).upload()

        assertEquals(1, summary.uploaded)
        val after = requireNotNull(db.trackDao().byId(row.id))
        assertEquals(SyncState.SYNCED.name, after.syncState)
        assertEquals(900L, after.serverId)
        assertEquals(77L, after.serverSeq)
        assertEquals(account, after.accountKey)
        // 本机整理痕迹一个都不能丢
        assertTrue(after.favorite)
        assertEquals(7L, after.localCategoryId)
        assertEquals(1_700_000_000_000, after.addedAtMs)
        assertEquals(row.localPath, after.localPath)
        assertEquals("本机的一条", after.title)
        // 只发 8 个字段里的键：视频页码 1、媒体类型按小写线上值
        assertEquals("audio", transport.sent.single().mediaType)
        assertEquals(1, transport.sent.single().page)
    }

    /** 服务器已有同键（`created=false`）：标题以服务器为准，但本机改过名的不动。 */
    @Test fun existingServerRowWinsOnTitleUnlessLocallyRenamed() = runBlocking {
        val plain = track(title = "手机标题", localPath = file().absolutePath)
        val renamed = track(title = "手机标题", localPath = file().absolutePath, localTitleOverride = "我改的名字")
        insert(plain, renamed)
        LibraryUploader(
            db,
            FakeUploader(
                mapOf(
                    "手机标题" to created(title = "网页标题", created = false),
                    "我改的名字" to created(title = "网页标题", created = false),
                ),
            ),
            account,
        ).upload()

        assertEquals("网页标题", requireNotNull(db.trackDao().byId(plain.id)).title)
        // 改过名的那条：显示名（localTitleOverride）不动，原始标题也没被服务器的覆盖
        assertEquals("我改的名字", requireNotNull(db.trackDao().byId(renamed.id)).localTitleOverride)
        assertEquals("手机标题", requireNotNull(db.trackDao().byId(renamed.id)).title)
    }

    // ---------- 失败 ----------

    /** 云端已删（409）：不删本机文件、不改行，攒着给阶段 5 的批量确认。 */
    @Test fun trackDeletedKeepsLocalFileAndRow() = runBlocking {
        val path = file()
        val row = track(localPath = path.absolutePath)
        insert(row)
        val summary = LibraryUploader(
            db,
            FakeUploader(mapOf("本机的一条" to LibrarySyncException(LibrarySyncProblem.TRACK_DELETED, "云端已删"))),
            account,
        ).upload()

        assertEquals(1, summary.conflicted)
        assertEquals(0, summary.uploaded)
        assertTrue(path.exists())
        val after = requireNotNull(db.trackDao().byId(row.id))
        assertEquals(SyncState.LOCAL_ONLY.name, after.syncState)
        assertNull(after.serverId)
    }

    /** 4xx 业务错：跳过这一条，后面的照常传。 */
    @Test fun badInputSkipsOnlyThatRow() = runBlocking {
        val bad = track(title = "坏标题", localPath = file().absolutePath)
        val good = track(title = "好标题", localPath = file().absolutePath)
        insert(bad, good)
        val transport = FakeUploader(
            mapOf(
                "坏标题" to LibrarySyncException(LibrarySyncProblem.INPUT, "标题不能为空"),
                "好标题" to created(),
            ),
        )
        val summary = LibraryUploader(db, transport, account).upload()
        assertEquals(1, summary.failed)
        assertEquals(1, summary.uploaded)
        assertEquals(2, transport.sent.size)
    }

    /** 429：退避后重试一次；再撞就整批停，剩下的保持 LOCAL_ONLY 等下一轮。 */
    @Test fun rateLimitStopsTheBatchAfterOneRetry() = runBlocking {
        val first = track(title = "第一条", localPath = file().absolutePath)
        val second = track(title = "第二条", localPath = file().absolutePath)
        insert(first, second)
        var attempts = 0
        val transport = object : LibraryUploadTransport {
            val sent = mutableListOf<String>()
            override suspend fun create(entry: UploadEntry): CreatedTrack {
                sent += entry.title
                attempts++
                throw LibrarySyncException(LibrarySyncProblem.RATE_LIMITED, "太快了", retryAfterSeconds = 1)
            }
        }
        val summary = LibraryUploader(db, transport, account, sleep = { }).upload()

        assertEquals(LibrarySyncProblem.RATE_LIMITED, summary.stopped)
        assertEquals(2, attempts)  // 第一条 + 一次重试
        assertEquals(0, summary.uploaded)
        assertEquals(SyncState.LOCAL_ONLY.name, requireNotNull(db.trackDao().byId(first.id)).syncState)
        assertEquals(SyncState.LOCAL_ONLY.name, requireNotNull(db.trackDao().byId(second.id)).syncState)
    }

    /** 网络抖动：整批停，一条都不写回（下一轮幂等重跑）。 */
    @Test fun networkFailureStopsImmediately() = runBlocking {
        val row = track(localPath = file().absolutePath)
        insert(row)
        var calls = 0
        val transport = object : LibraryUploadTransport {
            override suspend fun create(entry: UploadEntry): CreatedTrack {
                calls++
                throw LibrarySyncException(LibrarySyncProblem.NETWORK, "网络不可用")
            }
        }
        val summary = LibraryUploader(db, transport, account).upload()
        assertEquals(LibrarySyncProblem.NETWORK, summary.stopped)
        assertEquals(1, calls)
        assertNotNull(db.trackDao().byId(row.id))
        assertNull(requireNotNull(db.trackDao().byId(row.id)).serverId)
    }
}
