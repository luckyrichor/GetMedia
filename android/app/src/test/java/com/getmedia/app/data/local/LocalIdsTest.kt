package com.getmedia.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

class LocalIdsTest {

    @Test fun idIsAlwaysNegativeAndStable() {
        val id = LocalIds.localId("bilibili", "BV1xx411c7mD")
        assertTrue("id 必须为负数（本机行约定）", id < 0)
        assertEquals(id, LocalIds.localId("bilibili", "BV1xx411c7mD"))
    }

    @Test fun platformIsPartOfTheKey() {
        assertNotEquals(
            LocalIds.localId("bilibili", "123"),
            LocalIds.localId("douyin", "123"),
        )
    }

    @Test fun matchesDocumentedFormula() {
        // 与实现独立算一遍：-(sha256("platform:key") 的低 63 位)，防止有人改动规则却没人发现
        val digest = MessageDigest.getInstance("SHA-256").digest("douyin:7412".toByteArray())
        val expected = -((ByteBuffer.wrap(digest).long and Long.MAX_VALUE).takeIf { it > 0 } ?: 1L)
        assertEquals(expected, LocalIds.localId("douyin", "7412"))
    }

    // ---------- 多 P：id 必须带页码，否则 P2 覆盖 P1 ----------

    @Test fun bilibiliPageIsPartOfTheKey() {
        assertEquals("BV1xx411c7mD", LocalIds.key("bilibili", "BV1xx411c7mD", 1))
        assertEquals("BV1xx411c7mD-p2", LocalIds.key("bilibili", "BV1xx411c7mD", 2))
        // 页码 <= 0 不该出现（链接解析会挡住），这里按"没有页码"处理，不产生 "-p0"
        assertEquals("BV1xx411c7mD", LocalIds.key("bilibili", "BV1xx411c7mD", 0))
    }

    @Test fun bilibiliMultiPartIdsDifferFromFirstPart() {
        val p1 = LocalIds.localId("bilibili", "BV1xx411c7mD", 1)
        val p2 = LocalIds.localId("bilibili", "BV1xx411c7mD", 2)
        // 与手写键算出的结果一致：两条提取路径共用同一条规则
        assertEquals(LocalIds.localId("bilibili", "BV1xx411c7mD-p2"), p2)
        assertNotEquals("P2 不能与 P1 同 id，否则会直接覆盖", p1, p2)
    }

    @Test fun douyinIgnoresPage() {
        // 抖音没有分 P：即使服务器给了 page，也不能改变 id
        assertEquals("7412", LocalIds.key("douyin", "7412", 2))
        assertEquals(LocalIds.localId("douyin", "7412"), LocalIds.localId("douyin", "7412", 2))
    }

    @Test fun keyIsIdempotentForMultiPart() {
        // 手机直提取写进库的 videoId 本身就是键形态（BV1xx-p2），快照原样带上它；
        // 恢复时若再拼一次就成了 BV1xx-p2-p2 —— 后缀每往返一次长一截，id 也跟着变，
        // 同一台设备恢复一次就多一条重复。键必须幂等。
        val once = LocalIds.key("bilibili", "BV1xx411c7mD", 2)
        assertEquals(once, LocalIds.key("bilibili", once, 2))
        assertEquals(
            "恢复算出的 id 必须与建库时相同",
            LocalIds.localId("bilibili", "BV1xx411c7mD", 2),
            LocalIds.localId("bilibili", once, 2),
        )
    }

    @Test fun keyedPageDoesNotSwallowAnotherPage() {
        // 已经是 P2 的行若被说成 P3（脏数据），不能当成 P2 放过去
        assertEquals("BV1xx411c7mD-p2-p3", LocalIds.key("bilibili", "BV1xx411c7mD-p2", 3))
    }
}
