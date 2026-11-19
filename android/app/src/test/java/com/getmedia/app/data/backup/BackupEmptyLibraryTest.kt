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
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * 本地库为空（0 首）时不上传。
 *
 * 空清单是合法快照，但没有恢复价值，却会占掉云端 3 份里的一份；够 3 份之后
 * 每传一份就把最旧的一份挤掉。新设备 / 重装后先登录，正好是空库，
 * 所以自动触发点（进前台、退后台、写操作防抖、前台周期）都必须一个请求都不发，
 * 而且**不能写 lastHash**——写了会把"已备份"记成空清单，之后真有内容时反而可能被判成没变。
 *
 * 「立即备份」是用户明确要求，走确认后放行（见 [BackupManager.confirmEmptyBackup]）。
 */
@RunWith(AndroidJUnit4::class)
class BackupEmptyLibraryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val uploads = AtomicInteger()
    private val listed = AtomicInteger()

    private inner class CountingTransport : BackupTransport {
        override suspend fun upload(body: ByteArray, appVersion: String, device: String): BackupUpload {
            uploads.incrementAndGet()
            return BackupUpload("bkp_empty", emptyList())
        }

        override suspend fun list(): List<BackupItem> {
            listed.incrementAndGet()
            return emptyList()
        }

        override suspend fun download(backupId: String): BackupPayload = BackupPayload(ByteArray(0), null)
        override suspend fun deleteAll(): Int = 0
    }

    private fun start(state: BackupPrefs.State) {
        val session = SessionStore.Session(
            loginName = "u1", token = "tok", expiresAt = 0, deviceId = null, consentRequired = false,
        )
        BackupManager.resetForTest()
        BackupPrefs(context).write(session.loginName, state)
        BackupManager.transportOverride = CountingTransport()
        BackupManager.sessionOverride = session
        BackupManager.initialize(context)
    }

    private fun awaitUploads(timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (uploads.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private fun seedTrack() = runBlocking {
        AppDatabase.get(context).trackDao().upsertAll(
            listOf(
                TrackEntity(
                    id = -9001, platform = "bilibili", videoId = "BVempty", title = "有内容的一首",
                    uploader = null, durationSec = 1.0, audioBytes = 10, audioFormat = "m4a",
                    sourceUrl = "https://www.bilibili.com/video/BVempty", favorite = false,
                    localCategoryId = 1, addedAtMs = 1, sortIndex = 0, mediaType = "AUDIO",
                ),
            ),
        )
    }

    private fun clearLibrary() = runBlocking {
        val dao = AppDatabase.get(context).trackDao()
        val ids = dao.allNow().map { it.id }
        if (ids.isNotEmpty()) dao.deleteByIds(ids)
    }

    @After
    fun tearDown() {
        clearLibrary()
        BackupManager.resetForTest()
    }

    @Test
    fun emptyLibraryNeverUploadsOnAutomaticTriggers() {
        clearLibrary()
        start(BackupPrefs.State(enabled = true))

        // 退到后台是立即执行的那条触发点；另外三条要等 5 秒 / 30 秒 / 10 分钟，
        // 但都走同一个 runCheck，这里用最短的一条把结论钉住。
        BackupManager.onBackground()
        BackupManager.backupNow()   // 未确认的空库手动备份也先不发
        Thread.sleep(600)

        assertEquals("空库不应发出上传", 0, uploads.get())
        assertEquals("不应顺带去拉云端列表", 0, listed.get())
        assertNull("空库上传被拦后不能写 lastHash", BackupPrefs(context).read("u1").lastHash)
    }

    /** 反向对照：库里有内容时同一条触发点会真的发出去，说明上面不是因为假实现没接上。 */
    @Test
    fun nonEmptyLibraryStillUploads() {
        seedTrack()
        start(BackupPrefs.State(enabled = true, lastAtMs = 0))

        BackupManager.onBackground()
        awaitUploads()

        assertEquals("有内容时应当上传", 1, uploads.get())
        assertEquals(1, BackupPrefs(context).read("u1").lastTracks)
    }
}
