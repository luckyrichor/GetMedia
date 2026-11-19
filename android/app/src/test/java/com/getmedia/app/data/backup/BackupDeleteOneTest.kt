package com.getmedia.app.data.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.account.ApiException
import com.getmedia.app.account.SessionStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * 单份删除（误传的空备份只能靠它清掉）。
 *
 * 关键点：删的若正是"上次备份"，`lastId` 要清掉，否则设置页会一直以为云端还有东西；
 * `lastHash` 一律不动——刚删掉的清单不该在下一个窗口被自动传回来。
 */
@RunWith(AndroidJUnit4::class)
class BackupDeleteOneTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val deleted = AtomicReference<String?>(null)
    private var failWith: Throwable? = null

    private inner class RecordingTransport : BackupTransport {
        override suspend fun upload(body: ByteArray, appVersion: String, device: String): BackupUpload =
            BackupUpload("bkp_x", emptyList())

        override suspend fun list(): List<BackupItem> = emptyList()
        override suspend fun download(backupId: String): BackupPayload = BackupPayload(ByteArray(0), null)

        override suspend fun delete(backupId: String): Boolean {
            failWith?.let { throw it }
            deleted.set(backupId)
            return true
        }

        override suspend fun deleteAll(): Int = 0
    }

    private fun start(state: BackupPrefs.State) {
        val session = SessionStore.Session(
            loginName = "u1", token = "tok", expiresAt = 0, deviceId = null, consentRequired = false,
        )
        BackupManager.resetForTest()
        BackupPrefs(context).write(session.loginName, state)
        BackupManager.transportOverride = RecordingTransport()
        BackupManager.sessionOverride = session
        BackupManager.initialize(context)
        deleted.set(null)
        failWith = null
    }

    /**
     * 回调走 `Dispatchers.Main`：Robolectric 下主线程队列不会自己跑，
     * 光 sleep 会一直等不到，必须顺手 idle 一下主 Looper。
     */
    private fun await(timeoutMs: Long = 5_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    @After
    fun tearDown() {
        BackupManager.resetForTest()
    }

    @Test
    fun deletingTheLastBackupClearsItsIdButKeepsHash() {
        val hash = "h-keep"
        start(BackupPrefs.State(enabled = true, lastHash = hash, lastId = "bkp_a"))

        var result: Result<Boolean>? = null
        BackupManager.deleteOne("bkp_a") { result = it }
        await { result != null }

        assertEquals("bkp_a", deleted.get())
        assertTrue(result!!.getOrThrow())
        assertNull("删掉的就是上次备份，id 要清掉", BackupPrefs(context).read("u1").lastId)
        assertEquals("lastHash 不能动，否则会被立刻重传", hash, BackupPrefs(context).read("u1").lastHash)
    }

    @Test
    fun deletingAnotherBackupKeepsLastId() {
        start(BackupPrefs.State(enabled = true, lastHash = "h", lastId = "bkp_a"))

        var result: Result<Boolean>? = null
        BackupManager.deleteOne("bkp_b") { result = it }
        await { result != null }

        assertEquals("bkp_b", deleted.get())
        assertEquals("删的不是上次那份，id 保留", "bkp_a", BackupPrefs(context).read("u1").lastId)
    }

    @Test
    fun serverErrorSurfacesAsFailure() {
        start(BackupPrefs.State(enabled = true, lastHash = "h", lastId = "bkp_a"))
        failWith = ApiException(404, "backup_not_found", "备份不存在")

        var result: Result<Boolean>? = null
        BackupManager.deleteOne("bkp_a") { result = it }
        await { result != null }

        assertTrue("失败要能被界面看到", result!!.isFailure)
        assertEquals("备份不存在", result!!.exceptionOrNull()?.message)
        assertEquals("失败时不能动 lastId", "bkp_a", BackupPrefs(context).read("u1").lastId)
    }

    @Test
    fun withoutSessionItFailsInsteadOfSending() {
        BackupManager.resetForTest()
        var result: Result<Boolean>? = null
        BackupManager.deleteOne("bkp_a") { result = it }
        await { result != null }

        assertTrue(result!!.isFailure)
        assertNull("没有会话时不能发出请求", deleted.get())
        assertFalse(BackupManager.state.value.loggedIn)
    }
}
