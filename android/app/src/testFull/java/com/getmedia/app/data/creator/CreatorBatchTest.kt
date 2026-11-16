package com.getmedia.app.data.creator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CreatorBatchTest {

    private fun item(position: Int) = CreatorItem(
        position = position,
        videoId = "BV$position",
        title = "第 $position 条",
        sourceUrl = "https://www.bilibili.com/video/BV$position",
    )

    private fun batch(vararg positions: Int) =
        CreatorBatch(positions.map { BatchEntry(item = item(it)) })

    @Test fun newBatchSelectsEverything() {
        val b = batch(1, 2, 3)
        assertEquals(3, b.selectedCount)
        assertEquals(3, b.pendingSelected)
        assertFalse(b.isRunning)
        assertFalse(b.settled)
    }

    @Test fun toggleOnlyAffectsPendingRows() {
        var b = batch(1, 2)
        b = b.markDone(1)
        b = b.toggle(1)
        assertTrue(b.entries.first { it.item.position == 1 }.selected)
        b = b.toggle(2)
        assertFalse(b.entries.first { it.item.position == 2 }.selected)
        assertEquals(1, b.selectedCount)
    }

    @Test fun claimNextSkipsUnselectedAndMarksRunning() {
        var b = batch(1, 2)
        b = b.toggle(1)
        val (next, claimed) = b.claimNext()
        assertEquals(2, claimed?.position)
        assertEquals(1, next.runningCount)
        assertTrue(next.isRunning)
        assertFalse(next.settled)
        // 未勾选的 1 仍然待处理，但不会被领走
        assertNull(next.claimNext().second)
    }

    @Test fun markDoneAndFailedCountOnce() {
        var b = batch(1, 2)
        b = b.markDone(1)
        b = b.markFailed(2, "网络不可用")
        assertEquals(1, b.doneCount)
        assertEquals(1, b.failedCount)
        assertEquals("网络不可用", b.entries[1].error)
        assertTrue(b.settled)
    }

    @Test fun cancelLeavesRunningRowAlone() {
        var b = batch(1, 2, 3)
        b = b.claimNext().first
        b = b.cancel()
        assertEquals(1, b.runningCount)
        assertEquals(2, b.cancelledCount)
        assertFalse(b.isRunning.not())
        // 取消后 finish 当前条，仍然是 done，而不是被改写成 cancelled
        b = b.markDone(1)
        assertEquals(1, b.doneCount)
        assertTrue(b.settled)
    }

    @Test fun retryFailedReselectsOnlyFailures() {
        var b = batch(1, 2, 3)
        b = b.markDone(1)
        b = b.markFailed(2, "超时")
        b = b.toggle(3)
        b = b.retryFailed()
        val retried = b.entries.first { it.item.position == 2 }
        assertEquals(BatchState.PENDING, retried.state)
        assertTrue(retried.selected)
        assertNull(retried.error)
        // 已完成的不会被重跑，主动取消勾选的也保持不勾选
        assertEquals(BatchState.DONE, b.entries.first { it.item.position == 1 }.state)
        assertFalse(b.entries.first { it.item.position == 3 }.selected)
    }

    @Test fun selectAllIgnoresFinishedRows() {
        var b = batch(1, 2, 3)
        b = b.markDone(1)
        b = b.selectAll(false)
        // 已完成的行不再参与选择，保持原样；未执行的两行被清掉。
        assertTrue(b.entries.first { it.item.position == 1 }.selected)
        assertFalse(b.entries.first { it.item.position == 2 }.selected)
        assertEquals(1, b.selectedCount)
        b = b.selectAll(true)
        assertEquals(3, b.selectedCount)
        assertEquals(BatchState.DONE, b.entries.first { it.item.position == 1 }.state)
    }

    @Test fun summaryTexts() {
        var b = batch(1, 2)
        assertEquals("批量完成：2 条全部成功", b.markDone(1).markDone(2).summary())

        b = batch(1, 2).markDone(1).markFailed(2, "失败")
        assertEquals("批量完成：成功 1 条，失败 1 条", b.summary())

        b = batch(1).markFailed(1, "失败")
        assertEquals("批量失败：1 条都没能提取", b.summary())

        b = batch(1, 2).markDone(1).cancel()
        assertEquals("已停止：成功 1 条，其余未开始", b.summary())

        b = batch(1).cancel()
        assertEquals("已取消，没有开始提取", b.summary())
    }

    @Test fun emptyBatchIsSettled() {
        val b = CreatorBatch()
        assertTrue(b.settled)
        assertEquals(0, b.pendingSelected)
    }
}
