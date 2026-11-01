package com.getmedia.app.account

import org.junit.Assert.*
import org.junit.Test

class AccountRulesTest {
    @Test fun acceptsNormalNamesAndNormalizesCase() {
        assertNull(AccountRules.checkLoginName("Luo_Chen-1"))
        assertNull(AccountRules.checkLoginName("  abc  "))
    }

    @Test fun fullWidthLettersAreNormalizedLikeServer() {
        assertNull(AccountRules.checkLoginName("ＡＢＣ１２３"))
    }

    @Test fun rejectsBadNames() {
        assertNotNull(AccountRules.checkLoginName("ab"))
        assertNotNull(AccountRules.checkLoginName("a".repeat(33)))
        assertNotNull(AccountRules.checkLoginName("张三丰"))
        assertNotNull(AccountRules.checkLoginName("a b c"))
        assertNotNull(AccountRules.checkLoginName("13800138000"))
        assertNull(AccountRules.checkLoginName("123456"))   // 少于 7 位的纯数字允许
    }

    @Test fun passwordRules() {
        assertNotNull(AccountRules.checkPassword("short", "abc"))
        assertNotNull(AccountRules.checkPassword("x".repeat(129), "abc"))
        assertNotNull(AccountRules.checkPassword("SameName", "samename"))
        assertNull(AccountRules.checkPassword("long-enough-1", "abc"))
    }

    @Test fun recoveryCodeGroupingRoundTrips() {
        val raw = "ABCD2345EFGH6789"
        assertEquals("ABCD 2345 EFGH 6789", AccountRules.groupRecoveryCode(raw))
        assertEquals(raw, AccountRules.cleanRecoveryCode("abcd-2345 efgh 6789"))
    }
}
