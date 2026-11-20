package com.getmedia.app.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.account.ApiException
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 429 限频**不是失败**。
 *
 * 场景：刚备份成功，用户在 10 分钟内又点"立即备份"（`force` 绕过本地窗口），服务端按每账号
 * 10 分钟 1 次回 429。正确表现是——上次成功的时间与条数一个字都不改，`dirty` 不置位，
 * 只记一个"到点之前别再说失败"的时间戳；设置页显示"刚备份过，请 10 分钟后再试"。
 *
 * 旧实现把 429 当失败（`dirty = true`），状态行会变成"上次备份失败"，用户以为备份坏了。
 */
@RunWith(AndroidJUnit4::class)
class BackupThrottleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: BackupPrefs

    private inner class RateLimitedTransport : BackupTransport {
        override suspend fun upload(body: ByteArray, appVersion: String, device: String): BackupUpload =
            throw ApiException(429, "rate_limited", "请稍后再试")

        override suspend fun list(): List<BackupItem> = emptyList()
        override suspend fun download(backupId: String): BackupPayload = BackupPayload(ByteArray(0), null)
        override suspend fun delete(backupId: String): Boolean = false
        override suspend fun deleteAll(): Int = 0
    }

    private fun start(state: BackupPrefs.State) {
        val session = SessionStore.Session(
            loginName = "u1", token = "tok", expiresAt = 0, deviceId = null, consentRequired = false,
        )
        BackupManager.resetForTest()
        // 空库现在一律不上传（`BackupEmptyLibraryTest`），这条用例要验的是 429 的处理，
        // 所以先放一首，让请求真的发出去。
        runBlocking {
            AppDatabase.get(context).trackDao().upsertAll(
                listOf(
                    TrackEntity(
                        id = -9003, platform = "bilibili", videoId = "BVthrottle", title = "限频用",
                        uploader = null, durationSec = 1.0, audioBytes = 10, audioFormat = "m4a",
                        sourceUrl = "https://www.bilibili.com/video/BVthrottle", favorite = false,
                        localPath = "/files/-9003.m4a",
                        localCategoryId = 1, addedAtMs = 1, sortIndex = 0, mediaType = "AUDIO",
                    ),
                ),
            )
        }
        prefs = BackupPrefs(context)
        prefs.write(session.loginName, state)
        BackupManager.transportOverride = RateLimitedTransport()
        BackupManager.sessionOverride = session
        BackupManager.initialize(context)
    }

    private fun awaitThrottle() {
        val deadline = System.currentTimeMillis() + 5_000
        while (BackupManager.state.value.throttleUntilMs == 0L && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            val dao = AppDatabase.get(context).trackDao()
            val ids = dao.allNow().map { it.id }
            if (ids.isNotEmpty()) dao.deleteByIds(ids)
        }
        BackupManager.resetForTest()
    }

    @Test
    fun rateLimitKeepsLastSuccessAndIsNotAFailure() {
        val lastAt = System.currentTimeMillis() - 60_000
        start(
            BackupPrefs.State(
                enabled = true, lastHash = "h1", lastAtMs = lastAt, lastTracks = 7,
                lastId = "bkp_1", dirty = false,
            )
        )

        BackupManager.backupNow()
        awaitThrottle()

        val state = BackupManager.state.value
        assertTrue("应记下解除限频的时间点", state.throttleUntilMs > System.currentTimeMillis())
        assertEquals("上次成功时间不能被改写", lastAt, state.lastAtMs)
        assertEquals("上次备份条数不能被改写", 7, state.lastTracks)
        assertEquals("备份 id 不能被清", "bkp_1", state.lastId)
        assertFalse("429 不是失败，不能标脏", state.dirty)

        // 跨进程也要记得（否则下次进设置页又变成"失败"）
        val stored = BackupPrefs(context).read("u1")
        assertTrue(stored.throttleUntilMs > 0)
        assertEquals(lastAt, stored.lastAtMs)
        assertFalse(stored.dirty)
    }

    @Test
    fun expiredThrottleIsClearedOnNextPublish() {
        start(
            BackupPrefs.State(
                enabled = true, lastHash = "h1", lastAtMs = System.currentTimeMillis() - 900_000,
                lastTracks = 7, throttleUntilMs = System.currentTimeMillis() - 1_000,
            )
        )

        // 任何一次状态刷新都会走 publish()，过期的提示应当自己消失
        BackupManager.setEnabled(true)

        assertEquals("过期的限频提示应自动清掉", 0L, BackupManager.state.value.throttleUntilMs)
        assertEquals(0L, BackupPrefs(context).read("u1").throttleUntilMs)
    }
}
