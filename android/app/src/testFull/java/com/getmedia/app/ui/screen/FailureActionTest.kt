package com.getmedia.app.ui.screen

import com.getmedia.app.data.serverextract.ServerProblem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 失败卡片给哪个出路，是这个映射说了算。它决定用户下一步做什么，所以要钉住。
 */
class FailureActionTest {

    @Test
    fun missingTokenOnlyOffersLogin() {
        assertEquals(FailureAction.LOGIN, failureAction(ServerProblem.LOGIN_REQUIRED))
    }

    /**
     * 裸 404 分不出"没登录"和"这个号没权限"：只给"去登录"的话，
     * 换一个本来就没权限的账号登进来还会卡在同一张卡片上，所以两条路都要给。
     */
    @Test
    fun notOwnerOffersBothLoginAndLocal() {
        assertEquals(FailureAction.LOGIN_OR_LOCAL, failureAction(ServerProblem.NOT_OWNER))
    }

    @Test
    fun serverSideProblemsLeadToLocalExtraction() {
        listOf(
            ServerProblem.UNAVAILABLE,
            ServerProblem.STORAGE_FULL,
            ServerProblem.SERVER,
            ServerProblem.TIMEOUT,
            ServerProblem.JOB_GONE,
        ).forEach { problem ->
            assertEquals("$problem 应改用本机提取", FailureAction.LOCAL, failureAction(problem))
        }
    }

    @Test
    fun transientAndInputProblemsOnlyRetry() {
        listOf(
            ServerProblem.NETWORK,
            ServerProblem.RATE_LIMITED,
            ServerProblem.INPUT,
            null,
        ).forEach { problem ->
            assertEquals("$problem 应只给重试", FailureAction.RETRY, failureAction(problem))
        }
    }
}
