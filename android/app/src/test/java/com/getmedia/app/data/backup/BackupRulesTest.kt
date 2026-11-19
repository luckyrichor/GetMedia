package com.getmedia.app.data.backup

import com.getmedia.app.account.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class BackupRulesTest {

    private fun state(
        enabled: Boolean = true,
        lastHash: String? = "h1",
        lastAtMs: Long = 0,
        blocked: String? = null,
    ) = BackupPrefs.State(enabled = enabled, lastHash = lastHash, lastAtMs = lastAtMs, blocked = blocked)

    private val now = 1_800_000_000_000L

    @Test
    fun uploadsWhenHashChangedAndWindowPassed() {
        assertTrue(BackupRules.shouldUpload(state(lastHash = "old"), "new", now))
    }

    @Test
    fun skipsWhenNothingChanged() {
        assertFalse(BackupRules.shouldUpload(state(lastHash = "same"), "same", now))
    }

    @Test
    fun skipsInsideTenMinuteWindow() {
        val s = state(lastHash = "old", lastAtMs = now - BackupRules.WINDOW_MS + 1)
        assertFalse(BackupRules.shouldUpload(s, "new", now))
        assertTrue(BackupRules.shouldUpload(s, "new", s.lastAtMs + BackupRules.WINDOW_MS))
    }

    @Test
    fun skipsWhenDisabledOrBlocked() {
        assertFalse("关闭后不传", BackupRules.shouldUpload(state(enabled = false, lastHash = "old"), "new", now))
        assertFalse(
            "被服务端停用后不传",
            BackupRules.shouldUpload(state(lastHash = "old", blocked = BackupPrefs.BLOCKED_TOO_LARGE), "new", now),
        )
    }

    @Test
    fun forceBypassesWindowAndSameHash() {
        val justUploaded = state(lastHash = "same", lastAtMs = now)
        assertTrue("立即备份不受窗口限制", BackupRules.shouldUpload(justUploaded, "same", now, force = true))
        assertFalse("但停用状态仍然拦住", BackupRules.shouldUpload(
            state(enabled = false, lastHash = "same", lastAtMs = now), "same", now, force = true,
        ))
    }

    @Test
    fun tooLargeAndInvalidBecomeBlockingStates() {
        assertEquals(BackupOutcome.TooLarge, BackupOutcome.of(ApiException(413, "backup_too_large", "备份内容过大")))
        val invalid = BackupOutcome.of(ApiException(422, "backup_invalid", "title 超过 300 个字符"))
        assertEquals(BackupOutcome.Invalid("title 超过 300 个字符"), invalid)
    }

    @Test
    fun missingEndpointIsUnsupportedNotAnError() {
        assertEquals(BackupOutcome.Unsupported, BackupOutcome.of(ApiException(404, "http_404", "未找到")))
    }

    @Test
    fun authAndRateLimitAreTheirOwnBranches() {
        assertEquals(BackupOutcome.Unauthorized, BackupOutcome.of(ApiException(401, "unauthenticated", "登录已失效")))
        assertEquals(BackupOutcome.RateLimited, BackupOutcome.of(ApiException(429, "rate_limited", "太频繁")))
    }

    @Test
    fun networkAndUnknownErrorsStaySilent() {
        assertTrue(BackupOutcome.of(SocketTimeoutException("timeout")) is BackupOutcome.Failed)
        assertTrue(BackupOutcome.of(IOException("离线")) is BackupOutcome.Failed)
        assertTrue(BackupOutcome.of(ApiException(500, "internal", "服务暂时不可用")) is BackupOutcome.Failed)
    }
}
