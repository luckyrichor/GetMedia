package com.getmedia.app.ui.screen

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.export.ExportController
import com.getmedia.app.data.export.ExportPlan
import com.getmedia.app.data.export.ExportService
import com.getmedia.app.data.export.ExportSession
import com.getmedia.app.data.export.ExportTarget
import com.getmedia.app.data.export.ExportTrack
import com.getmedia.app.data.export.ExportWriter
import com.getmedia.app.data.export.WriteCancelled
import com.getmedia.app.ui.theme.GetMediaTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 导出进行中的「取消导出」入口。
 *
 * 背景：通知栏的取消要先展开通知卡片才点得到（小米上更不明显），用户希望在应用内也能停。
 * 现状是确认页底部那颗主按钮在运行中就叫「取消导出」，并且和通知走同一个
 * [ExportController.cancel] → [ExportSession.cancel]；这里把三件事钉住：
 *
 * 1. 320 dp + 字体 200%、亮暗两版下，按钮都可见、可点、触控高度 ≥ 48 dp；
 * 2. 点它确实停在半路：已完成项保留、当前项临时文件删掉、剩余项进重试；
 * 3. 它和通知栏「取消」（服务收到 [ExportService.ACTION_CANCEL]）留下的结果完全一致。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportSheetCancelTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val tracks = listOf(
        ExportTrack(id = 1, title = "深夜电台里的一首很长的歌名用来撑满整行", audioFormat = "m4a", audioBytes = 12_000_000, localPath = "/tmp/a.m4a"),
        ExportTrack(id = 2, title = "短音频", audioFormat = "mp3", audioBytes = 3_000_000, localPath = "/tmp/b.mp3"),
        ExportTrack(id = 3, title = "视频稿件", audioFormat = "mp4", mediaType = "VIDEO", audioBytes = 88_000_000, localPath = "/tmp/c.mp4"),
    )

    @After
    fun tearDown() {
        // 界面可能停在 Running 上，clear() 会被 running 拦住，用测试专用的清场
        ExportController.forceClearForTest()
    }

    private fun forceFontScale(scale: Float) {
        val config = Configuration(rule.activity.resources.configuration).apply { fontScale = scale }
        rule.activity.resources.updateConfiguration(config, rule.activity.resources.displayMetrics)
    }

    private fun render(dark: Boolean) {
        val density = rule.activity.resources.displayMetrics.density
        forceFontScale(2f)
        rule.setContent {
            GetMediaTheme(darkTheme = dark) {
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        ExportSheet(
                            tracks = tracks,
                            onDismiss = {},
                            preview = ExportPreview(
                                tree = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload%2FGetMedia"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** Dialog 是独立窗口，截的是 Dialog 的 decorView 而不是 Activity 的。 */
    private fun saveShot(name: String) {
        runCatching {
            val view = ShadowDialog.getLatestDialog()?.window?.decorView ?: rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val dir = File("build/reports/export-shots").apply { mkdirs() }
            FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("[shot] $name ${view.width}x${view.height}")
        }.onFailure { println("[shot] $name 截图失败：$it") }
    }

    private fun assertCancelButtonIsUsable() {
        rule.onNodeWithText("取消导出").assertIsDisplayed()
        rule.onNodeWithText("取消导出").assertIsEnabled()
        rule.onNodeWithText("取消导出").assertHasClickAction()
        // 200% 字体最容易把底部按钮压成一条缝
        rule.onNodeWithText("取消导出").assertHeightIsAtLeast(48.dp)
        // 进度本身也要看得见，不然"能取消"也说明不了什么
        rule.onNodeWithText("正在导出").assertIsDisplayed()
        rule.onNodeWithText("1/3").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
    fun cancelButtonStaysReachableAt200PercentLight() {
        ExportController.installForTest(session(), runningState())
        render(dark = false)

        assertCancelButtonIsUsable()
        saveShot("export-c-cancel-320dp-200-light")
    }

    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
    fun cancelButtonStaysReachableAt200PercentDark() {
        ExportController.installForTest(session(), runningState())
        render(dark = true)

        assertCancelButtonIsUsable()
        saveShot("export-c-cancel-320dp-200-dark")
    }

    /**
     * 真跑起来再点：第 2 项写到一半时按钮被按下，验证停在半路的形态。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
    fun inAppCancelStopsMidRunAndKeepsWhatIsDone() {
        val target = BlockingTarget()
        val session = session(target)
        ExportController.installForTest(session, runningState())
        render(dark = false)

        val worker = startRun(session)
        assertTrue("第 2 项迟迟没开始写", target.blocked.await(5, TimeUnit.SECONDS))

        rule.onNodeWithText("取消导出").performClick()
        val shape = finishCancel(session, target, worker)

        assertEquals(ExportSession.Reason.USER_CANCEL, shape.reason)
        assertEquals("已取消导出", shape.reason.text)
        assertEquals("已完成的那项要留着", listOf(1L to ExportSession.Status.DONE), shape.results)
        assertEquals("没轮到的不算失败", 2, shape.notRun)
        assertEquals(setOf(2L, 3L), shape.retryIds)
        assertEquals("半成品必须删掉，只留第 1 项的成品", setOf("曲目 1.m4a"), shape.files)
    }

    /**
     * 应用内按钮与通知栏「取消」必须走到同一个终态。
     *
     * 通知那条路径由 [ExportService] 的 `ACTION_CANCEL` → `ExportController.cancel()` 完成，
     * 这里把两条入口都真跑一遍，比结果、比没跑的项、比目录残留。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w320dp-h640dp-xhdpi")
    fun inAppCancelLeavesTheSameShapeAsNotificationCancel() {
        // 入口一：应用内按钮
        val byButton = runAndCancel { session, target, worker ->
            rule.onNodeWithText("取消导出").performClick()
            finishCancel(session, target, worker)
        }

        // 入口二：通知栏的取消（服务收到 ACTION_CANCEL）
        val service = Robolectric.buildService(ExportService::class.java).create().get()
        val byNotification = runAndCancel { _, target, worker ->
            service.onStartCommand(Intent(ExportService.ACTION_CANCEL), 0, 1)
            finishCancel(ExportController.session!!, target, worker)
        }

        assertEquals(byButton, byNotification)
        // 具体长什么样也要钉住，否则两条一起错也会"一致地通过"
        assertEquals(listOf(1L to ExportSession.Status.DONE), byButton.results)
        assertEquals(2, byButton.notRun)
        assertEquals(setOf("曲目 1.m4a"), byButton.files)
    }

    // ── 下面是把"真跑起来 → 停在半路"这套动作串起来的脚手架 ─────────────────

    private data class Shape(
        val results: List<Pair<Long, ExportSession.Status>>,
        val notRun: Int,
        val retryIds: Set<Long>,
        val files: Set<String>,
        val reason: ExportSession.Reason,
    )

    private fun runningState() = ExportSession.State.Running(total = 3, done = 1, failed = 0, current = "曲目 2.m4a")

    private fun session(target: ExportTarget = BlockingTarget()) = ExportSession(
        items = tracks.mapIndexed { index, track ->
            ExportPlan.Item(
                track = track.copy(localPath = sourceFile(index + 1).path),
                dirs = emptyList(),
                fileName = "曲目 ${index + 1}.${track.audioFormat}",
            )
        },
        writer = ExportWriter(target),
    )

    private fun sourceFile(index: Int): File {
        val dir = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "export-cancel-test").apply { mkdirs() }
        return File(dir, "s$index.${if (index == 3) "mp4" else "m4a"}").apply { writeBytes(ByteArray(8) { 1 }) }
    }

    private fun startRun(session: ExportSession) = thread(isDaemon = true) {
        runBlocking { session.run() }
    }

    private fun finishCancel(session: ExportSession, target: BlockingTarget, worker: Thread): Shape {
        target.release()
        worker.join(5_000)
        assertTrue("取消后跑批没退出来", !worker.isAlive)
        val state = session.state.value as ExportSession.State.Interrupted
        return Shape(
            results = state.results.map { it.id to it.status },
            notRun = state.notRun,
            retryIds = session.retryIds,
            files = target.present(),
            reason = state.reason,
        )
    }

    private fun runAndCancel(cancel: (ExportSession, BlockingTarget, Thread) -> Shape): Shape {
        ExportController.forceClearForTest()
        val target = BlockingTarget()
        val session = session(target)
        ExportController.installForTest(session, runningState())
        if (ShadowDialog.getLatestDialog() == null) render(dark = false)

        val worker = startRun(session)
        assertTrue("第 2 项迟迟没开始写", target.blocked.await(5, TimeUnit.SECONDS))
        return cancel(session, target, worker)
    }

    /**
     * 第 2 项写不完的假目标：卡在 2 号文件上，等测试点完取消再放行。
     *
     * 名字与真实目标一样先临时名再改名，只是为了 [present] 能说清"目录里还剩什么"。
     */
    private class BlockingTarget : ExportTarget {
        private object Dir : ExportTarget.Dir
        private class Doc(val name: String) : ExportTarget.Doc

        private val files = LinkedHashMap<String, Doc>()
        private var writes = 0

        val blocked = CountDownLatch(1)
        private val release = CountDownLatch(1)

        fun release() = release.countDown()

        fun present(): Set<String> = synchronized(files) { files.keys.toSet() }

        override fun ensureDir(dirs: List<String>): ExportTarget.Dir = Dir

        override fun childNames(dir: ExportTarget.Dir): Set<String> = present()

        override fun createDocument(dir: ExportTarget.Dir, mimeType: String, name: String): ExportTarget.Doc =
            Doc(name).also { doc -> synchronized(files) { files[name] = doc } }

        override fun write(doc: ExportTarget.Doc, source: File, stop: () -> Boolean): Long {
            writes++
            if (writes == 2) {
                blocked.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            if (stop()) throw WriteCancelled()
            return source.length()
        }

        override fun rename(doc: ExportTarget.Doc, newName: String): Boolean {
            synchronized(files) {
                files.remove((doc as Doc).name)
                files[newName] = Doc(newName)
            }
            return true
        }

        override fun delete(doc: ExportTarget.Doc) {
            synchronized(files) { files.remove((doc as Doc).name) }
        }

        override fun availableBytes(): Long = Long.MAX_VALUE
    }
}
