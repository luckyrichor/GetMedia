package com.getmedia.app.data.library

import java.net.UnknownHostException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量补下载的计数与结果（0.1.16）。
 *
 * 这里锁死的是**"成功数 = 真正落地的条数"**：入口说成功但回读没有文件，必须算失败；
 * 同一批里的重复项只算一次；每条都要有结果，失败带原因。
 * 四个场景与真机验收里点名的四种情况一一对应。
 */
class RefillBatchTest {

    private fun items(vararg ids: Long) = ids.map { RefillBatch.Item(it, "曲目 $it") }

    // ① 两条同时下载：都成功，计数 2，两条各有结果
    @Test
    fun twoItemsBothLand() = runBlocking {
        val landed = mutableSetOf<Long>()
        val results = RefillBatch.run(
            items = items(1, 2),
            download = { id -> landed += id },
            landed = { it in landed },
        )
        assertEquals(2, results.size)
        assertEquals(2, RefillBatch.okCount(results))
        assertEquals("已下载 2 项，可以开始导出了", RefillBatch.message(results))
        assertTrue(results.all { it.ok && it.reason == null })
    }

    // ② 期间发生同步或移除：被移除那条要给原因，另一条照常成功
    @Test
    fun removedMidwayCountsAsFailureWithReason() = runBlocking {
        val present = mutableSetOf(1L)
        val results = RefillBatch.run(
            items = items(1, 2),
            download = { id ->
                if (id !in present) error("这一条已被移除")
                present += id
            },
            landed = { it in present },
        )
        assertEquals(1, RefillBatch.okCount(results))
        val failed = RefillBatch.failed(results).single()
        assertEquals(2L, failed.id)
        assertEquals("这一条已被移除", failed.reason)
        assertEquals("已下载 1 项；没取到 1 项：曲目 2（这一条已被移除）", RefillBatch.message(results))
    }

    // ③ 列表有重复项：同一个 id 只处理一次，不重复计数
    @Test
    fun duplicateIdsHandledOnce() = runBlocking {
        val calls = mutableListOf<Long>()
        val landed = mutableSetOf<Long>()
        val results = RefillBatch.run(
            items = items(7, 7, 7),
            download = { id -> calls += id; landed += id },
            landed = { it in landed },
        )
        assertEquals(listOf(7L), calls)
        assertEquals(1, results.size)
        assertEquals(1, RefillBatch.okCount(results))
    }

    // ④ 只有部分成功：数字必须与落地数一致
    @Test
    fun partialSuccessCountMatchesLanded() = runBlocking {
        val landed = mutableSetOf<Long>()
        val results = RefillBatch.run(
            items = items(1, 2, 3),
            download = { id ->
                if (id == 2L) throw UnknownHostException("Unable to resolve host \"www.douyin.com\"")
                landed += id
            },
            landed = { it in landed },
        )
        assertEquals(2, RefillBatch.okCount(results))
        val failed = RefillBatch.failed(results).single()
        assertEquals(2L, failed.id)
        // 断网原文不进界面：换成能给人看的一句话
        assertEquals("网络不可用，稍后再试", failed.reason)
        assertFalse(RefillBatch.message(results).contains("Unable to resolve host"))
    }

    // 入口不抛异常、但回读没有文件 → 不能算成功（这正是旧写法虚报的口子）
    @Test
    fun missingFileAfterSuccessIsFailure() = runBlocking {
        val results = RefillBatch.run(
            items = items(5),
            download = { /* 入口"成功"了，什么也没留下 */ },
            landed = { false },
        )
        val result = results.single()
        assertFalse(result.ok)
        assertEquals("下载完成但文件没有落到本机", result.reason)
        assertEquals(0, RefillBatch.okCount(results))
        assertEquals("没取到 1 项：曲目 5（下载完成但文件没有落到本机）", RefillBatch.message(results))
    }

    // 回读本身抛异常也不能把整批带崩
    @Test
    fun landedCheckCrashDoesNotAbortBatch() = runBlocking {
        val results = RefillBatch.run(
            items = items(1, 2),
            download = {},
            landed = { id -> if (id == 1L) error("数据库忙") else true },
        )
        assertEquals(1, RefillBatch.okCount(results))
        assertEquals(1L, RefillBatch.failed(results).single().id)
    }

    // 用户点了「取消」：整批停下，已经落地的照常返回，剩下的既不成功也不记成失败
    @Test
    fun cancelStopsTheBatchAndKeepsWhatAlreadyLanded() = runBlocking {
        val calls = mutableListOf<Long>()
        val results = RefillBatch.run(
            items = items(1, 2, 3),
            download = { id ->
                calls += id
                if (id == 2L) throw CancellationException("下载已取消")
            },
            landed = { true },
        )
        assertEquals("取消之后不该再碰第三项", listOf(1L, 2L), calls)
        assertEquals("取消不是失败：已经下好的那条留在结果里", 1, results.size)
        assertTrue(results.single().ok)
        assertNull(RefillBatch.failuresText(results))
    }

    // 失败明细最多列 3 条，多的用「另有 N 项」收口，避免一行长到看不完
    @Test
    fun failuresTextIsTruncated() {
        val results = (1L..5L).map { RefillBatch.Result(it, "曲目 $it", ok = false, reason = "没取到") }
        val text = RefillBatch.failuresText(results)
        assertTrue(text!!.startsWith("没取到 5 项："))
        assertTrue(text.endsWith("；另有 2 项"))
        assertNull(RefillBatch.failuresText(results.filter { it.ok }))
    }
}
