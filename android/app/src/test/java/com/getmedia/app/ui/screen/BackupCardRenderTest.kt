package com.getmedia.app.ui.screen

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.getmedia.app.data.backup.BackupItem
import com.getmedia.app.data.backup.BackupManager
import com.getmedia.app.data.backup.BackupPrefs
import com.getmedia.app.ui.theme.GetMediaTheme
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 设置页「数据备份」卡片的渲染回归。
 *
 * 跑在 JVM（Robolectric）上：模拟器里没有 owner 会话，登录态的分支拍不到，
 * 所以这里直接注入 [BackupManager.UiState] 渲染真实组件，把四种状态都留一张图。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackupCardRenderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun restorePreviewExplainsWhatCanBeRecoveredInPlainLanguage() {
        val empty = restorePreviewMessage(0)
        val positive = restorePreviewMessage(2)
        assertTrue(empty.contains("没有发现可补回的 B 站或抖音作品"))
        assertTrue(positive.contains("可能补回 2 项"))
        for (message in listOf(empty, positive)) {
            assertTrue(message.contains("请用原文件重新导入"))
            assertFalse(listOf("主键", "编号", "未命中").any(message::contains))
        }
    }

    private fun render(state: BackupManager.UiState, dark: Boolean = false) {
        rule.setContent {
            GetMediaTheme(darkTheme = dark) {
                Box(Modifier.width(360.dp).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    androidx.compose.foundation.layout.Column(Modifier.padding(horizontal = 12.dp)) {
                        BackupSection(injected = state)
                    }
                }
            }
        }
    }

    private fun saveShot(name: String) {
        runCatching {
            val dir = File("build/reports/backup-shots").apply { mkdirs() }
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            FileOutputStream(File(dir, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }.onFailure { println("[shot] $name 截图失败：$it") }
    }

    private val normal = BackupManager.UiState(
        loggedIn = true, enabled = true, lastAtMs = 1_763_462_520_000, lastTracks = 128,
        lastId = "bkp_1", dirty = false,
    )

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun normalStateLight() {
        render(normal)
        saveShot("backup-light-normal")
        rule.onNodeWithText("上次备份：128 首", substring = true).assertIsDisplayed()
        rule.onNodeWithText("立即备份").assertIsDisplayed()
        rule.onNodeWithText("删除云端备份").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun normalStateDark() {
        render(normal, dark = true)
        saveShot("backup-dark-normal")
        rule.onNodeWithText("上次备份：128 首", substring = true).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun blockedTooLargeStaysVisible() {
        render(normal.copy(blocked = BackupPrefs.BLOCKED_TOO_LARGE))
        saveShot("backup-light-blocked")
        rule.onNodeWithText("备份已停用：库太大（超过 512 KiB）").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun throttledShowsWaitHintInsteadOfFailure() {
        render(normal.copy(throttleUntilMs = System.currentTimeMillis() + 600_000))
        saveShot("backup-light-throttled")
        rule.onNodeWithText("刚备份过，请 10 分钟后再试").assertIsDisplayed()
        rule.onAllNodesWithText("上次备份失败", substring = true).assertCountEquals(0)
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun loggedOutShowsHint() {
        render(BackupManager.UiState(loggedIn = false))
        saveShot("backup-light-loggedout")
        rule.onNodeWithText("登录后可用备份").assertIsDisplayed()
    }

    /** 空库点「立即备份」：确认框必须把"会挤掉一份"说清楚，不能只说"确定要传吗"。 */
    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun emptyLibraryConfirmExplainsCost() {
        render(normal.copy(lastTracks = 0, lastAtMs = 0, emptyConfirmPending = true))
        saveShot("backup-light-empty-confirm")
        rule.onNodeWithText("本地曲库为空").assertIsDisplayed()
        rule.onNodeWithText("移除最久的备份", substring = true).assertIsDisplayed()
        rule.onNodeWithText("仍要上传").assertIsDisplayed()
    }

    /** 确认框要按云端**真实份数**说话：一份都没有时不能吓唬人说"会移除最久的"。 */
    @Test
    fun emptyBackupMessageFollowsRealCloudCount() {
        // 没查到（离线/超时）：只说规则，不编造份数
        assertEquals("云端最多保留 3 份备份。备份满时上传会移除最久的备份。", emptyBackupMessage(-1))
        // 0/1/2 份走同一句：只说现有份数
        assertEquals("云端现有 0 份备份（上限 3 份）。", emptyBackupMessage(0))
        assertEquals("云端现有 1 份备份（上限 3 份）。", emptyBackupMessage(1))
        assertEquals("云端现有 2 份备份（上限 3 份）。", emptyBackupMessage(2))
        assertFalse(emptyBackupMessage(0).contains("移除"))
        // 满 3 份才会真的移除最久的
        assertEquals("云端现有 3 份备份（上限 3 份）。上传会移除最久的备份。", emptyBackupMessage(3))
    }

    /** 恢复决策没做之前：卡片上常驻一条提示与「去处理」，不是只弹一次了事。 */
    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun restorePausedBannerStaysWithEntry() {
        render(normal.copy(lastTracks = 0, lastAtMs = 0, restoreDecisionPending = true, restoreCloudTracks = 128))
        saveShot("backup-light-restore-paused")
        rule.onNodeWithText("自动备份已暂停：等你决定是否恢复云端备份").assertIsDisplayed()
        rule.onNodeWithText("云端有 128 首的备份", substring = true).assertIsDisplayed()
        rule.onNodeWithText("去处理").assertIsDisplayed()
    }

    // ---------- 恢复决策弹窗 ----------

    private fun renderDecisionSheet(dark: Boolean) {
        rule.setContent {
            GetMediaTheme(darkTheme = dark) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    Box(Modifier.width(320.dp).padding(16.dp)) {
                        RestoreDecisionSheet(
                            cloudTracks = 128,
                            cloudAtMs = 1_763_462_520_000,
                            onRestore = {},
                            onKeepLocal = {},
                            onLater = {},
                        )
                    }
                }
            }
        }
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun restoreDecisionLight() {
        renderDecisionSheet(dark = false)
        saveShot("restore-decision-light")
        rule.onNodeWithText("云端已有曲库备份").assertIsDisplayed()
        rule.onNodeWithText("128 首", substring = true).assertIsDisplayed()
        rule.onNodeWithText("恢复云端备份").assertIsDisplayed()
        rule.onNodeWithText("用本机内容（不恢复）").assertIsDisplayed()
        rule.onNodeWithText("稍后再说").assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun restoreDecisionDark() {
        renderDecisionSheet(dark = true)
        saveShot("restore-decision-dark")
        rule.onNodeWithText("云端已有曲库备份").assertIsDisplayed()
        rule.onNodeWithText("自动备份先暂停", substring = true).assertIsDisplayed()
    }

    // ---------- 恢复：选一份备份 ----------

    private val cloud = listOf(
        BackupItem("bkp_new", 41_000, 128, 1_763_462_520, "Pixel 8"),
        BackupItem("bkp_old", 39_500, 121, 1_762_000_000, "Redmi K70"),
    )

    private fun renderPicker(dark: Boolean) {
        rule.setContent {
            GetMediaTheme(darkTheme = dark) {
                Box(Modifier.width(360.dp).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    androidx.compose.material3.Surface(
                        Modifier.padding(16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text("选择要恢复的备份", style = MaterialTheme.typography.titleLarge)
                            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                            BackupPickerContent(cloud, cloud.first()) {}
                        }
                    }
                }
            }
        }
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun pickerLight() {
        renderPicker(dark = false)
        saveShot("restore-light-picker")
        rule.onNodeWithText("128 首", substring = true).assertIsDisplayed()
        rule.onNodeWithText("恢复只补", substring = true).assertIsDisplayed()
    }

    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun pickerDark() {
        renderPicker(dark = true)
        saveShot("restore-dark-picker")
        rule.onNodeWithText("128 首", substring = true).assertIsDisplayed()
    }

    /** 0 首的备份要标成「空备份」并且点不动——它能被选中就会让人以为"恢复了一份备份"。 */
    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun pickerMarksEmptyBackupAndIgnoresTap() {
        val picked = mutableListOf<BackupItem>()
        val withEmpty = cloud + BackupItem("bkp_empty", 12, 0, 1_763_600_000, "Redmi K70")
        rule.setContent {
            GetMediaTheme(darkTheme = false) {
                Box(Modifier.width(360.dp).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    androidx.compose.material3.Surface(
                        Modifier.padding(16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text("选择要恢复的备份", style = MaterialTheme.typography.titleLarge)
                            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                            BackupPickerContent(withEmpty, null) { picked += it }
                        }
                    }
                }
            }
        }
        saveShot("restore-picker-empty")

        rule.onNodeWithText("空备份", substring = true).assertIsDisplayed()
        rule.onNodeWithText("上传时本地没有条目", substring = true).assertIsDisplayed()
        rule.onNodeWithText("空备份", substring = true).performClick()
        org.junit.Assert.assertTrue("空备份不应可被选中", picked.isEmpty())
    }

    /** 管理云端备份：每份一行、每行一个删除；空备份也要标出来。 */
    @Test
    @Config(sdk = [33], qualifiers = "w360dp-h740dp-xhdpi")
    fun cloudBackupListHasPerItemDelete() {
        val deleted = mutableListOf<String>()
        val items = cloud + BackupItem("bkp_empty", 12, 0, 1_763_600_000, "Redmi K70")
        rule.setContent {
            GetMediaTheme(darkTheme = false) {
                Box(Modifier.width(360.dp).fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    androidx.compose.material3.Surface(
                        Modifier.padding(16.dp),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                    ) {
                        Column(Modifier.padding(20.dp)) {
                            Text("云端备份", style = MaterialTheme.typography.titleLarge)
                            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
                            CloudBackupsContent(items, false, null, null) { deleted += it.id }
                        }
                    }
                }
            }
        }
        saveShot("backup-cloud-list")

        rule.onNodeWithText("128 首", substring = true).assertIsDisplayed()
        rule.onNodeWithText("空备份", substring = true).assertIsDisplayed()
        rule.onAllNodesWithText("删除").assertCountEquals(items.size)

        rule.onAllNodesWithText("删除")[0].performClick()
        org.junit.Assert.assertEquals(listOf("bkp_new"), deleted)
    }
}
