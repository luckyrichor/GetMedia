package com.getmedia.app.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.LocalIds
import com.getmedia.app.data.local.PlaylistEntity
import com.getmedia.app.data.local.PlaylistItemEntity
import com.getmedia.app.data.local.PlaylistRemovalEntity
import com.getmedia.app.data.local.TrackEntity
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

/**
 * 整理信息同步：类别路径换算、收藏/类别推送、播放列表双向同步。
 *
 * 全部用内存库 + 假通道，不出网；断言只看落库结果，不看提示文案。
 */
@RunWith(AndroidJUnit4::class)
class LibraryCollectionsTest {

    private lateinit var db: AppDatabase
    private val account = "boss"

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    // ---------- 夹具 ----------

    private fun track(videoId: String, favorite: Boolean = false, metaDirty: Boolean = false,
                      serverId: Long = 1) = TrackEntity(
        id = LocalIds.localId("douyin", videoId, 1),
        platform = "douyin",
        videoId = videoId,
        title = "条目 $videoId",
        uploader = "某博主",
        durationSec = 12.0,
        audioBytes = null,
        audioFormat = "m4a",
        sourceUrl = "https://www.douyin.com/video/$videoId",
        localPath = "/files/${videoId}.m4a",
        favorite = favorite,
        metaDirty = metaDirty,
        mediaType = "AUDIO",
        serverId = serverId,
        serverSeq = 1,
        syncState = "SYNCED",
        accountKey = account,
    )

    private class FakeMeta(val result: (Long) -> CloudTrack) : MetaWriteTransport {
        val calls = mutableListOf<Triple<Long, Boolean, String>>()
        override suspend fun patchMeta(trackId: Long, favorite: Boolean, category: String): CloudTrack {
            calls += Triple(trackId, favorite, category)
            return result(trackId)
        }
    }

    private class FakeLists(
        private var remote: MutableList<CloudPlaylist> = mutableListOf(),
        private val failListWith: LibrarySyncProblem? = null,
    ) : PlaylistReadTransport, PlaylistWriteTransport {
        val puts = mutableListOf<Pair<String, List<CloudPlaylistItem>>>()
        val deletes = mutableListOf<String>()
        var listCalls = 0

        override suspend fun list(): List<CloudPlaylist> {
            listCalls++
            if (failListWith != null) throw LibrarySyncException(failListWith, "接口不可用")
            return remote.toList()
        }

        override suspend fun put(name: String, items: List<CloudPlaylistItem>): CloudPlaylist {
            puts += name to items
            val row = CloudPlaylist(name, deleted = false, seq = 9, items = items)
            remote.removeAll { it.name == name }
            remote += row
            return row
        }

        override suspend fun delete(name: String) {
            deletes += name
            remote.removeAll { it.name == name }
        }
    }

    /** 内存库不跑 onCreate 回调，默认类别「未分类」（id=1）要自己补上。 */
    private suspend fun seedDefaultCategory() {
        db.categoryDao().insert(
            com.getmedia.app.data.local.CategoryEntity(
                id = CategoryPaths.DEFAULT_ID, name = "未分类", parentId = null, kind = "LEAF",
            ),
        )
    }

    private fun cloudTrack(id: Long) = CloudTrack(
        id = id, platform = "douyin", videoId = "v$id", page = 1, title = "云端",
        uploader = null, durationSec = null, sourceUrl = "", mediaType = "audio",
        deleted = false, updatedSeq = 11, updatedAtSec = 1_700_000_011,
    )

    // ---------- 类别路径 ----------

    @Test fun pathIsBuiltFromCategoryTree() = runBlocking {
        seedDefaultCategory()
        val dao = db.categoryDao()
        val root = dao.insert(com.getmedia.app.data.local.CategoryEntity(name = "音乐", parentId = null, kind = "CONTAINER"))
        val leaf = dao.insert(com.getmedia.app.data.local.CategoryEntity(name = "古风", parentId = root, kind = "LEAF"))

        assertEquals("音乐/古风", CategoryPaths.pathOf(dao, leaf))
        assertEquals("", CategoryPaths.pathOf(dao, CategoryPaths.DEFAULT_ID))
    }

    @Test fun ensurePathCreatesMissingLevelsAndReusesExisting() = runBlocking {
        seedDefaultCategory()
        val dao = db.categoryDao()
        val first = CategoryPaths.ensurePath(dao, "音乐/古风")
        val again = CategoryPaths.ensurePath(dao, "音乐/古风")
        val sibling = CategoryPaths.ensurePath(dao, "音乐/摇滚")

        assertEquals("同路径必须复用同一个节点", first, again)
        assertEquals("同层新名字要新建", 3, dao.allNow().count { it.id != CategoryPaths.DEFAULT_ID })
        val music = dao.byId(first)?.parentId?.let { dao.byId(it) }
        assertEquals("音乐", music?.name)
        assertEquals("CONTAINER", music?.kind)
        assertEquals("末级是 LEAF", "LEAF", dao.byId(sibling)?.kind)
        assertEquals(CategoryPaths.DEFAULT_ID, CategoryPaths.ensurePath(dao, "  "))
    }

    // ---------- 收藏与类别推送 ----------

    @Test fun metaPushClearsDirtyOnlyForPushedRows() = runBlocking {
        db.trackDao().upsertAll(listOf(track("a", favorite = true, metaDirty = true)))
        val transport = FakeMeta { cloudTrack(it) }

        val result = LibraryMetaPush(db, transport, account).push()

        assertEquals(1, result.pushed)
        assertEquals(1, transport.calls.size)
        assertEquals(true, transport.calls[0].second)
        val row = db.trackDao().allNow().single()
        assertFalse("推成功要清脏位", row.metaDirty)
        assertEquals("服务器版本要跟上", 11L, row.serverSeq)
    }

    @Test fun metaPushKeepsDirtyWhenNetworkFails() = runBlocking {
        db.trackDao().upsertAll(listOf(track("a", favorite = true, metaDirty = true)))
        val transport = object : MetaWriteTransport {
            override suspend fun patchMeta(trackId: Long, favorite: Boolean, category: String): CloudTrack =
                throw LibrarySyncException(LibrarySyncProblem.NETWORK, "断网了")
        }

        val result = LibraryMetaPush(db, transport, account).push()

        assertEquals(0, result.pushed)
        assertEquals(LibrarySyncProblem.NETWORK, result.stopped)
        assertTrue("下一轮还得接着推", db.trackDao().allNow().single().metaDirty)
    }

    @Test fun metaPushSendsCategoryPath() = runBlocking {
        seedDefaultCategory()
        val dao = db.categoryDao()
        val root = dao.insert(com.getmedia.app.data.local.CategoryEntity(name = "音乐", parentId = null, kind = "CONTAINER"))
        val leaf = dao.insert(com.getmedia.app.data.local.CategoryEntity(name = "古风", parentId = root, kind = "LEAF"))
        db.trackDao().upsertAll(listOf(track("a", metaDirty = true).copy(localCategoryId = leaf)))
        val transport = FakeMeta { cloudTrack(it) }

        LibraryMetaPush(db, transport, account).push()

        assertEquals("音乐/古风", transport.calls.single().third)
    }

    @Test fun unboundRowsAreNotPushed() = runBlocking {
        db.trackDao().upsertAll(listOf(track("a", favorite = true, metaDirty = true).copy(serverId = null)))
        val transport = FakeMeta { cloudTrack(it) }

        val result = LibraryMetaPush(db, transport, account).push()

        assertEquals(0, result.pushed)
        assertTrue("没绑服务器 id 的行留着，等绑定", transport.calls.isEmpty())
        assertTrue(db.trackDao().allNow().single().metaDirty)
    }

    // ---------- 播放列表 ----------

    @Test fun pullCreatesRemoteListAndMapsItemsToLocalRows() = runBlocking {
        db.trackDao().upsertAll(listOf(track("a"), track("b")))
        val remote = mutableListOf(
            CloudPlaylist(
                name = "跑步", deleted = false, seq = 3,
                items = listOf(CloudPlaylistItem("douyin", "b", 1), CloudPlaylistItem("douyin", "a", 1)),
            ),
        )
        val transport = FakeLists(remote)

        val result = LibraryPlaylistSync(db, transport, transport).sync(account)

        assertEquals(1, result.created)
        val list = db.playlistDao().allNow().single()
        assertEquals("跑步", list.name)
        val items = db.playlistDao().itemsNow(list.id).sortedBy { it.orderIndex }
        assertEquals(
            "云端顺序要照搬",
            listOf("b", "a"),
            items.mapNotNull { db.trackDao().byId(it.trackId)?.videoId },
        )
    }

    @Test fun localDirtyListIsNotOverwrittenByPull() = runBlocking {
        db.trackDao().upsertAll(listOf(track("a"), track("b")))
        val id = db.playlistDao().insert(PlaylistEntity(name = "跑步", dirty = true, syncedAt = 5))
        db.playlistDao().add(listOf(PlaylistItemEntity(id, db.trackDao().allNow().first { it.videoId == "a" }.id, orderIndex = 0)))
        val remote = mutableListOf(
            CloudPlaylist("跑步", deleted = false, seq = 9, items = listOf(CloudPlaylistItem("douyin", "b", 1))),
        )
        val transport = FakeLists(remote)

        LibraryPlaylistSync(db, transport, transport).sync(account)

        val items = db.playlistDao().itemsNow(id)
        assertEquals("本地未推送的改动不能被覆盖", 1, items.size)
        assertEquals(db.trackDao().allNow().first { it.videoId == "a" }.id, items.single().trackId)
    }

    @Test fun firstAlignmentFollowsCloudEvenIfLocalIsDirty() = runBlocking {
        db.trackDao().upsertAll(listOf(track("a"), track("b")))
        val id = db.playlistDao().insert(PlaylistEntity(name = "跑步", dirty = true, syncedAt = 0))
        db.playlistDao().add(listOf(PlaylistItemEntity(id, db.trackDao().allNow().first { it.videoId == "a" }.id, orderIndex = 0)))
        val remote = mutableListOf(
            CloudPlaylist("跑步", deleted = false, seq = 9, items = listOf(CloudPlaylistItem("douyin", "b", 1))),
        )
        val transport = FakeLists(remote)

        LibraryPlaylistSync(db, transport, transport).sync(account)

        val items = db.playlistDao().itemsNow(id)
        assertEquals("首次对齐以云端为准", 1, items.size)
        assertEquals(db.trackDao().allNow().first { it.videoId == "b" }.id, items.single().trackId)
        assertFalse("对齐后不再待推送", db.playlistDao().byId(id)!!.dirty)
    }

    @Test fun dirtyListIsPushedWithPlatformKeys() = runBlocking {
        db.trackDao().upsertAll(listOf(track("a")))
        val id = db.playlistDao().insert(PlaylistEntity(name = "跑步", dirty = true, syncedAt = 5))
        db.playlistDao().add(listOf(PlaylistItemEntity(id, db.trackDao().allNow().single().id, orderIndex = 0)))
        val transport = FakeLists()

        val result = LibraryPlaylistSync(db, transport, transport).sync(account)

        assertEquals(1, result.pushed)
        assertEquals("跑步", transport.puts.single().first)
        assertEquals(
            "成员要按平台三元组发出去",
            listOf(CloudPlaylistItem("douyin", "a", 1)),
            transport.puts.single().second,
        )
        assertFalse(db.playlistDao().byId(id)!!.dirty)
        assertTrue("对齐过的列表要记时间", db.playlistDao().byId(id)!!.syncedAt > 0)
    }

    @Test fun tombstoneRemovesLocalListButKeepsTracks() = runBlocking {
        db.trackDao().upsertAll(listOf(track("a")))
        val id = db.playlistDao().insert(PlaylistEntity(name = "跑步", syncedAt = 5))
        db.playlistDao().add(listOf(PlaylistItemEntity(id, db.trackDao().allNow().single().id, orderIndex = 0)))
        val transport = FakeLists(mutableListOf(CloudPlaylist("跑步", deleted = true, seq = 9, items = emptyList())))

        val result = LibraryPlaylistSync(db, transport, transport).sync(account)

        assertEquals(1, result.removed)
        assertNull("列表没了", db.playlistDao().byName("跑步"))
        assertEquals("条目本身不能被删", 1, db.trackDao().allNow().size)
    }

    @Test fun queuedDeletionIsReplayedBeforePull() = runBlocking {
        db.playlistDao().addRemoval(PlaylistRemovalEntity(account, "旧名", requestedAt = 1))
        val transport = FakeLists()

        LibraryPlaylistSync(db, transport, transport).sync(account)

        assertEquals(listOf("旧名"), transport.deletes)
        assertTrue("重放成功要出队", db.playlistDao().removals(account).isEmpty())
    }

    @Test fun listEndpointUnavailableStopsTheRoundSilently() = runBlocking {
        db.trackDao().upsertAll(listOf(track("a")))
        val id = db.playlistDao().insert(PlaylistEntity(name = "跑步", dirty = true, syncedAt = 5))
        db.playlistDao().add(listOf(PlaylistItemEntity(id, db.trackDao().allNow().single().id, orderIndex = 0)))
        val transport = FakeLists(failListWith = LibrarySyncProblem.NOT_AVAILABLE)

        val result = LibraryPlaylistSync(db, transport, transport).sync(account)

        assertEquals(LibrarySyncProblem.NOT_AVAILABLE, result.stopped)
        assertTrue("服务器没这个接口时不要硬推", transport.puts.isEmpty())
        assertTrue("脏位留着，部署后自愈", db.playlistDao().byId(id)!!.dirty)
    }

    @Test fun itemsMissingLocallyBecomePlaceholdersInsteadOfDisappearing() = runBlocking {
        db.trackDao().upsertAll(listOf(track("a")))
        val remote = mutableListOf(
            CloudPlaylist(
                name = "跑步", deleted = false, seq = 3,
                items = listOf(
                    CloudPlaylistItem("douyin", "not-yet-synced", 1),
                    CloudPlaylistItem("douyin", "a", 1),
                ),
            ),
        )
        val transport = FakeLists(remote)

        LibraryPlaylistSync(db, transport, transport).sync(account)

        val list = db.playlistDao().allNow().single()
        val items = db.playlistDao().itemsNow(list.id).sortedBy { it.orderIndex }
        assertEquals("成员一个都不能丢：对不上的要补占位行", 2, items.size)
        assertEquals("顺序按云端", listOf(0, 1), items.map { it.orderIndex })

        val missingId = LocalIds.localId("douyin", "not-yet-synced", 1)
        assertEquals("缺的那条要指向确定性 id", missingId, items.first().trackId)
        val placeholder = db.trackDao().byId(missingId)
        assertNotNull("占位行必须落库", placeholder)
        assertEquals(
            "状态与「云端未下载」完全一致，界面才会标未下载",
            LibraryMark.CLOUD_ONLY, placeholder!!.syncState,
        )
        assertNull("占位行没有云端身份，等曲库行到了再绑", placeholder.serverId)
        assertNull("占位行没有文件", placeholder.localPath)
        assertEquals("标题先放作品号", "not-yet-synced", placeholder.title)
        assertFalse(
            "占位行不能被当成本机新增上传",
            com.getmedia.app.data.local.SyncState.mayUploadToLibrary(
                com.getmedia.app.data.local.SyncState.of(placeholder.syncState), hasLocalFile = false,
            ),
        )
    }

    @Test fun queuedDeletionKeysAreNotRevivedByPlaceholders() = runBlocking {
        // 用户刚在手机上删掉这件作品，删除还没重放成功。列表里若还有它，
        // 不能靠"补占位行"把它复活成一条未下载条目。
        db.libraryAuxDao().addRemoval(
            com.getmedia.app.data.local.TrackRemovalEntity(
                platform = "douyin", videoKey = "gone", account = account,
            ),
        )
        val remote = mutableListOf(
            CloudPlaylist(
                name = "跑步", deleted = false, seq = 3,
                items = listOf(CloudPlaylistItem("douyin", "gone", 1)),
            ),
        )
        val transport = FakeLists(remote)

        LibraryPlaylistSync(db, transport, transport).sync(account)

        val list = db.playlistDao().allNow().single()
        assertTrue("删过的作品不补", db.playlistDao().itemsNow(list.id).isEmpty())
        assertNull(db.trackDao().byId(LocalIds.localId("douyin", "gone", 1)))
    }
}
