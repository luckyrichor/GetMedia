package com.getmedia.app.update

import com.getmedia.app.account.LatestRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateTargetTest {

    private fun release(freeApk: String = "", freeSha: String = "") = LatestRelease(
        version = "0.1.28", notes = "说明",
        apk = "https://example.invalid/full.apk", sha256 = "f".repeat(64),
        freeApk = freeApk, freeSha256 = freeSha,
    )

    @Test
    fun fullEditionUsesMainLink() {
        val link = UpdateTarget.pick(release(freeApk = "https://example.invalid/free.apk", freeSha = "a".repeat(64)), false)
        assertEquals("https://example.invalid/full.apk", link?.apk)
        assertEquals("f".repeat(64), link?.sha256)
    }

    @Test
    fun freeEditionUsesFreeLink() {
        val link = UpdateTarget.pick(release(freeApk = "https://example.invalid/free.apk", freeSha = "a".repeat(64)), true)
        assertEquals("https://example.invalid/free.apk", link?.apk)
        assertEquals("a".repeat(64), link?.sha256)
    }

    @Test
    fun freeEditionNeverFallsBackToFullBuild() {
        // 0.1.27 的 bug：服务端只有完整版链接时，免费版照样下了完整版安装包。
        assertNull(UpdateTarget.pick(release(), true))
    }

    @Test
    fun rejectsNonHttpsAndBlankLinks() {
        assertNull(UpdateTarget.pick(release(freeApk = "http://example.invalid/free.apk"), true))
        assertNull(UpdateTarget.pick(release(freeApk = ""), true))
        assertNull(UpdateTarget.pick(LatestRelease("0.1.28", "", "", "f".repeat(64)), false))
    }
}
