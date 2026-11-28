package com.getmedia.app.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.LibraryCursorEntity
import com.getmedia.app.data.local.LocalIds
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.SyncState
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 阶段 2 的拉取：只建 `CLOUD_ONLY` 行、不碰任何已有行、水位按整页推进、重跑不重复。
 *
 * 用内存库直接看落库结果，不经过 UI 也不出网（[FakeTransport] 记账）。
 */
@RunWith(AndroidJUnit4::class)
class LibrarySyncEngineTest {

    private lateinit var db: AppDatabase
    private val account = "boss"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    // ---------- 夹具 ----------

    private fun cloud(
        id: Long,
        videoId: String = "BV$id",
        page: Int = 1,
        deleted: Boolean = false,
        seq: Long = id,
        title: String = "云端 $id",
        mediaType: String = "audio",
        storage: String = "metadata",
        favorite: Boolean = false,
        category: String = "",
    ) = CloudTrack(
        id = id, platform = "bilibili", videoId = videoId, page = page, title = title,
        uploader = "某UP", durationSec = 12.5,
        sourceUrl = "https://www.bilibili.com/video/$videoId?p=$page",
        mediaType = mediaType, deleted = deleted, updatedSeq = seq, updatedAtSec = 1_700_000_000 + id,
        storage = storage, favorite = favorite, category = category,
    )

    private class FakeTransport(private val pages: MutableList<Any>) : LibrarySyncTransport {
        val calls = mutableListOf<Long>()
        override suspend fun pull(sinceSeq: Long, limit: Int): PullPage {
            calls += sinceSeq
            val next = pages.removeFirstOrNull() ?: error("多打了一次请求：since=$sinceSeq")
            if (next is LibrarySyncException) throw next
            return next as PullPage
        }
    }

    private fun engine(pages: List<Any>, size: Int = 2): Pair<LibrarySyncEngine, FakeTransport> {
        val t = FakeTransport(pages.toMutableList())
        return LibrarySyncEngine(db, t, pageSize = size, nowMs = { 1_800_000_000_000 }) to t
    }

    private fun page(items: List<CloudTrack>, hasMore: Boolean) =
        PullPage(items, items.maxOfOrNull { it.updatedSeq } ?: 0, hasMore)

    private suspend fun rows() = db.trackDao().allNow()

    private suspend fun cursor() = db.libraryAuxDao().cursor(account)?.lastPos

    // ---------- 建行 ----------

    @Test fun playlistPlaceholderIsBoundAndTitledWhenCloudRowArrives() = runBlocking {
        // 顺序：播放列表先到（曲库里还没有这件作品）→ 拉取补了一条占位行，
        // 标题只有作品号、没有 serverId。下一轮曲库行到了，必须**绑到同一行**并换上真标题。
        val id = LocalIds.localId("bilibili", "BV9", 1)
        db.trackDao().insertIgnore(
            listOf(
                TrackEntity(
                    id = id, platform = "bilibili", videoId = "BV9", title = "BV9",
                    uploader = null, durationSec = null, audioBytes = null, audioFormat = "",
                    sourceUrl = "", localPath = null, addedAtMs = 1, updatedAtMs = 1,
                    syncState = LibraryMark.CLOUD_ONLY, accountKey = account,
                ),
            ),
        )
        val (e, _) = engine(
            listOf(page(listOf(cloud(9, videoId = "BV9", title = "真标题")), hasMore = false)),
        )
        val result = e.pull(account)

        assertEquals("占位行要被绑定，不能另起一行", 1, rows().size)
        val row = db.trackDao().byId(id)!!
        assertEquals(9L, row.serverId)
        assertEquals(LibraryMark.CLOUD_ONLY, row.syncState)
        assertEquals("绑定后换成云端标题", "真标题", row.title)
        assertEquals(1, result.bound)
    }

    /**
     * 占位行的类型标的是"未知"（云端播放列表只存平台三元组），视频云端行到达时必须照样绑上。
     *
     * 0.1.40 真机故障：补绑定的候选按 `mediaType` 相等来筛，占位行猜的是 AUDIO，视频行
     * 永远选不中，于是这条视频一直停在「音频」分类里，标题也一直是作品号。
     */
    @Test fun videoPlaceholderIsBoundEvenThoughItsTypeIsUnknown() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV9", 1)
        db.trackDao().insertIgnore(
            listOf(
                TrackEntity(
                    id = id, platform = "bilibili", videoId = "BV9", title = "BV9",
                    uploader = null, durationSec = null, audioBytes = null, audioFormat = "",
                    sourceUrl = "", localPath = null, addedAtMs = 1, updatedAtMs = 1,
                    mediaType = MediaKind.UNKNOWN_NAME,
                    syncState = LibraryMark.CLOUD_ONLY, accountKey = account,
                ),
            ),
        )
        val (e, _) = engine(
            listOf(page(listOf(cloud(9, videoId = "BV9", title = "真标题", mediaType = "video")), hasMore = false)),
        )
        val result = e.pull(account)

        assertEquals("占位行要绑定，不能另起一行", 1, rows().size)
        val row = db.trackDao().byId(id)!!
        assertEquals(9L, row.serverId)
        assertEquals("类型要按云端改回视频", MediaKind.VIDEO.name, row.mediaType)
        assertEquals("标题要换成云端标题", "真标题", row.title)
        assertEquals(1, result.bound)
    }

    /**
     * 服务端计数器被重置（清库、或硬删到 `MAX(updated_seq)` 回落）时，服务端会无视水位从 0
     * 重发全量。客户端必须**跟着把水位降下来**。
     *
     * 0.1.40 真机故障：这里原来有 `.coerceAtLeast(since)`，设备每轮都拿一个永远高于全库最大
     * 序号的水位去问，只拿得到空页——改名、新增的视频在另一台手机上永远收不到。
     */
    @Test fun watermarkFollowsServerWhenItsCounterWasReset() = runBlocking {
        db.libraryAuxDao().putCursor(LibraryCursorEntity(account = account, lastPos = 156))
        val (e, t) = engine(listOf(page(listOf(cloud(1), cloud(3, seq = 3)), hasMore = false)))

        val result = e.pull(account)

        assertEquals("这一页是按本机旧水位去问的", listOf(156L), t.calls)
        assertEquals("水位要降到服务端给的真实位置", 3L, result.cursor)
        assertEquals(3L, cursor())
    }

    /**
     * 云端被硬删（没写墓碑）之后，本机那行的 `serverId` 是个指向虚空的指针：`eligible()` 第一道
     * 判断 `serverId != null` 就返回 false，于是它既不上传、也不进「上传本机新增」的计数——
     * 用户看到的是「0 条，点不了」。全量拉完之后必须把它脱钩，重新排成可上传的条目。
     */
    @Test fun orphanRowWhoseCloudRowVanishedIsDetached() = runBlocking {
        val id = LocalIds.localId("local", "老友记", 1)
        val file = java.io.File.createTempFile("gm-engine-", ".mp4").apply { writeBytes(ByteArray(64) { 1 }) }
        try {
            db.trackDao().insertIgnore(
                listOf(
                    TrackEntity(
                        id = id, platform = "local", videoId = "老友记", title = "老友记",
                        uploader = null, durationSec = null, audioBytes = 64, audioFormat = "mp4",
                        sourceUrl = "", localPath = file.absolutePath, addedAtMs = 1, updatedAtMs = 1,
                        mediaType = MediaKind.VIDEO.name,
                        serverId = 40, serverSeq = 40, serverStorage = "metadata",
                        confirmedTitle = "老友记", syncState = SyncState.LOCAL_ONLY.name,
                        accountKey = account,
                    ),
                ),
            )
            // 云端只剩另一条，40 号既不在活行里也不在墓碑里
            val (e, _) = engine(listOf(page(listOf(cloud(1)), hasMore = false)))

            val result = e.pull(account, full = true)

            assertEquals("要认出这条孤儿", 1, result.detached)
            val row = db.trackDao().byId(id)!!
            assertNull("云端身份要清掉，否则永远进不了上传候选", row.serverId)
            assertNull(row.serverSeq)
            assertNull(row.serverStorage)
            assertNull("绑定基准也要清掉，等重新上传后再写", row.confirmedTitle)
            assertEquals("有文件 → 重新排队成本机新增", SyncState.LOCAL_ONLY.name, row.syncState)
            assertEquals("文件不能被碰", file.absolutePath, row.localPath)
        } finally {
            file.delete()
        }
    }

    /** 云端写了墓碑的条目证明"知道它存在过"，不能算孤儿（否则会把用户刚删的东西又传上去）。 */
    @Test fun tombstonedRowIsNotDetached() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV40", 1)
        db.trackDao().insertIgnore(
            listOf(
                TrackEntity(
                    id = id, platform = "bilibili", videoId = "BV40", title = "四十",
                    uploader = null, durationSec = null, audioBytes = null, audioFormat = "",
                    sourceUrl = "https://b/40", localPath = null, addedAtMs = 1, updatedAtMs = 1,
                    // 收藏是"整理痕迹"：墓碑只会记成待确认，不会把行直接删掉
                    favorite = true,
                    serverId = 40, serverSeq = 40, serverStorage = "metadata",
                    syncState = LibraryMark.CLOUD_ONLY, accountKey = account,
                ),
            ),
        )
        val (e, _) = engine(listOf(page(listOf(cloud(40, deleted = true)), hasMore = false)))

        val result = e.pull(account, full = true)

        assertEquals(0, result.detached)
        assertEquals(40L, db.trackDao().byId(id)!!.serverId)
    }

    /** 增量拉取拿不到云端 id 全集，绝不能对账（否则会把云端还有的行误判成孤儿）。 */
    @Test fun incrementalPullNeverReconciles() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV40", 1)
        db.trackDao().insertIgnore(
            listOf(
                TrackEntity(
                    id = id, platform = "bilibili", videoId = "BV40", title = "四十",
                    uploader = null, durationSec = null, audioBytes = null, audioFormat = "",
                    sourceUrl = "https://b/40", localPath = null, addedAtMs = 1, updatedAtMs = 1,
                    serverId = 40, serverSeq = 40, serverStorage = "metadata",
                    syncState = LibraryMark.CLOUD_ONLY, accountKey = account,
                ),
            ),
        )
        db.libraryAuxDao().putCursor(LibraryCursorEntity(account = account, lastPos = 5))
        val (e, t) = engine(listOf(page(listOf(cloud(9, seq = 9)), hasMore = false)))

        val result = e.pull(account)

        assertEquals(listOf(5L), t.calls)
        assertEquals(0, result.detached)
        assertEquals(40L, db.trackDao().byId(id)!!.serverId)
    }

    @Test fun createsCloudOnlyRowsWithServerIdentity() = runBlocking {        val (e, t) = engine(listOf(page(listOf(cloud(1, page = 2), cloud(2)), hasMore = false)))
        val result = e.pull(account)

        assertEquals(2, result.added)
        assertEquals(0, result.skipped)
        assertEquals(listOf(0L), t.calls)

        val rows = rows().associateBy { it.id }
        val p2 = rows.getValue(LocalIds.localId("bilibili", "BV1", 2))
        assertEquals(LibraryMark.CLOUD_ONLY, p2.syncState)
        assertEquals("BV1-p2", p2.videoId)
        assertEquals(1L, p2.serverId)
        assertEquals(2, p2.serverPage)
        assertEquals(1L, p2.serverSeq)
        assertEquals(account, p2.accountKey)
        assertNull("云端未下载：没有本地文件", p2.localPath)
        assertNull(p2.audioBytes)
        assertEquals("", p2.audioFormat)
        // 收藏与类别保持默认，否则会被当成"整理痕迹"进备份
        assertEquals(MetadataDefaults.FAVORITE, p2.favorite)
        assertEquals(MetadataDefaults.CATEGORY, p2.localCategoryId)
    }

    @Test fun pulledRowIsAddressedLikeLocallyExtractedOne() = runBlocking {
        // 与直提取同一套 id/键：同一作品下次从手机提取会落回这一行，而不是新增一条
        val (e, _) = engine(listOf(page(listOf(cloud(5, videoId = "BVxx", page = 3)), hasMore = false)))
        e.pull(account)
        val row = rows().single()
        assertEquals(LocalIds.localId("bilibili", "BVxx", 3), row.id)
        assertEquals("BVxx-p3", row.videoId)
        assertEquals(3, row.serverPage)
    }

    // ---------- 不碰已有行 ----------

    /**
     * 已有行只允许"补绑定"：`serverId` 那条线可以补上，其余本地字段一个都不许动。
     *
     * 0.1.26 之前这里是"一个字段都不动"，结果本机导入的条目在服务器上明明有同键行，
     * 却永远是"改不动的本机镜像"——改名、删除都传不出去。
     */
    @Test fun existingRowsOnlyGainBindingAndKeepLocalFields() = runBlocking {
        val restoredId = LocalIds.localId("bilibili", "BV1", 1)
        val localOnlyId = LocalIds.localId("bilibili", "BV2", 1)
        val syncedId = LocalIds.localId("bilibili", "BV3", 1)
        db.trackDao().upsertAll(
            listOf(
                TrackEntity(
                    id = restoredId, platform = "bilibili", videoId = "BV1", title = "恢复来的名字",
                    uploader = "UP", durationSec = 10.0, audioBytes = 999, audioFormat = "m4a",
                    sourceUrl = "https://b/1", localPath = "/files/-1.audio", favorite = true,
                    localCategoryId = 2, localTitleOverride = "我改的名字", sortIndex = 7,
                    syncState = SyncState.RESTORED.name,
                ),
                TrackEntity(
                    id = localOnlyId, platform = "bilibili", videoId = "BV2", title = "手机提取的",
                    uploader = "UP", durationSec = 10.0, audioBytes = 888, audioFormat = "m4a",
                    sourceUrl = "https://b/2", localPath = "/files/-2.audio",
                    syncState = SyncState.LOCAL_ONLY.name,
                ),
                TrackEntity(
                    id = syncedId, platform = "bilibili", videoId = "BV3", title = "已同步的",
                    uploader = "UP", durationSec = 10.0, audioBytes = 777, audioFormat = "m4a",
                    sourceUrl = "https://b/3", localPath = "/files/-3.audio",
                    syncState = SyncState.SYNCED.name, serverId = 30, serverSeq = 3, accountKey = account,
                ),
            ),
        )
        val before = rows().associateBy { it.id }

        val (e, _) = engine(
            listOf(page(listOf(cloud(1, videoId = "BV1"), cloud(2, videoId = "BV2"), cloud(3, videoId = "BV3")), hasMore = false)),
        )
        val result = e.pull(account)

        assertEquals("一条都不该新增", 0, result.added)
        assertEquals(3, result.skipped)
        assertEquals("两条没有 serverId 的补上了绑定", 2, result.bound)

        val after = rows().associateBy { it.id }
        // 本地整理痕迹与文件名一个都不许动
        assertEquals(
            before.getValue(restoredId).localTitleOverride,
            after.getValue(restoredId).localTitleOverride,
        )
        assertEquals(before.getValue(restoredId).favorite, after.getValue(restoredId).favorite)
        assertEquals(before.getValue(restoredId).localCategoryId, after.getValue(restoredId).localCategoryId)
        assertEquals(before.getValue(restoredId).sortIndex, after.getValue(restoredId).sortIndex)
        assertEquals(before.getValue(restoredId).title, after.getValue(restoredId).title)
        assertEquals(before.getValue(localOnlyId).localPath, after.getValue(localOnlyId).localPath)
        // 补上的只有那条线
        assertEquals(1L, after.getValue(restoredId).serverId)
        assertEquals(2L, after.getValue(localOnlyId).serverId)
        // 已经绑在别的 id 上的行不动（同键不同 id 不算同一条）
        assertEquals(before.getValue(syncedId), after.getValue(syncedId))
    }

    // ---------- 同键补绑定（0.1.26） ----------

    private fun localCloud(
        id: Long,
        videoId: String,
        page: Int = 1,
        seq: Long = 117,
        storage: String = "archive",
        mediaType: String = "audio",
    ) = CloudTrack(
        id = id, platform = "local", videoId = videoId, page = page, title = videoId,
        uploader = null, durationSec = 254.0, sourceUrl = "", mediaType = mediaType,
        deleted = false, updatedSeq = seq, updatedAtSec = 1_700_000_000 + id, storage = storage,
    )

    private fun localRow(
        videoId: String = "望",
        state: String = SyncState.LOCAL_ONLY.name,
        serverId: Long? = null,
    ) = TrackEntity(
        id = LocalIds.localId("local", videoId, 1), platform = "local", videoId = videoId,
        title = "望", uploader = null, durationSec = 254.0, audioBytes = 12_345,
        audioFormat = "m4a", sourceUrl = "", localPath = "/files/$videoId.m4a",
        favorite = true, localCategoryId = 3, sortIndex = 2, syncState = state, serverId = serverId,
    )

    /** 本机导入、没有来源链接的行：同键就补 `serverId`，标题/文件/收藏/顺序全不动。 */
    @Test fun unboundLocalRowGetsBoundToSameKeyCloudRow() = runBlocking {
        db.trackDao().upsertAll(listOf(localRow()))

        val (e, _) = engine(listOf(page(listOf(localCloud(40, "望")), hasMore = false)))
        val result = e.pull(account)

        assertEquals("不新增行", 0, result.added)
        assertEquals(1, result.bound)
        val row = rows().single()
        assertEquals(40L, row.serverId)
        assertEquals(117L, row.serverSeq)
        assertEquals("archive", row.serverStorage)
        assertEquals(account, row.accountKey)
        assertEquals("有本地文件就是已同步", SyncState.SYNCED.name, row.syncState)
        assertEquals("文件不动", "/files/望.m4a", row.localPath)
        assertEquals("标题不动", "望", row.title)
        assertTrue("收藏不动", row.favorite)
        assertEquals("类别不动", 3L, row.localCategoryId)
        assertEquals("手动顺序不动", 2L, row.sortIndex)
    }

    /** 媒体类型不一致就不绑：音频和视频共用同一个键，绑错等于把两条不同的东西并成一条。 */
    @Test fun bindingRequiresSameMediaType() = runBlocking {
        db.trackDao().upsertAll(listOf(localRow()))

        val (e, _) = engine(listOf(page(listOf(localCloud(40, "望", mediaType = "video")), hasMore = false)))
        val result = e.pull(account)

        assertEquals(0, result.bound)
        assertNull("类型不符不许绑", rows().single().serverId)
    }

    /** 用户手动解除过关联（DETACHED）的行不自动绑回去，否则"保留本机、脱离云端"就白选了。 */
    @Test fun detachedRowIsNotRebound() = runBlocking {
        db.trackDao().upsertAll(listOf(localRow(state = LibraryMark.DETACHED)))

        val (e, _) = engine(listOf(page(listOf(localCloud(40, "望")), hasMore = false)))
        val result = e.pull(account)

        assertEquals(0, result.bound)
        assertNull(rows().single().serverId)
    }

    /** 本机刚删、还没重放成功的键：连绑定都不能做，否则下一轮又变成"云端有、本机删过"。 */
    @Test fun keyWaitingForRemovalIsNotBound() = runBlocking {
        db.trackDao().upsertAll(listOf(localRow()))
        db.libraryAuxDao().addRemoval(
            com.getmedia.app.data.local.TrackRemovalEntity(
                platform = "local", videoKey = LocalIds.key("local", "望", 1), account = account,
                remoteId = 40, requestedAt = 1, expectedSeq = 117, generation = 1,
            ),
        )

        val (e, _) = engine(listOf(page(listOf(localCloud(40, "望")), hasMore = false)))
        val result = e.pull(account)

        assertEquals(0, result.bound)
        assertNull(rows().single().serverId)
    }

    /** 升级后的那次一次性回填：无视水位从 0 拉，才能看到早期行并补上绑定。 */
    @Test fun fullPullIgnoresWatermarkAndBinds() = runBlocking {
        db.trackDao().upsertAll(listOf(localRow()))
        db.libraryAuxDao().putCursor(LibraryCursorEntity(account, 116, 0))

        val (e, t) = engine(listOf(page(listOf(localCloud(40, "望")), hasMore = false)))
        val result = e.pull(account, full = true)

        assertEquals("即使水位已在 116 也要从 0 拉", listOf(0L), t.calls)
        assertEquals(1, result.bound)
        assertEquals(40L, rows().single().serverId)
        assertEquals("水位回到最新", 117L, cursor())
    }

    @Test fun tombstoneDoesNotRemoveLocalRow() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV1", 1)
        db.trackDao().upsertAll(
            listOf(
                TrackEntity(
                    id = id, platform = "bilibili", videoId = "BV1", title = "本机有文件",
                    uploader = null, durationSec = 10.0, audioBytes = 100, audioFormat = "m4a",
                    sourceUrl = "https://b/1", localPath = "/files/-1.audio",
                    syncState = SyncState.SYNCED.name, serverId = 1, serverSeq = 1, accountKey = account,
                ),
            ),
        )
        val (e, _) = engine(listOf(page(listOf(cloud(1, videoId = "BV1", deleted = true)), hasMore = false)))
        val result = e.pull(account)

        assertEquals(1, result.tombstones)
        assertEquals(0, result.added)
        assertNotNull("云端删了也不许动本机这一行（阶段 5 才弹确认）", rows().firstOrNull { it.id == id })
        assertEquals(1, rows().size)
        assertEquals("有文件的行只记待确认，不自动删", 1, db.libraryAuxDao().markCount(account))
        assertEquals("/files/-1.audio", rows().single().localPath)
    }

    /** 本机导入（`local`）的行收到墓碑：记录与**我们那份副本**都要真删。
     *
     *  平台条目有原链接、能重新取回，所以只记待确认（见 [tombstoneDoesNotRemoveLocalRow]）；
     *  `local` 行没链接、删了就是删了，留着文件只会变成「看不见、删不掉、还占空间」的孤儿。
     *  删文件放在事务外（`applyPage` 只把路径带回来，`pull` 落完库才删），所以这里要真有一个文件，
     *  否则测不出「只是从列表里消失」和「盘上也没了」的区别。 */
    @Test fun tombstoneForLocalRowDeletesTheRowAndItsCopy() = runBlocking {
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "gm-tomb-${System.nanoTime()}")
        assertTrue(dir.mkdirs())
        val file = java.io.File(dir, "loc_1.mp4").apply { writeBytes(ByteArray(1024)) }
        assertTrue("先得有个真文件", file.exists())

        val id = LocalIds.localId("local", "loc_1", 1)
        db.trackDao().upsertAll(
            listOf(
                TrackEntity(
                    id = id, platform = "local", videoId = "loc_1", title = "本机视频",
                    uploader = null, durationSec = 30.0, audioBytes = 1024, audioFormat = "mp4",
                    sourceUrl = "", localPath = file.absolutePath, mediaType = MediaKind.VIDEO.name,
                    syncState = SyncState.SYNCED.name, serverId = 80, serverSeq = 20, accountKey = account,
                    serverStorage = "archive",
                ),
            ),
        )

        val item = localCloud(80, "loc_1", mediaType = MediaKind.VIDEO.name)
            .copy(deleted = true, updatedSeq = 20)
        val (e, _) = engine(listOf(page(listOf(item), hasMore = false)))
        val result = e.pull(account)

        assertEquals(1, result.tombstones)
        assertEquals(1, result.removedRows)
        assertTrue("行要删掉", rows().isEmpty())
        assertTrue("我们那份副本要真删，不能只从列表里消失", !file.exists())
        assertEquals("本机导入的删除是静默的，不进待确认", 0, db.libraryAuxDao().markCount(account))
        assertTrue("临时目录要清掉", dir.delete() || !dir.exists())
    }
    @Test fun tombstoneWithoutFileOrTraceOnlyRemovesTheCloudMirror() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV1", 1)
        db.trackDao().upsertAll(listOf(cloudRow(id, deletedMirror = true)))
        val (e, _) = engine(listOf(page(listOf(cloud(1, videoId = "BV1", deleted = true)), hasMore = false)))
        val result = e.pull(account)

        assertEquals(1, result.removedRows)
        assertEquals(0, db.libraryAuxDao().markCount(account))
        assertTrue("纯云端镜像无文件无痕迹，可以直接移出列表", rows().isEmpty())
    }

    @Test fun newerServerVersionAdoptsCloudOrganizationButKeepsLocalFiles() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV1", 1)
        db.trackDao().upsertAll(listOf(
            cloudRow(id, deletedMirror = false).copy(
                favorite = true, localCategoryId = 3, sortIndex = 7, localPath = "/files/-1.audio",
                title = "旧标题", confirmedTitle = "旧标题", serverSeq = 1, serverStorage = "archive",
            ),
        ))
        // 云端这一条没有收藏、也没有类别：本机没标脏，说明就是云端的状态（别的设备取消过）
        val (e, _) = engine(listOf(page(listOf(cloud(1, videoId = "BV1", seq = 5, title = "服务器新名")), hasMore = false)))
        e.pull(account)

        val row = rows().single()
        assertEquals("服务器标题为准", "服务器新名", row.title)
        assertEquals("服务器新名", row.confirmedTitle)
        assertEquals(5L, row.serverSeq)
        assertEquals("收藏以云端为准", false, row.favorite)
        assertEquals("类别以云端为准", CategoryPaths.DEFAULT_ID, row.localCategoryId)
        assertEquals("手动顺序不动", 7L, row.sortIndex)
        assertEquals("本地文件不动", "/files/-1.audio", row.localPath)
    }

    @Test fun dirtyOrganizationIsNotOverwrittenByPull() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV1", 1)
        db.trackDao().upsertAll(listOf(
            cloudRow(id, deletedMirror = false).copy(
                favorite = true, localCategoryId = 3, metaDirty = true,
                title = "旧标题", confirmedTitle = "旧标题", serverSeq = 1,
            ),
        ))
        val (e, _) = engine(listOf(page(listOf(cloud(1, videoId = "BV1", seq = 5)), hasMore = false)))
        e.pull(account)

        val row = rows().single()
        assertEquals("本机刚点的收藏不能被拉取抹掉", true, row.favorite)
        assertEquals("本机刚改的类别不能被拉取抹掉", 3L, row.localCategoryId)
        assertTrue("脏位留着，等推送把它带走", row.metaDirty)
        assertEquals("服务器版本仍然要跟上", 5L, row.serverSeq)
    }

    @Test fun cloudRowCarriesFavoriteAndCreatesCategoryPath() = runBlocking {
        val (e, _) = engine(listOf(page(listOf(
            cloud(1, videoId = "BV1", favorite = true, category = "音乐/古风"),
        ), hasMore = false)))
        e.pull(account)

        val row = rows().single()
        assertTrue("云端收藏要落到本机", row.favorite)
        val category = db.categoryDao().byId(row.localCategoryId)
        assertEquals("古风", category?.name)
        val parent = category?.parentId?.let { db.categoryDao().byId(it) }
        assertEquals("音乐", parent?.name)
    }

    @Test fun pendingDraftIsNeverOverwrittenByPull() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV1", 1)
        db.trackDao().upsertAll(listOf(
            cloudRow(id, deletedMirror = false).copy(
                title = "我的草稿", localTitleOverride = "我的草稿", confirmedTitle = "服务器旧名",
                pendingTitle = "我的草稿", pendingTitleBaseSeq = 3, pendingTitleGen = 123,
                serverSeq = 3, syncState = LibraryMark.PENDING_UP,
            ),
        ))
        val (e, _) = engine(listOf(page(listOf(cloud(1, videoId = "BV1", seq = 5, title = "网页改的")), hasMore = false)))
        e.pull(account)

        val row = rows().single()
        // 拉取只更新服务器字段；草稿与它的显示标题必须原样保留，冲突交给 LibraryTitlePush 处理
        assertEquals("我的草稿", row.title)
        assertEquals("我的草稿", row.pendingTitle)
        assertEquals("我的草稿", row.localTitleOverride)
        assertEquals("服务器旧名", row.confirmedTitle)
        assertEquals("服务器版本照记，供推送时判冲突", 5L, row.serverSeq)
        assertEquals(LibraryMark.PENDING_UP, row.syncState)
    }

    @Test fun localRenameSurvivesPullWhenServerRowDidNotChange() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV1", 1)
        db.trackDao().upsertAll(listOf(
            cloudRow(id, deletedMirror = false).copy(
                title = "我以前改的名字", localTitleOverride = "我以前改的名字",
                confirmedTitle = "服务器原名", serverSeq = 1, serverStorage = null,
            ),
        ))
        // 版本没动、标题也没动，只是本机缺 storage：这次合并不能顺手把本机改名抹掉
        val (e, _) = engine(listOf(page(listOf(cloud(1, videoId = "BV1", seq = 1, title = "服务器原名")), hasMore = false)))
        e.pull(account)

        val row = rows().single()
        assertEquals("服务器这一行没变，本机改名保留", "我以前改的名字", row.title)
        assertEquals("我以前改的名字", row.localTitleOverride)
        assertEquals("服务器原名", row.confirmedTitle)
        assertEquals("metadata", row.serverStorage)
    }

    @Test fun serverRenameWinsOverStaleLocalOverride() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV1", 1)
        db.trackDao().upsertAll(listOf(
            cloudRow(id, deletedMirror = false).copy(
                title = "出山", localTitleOverride = "出山", confirmedTitle = "出山", serverSeq = 1,
            ),
        ))
        // 别的设备把这条改名了：服务器版本前进
        val (e, _) = engine(listOf(page(listOf(cloud(1, videoId = "BV1", seq = 5, title = "出山3")), hasMore = false)))
        e.pull(account)

        val row = rows().single()
        assertEquals("服务器改过名就以服务器为准", "出山3", row.title)
        assertEquals("出山3", row.confirmedTitle)
        assertNull("遗留的本机改名必须清掉，否则下一次合并又会挡住服务器标题", row.localTitleOverride)
        assertEquals(5L, row.serverSeq)
    }

    /**
     * 0.1.44 真机故障的复现：手机 A 拉到过《出山3》，却被"本机改名优先"分支挡掉标题，
     * 同时把新版本号写进了 `serverSeq`。此后版本号再也比不过，增量拉取连这一行都不会再送，
     * 光靠"版本前进才合并"救不回来——必须让标题漂移本身也算一次"服务器动过"。
     */
    @Test fun serverRenameIsAdoptedEvenWhenSeqAlreadyCaughtUp() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV1", 1)
        db.trackDao().upsertAll(listOf(
            cloudRow(id, deletedMirror = false).copy(
                title = "出山", localTitleOverride = "出山", confirmedTitle = "出山",
                serverSeq = 9, serverStorage = "metadata",
            ),
        ))
        val (e, _) = engine(listOf(page(listOf(cloud(1, videoId = "BV1", seq = 9, title = "出山3")), hasMore = false)))
        e.pull(account)

        val row = rows().single()
        assertEquals("版本号已经追上也不能挡住服务器标题", "出山3", row.title)
        assertNull(row.localTitleOverride)
    }

    @Test fun storageIsWrittenEvenWhenSeqDidNotMove() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV1", 1)
        db.trackDao().upsertAll(listOf(
            cloudRow(id, deletedMirror = false).copy(serverStorage = null, serverSeq = 1),
        ))
        val (e, _) = engine(listOf(
            page(listOf(cloud(1, videoId = "BV1", seq = 1).copy(storage = "archive")), hasMore = false),
        ))
        e.pull(account)

        assertEquals("版本没动也要补上存储类型", "archive", rows().single().serverStorage)
    }

    @Test fun boundRowWithoutStorageTriggersOneFullBackfill() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV7", 1)
        db.trackDao().upsertAll(listOf(
            cloudRow(id, deletedMirror = false).copy(
                videoId = "BV7", serverId = 7, serverSeq = 7, serverStorage = null,
            ),
        ))
        db.libraryAuxDao().putCursor(LibraryCursorEntity(account, 99, 0))
        val (e, t) = engine(listOf(page(listOf(cloud(7, videoId = "BV7", seq = 7)), hasMore = false)))
        e.pull(account)

        assertEquals("有绑定行缺 storage，就从 0 全量拉一次", listOf(0L), t.calls)
        assertEquals("metadata", rows().single().serverStorage)
    }

    @Test fun pullDoesNotRecreateAKeyWaitingForRemoval() = runBlocking {
        db.libraryAuxDao().addRemoval(
            com.getmedia.app.data.local.TrackRemovalEntity(
                platform = "bilibili", videoKey = "BV1", account = account,
                remoteId = 99, requestedAt = 1, expectedSeq = 3, generation = 1,
            ),
        )
        val (e, _) = engine(listOf(page(listOf(cloud(1, videoId = "BV1")), hasMore = false)))
        val result = e.pull(account)
        assertEquals("删除还没重放成功，不能把同键行建回来", 0, result.added)
        assertTrue(rows().isEmpty())
    }

    // ---------- 存量标题对齐（0.1.45 修复） ----------

    /**
     * `confirmedTitle` 早就是服务器的新名字，`title` 却还停在旧名。
     *
     * 这种行是 0.1.44 及以前的合并规则留下的：它把服务器标题记进 `confirmedTitle`、把版本号
     * 记进 `serverSeq`，只把 `title` 留下。版本号追上之后增量拉取不再送它，全量拉的合并也
     * 因为"版本没前进、标题也没漂移"而跳过——只能就地改。
     */
    @Test fun staleLocalRenameIsAlignedToConfirmedTitle() = runBlocking {
        val id = LocalIds.localId("douyin", "7664543280277590195", 1)
        val stale = cloudRow(id, deletedMirror = false).copy(
            title = "出山", localTitleOverride = "出山", confirmedTitle = "出山3",
            serverSeq = 9, serverStorage = "metadata",
        )
        db.trackDao().upsertAll(listOf(stale))

        LibrarySyncRunner.alignTitles(db, account)

        val row = rows().single()
        assertEquals("服务器确认过的名字要落到显示标题上", "出山3", row.title)
        assertNull("遗留的本机改名清掉，否则下一次合并又会挡住服务器标题", row.localTitleOverride)
        assertEquals("出山3", row.confirmedTitle)
        assertEquals("版本号不该被这一步动", 9L, row.serverSeq)
    }

    @Test fun pendingDraftIsNeverAligned() = runBlocking {
        val id = LocalIds.localId("douyin", "1", 1)
        db.trackDao().upsertAll(listOf(
            cloudRow(id, deletedMirror = false).copy(
                title = "我的草稿", localTitleOverride = "我的草稿", confirmedTitle = "服务器旧名",
                pendingTitle = "我的草稿", pendingTitleBaseSeq = 3, pendingTitleGen = 1,
                serverSeq = 3, syncState = LibraryMark.PENDING_UP,
            ),
        ))

        LibrarySyncRunner.alignTitles(db, account)

        val row = rows().single()
        assertEquals("有草稿的行归 LibraryTitlePush 收尾，这里一个字都不能动", "我的草稿", row.title)
        assertEquals("我的草稿", row.localTitleOverride)
    }

    private fun cloudRow(id: Long, deletedMirror: Boolean) = TrackEntity(
        id = id, platform = "bilibili", videoId = "BV1", title = "云端镜像",
        uploader = "UP", durationSec = 10.0, audioBytes = null, audioFormat = "",
        sourceUrl = "https://b/1", localPath = null, favorite = false, localCategoryId = 1,
        syncState = if (deletedMirror) LibraryMark.CLOUD_ONLY else SyncState.SYNCED.name,
        serverId = 1, serverSeq = 1, accountKey = account, serverStorage = "metadata",
    )

    // ---------- 水位 ----------

    @Test fun fullPagingWalksTheWholeLibrary() = runBlocking {
        val (e, t) = engine(
            listOf(
                page(listOf(cloud(1), cloud(2)), hasMore = true),
                page(listOf(cloud(3)), hasMore = false),
            ),
        )
        val result = e.pull(account)
        assertEquals(3, result.added)
        assertEquals(2, result.pages)
        assertEquals("第二页要从第一页末尾的 seq 起", listOf(0L, 2L), t.calls)
        assertEquals(3L, cursor())
    }

    @Test fun waterMarkStaysWhenALaterPageFails() = runBlocking {
        val (e, _) = engine(
            listOf(
                page(listOf(cloud(1), cloud(2)), hasMore = true),
                LibrarySyncException(LibrarySyncProblem.NETWORK, "网络断了"),
            ),
        )
        try {
            e.pull(account)
            fail("第二页失败要抛出去")
        } catch (e: LibrarySyncException) {
            assertEquals(LibrarySyncProblem.NETWORK, e.problem)
        }
        assertEquals("第一页的成果与水位都在", 2, rows().size)
        assertEquals(2L, cursor())
    }

    @Test fun resumesAfterFailureWithoutDuplicates() = runBlocking {
        val (failed, _) = engine(
            listOf(
                page(listOf(cloud(1), cloud(2)), hasMore = true),
                LibrarySyncException(LibrarySyncProblem.SERVER, "500"),
            ),
        )
        runCatching { failed.pull(account) }

        // 恢复后续跑：只从水位 2 开始，补上第 3 条
        val (resumed, t) = engine(listOf(page(listOf(cloud(3)), hasMore = false)))
        val result = resumed.pull(account)

        assertEquals(listOf(2L), t.calls)
        assertEquals(1, result.added)
        assertEquals(3, rows().size)
        assertEquals(3L, cursor())
    }

    @Test fun restartFromScratchAddsNothing() = runBlocking {
        val pages = listOf(
            page(listOf(cloud(1), cloud(2)), hasMore = true),
            page(listOf(cloud(3)), hasMore = false),
        )
        val (first, _) = engine(pages)
        first.pull(account)
        val snapshotBefore = rows()

        // 模拟"行写进去了、水位没来得及落"：把水位清零再拉一遍
        db.libraryAuxDao().putCursor(LibraryCursorEntity(account, 0, 0))
        val (again, t) = engine(pages)
        val result = again.pull(account)

        assertEquals(0, result.added)
        assertEquals(3, result.skipped)
        assertEquals("一页都不能少地重走一遍", listOf(0L, 2L), t.calls)
        assertEquals("重跑后库里还是那 3 行", snapshotBefore, rows())
    }

    @Test fun anotherAccountsRowsAreNotAdopted() = runBlocking {
        val id = LocalIds.localId("bilibili", "BV1", 1)
        db.trackDao().upsertAll(
            listOf(
                TrackEntity(
                    id = id, platform = "bilibili", videoId = "BV1", title = "前一个账号的",
                    uploader = null, durationSec = 10.0, audioBytes = 100, audioFormat = "m4a",
                    sourceUrl = "https://b/1", localPath = "/files/-1.audio",
                    syncState = SyncState.SYNCED.name, serverId = 1, serverSeq = 1, accountKey = "someone-else",
                ),
            ),
        )
        val (e, _) = engine(listOf(page(listOf(cloud(1, videoId = "BV1")), hasMore = false)))
        val result = e.pull(account)

        assertEquals(0, result.added)
        assertEquals(1, result.skipped)
        assertEquals("不许把别人的行认成自己的", "someone-else", rows().single().accountKey)
    }

    @Test fun emptyLibraryPullsUntilHasMoreIsFalse() = runBlocking {
        val (e, t) = engine(listOf(page(emptyList(), hasMore = false)))
        val result = e.pull(account)
        assertEquals(0, result.added)
        assertEquals("空库也走完整的一页，水位保持 0", 0L, result.cursor)
        assertEquals(1, t.calls.size)
    }
}

/** 拉取建行时该用的默认值，写在这里是为了让断言有名字可读。 */
private object MetadataDefaults {
    const val FAVORITE = false
    const val CATEGORY = 1L
}
