package com.getmedia.app.account

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AccountJsonTest {
    @Test fun errorKeepsServerCodeAndDetail() {
        val e = AccountJson.error(409, """{"detail":"协议已更新，请阅读最新版本后重新同意","code":"legal_outdated"}""", null)
        assertEquals(409, e.status); assertEquals("legal_outdated", e.code)
        assertEquals("协议已更新，请阅读最新版本后重新同意", e.message)
    }

    @Test fun deviceLimitCarriesDeviceList() {
        val body = """{"detail":"已登录的设备数量已达上限","code":"device_limit","devices":[
            {"id":"dev_1","channel":"android","device":"Pixel","app_version":"0.1.0","created_at":1,"last_used_at":2,"current":false}]}"""
        val e = AccountJson.error(409, body, null)
        assertEquals("device_limit", e.code)
        assertEquals(1, e.devices.size)
        assertEquals("dev_1", e.devices[0].id)
        assertEquals("Pixel", e.devices[0].device)
    }

    @Test fun rateLimitReadsRetryAfter() {
        assertEquals(30, AccountJson.error(429, """{"detail":"太频繁","code":"rate_limited"}""", "30").retryAfterSec)
        assertNull(AccountJson.error(429, "{}", "abc").retryAfterSec)
    }

    @Test fun nonJsonBodyNeverEchoed() {
        val e = AccountJson.error(502, "<html>secret-internal-details</html>", null)
        assertEquals("http_502", e.code)
        assertFalse(e.message.contains("secret"))
        assertEquals(AccountJson.defaultMessage(502), e.message)
    }

    @Test fun registerResultCarriesRecoveryCodeOnce() {
        val r = AccountJson.auth(JSONObject("""{"session":{"token":"t","expires_at":9,"device_id":"dev_9"},
            "account":{"id":"acct_1","login_name":"abc","tier":"free"},"recovery_code":"ABCD2345EFGH6789"}"""))
        assertEquals("t", r.session.token); assertEquals("dev_9", r.session.deviceId)
        assertEquals("ABCD2345EFGH6789", r.recoveryCode)
        assertFalse(r.recoveryConfirmed)
    }

    @Test fun loginResultHasConsentAndRecoveryFlags() {
        val r = AccountJson.auth(JSONObject("""{"session":{"token":"t","expires_at":9},
            "account":{"id":"a","login_name":"abc","tier":"free"},"consent_required":true,"recovery_confirmed":true}"""))
        assertTrue(r.consentRequired); assertTrue(r.recoveryConfirmed); assertNull(r.recoveryCode); assertNull(r.session.deviceId)
    }

    @Test fun tierExpiryParsedFromLoginAndMe() {
        val login = AccountJson.auth(JSONObject("""{"session":{"token":"t","expires_at":9},
            "account":{"id":"a","login_name":"abc","tier":"full","tier_expires_at":1800000000}}"""))
        assertEquals("full", login.account.tier)
        assertEquals(1800000000L, login.account.tierExpiresAt)

        val me = AccountJson.me(JSONObject("""{"id":"a","login_name":"abc","tier":"full",
            "consent_required":false,"recovery_confirmed":true,"status":"active","tier_expires_at":1800000000}"""))
        assertEquals(1800000000L, me.tierExpiresAt)

        // 免费版与"永久"都是 null；老服务端没有这个字段也是 null；0 不当成"已过期"
        assertNull(AccountJson.me(JSONObject("""{"id":"a","login_name":"abc","tier":"free","tier_expires_at":null}""")).tierExpiresAt)
        assertNull(AccountJson.me(JSONObject("""{"id":"a","login_name":"abc","tier":"admin"}""")).tierExpiresAt)
        assertNull(AccountJson.me(JSONObject("""{"id":"a","login_name":"abc","tier":"full","tier_expires_at":0}""")).tierExpiresAt)
    }

    @Test fun redeemResultCarriesTierAndExpiry() {
        val r = AccountJson.redeem(JSONObject("""{"tier":"full","tier_expires_at":1800000000}"""))
        assertEquals("full", r.tier)
        assertEquals(1800000000L, r.tierExpiresAt)
        assertNull(AccountJson.redeem(JSONObject("""{"tier":"full","tier_expires_at":null}""")).tierExpiresAt)
    }

    @Test fun configHandlesNullMaintenanceAndMissingLatest() {
        val c = AccountJson.config(JSONObject("""{"registration_open":false,"invite_required":true,"maintenance":null,"min_version":"0.1.0"}"""))
        assertFalse(c.registrationOpen); assertTrue(c.inviteRequired); assertNull(c.maintenance); assertNull(c.latest)
        val d = AccountJson.config(JSONObject("""{"registration_open":true,"invite_required":false,"maintenance":"维护中","min_version":"0.1.0",
            "latest":{"version":"0.2.0","notes":"n","apk":"https://example.invalid/a.apk","sha256":"x"}}"""))
        assertEquals("维护中", d.maintenance); assertEquals("0.2.0", d.latest?.version)
    }

    @Test fun configParsesFreeBuildLink() {
        // 免费版与完整版包名不同：latest.free 是免费版自己的安装包
        val c = AccountJson.config(JSONObject("""{"registration_open":true,"invite_required":false,"min_version":"0.1.0",
            "latest":{"version":"0.1.28","notes":"n","apk":"https://example.invalid/full.apk","sha256":"f",
            "free":{"apk":"https://example.invalid/free.apk","sha256":"g"}}}"""))
        assertEquals("https://example.invalid/free.apk", c.latest?.freeApk)
        assertEquals("g", c.latest?.freeSha256)
        // 老服务端没有 free 时留空，绝不把完整版链接当成免费版的
        val d = AccountJson.config(JSONObject("""{"registration_open":true,"invite_required":false,"min_version":"0.1.0",
            "latest":{"version":"0.1.28","notes":"n","apk":"https://example.invalid/full.apk","sha256":"f"}}"""))
        assertEquals("", d.latest?.freeApk); assertEquals("", d.latest?.freeSha256)
    }

    @Test fun legalBundleParses() {
        val doc = """{"version":2,"effective_date":"2026-10-30","sha256":"ab","text":"t"}"""
        val b = AccountJson.legal(JSONObject("""{"documents":{"user_agreement":$doc,"privacy_policy":$doc}}"""))
        assertEquals(2, b.userAgreement.version); assertEquals("ab", b.privacyPolicy.sha256)
    }
}
