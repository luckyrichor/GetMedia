package com.getmedia.app.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.SyncState
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * 重新下载的节奏与熔断（设计 §5.6）：
 * 条间隔 3 秒 ±1 秒、连续失败 3 条暂停、没有来源链接的不计失败。
 * 真等 3 秒会让测试变慢，所以把 `sleep` 换成记录器——断言的是"要求睡多久"，不是"真的睡了"。
 */
@RunWith(AndroidJUnit4::class)
class RedownloadRunnerTest {

    private lateinit var db: AppDatabase
    private val sleeps = mutableListOf<Long>()
    private val attempts = AtomicInteger()

    private fun track(id: Long, url: String = "https://www.bilibili.com/video/BV$id") = TrackEntity(
        id = id, platform = "bilibili", videoId = "BV$id", title = "条目$id", uploader = null,
        durationSec = null, audioBytes = null, audioFormat = "m4a", sourceUrl = url,
        localPath = null, favorite = false, localCategoryId = 1, addedAtMs = 1, sortIndex = 0,
        mediaType = "AUDIO",
    )

    private fun runner(ok: Boolean) = RedownloadRunner(
        db = db,
        extract = { _, _ ->
            attempts.incrementAndGet()
            if (!ok) error("链接失效")
            TrackEntity(
                id = 0, platform = "bilibili", videoId = "BV", title = "重新下载", uploader = null,
                durationSec = 1.0, audioBytes = 10, audioFormat = "m4a",
                sourceUrl = "https://www.bilibili.com/video/BV", localPath = "/sdcard/x.m4a",
                favorite = false, localCategoryId = 1, addedAtMs = 0, sortIndex = 0, mediaType = "AUDIO",
            )
        },
        sleep = { sleeps += it },
        random = Random(7),
    )

    private fun awaitIdle(runner: RedownloadRunner, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val s = runner.state.value
            if (!s.running) return
            Thread.sleep(20)
        }
        throw AssertionError("等待重新下载结束超时")
    }

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun intervalIsThreeSecondsGiveOrTakeOne() {
        val r = runner(true)
        val samples = (1..200).map { r.intervalMs() }
        assertTrue("间隔必须落在 2–4 秒", samples.all { it in 2_000..4_000 })
        assertTrue("均值应接近 3 秒", samples.average() in 2_700.0..3_300.0)
    }

    @Test
    fun successfulRunWritesLocalPathAndSleepsBetweenItems() = runBlocking {
        db.trackDao().upsertAll(listOf(track(-1), track(-2), track(-3)))
        val r = runner(ok = true)
        r.start(listOf(-1, -2, -3))
        awaitIdle(r)

        assertEquals(3, r.state.value.ok)
        assertEquals("/sdcard/x.m4a", db.trackDao().byId(-1)?.localPath)
        assertEquals("三条之间睡两次", 2, sleeps.size)
        assertTrue(sleeps.all { it in 2_000..4_000 })
        assertTrue(r.state.value.finished)
    }

    @Test
    fun threeConsecutiveFailuresPauseTheRun() = runBlocking {
        db.trackDao().upsertAll(listOf(track(-1), track(-2), track(-3), track(-4)))
        val r = runner(ok = false)
        r.start(listOf(-1, -2, -3, -4))
        awaitIdle(r)

        val paused = r.state.value
        assertFalse(paused.running)
        assertEquals(3, paused.failed)
        assertEquals(3, attempts.get())
        assertNotNull("必须给出暂停原因", paused.paused)
        assertTrue(paused.paused!!.contains("连续 3 条失败"))
        assertNull("暂停后不再继续请求", db.trackDao().byId(-4)?.localPath)
    }

    @Test
    fun resumeContinuesFromTheRemainingItem() = runBlocking {
        db.trackDao().upsertAll(listOf(track(-1), track(-2), track(-3), track(-4)))
        // 前三次失败触发熔断，之后恢复：模拟用户点"继续"后网络恢复
        var calls = 0
        val r = RedownloadRunner(
            db = db,
            extract = { _, _ ->
                calls++
                if (calls <= 3) error("链接失效")
                TrackEntity(
                    id = 0, platform = "bilibili", videoId = "BV", title = "重新下载", uploader = null,
                    durationSec = 1.0, audioBytes = 10, audioFormat = "m4a",
                    sourceUrl = "https://www.bilibili.com/video/BV", localPath = "/sdcard/x.m4a",
                    favorite = false, localCategoryId = 1, addedAtMs = 0, sortIndex = 0, mediaType = "AUDIO",
                )
            },
            sleep = { },
            random = Random(1),
        )
        r.start(listOf(-1, -2, -3, -4))
        awaitIdle(r)
        assertNotNull(r.state.value.paused)

        r.resume()
        awaitIdle(r)

        assertEquals("继续后只剩队尾那一条", 4, calls)
        assertEquals(1, r.state.value.ok)
        assertNull(r.state.value.paused)
        assertNotNull(db.trackDao().byId(-4)?.localPath)
    }

    @Test
    fun entriesWithoutSourceUrlAreSkippedNotCountedAsFailures() = runBlocking {
        db.trackDao().upsertAll(listOf(track(-1, url = ""), track(-2)))
        val r = runner(ok = true)
        r.start(listOf(-1, -2))
        awaitIdle(r)

        assertEquals(1, r.state.value.needsImport)
        assertEquals(0, r.state.value.failed)
        assertEquals(1, r.state.value.ok)
        assertEquals("跳过的条目不需要等待", 0, sleeps.size)
    }

    @Test
    fun alreadyDownloadedEntriesAreNotFetchedAgain() = runBlocking {
        db.trackDao().upsertAll(listOf(track(-1).copy(localPath = "/sdcard/a.m4a")))
        val r = runner(ok = true)
        r.start(listOf(-1))
        awaitIdle(r)

        assertEquals(0, attempts.get())
        assertEquals(1, r.state.value.done)
        assertNull(r.state.value.paused)
    }

    // ---------- 分 P：链接里得有 ?p=N ----------

    @Test
    fun withPageOnlyFillsWhatTheUrlDoesNotSay() {
        assertEquals("https://www.bilibili.com/video/BV1?p=2", withPage("https://www.bilibili.com/video/BV1", 2))
        assertEquals("https://www.bilibili.com/video/BV1?x=1&p=2", withPage("https://www.bilibili.com/video/BV1?x=1", 2))
        // 链接自己写了页码就以链接为准（那是用户看得到、点得开的那一条）
        assertEquals("https://www.bilibili.com/video/BV1?p=3", withPage("https://www.bilibili.com/video/BV1?p=3", 2))
        assertEquals("https://www.bilibili.com/video/BV1", withPage("https://www.bilibili.com/video/BV1", 1))
        // 抖音没有分 P，别往上乱加参数
        assertEquals("https://v.douyin.com/abc", withPage("https://v.douyin.com/abc", 2))
    }

    @Test
    fun restoredPartTwoAsksTheExtractorForPartTwo() = runBlocking {
        // 服务器提取落库的行：videoId 是裸 id、sourceUrl 没有 ?p=，页码只在 serverPage 里
        db.trackDao().upsertAll(
            listOf(
                track(-1, url = "https://www.bilibili.com/video/BV1")
                    .copy(videoId = "BV1", serverPage = 2, syncState = SyncState.RESTORED.name)
            )
        )
        val seen = mutableListOf<String>()
        val r = RedownloadRunner(
            db = db,
            extract = { url, _ ->
                seen += url
                TrackEntity(
                    id = 0, platform = "bilibili", videoId = "BV$url", title = "P2", uploader = null,
                    durationSec = 1.0, audioBytes = 10, audioFormat = "m4a",
                    sourceUrl = url, localPath = "/sdcard/p2.m4a",
                    favorite = false, localCategoryId = 1, addedAtMs = 0, sortIndex = 0, mediaType = "AUDIO",
                )
            },
            sleep = { },
            random = Random(1),
        )
        r.start(listOf(-1))
        awaitIdle(r)

        assertEquals(listOf("https://www.bilibili.com/video/BV1?p=2"), seen)
        assertEquals(1, r.state.value.ok)
    }
}
