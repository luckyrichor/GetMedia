package com.getmedia.app.data.library

import com.getmedia.app.account.SessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段 3 硬性验收 ⑤：**两道闸在客户端本地判，闸门关了就是零请求**。
 *
 * 用计数器代替真网络与真库：`pull`/`upload` 的调用次数就是"发了几个请求"。
 * 这类断言只有把闸门放在调度最前面才过得去——一旦有人把 `pull` 提到闸门之前，
 * 这里立刻从 0 变成 1。
 */
class LibrarySyncRoundTest {

    private class Fixture(
        val candidates: Int = 0,
        val fail: LibrarySyncException? = null,
    ) {
        var pulls = 0
        var uploads = 0
        val round = LibrarySyncRound(
            pull = { pulls++; fail?.let { throw it } },
            candidateCount = { candidates },
            upload = { uploads++ },
        )
    }

    private fun session(
        tier: String? = "owner",
        consentRequired: Boolean = false,
        name: String = "boss",
    ) = SessionStore.Session(
        loginName = name, token = "tok", expiresAt = 0, deviceId = null,
        consentRequired = consentRequired, recoveryConfirmed = true, tier = tier,
    )

    @Test fun noSessionSendsNothing() = runBlocking {
        val f = Fixture(candidates = 3)
        assertEquals(LibrarySyncRound.Outcome.Blocked, f.round.run(null, SyncUploadDecision.ACCEPTED))
        assertEquals(0, f.pulls)
        assertEquals(0, f.uploads)
    }

    /** 没同意最新隐私说明：元数据上传没有对应文本，一个请求都不能发（不靠服务端 403 兜底）。 */
    @Test fun consentPendingSendsNothing() = runBlocking {
        val f = Fixture(candidates = 5)
        val out = f.round.run(session(consentRequired = true), SyncUploadDecision.ACCEPTED)
        assertEquals(LibrarySyncRound.Outcome.Blocked, out)
        assertEquals(0, f.pulls)
        assertEquals(0, f.uploads)
    }

    /** 免费档：完整版里连入口都不显示，后台更不该偷偷发。 */
    @Test fun freeTierSendsNothing() = runBlocking {
        val f = Fixture(candidates = 5)
        assertEquals(LibrarySyncRound.Outcome.Blocked, f.round.run(session(tier = "free"), SyncUploadDecision.ACCEPTED))
        assertEquals(0, f.pulls)
        assertEquals(0, f.uploads)
    }

    /** 档位还不知道（旧会话升级上来、或还没成功对过 `me()`）：宁可晚一轮，也别先传上去。 */
    @Test fun unknownTierSendsNothing() = runBlocking {
        val f = Fixture(candidates = 5)
        assertEquals(LibrarySyncRound.Outcome.Blocked, f.round.run(session(tier = null), SyncUploadDecision.NONE))
        assertEquals(0, f.pulls)
        assertEquals(0, f.uploads)
    }

    @Test fun ownerWithConsentPullsFirst() = runBlocking {
        val f = Fixture(candidates = 0)
        assertEquals(LibrarySyncRound.Outcome.Idle, f.round.run(session(), SyncUploadDecision.NONE))
        assertEquals(1, f.pulls)
        assertEquals(0, f.uploads)
    }

    /** 有本机独有条目且没决策过：只问，不传。 */
    @Test fun asksOnceInsteadOfUploading() = runBlocking {
        val f = Fixture(candidates = 7)
        assertEquals(LibrarySyncRound.Outcome.Ask(7), f.round.run(session(), SyncUploadDecision.NONE))
        assertEquals(1, f.pulls)
        assertEquals(0, f.uploads)
    }

    @Test fun acceptedUploadsAfterPull() = runBlocking {
        val f = Fixture(candidates = 2)
        assertEquals(LibrarySyncRound.Outcome.Uploaded, f.round.run(session(), SyncUploadDecision.ACCEPTED))
        assertEquals(1, f.pulls)
        assertEquals(1, f.uploads)
    }

    /** 拒绝过：以后不问也不传，但拉取照旧（拉下来的新条目还是要出现在列表上）。 */
    @Test fun declinedStillPullsButNeverUploads() = runBlocking {
        val f = Fixture(candidates = 4)
        assertEquals(LibrarySyncRound.Outcome.Declined, f.round.run(session(), SyncUploadDecision.DECLINED))
        assertEquals(1, f.pulls)
        assertEquals(0, f.uploads)
    }

    /** 拉取失败就不推：推的是"拉完之后"才成立的本机独有集合。 */
    @Test fun pullFailureStopsBeforeUpload() = runBlocking {
        val f = Fixture(
            candidates = 9,
            fail = LibrarySyncException(LibrarySyncProblem.NETWORK, "网络不可用"),
        )
        val out = f.round.run(session(), SyncUploadDecision.ACCEPTED)
        assertTrue(out is LibrarySyncRound.Outcome.PullFailed)
        assertEquals("网络不可用", (out as LibrarySyncRound.Outcome.PullFailed).message)
        assertEquals(1, f.pulls)
        assertEquals(0, f.uploads)
    }

    /** 闸门函数本身：tier 大小写不敏感，空档位与 `free` 一样不许同步。 */
    @Test fun gateAcceptsOwnerInAnyCase() {
        assertTrue(LibrarySyncGate.maySync(session(tier = "OWNER")))
        assertFalse(LibrarySyncGate.maySync(session(tier = "free")))
        assertFalse(LibrarySyncGate.maySync(session(tier = "")))
    }
}
