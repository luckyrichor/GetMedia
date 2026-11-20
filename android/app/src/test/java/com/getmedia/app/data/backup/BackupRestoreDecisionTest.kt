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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * 登录后的"要不要恢复云端备份"决策闸。
 *
 * 要拦住的事：重装或换机后先登录，本机寥寥几首（甚至 0 首），自动备份按指纹一变就传；
 * 服务端每账号只留 3 份，几轮下来云端有内容的备份全被顶掉。所以这个账号**第一次**登录时
 * 先问一次，用户选了才继续——不是"比条数"，是一次性决策。
 *
 * 六条场景：云端有内容 / 本机少量 / 云端全空 / 查询失败 / 三个选项 / 换账号。
 */
@RunWith(AndroidJUnit4::class)
class BackupRestoreDecisionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private inner class FakeTransport(
        @Volatile var cloud: List<BackupItem> = emptyList(),
        @Volatile var listError: Throwable? = null,
        private val payload: BackupPayload? = null,
    ) : BackupTransport {
        val uploads = AtomicInteger()
        val lists = AtomicInteger()

        override suspend fun upload(body: ByteArray, appVersion: String, device: String): BackupUpload {
            uploads.incrementAndGet()
            return BackupUpload("bkp_up", emptyList())
        }

        override suspend fun list(): List<BackupItem> {
            lists.incrementAndGet()
            listError?.let { throw it }
            return cloud
        }

        override suspend fun download(backupId: String): BackupPayload =
            payload ?: BackupPayload(ByteArray(0), null)

        override suspend fun delete(backupId: String): Boolean = true
        override suspend fun deleteAll(): Int = 0
    }

    private fun session(login: String) = SessionStore.Session(
        loginName = login, token = "tok", expiresAt = 0, deviceId = null, consentRequired = false,
    )

    private fun start(state: BackupPrefs.State, transport: FakeTransport, login: String = "u1") {
        BackupManager.resetForTest()
        BackupPrefs(context).write(login, state)
        BackupManager.transportOverride = transport
        BackupManager.sessionOverride = session(login)
        BackupManager.initialize(context)
    }

    private fun nowSec() = System.currentTimeMillis() / 1000

    private fun cloudItem(id: String = "bkp_cloud", tracks: Int = 5) =
        BackupItem(id, 40_000, tracks, nowSec() - 3_600, "Pixel 8")

    private fun await(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) Thread.sleep(20)
    }

    private fun syncTrack(videoId: String) = TrackEntity(
        id = -9100, platform = "bilibili", videoId = videoId, title = "本机这一首",
        uploader = null, durationSec = 1.0, audioBytes = 10, audioFormat = "m4a",
        sourceUrl = "https://www.bilibili.com/video/$videoId", favorite = true,
        localPath = "/files/-9100.m4a",
        localCategoryId = 1, addedAtMs = 1, sortIndex = 0, mediaType = "AUDIO",
    )

    private fun seedTrack(videoId: String = "BVdecision") = runBlocking {
        AppDatabase.get(context).trackDao().upsertAll(listOf(syncTrack(videoId)))
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

    // ---------- ① 新设备：本机 0 首，云端有内容 ----------

    @Test
    fun newDeviceWithCloudBackupAsksBeforeUploading() {
        clearLibrary()
        val transport = FakeTransport(cloud = listOf(cloudItem(tracks = 5)))
        start(BackupPrefs.State(enabled = true), transport)

        BackupManager.onBackground()
        await { BackupManager.state.value.restoreDecisionPending }
        Thread.sleep(300)

        val state = BackupManager.state.value
        assertTrue("云端有内容备份时应当弹决策", state.restoreAskVisible)
        assertTrue(state.restoreDecisionPending)
        assertEquals("弹窗上要显示云端那份的条数", 5, state.restoreCloudTracks)
        assertEquals("用户没选之前一个字节都不能传", 0, transport.uploads.get())
        assertEquals(
            "没做决策就不能落决策",
            BackupPrefs.RESTORE_UNKNOWN,
            BackupPrefs(context).read("u1").restoreDecision,
        )
    }

    // ---------- ② 本机已有 1 首，云端有 5 首 ----------

    @Test
    fun fewLocalTracksStillAskInsteadOfOverwritingCloud() {
        seedTrack("BVfew")
        val transport = FakeTransport(cloud = listOf(cloudItem(tracks = 5)))
        start(BackupPrefs.State(enabled = true, lastAtMs = 0), transport)

        BackupManager.onBackground()
        await { BackupManager.state.value.restoreDecisionPending }
        Thread.sleep(300)

        assertTrue("本机有 1 首也要问", BackupManager.state.value.restoreAskVisible)
        assertEquals("这一两首不能把云端 5 首挤掉", 0, transport.uploads.get())
    }

    // ---------- ③ 云端只有空备份：不打扰，直接放行 ----------

    @Test
    fun cloudWithOnlyEmptyBackupsDoesNotAskAndUploads() {
        seedTrack("BVonlyempty")
        val transport = FakeTransport(cloud = listOf(cloudItem(id = "bkp_empty", tracks = 0)))
        start(BackupPrefs.State(enabled = true, lastAtMs = 0), transport)

        BackupManager.onBackground()
        await { transport.uploads.get() > 0 }

        assertEquals("没有可恢复的内容就不该拦", 1, transport.uploads.get())
        assertFalse(BackupManager.state.value.restoreDecisionPending)
        assertEquals(
            "没有可恢复的内容 → 以本机为准",
            BackupPrefs.RESTORE_KEEP_LOCAL,
            BackupPrefs(context).read("u1").restoreDecision,
        )
    }

    // ---------- ④ 查询失败：本轮不传、不落决策，下次进前台再查 ----------

    @Test
    fun listFailureSkipsTheRoundAndRetriesOnNextForeground() {
        seedTrack("BVoffline")
        val transport = FakeTransport(listError = IOException("offline"))
        start(BackupPrefs.State(enabled = true, lastAtMs = 0), transport)

        BackupManager.onBackground()
        await { transport.lists.get() > 0 }
        Thread.sleep(300)

        assertEquals("查不动就别传", 0, transport.uploads.get())
        assertFalse("查询失败不算决策", BackupManager.state.value.restoreDecisionPending)
        assertEquals(
            "查询失败不能落决策",
            BackupPrefs.RESTORE_UNKNOWN,
            BackupPrefs(context).read("u1").restoreDecision,
        )

        // 网好了：下次进前台重查；云端没有有内容的备份 → 放行并上传
        transport.listError = null
        BackupManager.onForeground()
        await(8_000) { transport.uploads.get() > 0 }
        assertEquals("下一次进前台应当重查并放行", 1, transport.uploads.get())
    }

    // ---------- ⑤ 三个选项 ----------

    @Test
    fun laterKeepsPausedButBannerStays() {
        seedTrack("BVlater")
        val transport = FakeTransport(cloud = listOf(cloudItem(tracks = 5)))
        start(BackupPrefs.State(enabled = true, lastAtMs = 0), transport)
        BackupManager.onBackground()
        await { BackupManager.state.value.restoreDecisionPending }

        BackupManager.snoozeRestoreDecision()
        assertFalse("点过稍后就不再自动弹", BackupManager.state.value.restoreAskVisible)
        assertTrue("但卡片上的横幅要留着", BackupManager.state.value.restoreDecisionPending)

        // 再触发一次自动检查：仍然不上传（不是"点稍后就算同意"）
        BackupManager.onBackground()
        Thread.sleep(500)
        assertEquals("稍后不等于放行", 0, transport.uploads.get())

        // 「去处理」把弹窗叫回来
        BackupManager.showRestoreDecision()
        assertTrue("入口不能是死的", BackupManager.state.value.restoreAskVisible)
    }

    @Test
    fun keepLocalUnblocksAndUploadsOneSnapshot() {
        seedTrack("BVkeeplocal")
        val transport = FakeTransport(cloud = listOf(cloudItem(tracks = 5)))
        start(BackupPrefs.State(enabled = true, lastAtMs = 0), transport)
        BackupManager.onBackground()
        await { BackupManager.state.value.restoreDecisionPending }

        BackupManager.keepLocalNow()
        await { transport.uploads.get() > 0 }

        assertEquals("选了以本机为准就传一份", 1, transport.uploads.get())
        assertFalse(BackupManager.state.value.restoreDecisionPending)
        assertEquals(
            BackupPrefs.RESTORE_KEEP_LOCAL,
            BackupPrefs(context).read("u1").restoreDecision,
        )
    }

    @Test
    fun restoringFromCloudRecordsRestoredAndUnblocks() {
        clearLibrary()
        val snapshot = MetadataSnapshot.build(
            tracks = listOf(syncTrack("BVrestored")),
            categories = emptyList(),
            playlists = emptyList(),
            items = emptyList(),
            appVersion = "test",
            nowSec = nowSec(),
        )
        val transport = FakeTransport(
            cloud = listOf(cloudItem(tracks = 1)),
            payload = BackupPayload(snapshot.gzip, null),
        )
        start(BackupPrefs.State(enabled = true, lastAtMs = 0), transport)
        BackupManager.onBackground()
        await { BackupManager.state.value.restoreDecisionPending }

        assertEquals(1, runBlocking { BackupManager.previewRestore("bkp_cloud").getOrThrow() }.newIds)
        assertEquals(0, transport.uploads.get())
        assertTrue(BackupManager.state.value.restoreDecisionPending)
        val result = runBlocking { BackupManager.restoreNow("bkp_cloud") }

        assertEquals(1, result.getOrThrow().addedTracks)
        assertEquals(
            "恢复完成即决策",
            BackupPrefs.RESTORE_RESTORED,
            BackupPrefs(context).read("u1").restoreDecision,
        )
        assertFalse("恢复后横幅与弹窗都该消失", BackupManager.state.value.restoreDecisionPending)

        // 解禁之后照常自动备份
        BackupManager.onBackground()
        await { transport.uploads.get() > 0 }
        assertEquals("恢复后自动备份应当解禁", 1, transport.uploads.get())
    }

    // ---------- ⑥ 换账号不串 ----------

    @Test
    fun pendingDecisionDoesNotFollowYouToAnotherAccount() {
        clearLibrary()
        val transport = FakeTransport(cloud = listOf(cloudItem(tracks = 5)))
        start(BackupPrefs.State(enabled = true), transport)
        BackupManager.onBackground()
        await { BackupManager.state.value.restoreDecisionPending }
        assertTrue(BackupManager.state.value.restoreDecisionPending)

        // u1 退出，u2 登录（u2 早就选过"以本机为准"）
        BackupPrefs(context).write("u2", BackupPrefs.State(enabled = true, restoreDecision = BackupPrefs.RESTORE_KEEP_LOCAL))
        BackupManager.sessionOverride = session("u2")
        BackupManager.publishForTest()

        assertFalse("A 的待决策不能弹在 B 的界面上", BackupManager.state.value.restoreDecisionPending)
        assertFalse(BackupManager.state.value.restoreAskVisible)
    }

    @Test
    fun decisionOfOneAccountDoesNotSilenceAnother() {
        clearLibrary()
        val transport = FakeTransport(cloud = listOf(cloudItem(tracks = 5)))
        start(
            BackupPrefs.State(enabled = true, restoreDecision = BackupPrefs.RESTORE_KEEP_LOCAL),
            transport,
        )
        // u1 决策过：问都不问，也不出网查列表
        BackupManager.onBackground()
        Thread.sleep(300)
        assertEquals("已经决策过的账号不该再查云端列表", 0, transport.lists.get())

        // 换成从没决策过的 u2
        BackupPrefs(context).write("u2", BackupPrefs.State(enabled = true))
        BackupManager.sessionOverride = session("u2")
        BackupManager.publishForTest()
        BackupManager.onBackground()
        await { BackupManager.state.value.restoreDecisionPending }

        assertTrue("B 要问自己那一遍", BackupManager.state.value.restoreAskVisible)
        assertEquals(
            "A 的决策不能被 B 借走",
            BackupPrefs.RESTORE_UNKNOWN,
            BackupPrefs(context).read("u2").restoreDecision,
        )
        assertEquals(
            "A 自己的决策不能被改",
            BackupPrefs.RESTORE_KEEP_LOCAL,
            BackupPrefs(context).read("u1").restoreDecision,
        )
    }
}
