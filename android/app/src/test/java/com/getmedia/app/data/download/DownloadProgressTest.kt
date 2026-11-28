package com.getmedia.app.data.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 远端下载进度这条"界面唯一来源"的边界。
 *
 * 盯的是三件事：百分比不会越界、总长度未知时不编数字、收尾一定要清空
 * （否则界面上会永远挂着一条 100% 的进度条，比没有进度条更糟）。
 */
class DownloadProgressTest {

    @After fun tearDown() = DownloadProgress.finish()

    @Test fun fractionFollowsBytesAndNeverExceedsOne() {
        DownloadProgress.start(trackId = 7, title = "老友记第五季第14集")
        assertNull("刚开始还不知道总长度", DownloadProgress.state.value!!.fraction)

        DownloadProgress.update(7, 81_223_192, 162_446_384)
        assertEquals(0.5f, DownloadProgress.state.value!!.fraction!!, 0.001f)

        // 服务器声明的总长度比实际发的少：钳到 1，界面不该出现 103% 这种数
        DownloadProgress.update(7, 170_000_000, 162_446_384)
        assertEquals(1f, DownloadProgress.state.value!!.fraction!!, 0.0001f)
    }

    @Test fun unknownTotalStaysUnknown() {
        DownloadProgress.start(trackId = 7, title = "x")
        DownloadProgress.update(7, 1_048_576, null)
        val active = DownloadProgress.state.value!!
        assertNull("没给总长度就不该编百分比", active.fraction)
        assertEquals(1_048_576L, active.done)
        // 后面某一轮拿到了总长度就补上
        DownloadProgress.update(7, 2_097_152, 4_194_304)
        assertEquals(0.5f, DownloadProgress.state.value!!.fraction!!, 0.001f)
    }

    @Test fun lateCallbackFromAnotherTrackIsIgnored() {
        DownloadProgress.start(trackId = 7, title = "A")
        DownloadProgress.start(trackId = 8, title = "B")     // 上一条收尾晚了，锁已经换人
        DownloadProgress.update(7, 5_000, 10_000)
        val active = DownloadProgress.state.value!!
        assertEquals(8L, active.trackId)
        assertEquals("别把上一条的字节数记到这一条上", 0L, active.done)
    }

    @Test fun finishClearsEverything() {
        DownloadProgress.start(trackId = 7, title = "A")
        DownloadProgress.update(7, 100, 1000)
        DownloadProgress.finish()
        assertNull(DownloadProgress.state.value)
        // 收尾之后再到回调也不能把条重新点亮
        DownloadProgress.update(7, 200, 1000)
        assertNull(DownloadProgress.state.value)
    }
}
