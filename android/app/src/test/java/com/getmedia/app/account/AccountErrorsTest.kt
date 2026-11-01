package com.getmedia.app.account

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AccountErrorsTest {
    @Test fun apiMessageIsShownAsIs() {
        assertEquals("登录名或密码不正确", AccountErrors.userMessage(ApiException(401, "invalid_credentials", "登录名或密码不正确")))
    }

    @Test fun rateLimitAppendsWait() {
        val m = AccountErrors.userMessage(ApiException(429, "rate_limited", "操作太频繁", retryAfterSec = 90))
        assertTrue(m, m.contains("2 分钟"))
    }

    @Test fun absurdRetryAfterIgnored() {
        assertEquals("操作太频繁", AccountErrors.userMessage(ApiException(429, "rate_limited", "操作太频繁", retryAfterSec = 10_000_000)))
    }

    @Test fun ioAndUnknownDoNotLeakDetails() {
        assertFalse(AccountErrors.userMessage(IOException("https://x/secret?token=abc")).contains("secret"))
        assertFalse(AccountErrors.userMessage(IllegalStateException("password=hunter2")).contains("hunter2"))
    }

    @Test fun deviceLimitNeedsDevices() {
        val d = DeviceInfo("d1", "android", "Pixel", "0.1.0", 1, 2, false)
        assertEquals(listOf(d), AccountErrors.deviceLimit(ApiException(409, "device_limit", "x", devices = listOf(d))))
        assertNull(AccountErrors.deviceLimit(ApiException(409, "device_limit", "x")))
        assertNull(AccountErrors.deviceLimit(ApiException(401, "other", "x", devices = listOf(d))))
    }
}
