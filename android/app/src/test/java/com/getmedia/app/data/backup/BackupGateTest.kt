package com.getmedia.app.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.account.SessionStore
import com.getmedia.app.data.local.AppDatabase
import com.getmedia.app.data.local.TrackEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * 「关闭」与「待重新同意」这两种状态下，五个触发点一个请求都不发。
 *
 * 用记账的假实现顶替 [BackupApi]：断言的是"没有任何网络动作"，
 * 而不是"状态字段看起来对"。五个触发点里前台周期那条要先过 10 分钟，
 * 但它和另外四条走的是同一个 `runCheck`，而且 `onForeground` 被拦时连 ticker 都不会启动。
 */
@RunWith(AndroidJUnit4::class)
class BackupGateTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val calls = AtomicInteger()
    private lateinit var prefs: BackupPrefs

    private inner class CountingTransport : BackupTransport {
        override suspend fun upload(body: ByteArray, appVersion: String, device: String): BackupUpload {
            calls.incrementAndGet()
            return BackupUpload("bkp_test", emptyList())
        }

        override suspend fun list(): List<BackupItem> {
            calls.incrementAndGet()
            return emptyList()
        }

        override suspend fun download(backupId: String): BackupPayload {
            calls.incrementAndGet()
            return BackupPayload(ByteArray(0), null)
        }

        override suspend fun delete(backupId: String): Boolean {
            calls.incrementAndGet()
            return true
        }

        override suspend fun deleteAll(): Int {
            calls.incrementAndGet()
            return 0
        }
    }

    private fun start(state: BackupPrefs.State, consentRequired: Boolean = false) {
        val session = SessionStore.Session(
            loginName = "u1", token = "tok", expiresAt = 0, deviceId = null,
            consentRequired = consentRequired,
        )
        BackupManager.resetForTest()
        prefs = BackupPrefs(context)
        prefs.write(session.loginName, state)
        BackupManager.transportOverride = CountingTransport()
        BackupManager.sessionOverride = session
        BackupManager.initialize(context)
    }

    /** 四条不需要等时间的触发点；周期那条由 [BackupManager.onForeground] 起，被拦时压根不会启动。 */
    private fun fireTriggers() {
        BackupManager.onForeground()
        BackupManager.onBackground()
        BackupManager.noteWrite()
        BackupManager.backupNow()
    }

    @Before
    fun setUp() {
        calls.set(0)
    }

    @After
    fun tearDown() {
        // 空库不再上传（见 BackupEmptyLibraryTest），所以反向对照要先往库里放一首。
        runBlocking {
            val dao = AppDatabase.get(context).trackDao()
            val ids = dao.allNow().map { it.id }
            if (ids.isNotEmpty()) dao.deleteByIds(ids)
        }
        BackupManager.resetForTest()
    }

    @Test
    fun disabledSendsNothing() {
        start(BackupPrefs.State(enabled = false))
        fireTriggers()
        Thread.sleep(300)
        assertEquals("开关关闭时不应有任何网络请求", 0, calls.get())
    }

    @Test
    fun consentRequiredSendsNothing() {
        start(BackupPrefs.State(enabled = true), consentRequired = true)
        fireTriggers()
        Thread.sleep(300)
        assertEquals("待重新同意时不应有任何网络请求", 0, calls.get())
    }

    @Test
    fun blockedSendsNothing() {
        start(BackupPrefs.State(enabled = true, blocked = BackupPrefs.BLOCKED_TOO_LARGE))
        fireTriggers()
        Thread.sleep(300)
        assertEquals("被服务端停用后不应有任何网络请求", 0, calls.get())
    }

    /** 反向对照：门是开的就真会发，说明上面三条不是因为假实现没接上才"看起来没请求"。 */
    @Test
    fun enabledStillUploads() {
        // 空库现在一律不上传（空清单会挤掉云端有内容的备份），所以先放一首进去，
        // 这条用例要证的是"门开着"，不是"空库也能传"。
        runBlocking {
            AppDatabase.get(context).trackDao().upsertAll(
                listOf(
                    TrackEntity(
                        id = -9002, platform = "bilibili", videoId = "BVgate", title = "门开着的证据",
                        uploader = null, durationSec = 1.0, audioBytes = 10, audioFormat = "m4a",
                        sourceUrl = "https://www.bilibili.com/video/BVgate", favorite = false,
                        localPath = "/files/-9002.m4a",
                        localCategoryId = 1, addedAtMs = 1, sortIndex = 0, mediaType = "AUDIO",
                    ),
                ),
            )
        }
        start(BackupPrefs.State(enabled = true, lastHash = "old", lastAtMs = 0))
        BackupManager.backupNow()
        val deadline = System.currentTimeMillis() + 5_000
        while (calls.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertEquals("开启状态下应立即备份一次", 1, calls.get())
    }
}
