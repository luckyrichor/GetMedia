package com.getmedia.app.data.export

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.export.ExportSession.Reason
import com.getmedia.app.data.export.ExportSession.State
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowService
import java.io.File
import java.io.IOException

/**
 * 服务侧的两条停止入口：**Android 15 的 `onTimeout`** 与**通知里的「取消」**。
 *
 * 这两条是纯回调逻辑（不写文件），所以用 Robolectric 直接调；
 * 真正跑批时的三项一致性由 [ExportSessionTest.allStopPathsLeaveTheSameShape] 覆盖，
 * 端到端在模拟器上用真机目录验证。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExportServiceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ExportController.clear()
    }

    @After
    fun tearDown() {
        ExportController.clear()
    }

    private fun installTwoItems(): ExportSession {
        val folder = File(context.cacheDir, "export-service-test").apply { mkdirs() }
        val items = (1..2).map { index ->
            val file = File(folder, "t$index.m4a").apply { writeBytes(ByteArray(4) { 1 }) }
            ExportPlan.Item(
                track = ExportTrack(id = index.toLong(), title = "曲目 $index", audioFormat = "m4a", audioBytes = 4, localPath = file.path),
                dirs = emptyList(),
                fileName = "曲目 $index.m4a",
            )
        }
        val session = ExportSession(items, ExportWriter(NeverFinishingTarget()))
        ExportController.installForTest(session)
        return session
    }

    @Test
    fun onTimeoutInterruptsWithSystemWordingAndStopsService() {
        val session = installTwoItems()
        val service = Robolectric.buildService(ExportService::class.java).create().get()

        service.onTimeout(1, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

        val state = session.state.value
        assertTrue("必须当场置为 Interrupted，否则界面卡在 0/2：$state", state is State.Interrupted)
        state as State.Interrupted
        assertEquals(Reason.SYSTEM_TIMEOUT, state.reason)
        assertEquals("导出被系统中断，可重试失败项", state.reason.text)
        assertEquals("未跑的项要能重试", setOf(1L, 2L), session.retryIds)
        assertTrue("不给系统留一个不肯停的前台服务", shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun cancelActionFromNotificationStopsWithCancelWording() {
        val session = installTwoItems()
        val service = Robolectric.buildService(ExportService::class.java).create().get()

        service.onStartCommand(Intent(ExportService.ACTION_CANCEL), 0, 1)

        val state = session.state.value as State.Interrupted
        assertEquals(Reason.USER_CANCEL, state.reason)
        assertEquals("已取消导出", state.reason.text)
    }

    @Test
    fun startWithoutPendingRequestDoesNotStartForeground() {
        // 没有待跑请求（重复启动 / 进程被回收后系统重投）时不能挂一个空通知
        val controller = Robolectric.buildService(ExportService::class.java).create()
        val shadow: ShadowService = shadowOf(controller.get())

        controller.get().onStartCommand(ExportService.intent(context), 0, 1)

        assertTrue("应当直接停掉", shadow.isStoppedBySelf)
        assertNull("不该有前台通知", shadow.lastForegroundNotification)
    }

    /** 永远写不完的假目标：用来验证"停"的入口，不关心写文件本身。 */
    private class NeverFinishingTarget : ExportTarget {
        private object Dir : ExportTarget.Dir
        private class Doc(val name: String) : ExportTarget.Doc

        override fun ensureDir(dirs: List<String>): ExportTarget.Dir = Dir
        override fun childNames(dir: ExportTarget.Dir): Set<String> = emptySet()
        override fun createDocument(dir: ExportTarget.Dir, mimeType: String, name: String): ExportTarget.Doc = Doc(name)
        override fun write(doc: ExportTarget.Doc, source: File, stop: () -> Boolean): Long = throw IOException("not used")
        override fun rename(doc: ExportTarget.Doc, newName: String): Boolean = false
        override fun delete(doc: ExportTarget.Doc) = Unit
        override fun availableBytes(): Long = -1L
    }
}
