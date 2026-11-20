package com.getmedia.app.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RestoredIdentityTest {
    @Test fun legacyAndCanonicalP3AreEqual() {
        val legacy = identity("BV1KG411w7r2-p3", 1)
        assertEquals(3, legacy?.page)
        assertEquals(legacy, identity("BV1KG411w7r2", 3))
        assertNotEquals(legacy, identity("BV1KG411w7r2", 1))
        assertNotEquals(legacy, identity("BV1KG411w7r2", 3, kind = "video"))
    }

    @Test fun contradictoryOrInvalidPageIsRejected() {
        assertNull(identity("BV1KG411w7r2-p3", 2))
        assertNull(identity("BV1KG411w7r2-p3", 1, "?p=1"))
        assertNull(identity("BV1KG411w7r2", 1, "?p=0"))
        assertNull(identity("BV1KG411w7r2", 1, "?p=2&p=3"))
        assertNull(restoredIdentity("bilibili", "BV1KG411w7r2", 3,
            "https://www.bilibili.com/video/BV1KG411w7r3?p=3", "audio"))
    }

    private fun identity(id: String, page: Int, query: String = "", kind: String = "audio") =
        restoredIdentity("bilibili", id, page, "https://www.bilibili.com/video/${id.substringBefore("-p")}$query", kind)
}
