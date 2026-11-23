package com.getmedia.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateGateTest {

    private val hour = 60 * 60 * 1000L

    @Test
    fun neverCheckedYetIsAlwaysDue() {
        assertTrue(UpdateGate.shouldCheck(now = 1_000L, lastCheckAt = 0L, checkedVersion = null, currentVersion = "0.1.31"))
    }

    @Test
    fun sameVersionWaitsSixHours() {
        val last = 10 * hour
        assertFalse(UpdateGate.shouldCheck(last + 5 * hour, last, "0.1.31", "0.1.31"))
        assertFalse(UpdateGate.shouldCheck(last + 6 * hour - 1, last, "0.1.31", "0.1.31"))
        assertTrue(UpdateGate.shouldCheck(last + 6 * hour, last, "0.1.31", "0.1.31"))
        assertTrue(UpdateGate.shouldCheck(last + 7 * hour, last, "0.1.31", "0.1.31"))
    }

    /** 0.1.31 发布时踩到的那个坑：刚装上新版要能立刻查，不能等满 6 小时。 */
    @Test
    fun newVersionIgnoresTheTimerLeftByTheOldOne() {
        val last = 10 * hour
        assertTrue(UpdateGate.shouldCheck(last + 60_000L, last, "0.1.30", "0.1.31"))
    }

    /** 计时器属于"上一次成功查询"，版本号没记录时按没查过处理。 */
    @Test
    fun missingVersionRecordIsAlwaysDue() {
        val last = 10 * hour
        assertTrue(UpdateGate.shouldCheck(last + 60_000L, last, null, "0.1.31"))
    }

    /** 系统时间被往回调：不额外补一次请求，等它自然到点。 */
    @Test
    fun clockMovingBackwardsDoesNotTrigger() {
        val last = 10 * hour
        assertFalse(UpdateGate.shouldCheck(now = last - 3 * hour, lastCheckAt = last, checkedVersion = "0.1.31", currentVersion = "0.1.31"))
    }
}
