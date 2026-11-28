package com.getmedia.app.data.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 本机文件上传进度这条"界面唯一来源"的边界。
 *
 * 与 [DownloadProgressTest] 同源的几条纪律：百分比不越界、收尾一定清空。
 * 多出来的是批量备份特有的那种乱序——上一条的收尾回调晚到、或者批处理已经换了下一条，
 * 都不能把新一条的进度改乱。
 */
class UploadProgressTest {

    @After fun tearDown() = UploadProgress.finish()

    @Test fun fractionFollowsBytesAndNeverExceedsOne() {
        UploadProgress.start(trackId = 7, title = "自己录的一段", total = 162_446_384)
        assertEquals("还没写字节", 0L, UploadProgress.state.value!!.done)
        assertEquals(0f, UploadProgress.state.value!!.fraction!!, 0.0001f)

        UploadProgress.update(7, 81_223_192, 162_446_384)
        assertEquals(0.5f, UploadProgress.state.value!!.fraction!!, 0.001f)

        // 服务器/代理多算了几个字节也不能画出 101%
        UploadProgress.update(7, 170_000_000, 162_446_384)
        assertEquals(1f, UploadProgress.state.value!!.fraction!!, 0.0001f)
    }

    @Test fun updateWithoutKnownTotalKeepsTheOriginalTotal() {
        // 上报里 total 传 null（理论上不会，但别让它把已经知道的分母抹掉）
        UploadProgress.start(trackId = 7, title = "x", total = 4_194_304)
        UploadProgress.update(7, 2_097_152, null)
        val active = UploadProgress.state.value!!
        assertEquals(4_194_304L, active.total)
        assertEquals(0.5f, active.fraction!!, 0.001f)
    }

    @Test fun lateCallbackFromAnotherTrackIsIgnored() {
        UploadProgress.start(trackId = 7, title = "A", total = 10_000)
        UploadProgress.start(trackId = 8, title = "B", total = 20_000)   // 批处理换下一条了
        UploadProgress.update(7, 5_000, 10_000)
        val active = UploadProgress.state.value!!
        assertEquals(8L, active.trackId)
        assertEquals("别把上一条的字节数记到这一条上", 0L, active.done)
    }

    @Test fun updateBeforeStartIsIgnored() {
        // 收尾之后晚到的回调不能把条重新点亮
        UploadProgress.update(7, 200, 1000)
        assertNull(UploadProgress.state.value)
    }

    @Test fun finishClearsEverything() {
        UploadProgress.start(trackId = 7, title = "A", total = 1000)
        UploadProgress.update(7, 100, 1000)
        UploadProgress.finish()
        assertNull(UploadProgress.state.value)
    }
}
