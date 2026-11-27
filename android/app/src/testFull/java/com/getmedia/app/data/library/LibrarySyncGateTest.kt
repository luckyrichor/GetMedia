package com.getmedia.app.data.library

import com.getmedia.app.account.SessionStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步闸门的档位与**到期**判定。
 *
 * 到期这半条是本批新增的：服务端每小时才把过期的 `full` 刷成 `free`（`sweep_expired_tiers`），
 * 本机不能等它——否则到期后最多一小时里还在继续同步。所以 `tierExpiresAt` 一到，本地立刻关。
 */
class LibrarySyncGateTest {

    private fun session(
        tier: String?,
        expiresAt: Long? = null,
        consentRequired: Boolean = false,
        privacy: Int? = LibrarySyncGate.MIN_PRIVACY_FOR_SYNC,
    ) = SessionStore.Session(
        loginName = "alice", token = "tok", expiresAt = 0, deviceId = null,
        consentRequired = consentRequired, recoveryConfirmed = true,
        tier = tier, tierExpiresAt = expiresAt, consentedPrivacyVersion = privacy,
    )

    private val now = 1_800_000_000L

    @Test
    fun `免费版与未知档位都不许同步`() {
        assertFalse(LibrarySyncGate.isOwner(session("free"), now))
        assertFalse(LibrarySyncGate.isOwner(session(null), now))
        assertFalse(LibrarySyncGate.isOwner(null, now))
        // 老版本可能把档位写成大写，判定要不区分大小写
        assertFalse(LibrarySyncGate.isOwner(session("FREE"), now))
    }

    @Test
    fun `完整版与管理员都算`() {
        assertTrue(LibrarySyncGate.isOwner(session("full"), now))
        assertTrue(LibrarySyncGate.isOwner(session("FULL"), now))
        assertTrue(LibrarySyncGate.isOwner(session("admin"), now))
    }

    @Test
    fun `没有到期时间就是永久`() {
        assertTrue(LibrarySyncGate.isOwner(session("full", expiresAt = null), now))
        assertTrue(LibrarySyncGate.maySync(session("full", expiresAt = null), now))
    }

    @Test
    fun `到期前一秒还能同步，到期当天立刻不许`() {
        assertTrue(LibrarySyncGate.isOwner(session("full", expiresAt = now + 1), now))
        assertFalse(LibrarySyncGate.isOwner(session("full", expiresAt = now), now))
        assertFalse(LibrarySyncGate.isOwner(session("full", expiresAt = now - 1), now))
        assertFalse(LibrarySyncGate.maySync(session("full", expiresAt = now - 1), now))
    }

    @Test
    fun `到期判定只对有时效的档位成立`() {
        assertFalse(LibrarySyncGate.expired(session("free"), now))
        assertFalse(LibrarySyncGate.expired(session("admin"), now))
        assertFalse(LibrarySyncGate.expired(session("full", expiresAt = null), now))
        assertFalse(LibrarySyncGate.expired(session("full", expiresAt = now + 60), now))
        assertTrue(LibrarySyncGate.expired(session("full", expiresAt = now - 60), now))
    }

    @Test
    fun `隐私闸门仍然优先于档位`() {
        assertFalse(LibrarySyncGate.maySync(session("full", consentRequired = true), now))
        assertFalse(LibrarySyncGate.maySync(session("full", privacy = null), now))
        assertFalse(LibrarySyncGate.maySync(session("full", privacy = LibrarySyncGate.MIN_PRIVACY_FOR_SYNC - 1), now))
        assertTrue(LibrarySyncGate.maySync(session("full"), now))
    }
}
