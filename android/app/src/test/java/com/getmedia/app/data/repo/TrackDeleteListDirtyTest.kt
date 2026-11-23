package com.getmedia.app.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.CategoryEntity
import com.getmedia.app.data.local.MediaKind
import com.getmedia.app.data.local.PlaylistEntity
import com.getmedia.app.data.local.PlaylistItemEntity
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 删除作品之后，装着它的列表必须被标成"有本机改动"。
 *
 * 起因：清成员和标脏位是两件事。只清成员的话，本机看着少了几条，云端那份仍留着指向
 * 已删作品的成员，另一台设备上这个列表的项数就对不上；而且不标脏就不会有人去推，
 * 只能等下一次别的改动顺手覆盖，届时以谁为准也说不清。
 *
 * 用内存库直接看落库结果，不经过界面。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TrackDeleteListDirtyTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TrackRepository

    @Before
    fun setUp() = runBlocking {
        // removeAll 末尾会 withContext(Dispatchers.Main) 收播放队列。Robolectric 里
        // runBlocking 正占着主线程，真派发到主线程会在这里死锁；换成立即执行的调度器。
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // 生产库由 onCreate 回调插入 id=1 的"未分类"，内存库没有回调，这里补上
        db.categoryDao().insert(CategoryEntity(1, "未分类", null, "LEAF", 0))
        repo = TrackRepository(context, db)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        db.close()
    }

    private fun track(id: Long) = TrackEntity(
        id = id, platform = "local", videoId = "曲目$id", title = "曲目$id",
        uploader = null, durationSec = 1.0, audioBytes = 1, audioFormat = "m4a",
        sourceUrl = "", localPath = null, addedAtMs = 1,
        mediaType = MediaKind.AUDIO.name,
    )

    private suspend fun playlist(name: String, vararg trackIds: Long): Long {
        val id = db.playlistDao().insert(PlaylistEntity(name = name))
        db.playlistDao().add(
            trackIds.mapIndexed { index, trackId -> PlaylistItemEntity(id, trackId, orderIndex = index) },
        )
        return id
    }

    @Test
    fun deletingATrackMarksOnlyTheListsThatHeldIt() = runBlocking {
        db.trackDao().upsertAll(listOf(track(-1), track(-2)))
        val holder = playlist("列表1", -1)
        val other = playlist("列表2", -2)

        repo.removeAll(setOf(-1L))

        assertTrue("装着被删条目的列表要标脏", db.playlistDao().byId(holder)!!.dirty)
        assertFalse("没装它的列表不该被动到", db.playlistDao().byId(other)!!.dirty)
        assertEquals("成员跟着行一起清掉", 0, db.playlistDao().itemsNow(holder).size)
        assertEquals("另一个列表原样保留", 1, db.playlistDao().itemsNow(other).size)
    }

    @Test
    fun deletingATrackThatIsInNoListLeavesEveryListClean() = runBlocking {
        db.trackDao().upsertAll(listOf(track(-1), track(-2)))
        val list = playlist("列表1", -2)

        repo.removeAll(setOf(-1L))

        assertFalse("删的条目不在任何列表里，列表不该被标脏", db.playlistDao().byId(list)!!.dirty)
        assertEquals(1, db.playlistDao().itemsNow(list).size)
    }

    @Test
    fun deletingSeveralTracksMarksEveryHolder() = runBlocking {
        db.trackDao().upsertAll(listOf(track(-1), track(-2), track(-3)))
        val first = playlist("列表1", -1)
        val second = playlist("列表2", -2, -3)

        repo.removeAll(setOf(-1L, -2L))

        assertTrue(db.playlistDao().byId(first)!!.dirty)
        assertTrue(db.playlistDao().byId(second)!!.dirty)
        assertEquals(0, db.playlistDao().itemsNow(first).size)
        assertEquals("只删了 -2，-3 要留着", listOf(-3L), db.playlistDao().itemsNow(second).map { it.trackId })
    }
}
