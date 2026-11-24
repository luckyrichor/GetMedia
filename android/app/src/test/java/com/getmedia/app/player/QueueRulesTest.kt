package com.getmedia.app.player

import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueRulesTest {
    // 用命名参数：TrackEntity 以后再插字段也不会把这里的位置参数挤错位。
    private fun track(id: Long, video: String = "$id", hash: String? = null) = TrackEntity(
        id = id,
        platform = "douyin",
        videoId = video,
        title = "音频$id",
        uploader = null,
        durationSec = 30.0,
        audioBytes = 100L,
        audioFormat = "m4a",
        sourceUrl = "",
        audioSha256 = hash,
    )
    @Test fun sameIdCannotBeAddedAgainAfterRename() {
        val old = track(1)
        assertEquals(emptyList<TrackEntity>(), newQueueTracks(listOf(old), listOf(old.copy(title = "新名称"))))
    }
    @Test fun repeatedItemsInOneBatchAreDeduplicated() {
        assertEquals(listOf(track(1), track(2)), newQueueTracks(emptyList(), listOf(track(1), track(1), track(2))))
    }
    @Test fun sameFileHashWithDifferentIdsIsDeduplicated() {
        assertEquals(emptyList<TrackEntity>(), newQueueTracks(listOf(track(1, hash = "abc")), listOf(track(2, hash = "ABC"))))
    }
    @Test fun samePlatformSourceIsDeduplicatedWithoutHash() {
        assertEquals(emptyList<TrackEntity>(), newQueueTracks(listOf(track(1, "video")), listOf(track(2, "video"))))
    }
    @Test fun differentFilesWithSameTitleAreKept() {
        val other = track(2).copy(title = "音频1")
        assertEquals(listOf(other), newQueueTracks(listOf(track(1)), listOf(other)))
    }

    // ---------- 播放列表预载（0.1.13：一条空壳不能拖死整次播放） ----------

    @Test fun seedSkipsRowsWithoutUsableFileInsteadOfThrowing() = runBlocking {
        val good = track(1)
        val shell = track(2)
        val seeds = seedQueueItems(listOf(good, shell)) { t ->
            if (t.id == 2L) error("这条是从云端清单恢复的，本机还没有文件") else "/files/1.m4a"
        }
        assertEquals(listOf(good to "/files/1.m4a"), seeds)
    }

    @Test fun seedKeepsPlaylistOrderAndDropsBlankPaths() = runBlocking {
        val a = track(1); val b = track(2); val c = track(3)
        val seeds = seedQueueItems(listOf(a, b, c)) { t ->
            when (t.id) {
                1L -> "/files/1.m4a"
                2L -> ""
                else -> "/files/3.m4a"
            }
        }
        assertEquals(listOf(a to "/files/1.m4a", c to "/files/3.m4a"), seeds)
    }

    /** 取消不是"这条放不了"，是整次播放被取消：必须继续抛。 */
    @Test fun seedRethrowsCancellation() = runBlocking {
        val failure = runCatching {
            seedQueueItems(listOf(track(1))) { throw CancellationException("stop") }
        }
        assertTrue(failure.exceptionOrNull() is CancellationException)
    }

    // ---------- 未下载条目跳过（0.1.34：队列条数要跟列表一致） ----------

    @Test fun skipsConsecutiveMissingItemsInOneStep() {
        // 第 1 条有文件，2/3 没有 → 从 1 往后第一次能播的是 4。
        assertEquals(3, nextPlayableIndex(listOf(true, false, false, true), 0))
    }

    @Test fun wrapsAroundWhenMissingItemsAreAtTheEnd() {
        // 最后两条没有文件，循环回第一首，而不是停在那里。
        assertEquals(0, nextPlayableIndex(listOf(true, true, false, false), 1))
    }

    @Test fun returnsNullWhenNothingIsPlayable() {
        // 全都没下载：调用方要暂停，不能无限 seek。
        assertEquals(null, nextPlayableIndex(listOf(false, false), 0))
        assertEquals(null, nextPlayableIndex(emptyList(), 0))
    }

    @Test fun startingBeforeZeroFindsTheFirstPlayable() {
        // -1 表示"还没开始播"，用来给新队列挑起点：跳过开头的占位项。
        assertEquals(2, nextPlayableIndex(listOf(false, false, true), -1))
    }
}
