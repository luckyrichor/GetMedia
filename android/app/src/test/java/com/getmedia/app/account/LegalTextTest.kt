package com.getmedia.app.account

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LegalTextTest {
    // Gradle 单测的工作目录是 android/app
    private val docs = File("../../docs/legal")
    private val assets = File("src/main/assets/legal")

    @Test fun seedAssetsAreByteIdenticalToServerDocs() {
        for (name in listOf("user-agreement.md", "privacy-policy.md")) {
            assertArrayEquals("$name 与 docs/legal 不一致，会导致同意哈希对不上", File(docs, name).readBytes(), File(assets, name).readBytes())
        }
    }

    @Test fun parsesVersionDateAndHashLikeServer() {
        val raw = File(assets, "user-agreement.md").readBytes()
        val d = LegalText.parse(raw)
        assertTrue(d.version >= 1)
        assertTrue(d.effectiveDate.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
        assertEquals(LegalText.sha256(raw), d.sha256)
        assertEquals(64, d.sha256.length)
    }

    @Test fun headerWithFullWidthSpaceParses() {
        val d = LegalText.parse("# 标题\n\n版本：v3　生效日期：2026 年 1 月 5 日\n正文".toByteArray())
        assertEquals(3, d.version); assertEquals("2026-01-05", d.effectiveDate)
    }

    @Test fun missingHeaderFails() {
        assertThrows(IllegalStateException::class.java) { LegalText.parse("没有首部".toByteArray()) }
    }
}
