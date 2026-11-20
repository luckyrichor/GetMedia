package com.getmedia.app.data.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 自动同步开关关闭时**一轮都不跑**（隐私说明 v3：关闭后不再上传与拉取）。
 *
 * [LibrarySyncRunner.run] 的第一道判断就是这个函数；它返回 false 时连
 * `LibrarySyncClient` 都不会创建，因此删除队列也只会排队、不会重放。
 */
@RunWith(AndroidJUnit4::class)
class LibrarySyncEnabledTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val account = "boss"

    @Test fun closedSwitchBlocksEveryAutomaticRound() {
        val prefs = LibrarySyncPrefs(context)
        prefs.setEnabled(account, false)
        assertFalse("关闭后不许跑——一个请求都不发", LibrarySyncRunner.mayRunAutomatically(prefs, account))

        prefs.setEnabled(account, true)
        assertTrue(LibrarySyncRunner.mayRunAutomatically(prefs, account))
        assertFalse("手动询问挂着时也不自动跑",
            LibrarySyncRunner.mayRunAutomatically(prefs, account, manualPromptOpen = true))
    }
}
